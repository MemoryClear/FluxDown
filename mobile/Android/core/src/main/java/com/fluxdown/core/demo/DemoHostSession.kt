package com.fluxdown.core.demo

import com.fluxdown.core.host.CreateTaskRequest
import com.fluxdown.core.host.HostEvent
import com.fluxdown.core.host.HostSession
import com.fluxdown.core.host.HostSignal
import com.fluxdown.core.host.HostSnapshot
import com.fluxdown.core.model.BtFile
import com.fluxdown.core.model.Category
import com.fluxdown.core.model.CloudDevice
import com.fluxdown.core.host.HostErrorCode
import com.fluxdown.core.host.HostException
import com.fluxdown.core.model.HostInfo
import com.fluxdown.core.model.LinkDevice
import com.fluxdown.core.model.RssSource
import com.fluxdown.core.model.Queue
import com.fluxdown.core.model.RuntimeStats
import com.fluxdown.core.model.SeedingStatus
import com.fluxdown.core.model.Segment
import com.fluxdown.core.model.SelectionKind
import com.fluxdown.core.model.SelectionOutcome
import com.fluxdown.core.model.SelectionRequest
import com.fluxdown.core.model.SourceBytes
import com.fluxdown.core.model.Task
import com.fluxdown.core.model.TaskGroup
import com.fluxdown.core.model.TaskRuntime
import com.fluxdown.core.model.TaskStatus
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/**
 * 演示主机：引擎桥接（UniFFI `native/mobile`）接入前，用客户端模拟的数据驱动界面。
 * 数据与 1 Hz 推进规则复刻设计原型 `shared/data.js`（18 个任务、4 个队列、1 个任务组）。
 * UI 以 [com.fluxdown.core.model.HostRef.Demo] 明确标注为演示主机。
 */
class DemoHostSession(
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random(7),
    private val tickMs: Long = 1000,
) : HostSession {
    private val lock = Mutex()
    private val tasks = LinkedHashMap<String, Task>()
    private val segments = HashMap<String, MutableList<Segment>>()
    private val baseSpeed = HashMap<String, Long>()
    private val upSpeed = HashMap<String, Long>()
    private var positions = mapOf<String, Int>()
    private var priority: String? = null
    private val pendingSelections = ArrayList<SelectionRequest>()
    private var sink: ProducerScope<HostSignal>? = null
    private var seq = 0L
    private var nextId = 100
    private var config = DEFAULT_CONFIG
    private var configRevision = 1L
    private var rss = initialRss(clock() / 1000)

    init {
        seed()
    }

    override val signals: Flow<HostSignal> = channelFlow {
        lock.withLock {
            sink = this
            send(HostSignal.Snapshot(snapshot()))
        }
        while (isActive) {
            delay(tickMs)
            lock.withLock { tick() }
        }
    }

    override fun close() {
        sink = null
    }

    // ── 命令 ──────────────────────────────────────────────────────────────

    override suspend fun createTask(request: CreateTaskRequest): String = lock.withLock {
        val id = "t${nextId++}"
        val name = request.fileName.ifEmpty { inferName(request.url) }
        val bt = request.url.startsWith("magnet:")
        val t = Task(
            taskId = id, url = request.url, originUrl = request.url, fileName = name,
            saveDir = request.saveDir.ifEmpty { SAVE_ROOT }, status = if (request.startPaused) TaskStatus.Paused else TaskStatus.Preparing,
            downloadedBytes = 0, totalBytes = 0, createdAt = clock() / 1000, queueId = request.queueId,
        )
        tasks[id] = t
        emit(HostEvent.TaskChanged(t))
        if (bt && !request.startPaused) {
            val req = SelectionRequest(
                requestId = "sel-$id", taskId = id,
                kind = SelectionKind.Bt(BT_FILES),
                defaultChoice = SelectionOutcome.Bt(BT_FILES.map { it.index }),
                deadlineUnixMs = clock() + 30_000,
            )
            pendingSelections.add(req)
            emit(HostEvent.SelectionPending(req))
        }
        id
    }

    override suspend fun pause(taskId: String) = lock.withLock { pauseLocked(taskId) }
    override suspend fun resume(taskId: String) = lock.withLock { resumeLocked(taskId) }

    override suspend fun delete(taskId: String, deleteFiles: Boolean) = lock.withLock {
        if (tasks.remove(taskId) != null) {
            segments.remove(taskId)
            emit(HostEvent.TaskDeleted(taskId))
            emitStats()
        }
    }

    override suspend fun pauseMany(taskIds: List<String>) = lock.withLock { for (id in taskIds) pauseLocked(id) }
    override suspend fun resumeMany(taskIds: List<String>) = lock.withLock { for (id in taskIds) resumeLocked(id) }

    override suspend fun deleteMany(taskIds: List<String>, deleteFiles: Boolean) {
        taskIds.forEach { delete(it, deleteFiles) }
    }

    override suspend fun pauseAll() = lock.withLock {
        val ids = tasks.values.filter { it.status.isActive || it.status == TaskStatus.Pending }.map { it.taskId }
        for (id in ids) pauseLocked(id)
    }

    override suspend fun resumeAll() = lock.withLock {
        val ids = tasks.values.filter { it.status == TaskStatus.Paused || it.status == TaskStatus.Failed }.map { it.taskId }
        for (id in ids) resumeLocked(id)
    }

    override suspend fun rename(taskId: String, fileName: String) = lock.withLock {
        update(taskId) { it.copy(fileName = fileName) }
    }

    override suspend fun changeUrl(taskId: String, url: String) = lock.withLock {
        update(taskId) { it.copy(url = url, originUrl = url, errorMessage = "") }
    }

    override suspend fun rescan() = Unit

    override suspend fun moveToQueue(taskId: String, queueId: String) = lock.withLock {
        update(taskId) { it.copy(queueId = queueId) }
    }

    override suspend fun boost(taskId: String) = lock.withLock {
        priority = if (priority == taskId) null else taskId
        emit(HostEvent.PriorityTaskChanged(priority))
    }

    override suspend fun resolveSelection(requestId: String, outcome: SelectionOutcome) = lock.withLock {
        val req = pendingSelections.firstOrNull { it.requestId == requestId } ?: return@withLock
        pendingSelections.remove(req)
        emit(HostEvent.SelectionResolved(requestId))
        if (outcome == SelectionOutcome.Cancelled) {
            pauseLocked(req.taskId)
        } else if (outcome is SelectionOutcome.Bt) {
            val size = BT_FILES.filter { it.index in outcome.indices }.sumOf { it.size }
            start(req.taskId, size, 24, 6L * MB)
        }
    }

    override suspend fun patchConfig(expectedRevision: Long, values: Map<String, String>) = lock.withLock {
        if (expectedRevision != configRevision) throw HostException(HostErrorCode.Conflict, message = "revision $configRevision")
        config = config + values
        configRevision++
        emit(HostEvent.ConfigChanged(config, configRevision))
    }

    override suspend fun refreshRssSource(sourceId: String) = lock.withLock {
        rss = rss.map { if (it.sourceId == sourceId) it.copy(lastSuccessAt = clock() / 1000, failCount = 0, lastError = "") else it }
        emit(HostEvent.RssSourcesChanged(rss))
    }

    override suspend fun setRssSourceEnabled(sourceId: String, enabled: Boolean) = lock.withLock {
        rss = rss.map { if (it.sourceId == sourceId) it.copy(enabled = enabled) else it }
        emit(HostEvent.RssSourcesChanged(rss))
    }

    // ── 模拟 ──────────────────────────────────────────────────────────────

    private suspend fun tick() {
        for (t in tasks.values.toList()) {
            when {
                t.status == TaskStatus.Downloading && t.totalBytes > 0 -> advance(t)
                t.status == TaskStatus.Preparing && t.taskId !in pendingSelections.map { it.taskId } &&
                    random.nextFloat() < 0.08f -> start(t.taskId, 612L * MB, 8, 3L * MB)
                t.seedingStatus == SeedingStatus.Seeding -> {
                    val up = jitter(upSpeed[t.taskId] ?: 0)
                    val next = t.copy(uploadedBytes = t.uploadedBytes + up, seedingTimeSecs = t.seedingTimeSecs + 1)
                    tasks[t.taskId] = next
                    emit(progress(next, 0, up))
                }
            }
        }
        emitStats()
    }

    private suspend fun advance(t: Task) {
        val speed = maxOf(64L * KB, (baseSpeed[t.taskId] ?: (3L * MB)).let(::jitter))
        val segs = segments[t.taskId]
        var downloaded = (t.downloadedBytes + speed).coerceAtMost(t.totalBytes)
        if (segs != null && segs.isNotEmpty()) {
            val active = segs.indices.filter { segs[it].active == true }
            if (active.isEmpty()) {
                // 活跃段都已下满：激活下一批未完成段
                segs.indices.filter { segs[it].downloadedBytes < len(segs[it]) }.take(4).forEach { segs[it] = segs[it].copy(active = true) }
            }
            val now = segs.indices.filter { segs[it].active == true }
            val share = if (now.isEmpty()) 0 else speed / now.size
            for (i in now) {
                val s = segs[i]
                val d = (s.downloadedBytes + share).coerceAtMost(len(s))
                segs[i] = s.copy(downloadedBytes = d, active = d < len(s))
            }
            downloaded = segs.sumOf { it.downloadedBytes }.coerceAtMost(t.totalBytes)
            emit(HostEvent.TaskRuntimeChanged(runtime(t.taskId, t.totalBytes, segs)))
        }
        if (downloaded >= t.totalBytes) {
            val done = t.copy(status = TaskStatus.Completed, downloadedBytes = t.totalBytes, completedAt = clock() / 1000)
            tasks[t.taskId] = done
            segs?.replaceAll { it.copy(downloadedBytes = len(it), active = false) }
            emit(HostEvent.TaskChanged(done))
            return
        }
        val next = t.copy(downloadedBytes = downloaded)
        tasks[t.taskId] = next
        val up = upSpeed[t.taskId]?.let(::jitter) ?: 0
        emit(progress(next, speed, up))
    }

    private suspend fun start(id: String, total: Long, n: Int, speed: Long) {
        val t = tasks[id] ?: return
        val next = t.copy(status = TaskStatus.Downloading, totalBytes = total, downloadedBytes = 0, errorMessage = "")
        tasks[id] = next
        baseSpeed[id] = speed
        segments[id] = makeSegments(total, n, 0f, activeEvery = 2).toMutableList()
        emit(HostEvent.TaskChanged(next))
        emit(HostEvent.TaskRuntimeChanged(runtime(id, total, segments.getValue(id))))
    }

    private suspend fun pauseLocked(id: String) {
        val t = tasks[id] ?: return
        if (!(t.status.isActive || t.status == TaskStatus.Pending)) return
        segments[id]?.replaceAll { it.copy(active = false) }
        update(id) { it.copy(status = TaskStatus.Paused) }
        positions = positions - id
        emit(HostEvent.QueuePositionsChanged(positions))
    }

    private suspend fun resumeLocked(id: String) {
        val t = tasks[id] ?: return
        if (t.status == TaskStatus.Downloading || t.status == TaskStatus.Completed) return
        val status = if (t.totalBytes > 0) TaskStatus.Downloading else TaskStatus.Preparing
        segments[id]?.let { segs ->
            segs.indices.forEach { i -> segs[i] = segs[i].copy(active = segs[i].downloadedBytes < len(segs[i]) && i % 2 == 0) }
        }
        if (status == TaskStatus.Downloading && t.taskId !in baseSpeed) baseSpeed[id] = 3L * MB
        update(id) { it.copy(status = status, errorMessage = "", fileMissing = false) }
    }

    private suspend fun update(id: String, f: (Task) -> Task) {
        val t = tasks[id] ?: return
        val next = f(t)
        tasks[id] = next
        emit(HostEvent.TaskChanged(next))
    }

    private suspend fun emitStats() {
        val down = tasks.values.filter { it.status == TaskStatus.Downloading }.sumOf { baseSpeedNow(it) }
        val up = tasks.values.sumOf { if (it.status == TaskStatus.Downloading || it.seedingStatus == SeedingStatus.Seeding) upSpeed[it.taskId] ?: 0 else 0 }
        emit(
            HostEvent.RuntimeStatsChanged(
                RuntimeStats(
                    activeTasks = tasks.values.count { it.status == TaskStatus.Downloading },
                    pendingTasks = tasks.values.count { it.status == TaskStatus.Pending || it.status == TaskStatus.Preparing },
                    totalDownloadBps = down,
                    totalUploadBps = up,
                    diskFreeBytes = 186L * GB,
                    saveDir = SAVE_ROOT,
                ),
            ),
        )
    }

    private val lastSpeed = HashMap<String, Long>()
    private fun baseSpeedNow(t: Task): Long = lastSpeed[t.taskId] ?: baseSpeed[t.taskId] ?: 0

    private fun progress(t: Task, speed: Long, up: Long): HostEvent.TaskProgress {
        lastSpeed[t.taskId] = speed
        return HostEvent.TaskProgress(
            taskId = t.taskId, status = t.status.wire, downloadedBytes = t.downloadedBytes, totalBytes = t.totalBytes,
            speed = speed, uploadSpeed = up, fileName = t.fileName, errorMessage = t.errorMessage,
            uploadedBytes = t.uploadedBytes, seedingStatus = t.seedingStatus.wire,
        )
    }

    private suspend fun emit(e: HostEvent) {
        sink?.send(HostSignal.Event(e))
    }

    private fun jitter(v: Long): Long = (v * (0.82 + random.nextDouble() * 0.36)).toLong()

    private fun snapshot() = HostSnapshot(
        info = HostInfo("fluxdown-demo", "demo", 7, emptySet()),
        daemonConnected = true,
        tasks = tasks.values.toList(),
        runtime = segments.mapValues { (id, s) -> runtime(id, tasks[id]?.totalBytes ?: 0, s) },
        queues = QUEUES,
        queuePositions = positions,
        groups = GROUPS,
        stats = RuntimeStats(diskFreeBytes = 186L * GB, saveDir = SAVE_ROOT),
        priorityTaskId = priority,
        pendingSelections = pendingSelections.toList(),
        config = config,
        configRevision = configRevision,
        rssSources = rss,
        cloudDevices = CLOUD_DEVICES,
        linkDevices = LINK_DEVICES,
        categories = CATEGORIES,
    )

    private fun runtime(id: String, total: Long, s: List<Segment>) = TaskRuntime(
        taskId = id, sampleSequence = ++seq, activeTransfers = s.count { it.active == true },
        connectedPeers = null, totalBytes = total, segments = s.toList(),
    )

    // ── 种子数据（shared/data.js） ─────────────────────────────────────────

    private fun seed() {
        val now = clock() / 1000
        fun add(t: Task, segCount: Int = 0, speed: Long = 0, up: Long = 0) {
            tasks[t.taskId] = t
            if (speed > 0) baseSpeed[t.taskId] = speed
            if (up > 0) upSpeed[t.taskId] = up
            if (segCount > 0 && t.totalBytes > 0) {
                val p = t.downloadedBytes.toFloat() / t.totalBytes
                segments[t.taskId] = makeSegments(t.totalBytes, segCount, p, if (t.status == TaskStatus.Downloading) 2 else 999).toMutableList()
            }
        }
        add(
            demo("t01", "ubuntu-24.04.3-desktop-amd64.iso", "https://releases.ubuntu.com/24.04.3/ubuntu-24.04.3-desktop-amd64.iso",
                TaskStatus.Downloading, 6.1, 3.78, now - 4 * 60, "程序", sourceBytes = SourceBytes(cdn = (1.9 * GB).toLong(), nic = (0.42 * GB).toLong())),
            16, (18.6 * MB).toLong(),
        )
        add(
            demo("t02", "Interstellar.2014.2160p.UHD.BluRay.x265.mkv", "magnet:?xt=urn:btih:5a8f2c7d9e&dn=Interstellar.2014.2160p",
                TaskStatus.Downloading, 24.3, 9.2, now - 52 * 60, "视频", uploaded = (1.3 * GB).toLong()),
            24, (9.4 * MB).toLong(), (1.1 * MB).toLong(),
        )
        add(demo("t03", "Blender-4.5.2-macos-arm64.dmg", "https://download.blender.org/release/Blender4.5/blender-4.5.2-macos-arm64.dmg", TaskStatus.Preparing, 0.0, 0.0, now - 60, "程序"))
        add(
            demo("t04", "地球脉动 III 第1集 · 4K HDR.mp4", "https://media.example.tv/planet-earth-3/e01/master.m3u8",
                TaskStatus.Downloading, 3.4, 2.41, now - 30 * 60, "视频", sourceBytes = SourceBytes(proxy = (2.41 * GB).toLong())),
            8, (6.2 * MB).toLong(),
        )
        add(demo("t05", "node-v24.4.1.pkg", "https://nodejs.org/dist/v24.4.1/node-v24.4.1.pkg", TaskStatus.Pending, 82.4 / 1024, 0.0, now - 2 * 60, "程序"))
        add(
            demo("t06", "Apple WWDC26 Keynote.mp4", "https://www.youtube.com/watch?v=wwdc26keynote", TaskStatus.Downloading, 1.82, 0.51, now - 15 * 60, "视频"),
            8, (4.8 * MB).toLong(),
        )
        add(demo("t07", "Xcode_27.0.xip", "https://download.developer.apple.com/Developer_Tools/Xcode_27/Xcode_27.0.xip", TaskStatus.Pending, 8.9, 0.0, now - 3 * 3600, "程序", queue = Queue.LATER))
        add(demo("t08", "Android-Studio-2026.2.1-mac_arm.dmg", "https://redirector.gvt1.com/edgedl/android/studio/install/2026.2.1/android-studio-mac_arm.dmg", TaskStatus.Paused, 1.36, 0.88, now - 26 * 3600, "程序", queue = "night"), 8)
        add(
            demo("t09", "imagenet-subset-2026.tar.gz", "https://datasets.example.org/imagenet/subset-2026.tar.gz?sig=expired", TaskStatus.Failed, 12.7, 2.2, now - 2 * 86400, "压缩包",
                error = "HTTP 403 Forbidden：签名链接已过期，请更换下载源"),
            8,
        )
        add(demo("t10", "经典老电影合集.avi", "ed2k://|file|经典老电影合集.avi|1471026176|A1B2C3D4E5F6|/", TaskStatus.Paused, 1.37, 0.31, now - 3 * 86400, "视频"), 8)
        add(demo("t11", "2026 年度财务报告.pdf", "https://intranet.example.com/reports/2026-annual.pdf", TaskStatus.Completed, 18.2 / 1024, 18.2 / 1024, now - 5 * 3600, "文档"))
        add(demo("t12", "周杰伦 - 晴天.flac", "https://music.example.com/flac/qingtian.flac", TaskStatus.Completed, 42.6 / 1024, 42.6 / 1024, now - 4 * 86400, "其他", missing = true))
        add(demo("t13", "router-firmware-v3.2.1.bin", "ftp://ftp.example.net/firmware/router-firmware-v3.2.1.bin", TaskStatus.Completed, 31.5 / 1024, 31.5 / 1024, now - 6 * 86400, "程序"))
        add(
            demo("t14", "[SweetSub] 葬送的芙莉莲 S2 - 05 [1080p][CHS].mkv", "torrent-file://local", TaskStatus.Completed, 1.12, 1.12, now - 9 * 3600, "视频",
                origin = "https://share.example.moe/torrents/frieren-s2-05.torrent", uploaded = (1.68 * GB).toLong(), seeding = SeedingStatus.Seeding, seedSecs = 7420),
            up = 640L * KB,
        )
        add(demo("t15", "IMG_2041.HEIC", "https://photos.example.com/albums/9f2c/IMG_2041.HEIC", TaskStatus.Completed, 3.2 / 1024, 3.2 / 1024, now - 20 * 60, "其他", group = "g1"))
        add(demo("t16", "IMG_2042.HEIC", "https://photos.example.com/albums/9f2c/IMG_2042.HEIC", TaskStatus.Downloading, 2.9 / 1024, 1.7 / 1024, now - 20 * 60, "其他", group = "g1"), 8, 820L * KB)
        add(
            demo("t17", "VID_2043.MOV", "https://photos.example.com/albums/9f2c/VID_2043.MOV", TaskStatus.Failed, 214.0 / 1024, 12.0 / 1024, now - 20 * 60, "视频", group = "g1",
                error = "连接被重置（ECONNRESET），已重试 3 次"),
            8,
        )
        add(demo("t18", "深入理解计算机系统（第4版）.epub", "https://books.example.com/csapp-4e.epub", TaskStatus.Completed, 26.8 / 1024, 26.8 / 1024, now - 2 * 86400, "文档"))
        positions = mapOf("t05" to 1)
        priority = "t01"
    }

    private fun demo(
        id: String, name: String, url: String, status: TaskStatus, totalGb: Double, doneGb: Double, created: Long, dir: String,
        queue: String = "", group: String = "", error: String = "", origin: String = url, missing: Boolean = false,
        uploaded: Long = 0, seeding: SeedingStatus = SeedingStatus.None, seedSecs: Long = 0, sourceBytes: SourceBytes = SourceBytes(),
    ) = Task(
        taskId = id, url = url, originUrl = origin, fileName = name, saveDir = "$SAVE_ROOT/$dir", status = status,
        downloadedBytes = (doneGb * GB).toLong(), totalBytes = (totalGb * GB).toLong(), errorMessage = error, createdAt = created,
        completedAt = if (status == TaskStatus.Completed) created + 600 else 0, queueId = queue, groupId = group,
        fileMissing = missing, uploadedBytes = uploaded, seedingStatus = seeding, seedingTimeSecs = seedSecs, sourceBytes = sourceBytes,
    )

    private fun inferName(url: String): String =
        url.substringAfter("dn=", "").substringBefore('&').ifEmpty { url.substringBefore('?').substringAfterLast('/') }.ifEmpty { "download" }

    companion object {
        private const val KB = 1024L
        private const val MB = 1024L * 1024
        private const val GB = 1024L * 1024 * 1024
        private const val SAVE_ROOT = "/storage/emulated/0/Download/FluxDown"

        private fun len(s: Segment) = s.endByte - s.startByte + 1

        /** 原型 `segs(total, n, progress, activeEvery)`：前段多已下完，活跃段分散在中后部。 */
        fun makeSegments(total: Long, n: Int, progress: Float, activeEvery: Int): List<Segment> {
            val w = DoubleArray(n) { 0.6 + ((it * 37) % 11) / 10.0 }
            val sum = w.sum()
            var start = 0L
            return List(n) { i ->
                val end = if (i == n - 1) total - 1 else start + (total * w[i] / sum).toLong() - 1
                val local = (progress * 1.6 - (i.toDouble() / n) * 0.9 + ((i * 13) % 7) / 40.0).coerceIn(0.0, 1.0)
                val segLen = end - start + 1
                val seg = Segment(i, start, end, (segLen * local).toLong(), local < 1 && i % activeEvery == 0)
                start = end + 1
                seg
            }
        }

        val QUEUES = listOf(
            Queue(queueId = Queue.MAIN, name = "主队列", position = 0),
            Queue(queueId = Queue.LATER, name = "稍后下载", position = 1, isRunning = false),
            Queue(queueId = "night", name = "夜间大文件", position = 2, uploadLimitKbps = 512, maxConcurrent = 2, scheduleEnabled = true, scheduleStart = "01:00", scheduleStop = "07:30"),
            Queue(queueId = "work", name = "工作资料", position = 3, speedLimitKbps = 4096, maxConcurrent = 3, scheduleEnabled = true, scheduleStart = "09:00", scheduleStop = "18:00", scheduleDays = 31),
        )

        val GROUPS = listOf(TaskGroup("g1", "相册导出 · 2026-09-30", "https://photos.example.com/albums/9f2c", "$SAVE_ROOT/相册", 0))

        val BT_FILES = listOf(
            BtFile(0, "Interstellar.2014.2160p/Interstellar.2014.2160p.UHD.BluRay.x265.mkv", 23_900_000_000),
            BtFile(1, "Interstellar.2014.2160p/Subs/chs.srt", 96_000),
            BtFile(2, "Interstellar.2014.2160p/Subs/eng.srt", 88_000),
            BtFile(3, "Interstellar.2014.2160p/Extras/Making.Of.mkv", 1_240_000_000),
            BtFile(4, "Interstellar.2014.2160p/Extras/Trailer.mkv", 210_000_000),
            BtFile(5, "Interstellar.2014.2160p/poster.jpg", 2_400_000),
            BtFile(6, "Interstellar.2014.2160p/README.txt", 4_000),
            BtFile(7, "Interstellar.2014.2160p/sample.mkv", 64_000_000),
        )

        /** 内置基线 + 一个自定义分类（原型 `ebook`）。 */
        val CATEGORIES = Category.BUILTIN.filterNot { it.isOther } +
            Category("ebook", "电子书", "library", listOf("epub", "mobi", "azw3"), position = 7) +
            Category.BUILTIN.first { it.isOther }.copy(position = 8)

        /** 主机配置键（`config` 表，字符串值）；外观 / 移动端本地键不在主机侧。 */
        val DEFAULT_CONFIG = mapOf(
            "default_save_dir" to SAVE_ROOT,
            "download.remember_last_save_dir" to "true",
            "default_queue_id" to "",
            "file_exists_behavior" to "rename",
            "file_missing_action" to "keep",
            "dedup_same_url" to "false",
            "max_concurrent_tasks" to "5",
            "default_segments" to "0",
            "auto_max_connections" to "16",
            "cdn_multi_enabled" to "true",
            "multi_nic_enabled" to "true",
            "speed_limit_bytes" to "0",
            "upload_limit_bytes" to "0",
            "max_auto_retries" to "3",
            "auto_retry_delay_secs" to "5",
            "auto_resume_on_start" to "true",
            "use_server_time" to "false",
            "global_user_agent" to "",
            "download.notify_on_complete" to "true",
            "download.silent_download" to "false",
            "silent_skip_selection" to "false",
        )

        fun initialRss(now: Long) = listOf(
            RssSource("r1", "蜜柑计划 · 葬送的芙莉莲 S2", "https://mikanani.me/RSS/Bangumi?bangumiId=3519", true, true, 30, now - 12 * 60, "", 0, 3),
            RssSource("r2", "Hacker News · Show HN", "https://hnrss.org/show", true, false, 60, now - 40 * 60, "", 0, 18),
            RssSource("r3", "ACG.RIP 新番", "https://acg.rip/.xml", true, true, 30, now - 2 * 86400, "TLS 握手失败：证书已过期", 3, 0),
            RssSource("r4", "Linux 发行版 Torrents", "https://distrowatch.example/torrents.xml", false, true, 720, now - 9 * 86400, "", 0, 0),
        )

        val CLOUD_DEVICES = listOf(
            CloudDevice("d0", "Pixel 10 Pro", "android", isOnline = true, isCurrent = true, appVersion = "0.18.0"),
            CloudDevice("d1", "工作室 Mac mini", "macos", isOnline = true, isCurrent = false, appVersion = "0.18.0"),
            CloudDevice("d2", "办公室 ThinkPad", "windows", isOnline = false, isCurrent = false, appVersion = "0.17.4"),
        )

        val LINK_DEVICES = listOf(
            LinkDevice("SHA256:9F:2C:A1", "书房 Windows 台式机", "windows", online = true),
            LinkDevice("SHA256:41:7B:0D", "客厅 Linux 盒子", "linux", online = false),
        )
    }
}

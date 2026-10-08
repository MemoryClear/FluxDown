package com.fluxdown.app.feature.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.unit.dp
import com.fluxdown.app.AppContainer
import com.fluxdown.app.R
import com.fluxdown.app.data.Density
import com.fluxdown.app.feature.settings.account.AccountText
import com.fluxdown.app.feature.task.copyText
import com.fluxdown.app.i18n.str
import com.fluxdown.app.ui.fileCategory
import com.fluxdown.app.ui.tileIcon
import com.fluxdown.core.format.Format
import com.fluxdown.core.host.HostException
import com.fluxdown.core.model.CloudDevice
import com.fluxdown.core.protocol.CloudConnectionDto
import com.fluxdown.core.protocol.CloudPresence
import com.fluxdown.core.protocol.HostMethod
import com.fluxdown.core.protocol.Json
import com.fluxdown.core.protocol.RemoteCommandAction
import com.fluxdown.core.protocol.RemoteCommandParams
import com.fluxdown.core.protocol.RemoteTaskDto
import com.fluxdown.core.protocol.RemoteTaskRules
import com.fluxdown.core.protocol.RemoteTaskStatus
import com.fluxdown.core.protocol.callUnit
import com.fluxdown.fluxui.data.FlowSegmentUi
import com.fluxdown.fluxui.data.FlowStripState
import com.fluxdown.fluxui.data.MetaTone
import com.fluxdown.fluxui.data.RingKind
import com.fluxdown.fluxui.data.TaskRow
import com.fluxdown.fluxui.data.TaskRowDensity
import com.fluxdown.fluxui.data.buildTaskMeta
import com.fluxdown.fluxui.icons.FluxIcons
import com.fluxdown.fluxui.overlay.FluxDialogButton
import com.fluxdown.fluxui.overlay.FluxDialogButtonStyle
import com.fluxdown.fluxui.overlay.FluxDialogSpec
import com.fluxdown.fluxui.overlay.FluxMenuItem
import com.fluxdown.fluxui.overlay.FluxSwipeAction
import com.fluxdown.fluxui.overlay.FluxSwipeTone
import com.fluxdown.fluxui.overlay.FluxToastKind
import com.fluxdown.fluxui.overlay.LocalFluxOverlays
import com.fluxdown.fluxui.overlay.SwipeReveal
import com.fluxdown.fluxui.theme.FluxText
import com.fluxdown.fluxui.theme.FluxTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 远程任务的目标设备：名称 + 在线状态（null = 云端 presence 不可信，不据此禁用暂停 / 继续）。 */
@Immutable
internal data class RemoteTarget(val name: String, val online: Boolean?) {
    companion object {
        /** 云端名册按 deviceId 索引（同 iOS `DevicesModel.target`）。 */
        fun index(devices: List<CloudDevice>, connectionRaw: String?, localReady: Boolean): Map<String, RemoteTarget> {
            val known = CloudPresence.isKnown(CloudConnectionDto.fromJson(Json.parseOrNull(connectionRaw)), localReady)
            return devices.associate { it.deviceId to RemoteTarget(it.name, if (known) it.isOnline else null) }
        }
    }
}

/**
 * 远程任务命令（`agent.remote.command`）与行内过渡态（同 iOS `DevicesModel.issue` / `isBusy`）：
 * 命令在途，或已被接受但任务的状态 / `updatedAt` 还没回写（最长等 [SETTLE_MS]）时行显示进行中。
 */
@Stable
internal class RemoteCommands(private val container: AppContainer) {
    private class Settling(val status: RemoteTaskStatus, val updatedAt: String)

    private var inFlight by mutableStateOf<Set<String>>(emptySet())
    private var settling by mutableStateOf<Map<String, Settling>>(emptyMap())

    fun isBusy(task: RemoteTaskDto): Boolean {
        if (task.id in inFlight) return true
        val pending = settling[task.id] ?: return false
        return pending.status == task.status && pending.updatedAt == task.updatedAt
    }

    /** 适用性由调用方经 [RemoteTaskRules.canIssue] 保证；失败交给 [onError]。 */
    fun issue(action: RemoteCommandAction, task: RemoteTaskDto, deleteFiles: Boolean, onError: (HostException) -> Unit) {
        val id = task.id
        if (id in inFlight) return
        inFlight = inFlight + id
        settling = settling - id
        val before = Settling(task.status, task.updatedAt)
        val session = container.session
        container.appScope.launch {
            try {
                session.callUnit(HostMethod.agentRemoteCommand, RemoteCommandParams(id, action, deleteFiles = deleteFiles).toJson())
            } catch (e: HostException) {
                inFlight = inFlight - id
                onError(e)
                return@launch
            }
            inFlight = inFlight - id
            settling = settling + (id to before)
            delay(SETTLE_MS)
            if (settling[id] === before) settling = settling - id
        }
    }

    private companion object {
        /** 命令落地后等待状态事件回写的最长时间。 */
        const val SETTLE_MS = 6_000L
    }
}

/** 「远程任务」分区头（同历史分区头的安静小标题）。 */
@Composable
internal fun RemoteSectionHeader(e: RemoteHeaderEntry) {
    val c = FluxTheme.colors
    val t = FluxTheme.type
    val margin = FluxTheme.space.screenMargin
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = margin + 6.dp, end = margin + 6.dp, top = 26.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FluxText(str(R.string.remoteTasksGroup), style = t.micro, color = c.inkMuted, maxLines = 1)
        FluxText(str(R.string.nTasks, "n" to e.count), style = t.monoS, color = c.inkFaint, maxLines = 1)
    }
}

/**
 * 其他设备上执行的远程任务行（同 iOS `RemoteTaskRow` / PC 下载页远程行）：`→ 目标设备` + 状态 + 进度 / 字节 / 速度；
 * 环 = 暂停 / 继续（命令在途旋转；没有主操作时打开动作菜单），滑动取消 / 删除，长按含全部可用动作。
 * 控制矩阵只走 [RemoteTaskRules.canIssue]；未知状态没有任何控制。不参与多选，没有详情页。
 */
@Composable
internal fun RemoteTaskRow(entry: RemoteRowEntry, style: RowStyle, a11y: Boolean) {
    val task = entry.task
    val view = LocalDownloadsView.current
    val overlays = LocalFluxOverlays.current
    val haptics = FluxTheme.haptics
    val colors = FluxTheme.colors
    val context = LocalContext.current
    val margin = FluxTheme.space.screenMargin
    val compact = style.density == Density.Compact

    val target = view.remoteTargets[task.toDevice]
    val targetName = target?.name?.ifBlank { null } ?: task.toDevice
    val online = target?.online
    val busy = view.remoteCommands.isBusy(task)
    val readOnly = view.readOnly
    val latest by rememberUpdatedState(task)

    val s = RemoteRowStrings(
        pause = str(R.string.pause),
        resume = str(R.string.resume),
        cancel = str(R.string.cancel),
        delete = str(R.string.delete),
        copyLink = str(R.string.mobileSwipeCopyLink),
        more = str(R.string.moreActions),
        offline = str(R.string.errReasonTargetDeviceOffline),
    )

    fun allows(action: RemoteCommandAction): Boolean =
        !readOnly && !busy && RemoteTaskRules.canIssue(action, latest, online)

    fun issue(action: RemoteCommandAction, deleteFiles: Boolean = false) {
        haptics.tick()
        view.remoteCommands.issue(action, latest, deleteFiles) { e ->
            haptics.reject()
            overlays.toast(AccountText.error(context, e), FluxToastKind.Error)
        }
    }

    fun copyLink() {
        context.copyText(entry.name, latest.url)
        overlays.toast(context.getString(R.string.urlCopied), FluxToastKind.Success, FluxIcons.Copy)
    }

    fun confirmDelete() {
        overlays.showDialog(
            FluxDialogSpec(
                title = context.getString(R.string.deleteTask),
                message = entry.name,
                icon = FluxIcons.Trash2,
                buttons = buildList {
                    add(FluxDialogButton(context.getString(R.string.cancel)))
                    // 目标离线时无法让它删除文件，只能删记录：不提供「同时删除文件」
                    if (online != false) {
                        add(
                            FluxDialogButton(context.getString(R.string.deleteTaskAndFile), FluxDialogButtonStyle.Destructive) {
                                issue(RemoteCommandAction.Delete, deleteFiles = true)
                            },
                        )
                    }
                    add(
                        FluxDialogButton(context.getString(R.string.deleteTask), FluxDialogButtonStyle.Primary) {
                            issue(RemoteCommandAction.Delete)
                        },
                    )
                },
                stacked = true,
            ),
        )
    }

    val primary = RemoteTaskRules.primaryAction(task)
    val primaryLabel = when (primary) {
        RemoteCommandAction.Pause -> s.pause
        RemoteCommandAction.Resume -> s.resume
        else -> s.more
    }

    fun menuItems(): List<FluxMenuItem> = buildList {
        if (primary != null && allows(primary)) {
            add(
                FluxMenuItem.Action(
                    primaryLabel,
                    { issue(primary) },
                    icon = if (primary == RemoteCommandAction.Pause) FluxIcons.Pause else FluxIcons.Play,
                ),
            )
        }
        if (allows(RemoteCommandAction.Cancel)) add(FluxMenuItem.Action(s.cancel, { issue(RemoteCommandAction.Cancel) }, icon = FluxIcons.X))
        if (latest.url.isNotEmpty()) add(FluxMenuItem.Action(s.copyLink, ::copyLink, icon = FluxIcons.Copy))
        if (allows(RemoteCommandAction.Delete)) {
            add(FluxMenuItem.Divider)
            add(FluxMenuItem.Action(s.delete, ::confirmDelete, icon = FluxIcons.Trash2, destructive = true))
        }
    }

    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    fun openMenu() {
        val c = coords[0] ?: return
        if (!c.isAttached) return
        val items = menuItems()
        if (items.isNotEmpty()) overlays.showMenu(c.boundsInRoot(), items)
    }

    // 进行中 / 已暂停的任务因目标离线而不能暂停 / 继续
    val offlineBlocksPrimary = primary != null && !RemoteTaskRules.canIssue(primary, task, online)
    val meta = remember(task, targetName, offlineBlocksPrimary, colors, s) {
        colors.buildTaskMeta {
            part("→ $targetName", MetaTone.Ink)
            val status = task.status
            when (status) {
                RemoteTaskStatus.Failed -> part(firstLine(task.error) ?: context.getString(R.string.statusError), MetaTone.Coral)
                RemoteTaskStatus.Completed -> part(context.getString(R.string.statusCompleted), MetaTone.Mint)
                RemoteTaskStatus.Downloading -> Format.speed(task.speed)?.let { part(it.toString(), MetaTone.Accent) }
                    ?: part(context.getString(R.string.statusDownloading))
                RemoteTaskStatus.Pending, RemoteTaskStatus.Accepted -> part(context.getString(R.string.statusPending))
                RemoteTaskStatus.Paused -> part(context.getString(R.string.statusPaused))
                RemoteTaskStatus.Canceled -> part(context.getString(R.string.statusCanceled))
                is RemoteTaskStatus.Unknown -> part(context.getString(R.string.mobileRemoteStatusUnknown))
            }
            if (status !is RemoteTaskStatus.Unknown && status != RemoteTaskStatus.Failed) {
                part(Format.percent(task.progress.toFloat().coerceIn(0f, 1f)))
                val total = task.totalBytes
                part(
                    if (total != null && total > 0) {
                        "${Format.bytes(task.downloadedBytes)}/${Format.bytes(total)}"
                    } else {
                        Format.bytes(task.downloadedBytes).toString()
                    },
                )
            }
            if (offlineBlocksPrimary) part(s.offline, MetaTone.Amber)
        }
    }

    val progress = task.progress.toFloat().coerceIn(0f, 1f)
    val flowState = when (task.status) {
        RemoteTaskStatus.Downloading -> FlowStripState.Downloading
        RemoteTaskStatus.Pending, RemoteTaskStatus.Accepted -> FlowStripState.Pending
        RemoteTaskStatus.Paused, RemoteTaskStatus.Canceled -> FlowStripState.Paused
        RemoteTaskStatus.Failed -> FlowStripState.Failed
        RemoteTaskStatus.Completed, is RemoteTaskStatus.Unknown -> FlowStripState.Completed
    }
    val showFlow = task.status != RemoteTaskStatus.Completed && task.status !is RemoteTaskStatus.Unknown
    val ringKind = when {
        busy -> RingKind.Spinning
        primary == RemoteCommandAction.Pause -> RingKind.Pause
        primary == RemoteCommandAction.Resume -> RingKind.Play
        task.status == RemoteTaskStatus.Completed -> RingKind.Seeding
        else -> RingKind.More
    }

    fun onRing() {
        when {
            busy || readOnly -> Unit
            primary != null && allows(primary) -> issue(primary)
            primary != null -> {
                haptics.reject()
                overlays.toast(s.offline, FluxToastKind.Warn, FluxIcons.WifiOff)
            }
            else -> openMenu()
        }
    }

    val startActions = if (primary != null && allows(primary)) {
        listOf(
            FluxSwipeAction(primaryLabel, if (primary == RemoteCommandAction.Pause) FluxIcons.Pause else FluxIcons.Play, FluxSwipeTone.Accent) {
                issue(primary)
            },
        )
    } else {
        emptyList()
    }
    val endActions = buildList {
        if (allows(RemoteCommandAction.Cancel)) add(FluxSwipeAction(s.cancel, FluxIcons.X, FluxSwipeTone.Warn) { issue(RemoteCommandAction.Cancel) })
        if (allows(RemoteCommandAction.Delete)) add(FluxSwipeAction(s.delete, FluxIcons.Trash2, FluxSwipeTone.Danger, ::confirmDelete))
    }
    val customActions = if (a11y) {
        menuItems().filterIsInstance<FluxMenuItem.Action>().map { a -> CustomAccessibilityAction(a.label) { a.onClick(); true } }
    } else {
        emptyList()
    }

    Box(Modifier.fillMaxWidth().padding(horizontal = margin)) {
        Box(
            Modifier
                .fillMaxWidth()
                .cardSegment(entry.first, entry.last)
                .onGloballyPositioned { coords[0] = it },
        ) {
            SwipeReveal(startActions = startActions, endActions = endActions, enabled = !readOnly) {
                TaskRow(
                    fileName = entry.name,
                    meta = meta,
                    tileIcon = entry.category.tileIcon(bt = task.url.startsWith("magnet:")),
                    categoryColor = colors.category(entry.category.fileCategory()),
                    ringKind = ringKind,
                    ringProgress = if (showFlow || busy) progress else null,
                    ringContentDescription = primaryLabel,
                    onClick = ::openMenu,
                    onLongClick = ::openMenu,
                    onRingClick = ::onRing,
                    onTileClick = ::openMenu,
                    flowSegments = if (showFlow) listOf(FlowSegmentUi(1f, progress, task.status == RemoteTaskStatus.Downloading)) else null,
                    flowState = flowState,
                    density = if (compact) TaskRowDensity.Compact else TaskRowDensity.Comfortable,
                    horizontalPadding = 18.dp,
                    onClickLabel = s.more,
                    customActions = customActions,
                )
            }
        }
    }
}

@Immutable
private data class RemoteRowStrings(
    val pause: String,
    val resume: String,
    val cancel: String,
    val delete: String,
    val copyLink: String,
    val more: String,
    val offline: String,
)

private fun firstLine(s: String?): String? = s?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }

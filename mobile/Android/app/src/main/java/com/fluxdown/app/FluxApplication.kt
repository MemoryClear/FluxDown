package com.fluxdown.app

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import com.fluxdown.app.data.AppearanceRepo
import com.fluxdown.app.data.ViewPrefsRepo
import com.fluxdown.core.demo.DemoHostSession
import com.fluxdown.core.host.HostSession
import com.fluxdown.core.model.HostRef
import com.fluxdown.core.store.HostStore
import com.fluxdown.fluxui.theme.FluxFonts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private val Context.prefs by preferencesDataStore(name = "fluxdown")

class FluxApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // 冷启动在后台解析字体文件，避免首帧在主线程加载 ~5MB 字体
        container.appScope.launch(Dispatchers.IO) { FluxFonts.preload(this@FluxApplication) }
    }
}

/**
 * 进程级依赖：主机会话、状态仓库、设备本地偏好。
 *
 * 当前主机 = 演示主机（[DemoHostSession]）。UniFFI `native/mobile` 接入后，此处改为按
 * [HostRef] 打开本机 / 远端会话；UI 只依赖 [HostSession] 端口与 [HostStore]，无需改动。
 */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** HostStore 要求串行调度（工作副本单线程访问）。 */
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    val appearance = AppearanceRepo(context.prefs)
    val viewPrefs = ViewPrefsRepo(context.prefs)
    val store = HostStore(storeScope)

    private val _host = MutableStateFlow<HostRef>(HostRef.Demo(displayName = "Demo"))
    val host: StateFlow<HostRef> = _host.asStateFlow()

    var session: HostSession = DemoHostSession()
        private set

    init {
        store.attach(session)
    }

    private var lastRescanMs = 0L

    /**
     * 回到前台时请求主机重扫文件跟踪（`daemon.task.rescan`），10s 冷却。
     * 前后台均不周期轮询（空闲静默，AGENTS.md §4）。
     */
    fun rescanOnForeground() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastRescanMs < RESCAN_COOLDOWN_MS) return
        lastRescanMs = now
        appScope.launch {
            try {
                session.rescan()
            } catch (e: com.fluxdown.core.host.HostException) {
                android.util.Log.w("FluxDown", "rescan failed: ${e.code} ${e.message}")
            }
        }
    }

    private companion object {
        const val RESCAN_COOLDOWN_MS = 10_000L
    }
}

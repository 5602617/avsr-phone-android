package io.github.lejw0925.virtualscreen

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import io.github.lejw0925.virtualscreen.core.code
import io.github.lejw0925.virtualscreen.core.ActivityLog
import io.github.lejw0925.virtualscreen.core.ActivityLogEntry
import io.github.lejw0925.virtualscreen.core.RuntimeDiagnostics
import io.github.lejw0925.virtualscreen.core.failure
import io.github.lejw0925.virtualscreen.core.runtime
import io.github.lejw0925.virtualscreen.core.VirtualScreenSessionManager
import io.github.lejw0925.virtualscreen.core.ScreenAccessibilityService
import io.github.lejw0925.virtualscreen.phone.embedded.EmbeddedPrivilege
import io.github.lejw0925.virtualscreen.mcp.McpServerController
import io.github.lejw0925.virtualscreen.tunnel.TunnelManager
import io.github.lejw0925.virtualscreen.tunnel.TunnelMode
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update

class VirtualScreenApplication : Application() {
    private val runtimeScope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val runtimeMutex = Mutex()
    @Volatile private var shutdownJob: Job? = null
    private var startupJob: Job? = null
    private val mutableMcpChanging = MutableStateFlow(false)
    val mcpChanging = mutableMcpChanging.asStateFlow()
    private val mutableRuntimeTasks = MutableStateFlow(0)
    val runtimeTasks = mutableRuntimeTasks.asStateFlow()
    suspend fun <T> runtimeWork(block: suspend () -> T): T {
        mutableRuntimeTasks.update { it + 1 }
        try { return block() }
        catch (failure: Exception) { activityLog.failure("runtime_task", failure); throw failure }
        finally { mutableRuntimeTasks.update { it - 1 } }
    }
    val mcpWanted: Boolean get() = getSharedPreferences("runtime",MODE_PRIVATE).getBoolean("mcpEnabled",false)
    /** Tunnel mode the user last connected with; restored whenever MCP starts. Stops initiated by MCP shutdown keep it. */
    var tunnelWanted: TunnelMode
        get() = getSharedPreferences("runtime",MODE_PRIVATE).getString("tunnelMode",null)?.let { runCatching { TunnelMode.valueOf(it) }.getOrNull() } ?: TunnelMode.OFF
        set(mode) { getSharedPreferences("runtime",MODE_PRIVATE).edit().putString("tunnelMode",mode.name).apply() }
    private fun rememberMcp(enabled: Boolean) { getSharedPreferences("runtime",MODE_PRIVATE).edit().putBoolean("mcpEnabled",enabled).commit() }
    fun setMcpEnabled(enabled: Boolean) {
        activityLog.runtime("mcp_switch", if (enabled) "enable_requested" else "disable_requested")
        rememberMcp(enabled)
        if(enabled) {
            startForegroundService(Intent(this,VirtualScreenForegroundService::class.java))
            restoreMcp()
        } else {
            mutableMcpChanging.value = true
            runtimeScope.launch {
                try { runtimeMutex.withLock { tunnels.stop(); mcp.stop() } }
                finally { mutableMcpChanging.value = false }
            }
        }
    }
    /** Runs in the application scope so navigating away cannot cancel server startup. */
    @Synchronized fun restoreMcp() {
        if(!mcpWanted || startupJob?.isActive == true) return
        mutableMcpChanging.value = true
        startupJob = runtimeScope.launch {
            activityLog.runtime("mcp_restore", "starting", "backend=${settings.flow.value.backend}")
            try {
                awaitShutdown()
                runtimeMutex.withLock {
                    if(!mcpWanted) return@withLock
                    try {
                        sessions.initializeBackend(settings.flow.value.backend)
                        sessions.ensureAccessibility()
                    } catch(c: CancellationException) { throw c }
                      catch(e: Exception) {
                        activityLog.failure("display_backend_unavailable",e)
                    }
                    mcp.start(settings.flow.value.lan).getOrThrow()
                    sessions.clearInactiveDisplayError()
                    val wantedTunnel = tunnelWanted
                    if(wantedTunnel != TunnelMode.OFF && !tunnels.state().running) {
                        val (frpc, cloudflare) = tunnels.loadProfile()
                        runCatching { tunnels.start(wantedTunnel, frpc, cloudflare) }
                            .onFailure { activityLog.failure("tunnel_restore", it) }
                    }
                }
            } catch(c: CancellationException) { throw c }
              catch(e: Exception) { activityLog.failure("mcp_restore", e); rememberMcp(false); mcp.reportStartupError(e.code()) }
            finally { mutableMcpChanging.value = false }
        }
    }
    suspend fun awaitShutdown() { shutdownJob?.join() }
    @Synchronized fun stopRuntime(preserveMcp: Boolean = false) {
        if(::phoneConnection.isInitialized) phoneConnection.disconnect()
        if(!preserveMcp) rememberMcp(false)
        if(shutdownJob?.isActive == true) return
        shutdownJob = runtimeScope.launch { runtimeMutex.withLock {
            activityLog.runtime("runtime", "stopping", "preserve_mcp=$preserveMcp")
            runCatching { tunnels.stop() }.onFailure { activityLog.failure("tunnel_stop", it) }
            runCatching { mcp.stop() }.onFailure { activityLog.failure("mcp_stop", it) }
            runCatching { sessions.shutdown() }.onFailure { activityLog.failure("screen_shutdown", it) }
            activityLog.runtime("runtime", "stopped")
        } }
    }
    lateinit var activityLog: ActivityLog
        private set
    lateinit var sessions: VirtualScreenSessionManager
        private set
    lateinit var mcp: McpServerController
        private set
    lateinit var tunnels: TunnelManager
        private set

    lateinit var settings: io.github.lejw0925.virtualscreen.core.AppSettings
        private set
    lateinit var qqMusic: io.github.lejw0925.virtualscreen.media.QqMusicController
        private set
    lateinit var phoneSession: io.github.lejw0925.virtualscreen.phone.PhoneSession
        private set
    lateinit var phoneGlance: io.github.lejw0925.virtualscreen.phone.PhoneGlance
        private set
    lateinit var mainScreen: io.github.lejw0925.virtualscreen.phone.MainScreenControl
        private set
    lateinit var wirelessAdb: io.github.lejw0925.virtualscreen.phone.WirelessAdb
        private set
    lateinit var systemTools: io.github.lejw0925.virtualscreen.systemtools.PhoneSystemTools
        private set
    lateinit var phoneConnection: io.github.lejw0925.virtualscreen.phone.HostSocketClient
        private set
    lateinit var avsr: io.github.lejw0925.virtualscreen.app.AvsrAppController
        private set

    override fun onCreate() {
        super.onCreate()
        activityLog = ActivityLog(this)
        RuntimeDiagnostics(this, activityLog).install()
        org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("L")
        settings = io.github.lejw0925.virtualscreen.core.AppSettings(this)
        qqMusic = io.github.lejw0925.virtualscreen.media.QqMusicController(this)
        sessions = VirtualScreenSessionManager(this,configuredDisplay={ settings.configForViewer() }, log=activityLog,
            autoApproveRestart={ settings.flow.value.autoApproveRestart }, initialBackend=settings.flow.value.backend)
        phoneSession = io.github.lejw0925.virtualscreen.phone.PhoneSession(this)
        phoneGlance = io.github.lejw0925.virtualscreen.phone.PhoneGlance(this)
        mainScreen = io.github.lejw0925.virtualscreen.phone.MainScreenControl(this)
        systemTools = io.github.lejw0925.virtualscreen.systemtools.PhoneSystemTools(this)
        mcp = McpServerController(this, sessions, log=activityLog)
        tunnels = TunnelManager(this, mcpPort = { mcp.state().port }, log=activityLog)
        phoneConnection = io.github.lejw0925.virtualscreen.phone.HostSocketClient(this)
        wirelessAdb = io.github.lejw0925.virtualscreen.phone.WirelessAdb(this)
        avsr = io.github.lejw0925.virtualscreen.app.AvsrAppController(this)
        runtimeScope.launch {
            var privilegeWasReady = false
            combine(EmbeddedPrivilege.state, ScreenAccessibilityService.connected) { privilege, connected ->
                privilege to connected
            }.collect { (privilege, connected) ->
                val privilegeReady = privilege.phase == "ready"
                val justReady = privilegeReady && !privilegeWasReady
                privilegeWasReady = privilegeReady
                try {
                    // The saved backend was injected at construction. Do not overwrite a later
                    // explicit selection while its UI coroutine is persisting that choice.
                    sessions.refreshCapabilities()
                    // MCP can start before the local privilege service is ready.
                    // Restore an existing grant once at readiness, not on every
                    // disconnect (which could endlessly restart a failing service).
                    if (justReady && !connected) sessions.ensureAccessibility(requireExistingGrant = true)
                } catch (cancelled: CancellationException) { throw cancelled }
                  catch (failure: Exception) { activityLog.failure("backend_capabilities_refresh", failure) }
            }
        }
        avsr.login.restore()
        wirelessAdb.watchNetwork()
    }

    fun diagnosticReport(entries: List<ActivityLogEntry>): String = buildString {
        val screen = sessions.state.value
        val server = mcp.state()
        val tunnel = tunnels.state()
        appendLine("Virtual Screen diagnostic report · ${java.time.Instant.now()}")
        appendLine(RuntimeDiagnostics.environment(this@VirtualScreenApplication))
        appendLine("backend=${screen.backend}; state=${screen.status}; display=${screen.displayId}; error=${screen.errorCode}")
        appendLine("display_config=${screen.config.width}x${screen.config.height}@${screen.config.density}; preview=${screen.previewStatus}")
        appendLine("capabilities: display=${screen.capabilities.displayCreation}; capture=${screen.capabilities.frameCapture}; input=${screen.capabilities.inputInjection}; nodes=${screen.capabilities.accessibilityNodes}")
        appendLine("mcp_wanted=$mcpWanted; mcp_running=${server.running}; mcp_error=${server.lastError}; clients=${server.sessionCount}")
        appendLine("tunnel=${tunnel.mode}; phase=${tunnel.phase}; tunnel_error=${tunnel.errorCode}")
        appendLine("uptime_ms=${android.os.SystemClock.uptimeMillis()}; log_storage_error=${activityLog.writeError.value}")
        appendLine()
        entries.forEach { appendLine(it.text()) }
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        activityLog.runtime("memory", if (level == TRIM_MEMORY_UI_HIDDEN) "background" else "warning", "level=$level")
    }
}

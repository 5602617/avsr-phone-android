package io.github.lejw0925.virtualscreen.phone

import io.github.lejw0925.virtualscreen.BuildConfig
import io.github.lejw0925.virtualscreen.VirtualScreenApplication
import io.github.lejw0925.virtualscreen.core.OperationEnvelope
import io.github.lejw0925.virtualscreen.tunnel.TunnelState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PhoneBinding(val deviceId: String = "", val connectionId: String? = null,
    val phase: String = "disconnected", val executionEnabled: Boolean = false, val reason: String = "")

/** Deliberately excludes addresses, process logs and credentials. */
internal data class PhoneTunnelStatus(val phase: String, val running: Boolean, val errorCode: String?) {
    val connected get() = running && phase == "connected" && errorCode == null
    val failed get() = !running || phase == "error"
    fun connectionPhase(hadConnection: Boolean): String = when {
        connected -> "registering"
        failed -> "connection_failed"
        hadConnection || phase == "reconnecting" || errorCode != null -> "tunnel_reconnecting"
        else -> "tunnel_connecting"
    }
    fun reason(): String = errorCode ?: when {
        failed -> if (phase == "stopped") "TUNNEL_STOPPED" else "TUNNEL_START_FAILED"
        phase == "reconnecting" -> "TUNNEL_RECONNECTING"
        else -> ""
    }
    fun receipt(): Map<String, Any?> = mapOf("phase" to phase, "running" to running, "error_code" to errorCode)
    companion object {
        fun from(state: TunnelState) = PhoneTunnelStatus(state.phase, state.running,
            state.errorCode?.let { it.takeIf { code -> code.matches(Regex("[A-Z][A-Z0-9_]{0,95}")) } ?: "TUNNEL_FAILED" })
    }
}

/** Local fence is independent of the host conversation and is never cleared by a tool call. */
class PhoneSession(private val app: VirtualScreenApplication) {
    @Volatile private var glanceBearer = ""
    fun acceptsGlanceBearer(token: String?): Boolean = token != null && glanceBearer.length >= 24 &&
        java.security.MessageDigest.isEqual(token.toByteArray(),glanceBearer.toByteArray())
    private val prefs = app.getSharedPreferences("phone_connection", 0)
    private val mutable = MutableStateFlow(PhoneBinding())
    val flow = mutable.asStateFlow()
    var required: Boolean = prefs.getBoolean("required", false)
        private set
    @Synchronized fun bind(deviceId: String, connectionId: String, glanceToken: String = "") {
        if (mutable.value.connectionId != null && mutable.value.connectionId != connectionId) pause("connection_replaced")
        glanceBearer = glanceToken
        required = true
        prefs.edit().putBoolean("required", true).apply()
        mutable.value = PhoneBinding(deviceId, connectionId, "probing")
    }
    @Synchronized fun ready() { mutable.value = mutable.value.copy(phase = "ready") }
    @Synchronized fun pause(reason: String) {
        mutable.value = mutable.value.copy(executionEnabled = false, reason = reason)
        app.mainScreen.stop(if (reason == "user_stop") "USER_INTERRUPTED" else reason.uppercase())
        app.systemTools.invalidatePendingAlarmRequests()
        app.sessions.cancelActive()
        app.mcp.cancelPhoneCalls()
    }
    @Synchronized fun disconnect(reason: String) {
        glanceBearer = ""
        pause(reason)
        mutable.value = mutable.value.copy(connectionId = null, phase = "disconnected")
    }
    @Synchronized fun resume() {
        check(mutable.value.phase == "ready") { "PHONE_NOT_READY" }
        check(!app.mainScreen.policy.state.value.humanHandoff) { "HUMAN_HANDOFF_PENDING" }
        app.sessions.allowRemoteControl()
        mutable.value = mutable.value.copy(executionEnabled = true, reason = "")
    }
    @Synchronized fun requestHumanHandoff(taskId: String, sessionId: String, reason: String,
        owner: String, requestId: String) {
        checkHumanBinding(taskId, sessionId)
        app.mainScreen.policy.requestHumanHandoff(taskId, sessionId, reason)
        app.systemTools.invalidatePendingAlarmRequests()
        app.mainScreen.detachLeaseForHumanHandoff()
        app.mcp.cancelPhoneCallsExcept(owner, requestId)
    }
    @Synchronized fun acceptHumanHandoff(taskId: String, sessionId: String, handoffId: String) {
        checkHumanBinding(taskId, sessionId)
        app.mainScreen.policy.acceptHumanHandoff(taskId, sessionId, handoffId)
        val held = app.sessions.leaseStatus()
        val local = if (held?.owner == io.github.lejw0925.virtualscreen.core.VirtualScreenSessionManager.PHONE_CLIENT) held
            else app.sessions.phoneTakeover()
        app.mainScreen.rememberHumanLease(local.generation)
    }
    @Synchronized fun returnHumanHandoff(taskId: String, sessionId: String, handoffId: String) {
        checkHumanBinding(taskId, sessionId)
        val usage = app.mainScreen.policy.state.value
        check(usage.phase == "human_control" && usage.handoffSessionId == sessionId && usage.handoffId == handoffId) { "PHONE_HANDOFF_REQUEST_STALE" }
        app.mainScreen.returnHumanLease()
        app.mainScreen.policy.returnHumanHandoff(taskId, sessionId, handoffId)
    }
    private fun checkHumanBinding(taskId: String, sessionId: String) {
        val binding = mutable.value
        val usage = app.mainScreen.policy.state.value
        check(binding.phase == "ready" && binding.executionEnabled) { "PHONE_EXECUTION_PAUSED" }
        check(binding.connectionId != null && usage.connectionId == binding.connectionId && usage.taskId == taskId) {
            "PHONE_HANDOFF_REQUEST_STALE"
        }
        check(app.sessions.state.value.displayId != null && app.sessions.state.value.sessionId == sessionId) {
            "HUMAN_HANDOFF_SCREEN_LOST"
        }
    }
    /** Trusted host WS task lifecycle; inputId is correlated to a local submit consent, never interpreted as text. */
    @Synchronized fun bindTask(taskId: String, inputId: String? = null, executionId: String? = null) {
        check(mutable.value.phase == "ready" && mutable.value.executionEnabled) { "PHONE_NOT_READY" }
        app.mainScreen.bindTask(taskId, inputId, executionId)
    }
    @Synchronized fun finishTask(taskId: String, outcome: String = "completed", reason: String = "") {
        app.mainScreen.finishTask(taskId, outcome, reason)
    }
    @Synchronized fun requestMainScreen(taskId: String, reason: String): Boolean {
        check(required) { "PHONE_TASK_BINDING_REQUIRED" }
        val connectionId = checkNotNull(mutable.value.connectionId) { "PHONE_DISCONNECTED" }
        return app.mainScreen.policy.requestMainScreen(taskId, connectionId, reason)
    }
    fun checkTaskHeader(name: String, taskId: String?) {
        if (!required || name !in TASK_BOUND_TOOLS) return
        val current = app.mainScreen.policy.state.value
        check(current.taskId != null && taskId == current.taskId) { "PHONE_TASK_BINDING_REQUIRED" }
    }
    fun accepts(connectionId: String?): Boolean = !required ||
        (connectionId != null && connectionId == mutable.value.connectionId)
    fun usageReceipt(): Map<String, Any?> = app.mainScreen.policy.receipt()
    fun checkOperation(name: String) {
        val usage = app.mainScreen.policy.state.value
        if (usage.humanHandoff && name !in HUMAN_READ_TOOLS)
            error(if (usage.phase == "human_control") "HUMAN_CONTROL_ACTIVE" else "HUMAN_HANDOFF_PENDING")
        if (name == "phone_request_main_screen")
            check(usage.live) { usage.failureCode ?: "PHONE_TASK_BINDING_REQUIRED" }
        if (name in SCREEN_TOOLS) {
            if (required) check(usage.live) { usage.failureCode ?: "PHONE_TASK_BINDING_REQUIRED" }
            check(usage.phase != "waiting_borrow") {
                if (usage.borrowRequestSource == "direct_request") "MAIN_SCREEN_REQUEST_PENDING" else "MAIN_SCREEN_BORROW_PENDING"
            }
            check(usage.phase != "countdown") { "MAIN_SCREEN_COUNTDOWN" }
            if (usage.mainScreenAllowed && !name.startsWith("main_screen_")) error("MAIN_SCREEN_MODE_ACTIVE")
            if (name.startsWith("main_screen_")) check(usage.mainScreenAllowed) { "BACKGROUND_MAIN_SCREEN_NOT_ALLOWED" }
        }
        if(required && name !in setOf("phone_get_status", "get_status", "get_request_receipt", "qqmusic_get_playback",
                "phone_glance_status", "phone_glance_capture", "alarm_get_next", "alarm_get_request", "calendar_list", "calendar_read")) {
            check(mutable.value.connectionId != null) { "PHONE_DISCONNECTED" }
            check(mutable.value.executionEnabled) { "PHONE_EXECUTION_PAUSED" }
        }
    }
    fun status(): OperationEnvelope {
        val binding = mutable.value
        val screen = app.sessions.state.value
        val tunnel = PhoneTunnelStatus.from(app.tunnels.state())
        val target = runCatching { app.packageManager.getPackageInfo("com.tencent.qqmusic", 0) }.getOrNull()
        return OperationEnvelope.success(
            "device_id" to binding.deviceId, "connection_id" to binding.connectionId,
            "phase" to binding.phase, "execution_enabled" to binding.executionEnabled,
            "main_screen_glance_v1" to true,
            "main_screen_enabled" to app.mainScreen.active,
            "main_screen_task_input_id" to app.mainScreen.taskInputId,
            "execution_display" to if (app.mainScreen.active) "main_screen" else "virtual_screen",
            "reason" to binding.reason,
            "tunnel" to tunnel.receipt(),
            "apk" to mapOf("package_name" to app.packageName, "version_code" to BuildConfig.VERSION_CODE),
            "adapter" to mapOf("id" to "virtual-screen-qqmusic", "version" to BuildConfig.VERSION_NAME,
                "target_package" to "com.tencent.qqmusic", "target_version_code" to target?.longVersionCode),
            "capabilities" to mapOf("display" to screen.capabilities.displayCreation.name,
                "capture" to screen.capabilities.frameCapture.name, "input" to screen.capabilities.inputInjection.name,
                "nodes" to screen.capabilities.accessibilityNodes.name, "error" to screen.errorCode),
        ).let { it.copy(values = it.values + app.mainScreen.policy.receipt()) }
    }
    companion object {
        val SCREEN_TOOLS = setOf("create_screen", "configure_screen", "close_screen", "launch_app", "observe", "wait",
            "tap_at", "long_press_at", "swipe_path", "scroll_at", "input_text", "press_key", "node_action",
            "main_screen_observe", "main_screen_node_action", "main_screen_gesture", "main_screen_back", "main_screen_launch_app", "main_screen_finish",
            "phone_request_handoff")
        val TASK_BOUND_TOOLS = SCREEN_TOOLS + "phone_request_main_screen"
        private val HUMAN_READ_TOOLS = setOf("phone_get_status", "get_status", "get_request_receipt", "list_apps",
            "qqmusic_get_playback", "phone_glance_status", "alarm_get_next", "alarm_get_request", "calendar_list", "calendar_read")
    }
}

package io.github.lejw0925.virtualscreen.phone

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PhoneUsageState(
    val mode: String = "background",
    val taskMode: String = "background",
    val taskId: String? = null,
    val inputId: String? = null,
    val connectionId: String? = null,
    val phase: String = "idle",
    val failureCode: String? = null,
    val failureCategory: String? = null,
    val decision: String = "none",
    val grantSource: String? = null,
    val deadlineMs: Long? = null,
    val countdownSeconds: Int = 0,
    val borrowTimeoutSeconds: Int = 30,
    val retryUsed: Boolean = false,
    val restoreStatus: String = "not_needed",
    val pendingInputGrant: String? = null,
    val application: String? = null,
    val step: String = "",
    val handoffReason: String = "",
    val handoffSessionId: String? = null,
    val handoffId: String? = null,
    val handoffAction: String = "",
    val executionId: String? = null,
) {
    val mainScreenAllowed get() = phase == "running_main" && taskId != null
    val humanHandoff get() = phase in setOf("waiting_human", "human_control")
    val live get() = humanHandoff || phase in setOf("running_virtual", "waiting_borrow", "countdown", "running_main")
}

/** Local consent and task identity. No tool argument or model text can create a grant. */
class PhoneUsagePolicy(
    private val scope: CoroutineScope,
    initialMode: String = "background",
    initialTimeoutSeconds: Int = 30,
    private val saveMode: (String) -> Unit = {},
    private val saveTimeout: (Int) -> Unit = {},
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val mutable = MutableStateFlow(PhoneUsageState(
        mode = initialMode.takeIf { it == "global" } ?: "background",
        borrowTimeoutSeconds = initialTimeoutSeconds.coerceIn(5, 300)))
    val state = mutable.asStateFlow()
    private var timer: Job? = null
    private var pendingConnection: String? = null

    @Synchronized fun setMode(mode: String) {
        require(mode in setOf("background", "global")) { "INVALID_USAGE_MODE" }
        saveMode(mode)
        mutable.value = mutable.value.copy(mode = mode) // Existing task keeps its initial policy.
    }
    @Synchronized fun setBorrowTimeoutSeconds(seconds: Int) {
        require(seconds in 5..300) { "INVALID_BORROW_TIMEOUT" }
        saveTimeout(seconds)
        mutable.value = mutable.value.copy(borrowTimeoutSeconds = seconds)
    }
    /** Call only for a local submit action carrying this exact generated input id. */
    @Synchronized fun prepareInput(inputId: String, connectionId: String, mainScreen: Boolean) {
        pendingConnection = connectionId
        mutable.value = mutable.value.copy(pendingInputGrant = inputId.takeIf { mainScreen })
    }
    @Synchronized fun discardInput(inputId: String) {
        if (mutable.value.pendingInputGrant == inputId) {
            pendingConnection = null
            mutable.value = mutable.value.copy(pendingInputGrant = null)
        }
    }
    /** Only the authenticated host lifecycle may bind a task, never MCP parameters. */
    @Synchronized fun bindTask(taskId: String, inputId: String?, connectionId: String, executionId: String? = null) {
        require(taskId.isNotBlank() && connectionId.isNotBlank()) { "PHONE_TASK_BINDING_REQUIRED" }
        val old = mutable.value
        if (old.taskId == taskId && old.connectionId == connectionId && old.live) {
            // Execution segments share the same local consent; a host rebind is not a new grant.
            mutable.value = old.copy(executionId = executionId)
            return
        }
        check(!old.live) { "PHONE_TASK_BUSY" }
        timer?.cancel()
        val explicit = inputId != null && inputId == old.pendingInputGrant && connectionId == pendingConnection
        pendingConnection = null
        val allowed = explicit || old.mode == "global"
        mutable.value = PhoneUsageState(mode = old.mode, taskMode = old.mode, taskId = taskId,
            inputId = inputId, connectionId = connectionId, executionId = executionId,
            phase = if (allowed) "running_main" else "running_virtual",
            grantSource = if (explicit) "explicit_input" else if (allowed) "global_mode" else null,
            borrowTimeoutSeconds = old.borrowTimeoutSeconds)
    }
    @Synchronized fun describeStep(application: String?, step: String) {
        if (mutable.value.live) mutable.value = mutable.value.copy(application = application ?: mutable.value.application, step = step)
    }
    @Synchronized fun requestHumanHandoff(taskId: String, sessionId: String, reason: String) {
        val s = mutable.value
        check(s.taskId == taskId && s.phase == "running_virtual") { "PHONE_HANDOFF_REQUEST_STALE" }
        require(sessionId.isNotBlank() && reason.isNotBlank()) { "INVALID_HANDOFF_REQUEST" }
        timer?.cancel(); timer = null
        mutable.value = s.copy(phase = "waiting_human", handoffSessionId = sessionId,
            handoffId = java.util.UUID.randomUUID().toString(), handoffAction = "requested", handoffReason = reason, failureCode = null, failureCategory = null,
            deadlineMs = null, countdownSeconds = 0)
    }
    /** Only a local action may accept or return this exact task/display handoff. */
    @Synchronized fun acceptHumanHandoff(taskId: String, sessionId: String, handoffId: String) {
        val s = mutable.value
        check(s.taskId == taskId && s.handoffSessionId == sessionId && s.handoffId == handoffId && s.humanHandoff) { "PHONE_HANDOFF_REQUEST_STALE" }
        mutable.value = s.copy(phase = "human_control", handoffAction = "accepted")
    }
    @Synchronized fun returnHumanHandoff(taskId: String, sessionId: String, handoffId: String) {
        val s = mutable.value
        check(s.taskId == taskId && s.handoffSessionId == sessionId && s.handoffId == handoffId && s.phase == "human_control") { "PHONE_HANDOFF_REQUEST_STALE" }
        mutable.value = s.copy(phase = "running_virtual", handoffSessionId = null, handoffReason = "", handoffAction = "returned")
    }
    @Synchronized fun backgroundFailed(taskId: String, code: String): Boolean {
        val s = mutable.value
        if (s.taskId != taskId || s.phase != "running_virtual" || s.retryUsed) return false
        timer?.cancel()
        val deadline = nowMillis() + s.borrowTimeoutSeconds * 1000L
        mutable.value = s.copy(phase = "waiting_borrow", failureCode = code,
            failureCategory = if (code == "SECURE_WINDOW") "protected_content" else "virtual_screen_unavailable",
            deadlineMs = deadline)
        timer = scope.launch {
            delay(s.borrowTimeoutSeconds * 1000L)
            expire(taskId, deadline)
        }
        return true
    }
    @Synchronized fun expire(taskId: String, deadline: Long) {
        val s = mutable.value
        if (s.taskId == taskId && s.phase == "waiting_borrow" && s.deadlineMs == deadline && nowMillis() >= deadline)
            end("failed", "MAIN_SCREEN_NO_RESPONSE", "no_response")
    }
    /** Local card actions. A captured task id prevents a stale card approving another task. */
    @Synchronized fun respondBorrow(taskId: String, choice: String) {
        val s = mutable.value
        check(s.taskId == taskId && s.phase == "waiting_borrow") { "PHONE_BORROW_REQUEST_STALE" }
        if (nowMillis() >= (s.deadlineMs ?: 0)) {
            end("failed", "MAIN_SCREEN_NO_RESPONSE", "no_response")
            return
        }
        require(choice in setOf("this_once", "always", "decline")) { "INVALID_BORROW_CHOICE" }
        timer?.cancel()
        if (choice == "decline") { end("failed", "MAIN_SCREEN_DECLINED", "declined"); return }
        if (choice == "always") setMode("global")
        mutable.value = mutable.value.copy(phase = "countdown", countdownSeconds = 3,
            deadlineMs = nowMillis() + 3000L, decision = "borrowed", grantSource = "borrow_$choice", retryUsed = true)
        timer = scope.launch {
            repeat(3) { index ->
                delay(1000)
                countdownTick(taskId, 2 - index)
            }
        }
    }
    @Synchronized private fun countdownTick(taskId: String, remaining: Int) {
        val s = mutable.value
        if (s.taskId != taskId || s.phase != "countdown") return
        mutable.value = s.copy(countdownSeconds = remaining, deadlineMs = if (remaining == 0) null else s.deadlineMs,
            phase = if (remaining == 0) "running_main" else "countdown")
    }
    @Synchronized fun cancelCountdown(taskId: String) {
        check(mutable.value.taskId == taskId && mutable.value.phase == "countdown") { "PHONE_BORROW_REQUEST_STALE" }
        end("failed", "MAIN_SCREEN_DECLINED", "declined")
    }
    @Synchronized fun finishTask(taskId: String, outcome: String = "completed", reason: String = "") {
        if (mutable.value.taskId != taskId || !mutable.value.live) return
        val phase = when (outcome) {
            "completed" -> "completed"
            "finished" -> "idle" // The host execution segment ended; Persona owns the goal outcome.
            else -> "failed"
        }
        end(phase, reason.takeIf { phase == "failed" && it.isNotBlank() }, mutable.value.decision)
    }
    @Synchronized fun stopCurrent(reason: String = "USER_INTERRUPTED") {
        if (mutable.value.live) end("failed", reason, if (reason == "USER_CANCELLED") "cancelled" else "interrupted")
        pendingConnection = null
        mutable.value = mutable.value.copy(pendingInputGrant = null)
    }
    @Synchronized fun restoration(status: String, taskId: String? = state.value.taskId) {
        if (mutable.value.taskId == taskId) mutable.value = mutable.value.copy(restoreStatus = status)
    }
    private fun end(phase: String, code: String?, decision: String) {
        timer?.cancel(); timer = null
        mutable.value = mutable.value.copy(phase = phase, failureCode = code, decision = decision,
            countdownSeconds = 0, deadlineMs = null, handoffSessionId = null, handoffReason = "",
            handoffAction = if (mutable.value.humanHandoff) "cancelled" else mutable.value.handoffAction)
    }
    fun receipt(): Map<String, Any?> = state.value.let { s -> mapOf(
        "usage_mode" to s.mode, "task_usage_mode" to s.taskMode, "task_id" to s.taskId,
        "task_input_id" to s.inputId, "task_phase" to s.phase, "execution_id" to s.executionId, "borrow_decision" to s.decision,
        "main_screen_grant_source" to s.grantSource, "main_screen_allowed" to s.mainScreenAllowed,
        "main_screen_retry_used" to s.retryUsed, "borrow_deadline_ms" to s.deadlineMs,
        "countdown_seconds" to s.countdownSeconds, "task_failure_code" to s.failureCode,
        "task_failure_category" to s.failureCategory, "restore_status" to s.restoreStatus,
        "handoff_reason" to s.handoffReason, "handoff_session_id" to s.handoffSessionId, "handoff_id" to s.handoffId,
        "handoff_action" to s.handoffAction,
        "requires_user_action" to (s.humanHandoff || s.phase in setOf("waiting_borrow", "countdown"))) }
}

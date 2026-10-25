package io.github.lejw0925.virtualscreen.phone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import io.github.lejw0925.virtualscreen.app.MainScreenStatusOverlay
import io.github.lejw0925.virtualscreen.VirtualScreenApplication
import io.github.lejw0925.virtualscreen.backend.BackendOperation
import io.github.lejw0925.virtualscreen.core.*
import io.github.lejw0925.virtualscreen.mcp.NodeTreeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID
import kotlin.coroutines.resume

/** Task-scoped main-screen executor. Persistent preference lives in policy, grants never do. */
class MainScreenControl(private val app: VirtualScreenApplication) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = app.getSharedPreferences("phone_usage", 0)
    val policy = PhoneUsagePolicy(scope, prefs.getString("mode", "background") ?: "background",
        prefs.getInt("borrow_timeout_seconds", 30),
        saveMode = { prefs.edit().putString("mode", it).apply() },
        saveTimeout = { prefs.edit().putInt("borrow_timeout_seconds", it).apply() })
    private val mutable = MutableStateFlow<String?>(null)
    val grant = mutable.asStateFlow()
    val active get() = mutable.value != null && policy.state.value.mainScreenAllowed
    private var stopView: MainScreenStatusOverlay? = null
    private val mutableIndicatorVisible = MutableStateFlow(false)
    val indicatorVisible = mutableIndicatorVisible.asStateFlow()
    private val mutableIndicatorHeight = MutableStateFlow(0)
    val indicatorHeight = mutableIndicatorHeight.asStateFlow()
    private var windowManager: WindowManager? = null
    private var observation: String? = null
    private var observer: String? = null
    private val operationMutex = Mutex()
    @Volatile private var leaseGeneration: Long? = null
    @Volatile private var leaseOwner: String? = null
    @Volatile private var humanLeaseGeneration: Long? = null
    @Volatile private var inputId: String? = null
    private var legacyPendingMain = false
    private var returnPackage: String? = null
    private var grantTaskId: String? = null
    private var activeJob: kotlinx.coroutines.Job? = null
    val taskInputId get() = policy.state.value.inputId
    init {
        scope.launch {
            app.sessions.state.collect { state ->
                val usage = policy.state.value
                if (usage.humanHandoff && (state.displayId == null || state.sessionId != usage.handoffSessionId))
                    stop("HUMAN_HANDOFF_SCREEN_LOST")
                else if (!usage.humanHandoff && leaseGeneration != null && state.lease?.generation != leaseGeneration)
                    stop("LEASE_ENDED")
            }
        }
        scope.launch {
            policy.state.collect { state ->
                if (!state.mainScreenAllowed && mutable.value != null) releaseGrant()
                if (!state.live && leaseOwner != null) {
                    val owner = leaseOwner
                    leaseOwner = null; leaseGeneration = null
                    owner?.let(app.sessions::disconnectClient)
                }
            }
        }
    }
    /** Compatibility for the old task submit UI; does not grant access before a trusted task bind. */
    fun begin() {
        check(app.phoneSession.flow.value.phase == "ready") { "PHONE_NOT_READY" }
        legacyPendingMain = true
    }
    fun attachInput(id: String) {
        prepareInput(id, legacyPendingMain)
        legacyPendingMain = false
    }
    fun prepareInput(id: String, mainScreen: Boolean) {
        val connection = app.phoneSession.flow.value.connectionId ?: error("PHONE_DISCONNECTED")
        policy.prepareInput(id, connection, mainScreen)
    }
    fun bindTask(taskId: String, inputId: String?, executionId: String? = null) {
        val connection = app.phoneSession.flow.value.connectionId ?: error("PHONE_DISCONNECTED")
        policy.bindTask(taskId, inputId, connection, executionId)
    }
    fun finishTask(taskId: String, outcome: String = "completed", reason: String = "") {
        if (policy.state.value.taskId != taskId) return
        val humanHandoff = policy.state.value.humanHandoff
        if (humanHandoff) cancelHumanLease()
        policy.finishTask(taskId, outcome, reason)
        if (humanHandoff) app.sessions.cancelActive()
        if (grantTaskId == taskId) releaseGrant()
        leaseOwner?.let(app.sessions::disconnectClient)
        leaseGeneration = null
        leaseOwner = null
    }
    /** Human confirmation keeps the Task and display, but never retains the old remote lease. */
    fun detachLeaseForHumanHandoff() {
        check(policy.state.value.humanHandoff) { "PHONE_HANDOFF_REQUEST_STALE" }
        val owner = leaseOwner
        leaseGeneration = null
        leaseOwner = null
        owner?.let(app.sessions::disconnectClient)
        app.sessions.cancelActive()
    }
    fun rememberHumanLease(generation: Long) {
        check(policy.state.value.phase == "human_control") { "PHONE_HANDOFF_REQUEST_STALE" }
        humanLeaseGeneration = generation
    }
    fun returnHumanLease() {
        app.sessions.finishHumanHandoff()
        humanLeaseGeneration = null
    }
    private fun cancelHumanLease() {
        // A long local confirmation may renew its PHONE lease after idle expiry.
        // This method is called only while ending the still-current human task.
        val held = app.sessions.leaseStatus()
        val generation = if (humanLeaseGeneration != null && policy.state.value.humanHandoff &&
            held?.owner == VirtualScreenSessionManager.PHONE_CLIENT) held.generation else humanLeaseGeneration
        humanLeaseGeneration = null
        generation?.let(app.sessions::cancelHumanHandoff)
    }
    /** Validated lease pins this task to its owner/generation, including the borrow wait. */
    fun bindLease(owner: String, token: String) {
        check(policy.state.value.live) { "PHONE_TASK_BINDING_REQUIRED" }
        val lease = app.sessions.touchLease(owner, token) as? LeaseDecision.Granted ?: error("LEASE_REQUIRED")
        if (leaseGeneration == null) { leaseGeneration = lease.lease.generation; leaseOwner = owner }
        check(leaseGeneration == lease.lease.generation && leaseOwner == owner) { "PHONE_TASK_LEASE_CHANGED" }
    }
    private fun openGrant() {
        val state = policy.state.value
        check(state.mainScreenAllowed) { if (state.phase == "countdown") "MAIN_SCREEN_COUNTDOWN" else "BACKGROUND_MAIN_SCREEN_NOT_ALLOWED" }
        if (mutable.value != null) { check(grantTaskId == state.taskId) { "PHONE_TASK_BINDING_REQUIRED" }; return }
        val service = ScreenAccessibilityService.current ?: error("ACCESSIBILITY_NOT_ENABLED")
        returnPackage = service.mainDisplayApplicationPackage()
        lateinit var controls: MainScreenStatusOverlay
        controls = MainScreenStatusOverlay(service, app) { height ->
            if (stopView === controls) mutableIndicatorHeight.value = height
        }
        val manager = service.getSystemService(WindowManager::class.java)
        try {
            manager.addView(controls, WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                setTitle("AVSR main screen controls")
            })
            controls.opened()
        } catch (failure: Exception) {
            runCatching { manager.removeViewImmediate(controls) }
            controls.dispose()
            throw failure
        }
        stopView = controls
        windowManager = manager
        mutableIndicatorVisible.value = true
        grantTaskId = state.taskId
        policy.restoration(if (returnPackage == null) "original_app_unknown" else "pending")
        mutable.value = UUID.randomUUID().toString()
    }
    fun stop(reason: String = "USER_INTERRUPTED") {
        val owner = leaseOwner
        val humanHandoff = policy.state.value.humanHandoff
        if (humanHandoff) cancelHumanLease()
        policy.stopCurrent(reason)
        if (humanHandoff) app.sessions.cancelActive()
        legacyPendingMain = false
        releaseGrant()
        leaseGeneration = null
        leaseOwner = null
        owner?.let(app.sessions::disconnectClient)
    }
    private fun releaseGrant() {
        val hadGrant = mutable.value != null
        val returningTask = grantTaskId
        mutable.value = null // Revoke before cancelling an in-flight gesture or scheduling restoration.
        activeJob?.cancel(); activeJob = null
        inputId = null; observation = null; observer = null; grantTaskId = null
        val view = stopView; val manager = windowManager; val original = returnPackage
        stopView = null; windowManager = null; returnPackage = null
        mutableIndicatorVisible.value = false
        mutableIndicatorHeight.value = 0
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (view != null) runCatching { manager?.removeViewImmediate(view) }
            view?.dispose()
            if (hadGrant && original != null && policy.state.value.taskId == returningTask) {
                val restored = runCatching {
                    val intent = app.packageManager.getLaunchIntentForPackage(original) ?: error("ORIGINAL_APP_UNAVAILABLE")
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    (ScreenAccessibilityService.current ?: error("ACCESSIBILITY_NOT_ENABLED")).startActivity(intent)
                }.isSuccess
                policy.restoration(if (restored) "return_requested" else "return_failed", returningTask)
                if (restored) scope.launch {
                    repeat(3) {
                        kotlinx.coroutines.delay(250)
                        if (policy.state.value.taskId != returningTask || mutable.value != null) return@launch
                        if (ScreenAccessibilityService.current?.mainDisplayApplicationPackage() == original) {
                            policy.restoration("returned", returningTask)
                            return@launch
                        }
                    }
                    policy.restoration("return_unconfirmed", returningTask)
                }
            }
        }
    }
    fun checkAccess(): ScreenAccessibilityService {
        check(active) { "BACKGROUND_MAIN_SCREEN_NOT_ALLOWED" }
        check(grantTaskId == policy.state.value.taskId) { "PHONE_TASK_BINDING_REQUIRED" }
        check(app.phoneSession.flow.value.executionEnabled) { "PHONE_EXECUTION_PAUSED" }
        check(policy.state.value.connectionId == app.phoneSession.flow.value.connectionId) { "PHONE_CONNECTION_STALE" }
        return ScreenAccessibilityService.current ?: error("ACCESSIBILITY_NOT_ENABLED")
    }

    suspend fun call(name: String, owner: String, args: JSONObject, expectedTaskId: String? = null): OperationEnvelope = withContext(Dispatchers.Main.immediate) { operationMutex.withLock {
        check(expectedTaskId != null && expectedTaskId == policy.state.value.taskId) { "PHONE_TASK_BINDING_REQUIRED" }
        bindLease(owner, args.getString("lease_token"))
        openGrant()
        val service = checkAccess()
        activeJob = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        val generation = mutable.value
        if (name == "main_screen_finish") {
            activeJob = null
            finishTask(checkNotNull(policy.state.value.taskId))
            return@withContext OperationEnvelope.success("main_screen_enabled" to false, "display_id" to 0)
                .copy(values = policy.receipt() + mapOf("main_screen_enabled" to false, "display_id" to 0))
        }
        if (name == "main_screen_observe") {
            observation = null
            observer = null
            val mode = args.optString("mode", "both")
            val image = if (mode != "tree") app.phoneGlance.capture() else OperationEnvelope.success()
            if (!image.ok) return@withContext image
            check(mutable.value == generation) { "MAIN_SCREEN_NOT_SELECTED" }
            checkAccess()
            val id = UUID.randomUUID().toString()
            val nodes = if (mode != "screenshot") NodeTreeFormatter.values(service.snapshot(0, id), "compact") else emptyMap()
            check(mutable.value == generation) { "MAIN_SCREEN_NOT_SELECTED" }
            observation = id
            observer = owner
            return@withContext image.copy(values = image.values + nodes + mapOf("observation_id" to id,
                "display_id" to 0, "mode" to "main_screen", "main_screen_enabled" to true))
        }
        check(observer == owner && observation != null && observation == args.getString("observation_id")) { "STALE_OBSERVATION" }
        if (name == "main_screen_node_action") check(args.getString("node_id").startsWith("$observation:")) { "STALE_OBSERVATION" }
        observation = null // Every attempted input requires a new observation; never replay.
        checkAccess()
        val result = when (name) {
            "main_screen_node_action" -> {
                val r = service.act(0, BackendOperation.NodeAction(args.getString("node_id"), args.getString("action"),
                    args.optString("value").takeIf { args.has("value") }))
                OperationEnvelope(r.ok, r.code, values = r.values)
            }
            "main_screen_gesture" -> {
                val points = args.getJSONArray("points")
                val bounds = service.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
                val path = Path()
                for (i in 0 until points.length()) {
                    val point = points.getJSONArray(i)
                    val x = point.getDouble(0).toFloat(); val y = point.getDouble(1).toFloat()
                    require(x >= 0 && y >= 0 && x < bounds.width() && y < bounds.height()) { "COORDINATE_OUT_OF_BOUNDS" }
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                val gesture = GestureDescription.Builder().setDisplayId(0).addStroke(
                    GestureDescription.StrokeDescription(path, 0, args.optLong("duration_ms", 100))).build()
                suspendCancellableCoroutine { continuation ->
                    fun complete(ok: Boolean) { if (continuation.isActive) continuation.resume(
                        if (ok) OperationEnvelope.success() else OperationEnvelope.failure("INPUT_DISPATCH_UNCONFIRMED")) }
                    val accepted = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) { complete(true) }
                        override fun onCancelled(gestureDescription: GestureDescription?) { complete(false) }
                    }, null)
                    if (!accepted) complete(false)
                }
            }
            "main_screen_back" -> if (service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
                OperationEnvelope.success() else OperationEnvelope.failure("INPUT_DISPATCH_UNCONFIRMED")
            "main_screen_launch_app" -> {
                val component = ComponentName.unflattenFromString(args.getString("component")) ?: error("INVALID_COMPONENT")
                val info = app.packageManager.getActivityInfo(component, 0)
                require(info.enabled && info.exported && component.packageName != app.packageName) { "APP_NOT_LAUNCHABLE" }
                service.startActivity(Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                OperationEnvelope.success("component" to component.flattenToString())
            }
            else -> error("UNKNOWN_TOOL")
        }
        check(mutable.value == generation) { "USER_INTERRUPTED" }
        checkAccess()
        result.copy(values = result.values + policy.receipt() + mapOf("display_id" to 0, "requires_observation" to true,
            "execution_status" to if (result.ok) "accepted" else "unknown", "effect_status" to "unconfirmed"))
    } }
}

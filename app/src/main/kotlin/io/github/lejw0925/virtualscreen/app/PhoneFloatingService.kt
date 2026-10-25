package io.github.lejw0925.virtualscreen.app

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Region
import android.os.IBinder
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowInsets
import android.view.ViewConfiguration
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.*
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.lejw0925.virtualscreen.R
import io.github.lejw0925.virtualscreen.VirtualScreenApplication
import io.github.lejw0925.virtualscreen.ui.PhoneUiContent
import io.github.lejw0925.virtualscreen.ui.LocalOverlayPresentation
import io.github.lejw0925.virtualscreen.ui.LocalFloatingInteraction
import io.github.lejw0925.virtualscreen.ui.FloatingInteraction
import io.github.lejw0925.virtualscreen.ui.VirtualScreenTheme
import io.github.lejw0925.virtualscreen.core.ScreenAccessibilityService
import io.github.lejw0925.virtualscreen.core.failure
import io.github.lejw0925.virtualscreen.core.runtime
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** Owns only the window/lifetime. Claude's FloatingPanel content is injected through PhoneUiContent. */
class PhoneFloatingService : Service(), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
    override val viewModelStore = ViewModelStore()
    private val app get() = application as VirtualScreenApplication
    private var root: FrameLayout? = null
    private var content: ComposeView? = null
    private lateinit var manager: WindowManager
    private var acknowledgedMessage: String? = null
    private lateinit var foregroundNotification: Notification
    private lateinit var windowParams: WindowManager.LayoutParams
    private var expanded by mutableStateOf(false)
    private var imeVisible = false
    private var restingBottomBeforeIme: Int? = null
    private var alignEnd by mutableStateOf(false)
    private var shownAt by mutableStateOf(System.currentTimeMillis())
    private val prefs by lazy { getSharedPreferences("avsr_product", 0) }
    private var ownsRecording by mutableStateOf(false)
    private var inputError by mutableStateOf("")
    private var voiceMonitor: Job? = null
    private var voiceGestureStarted = false
    private var createdAt = 0L
    private val normalServiceType get() = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
    private val interaction = object : FloatingInteraction {
        override val expanded get() = this@PhoneFloatingService.expanded
        override val recording get() = ownsRecording
        override val error get() = inputError
        override val alignEnd get() = this@PhoneFloatingService.alignEnd
        override val shownAt get() = this@PhoneFloatingService.shownAt
        override fun expand(value: Boolean) = updateExpanded(value)
        override fun openApp() {
            updateExpanded(false)
            app.avsr.openApp()
        }
        override fun beginVoice() = beginVoiceRecording()
        override fun finishVoice(send: Boolean) = finishVoiceRecording(send)
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        createdAt = SystemClock.elapsedRealtime()
        recordLifecycle("created", "enabled=${app.avsr.state.value.overlayEnabled}; permission=${Settings.canDrawOverlays(this)}")
        savedState.performAttach(); savedState.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("phone-floating", "Persona", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 12, Intent(this, io.github.lejw0925.virtualscreen.ui.PhoneProductActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        foregroundNotification = Notification.Builder(this, "phone-floating").setSmallIcon(R.drawable.ic_status_approval)
            .setContentTitle("Persona").setContentText("Phone assistant").setContentIntent(open).setOngoing(true).build()
        startForeground(12120, foregroundNotification, normalServiceType)
        if (!Settings.canDrawOverlays(this) || !app.avsr.state.value.overlayEnabled) {
            recordLifecycle("stopping", "reason=disabled_or_permission_missing")
            stopSelf(); return
        }
        manager = getSystemService(WindowManager::class.java)
        // Wrap both ways: the collapsed orb must not block touches across a 290dp strip.
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT).apply {
                // Anchored at the bottom of the docked edge: bubbles, cards and the input line grow
                // upward and toward the screen middle, and the orb stays where the user left it (the
                // system would otherwise push an overflowing window back on screen and move the orb).
                // y is the distance from the bottom edge.
                alignEnd = prefs.getBoolean("overlay_end", false)
                gravity = Gravity.BOTTOM or if (alignEnd) Gravity.END else Gravity.START
                x = prefs.getInt("overlay_x", 16)
                y = prefs.getInt("overlay_bottom", (resources.displayMetrics.heightPixels * 0.3f).toInt())
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE }
        windowParams = params
        val container = object : FrameLayout(this) {
            private var downX = 0f; private var downY = 0f
            private var originX = 0; private var originY = 0; private var dragging = false
            private var cancelledVoiceGesture = false
            private val slop = ViewConfiguration.get(context).scaledTouchSlop
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_OUTSIDE -> {
                        finishVoiceRecording(false)
                        if (expanded) updateExpanded(false)
                        // This is only an outside-event copy. The system routes the original touch.
                        return false
                    }
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX; downY = event.rawY; originX = params.x; originY = params.y
                        dragging = false; cancelledVoiceGesture = false; voiceGestureStarted = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val moved = kotlin.math.hypot(event.rawX - downX, event.rawY - downY) > slop
                        if (!dragging && !cancelledVoiceGesture && moved && (voiceGestureStarted || !expanded)) {
                            // Compete before child dispatch: Compose may disallow parent interception.
                            cancelledVoiceGesture = voiceGestureStarted
                            dragging = !voiceGestureStarted
                            finishVoiceRecording(false)
                            val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                            try { super.dispatchTouchEvent(cancel) } finally { cancel.recycle() }
                        }
                        if (dragging) {
                            val dx = (event.rawX - downX).toInt()
                            val screen = manager.currentWindowMetrics.bounds
                            params.x = (if (alignEnd) originX - dx else originX + dx).coerceIn(0, (screen.width() - width).coerceAtLeast(0))
                            params.y = (originY - (event.rawY - downY)).toInt().coerceIn(0, (screen.height() - height).coerceAtLeast(0))
                            manager.updateViewLayout(this, params)
                        }
                    }
                    MotionEvent.ACTION_UP -> if (dragging) dock(this)
                    MotionEvent.ACTION_POINTER_DOWN -> {
                        dragging = false; cancelledVoiceGesture = true; finishVoiceRecording(false)
                        val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                        try { super.dispatchTouchEvent(cancel) } finally { cancel.recycle() }
                    }
                    MotionEvent.ACTION_CANCEL -> { finishVoiceRecording(false); if (dragging) dock(this) }
                }
                return if (dragging || cancelledVoiceGesture) true else super.dispatchTouchEvent(event)
            }
            override fun onWindowVisibilityChanged(visibility: Int) {
                super.onWindowVisibilityChanged(visibility)
                if (visibility != View.VISIBLE) {
                    finishVoiceRecording(false)
                    updateImePosition(false)
                } else if (expanded) requestApplyInsets()
            }
            override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                super.onWindowFocusChanged(hasWindowFocus)
                if (expanded && !hasWindowFocus) finishVoiceRecording(false)
            }
        }
        container.setViewTreeLifecycleOwner(this)
        container.setOnApplyWindowInsetsListener { _, insets ->
            imeVisible = insets.isVisible(WindowInsets.Type.ime())
            updateImePosition(expanded && imeVisible && container.visibility == View.VISIBLE
                && container.windowVisibility == View.VISIBLE)
            insets
        }
        container.setViewTreeSavedStateRegistryOwner(this)
        container.setViewTreeViewModelStoreOwner(this)
        val compose = ComposeView(this).apply {
            setContent {
                val state by app.avsr.state.collectAsState()
                val confirm = remember { { id: String, bounds: Rect -> confirmPresentation(id, bounds) } }
                CompositionLocalProvider(LocalOverlayPresentation provides confirm, LocalFloatingInteraction provides interaction) {
                    VirtualScreenTheme(app, accessibilityFeedback = false, fillViewport = false) {
                        PhoneUiContent.FloatingPanel(state, app.avsr)
                    }
                }
            }
        }
        container.addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        root = container; content = compose
        container.visibility = if (app.avsr.appUiVisible.value || app.avsr.humanViewerVisible.value) View.GONE else View.VISIBLE
        try {
            manager.addView(container, params)
            container.requestApplyInsets()
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            app.avsr.overlayServiceChanged(true)
            lifecycleScope.launch {
                combine(app.avsr.humanViewerVisible, app.avsr.appUiVisible, app.avsr.state) { human, ownUi, state ->
                    when {
                        !state.overlayEnabled -> true to "disabled"
                        human -> true to "human_viewer"
                        ownUi -> true to "app_ui"
                        else -> false to "background"
                    }
                }.distinctUntilChanged().collect { (hidden, reason) ->
                    if (hidden) { finishVoiceRecording(false); updateExpanded(false) } else shownAt = System.currentTimeMillis()
                    root?.visibility = if (hidden) View.GONE else View.VISIBLE
                    app.avsr.overlayVisibilityChanged(!hidden)
                    recordLifecycle(if (hidden) "hidden" else "visible", "reason=$reason")
                }
            }
        } catch (failure: Exception) {
            app.activityLog.failure("floating_window", failure)
            recordLifecycle("stopping", "reason=window_create_failed; type=${failure.javaClass.simpleName}")
            stopSelf()
        }
    }
    /** After a drag, attach to the half the orb's centre is on; the orb sits at the window's docked edge. */
    private fun dock(view: View) {
        val screen = manager.currentWindowMetrics.bounds.width()
        val orb = (58f * 1.4f * resources.displayMetrics.density).toInt()
        val center = if (alignEnd) screen - windowParams.x - orb / 2 else windowParams.x + orb / 2
        val toEnd = center > screen / 2
        alignEnd = toEnd
        windowParams.gravity = Gravity.BOTTOM or if (toEnd) Gravity.END else Gravity.START
        windowParams.x = (if (toEnd) screen - center - orb / 2 else center - orb / 2).coerceAtLeast(0)
        runCatching { manager.updateViewLayout(view, windowParams) }
        prefs.edit().putBoolean("overlay_end", toEnd).putInt("overlay_x", windowParams.x)
            .putInt("overlay_bottom", restingBottomBeforeIme ?: windowParams.y).apply()
    }
    private fun recordLifecycle(event: String, detail: String = "") {
        val state = "elapsed_ms=${SystemClock.elapsedRealtime() - createdAt}; $detail"
        app.activityLog.runtime("floating_service", event, state)
        android.util.Log.i("PhoneOverlay", "event=$event $state")
    }
    /** Main thread only. Query window geometry and our own root; never inspect other apps' text. */
    private fun confirmPresentation(messageId: String, textBounds: Rect): Boolean {
        if (app.avsr.state.value.conversation.lastOrNull { it.role == "assistant" }?.id != messageId) return false
        if (acknowledgedMessage == messageId) return true
        if (!isRegionVisible(textBounds)) return false
        app.avsr.acknowledgeMessage(messageId, "overlay") // A preview remains detail_read=false.
        acknowledgedMessage = messageId
        return true
    }
    private fun isRegionVisible(textBounds: Rect): Boolean = runCatching {
            val container = root ?: return@runCatching false
            if (!container.isAttachedToWindow || !container.isShown || container.windowVisibility != View.VISIBLE || textBounds.isEmpty)
                return@runCatching false
            if (!getSystemService(PowerManager::class.java).isInteractive || getSystemService(KeyguardManager::class.java).isKeyguardLocked)
                return@runCatching false
            val service = ScreenAccessibilityService.current ?: return@runCatching false
            val displayId = container.display?.displayId ?: return@runCatching false
            // View.getAccessibilityWindowId is hidden API; the public node API supplies the same exact id.
            val ownNode = container.createAccessibilityNodeInfo() ?: return@runCatching false
            val ownId = try { ownNode.windowId } finally { ownNode.recycle() }
            if (ownId < 0) return@runCatching false
            val windows = service.windowsOnAllDisplays.get(displayId).orEmpty()
            val ownWindow = windows.firstOrNull { it.id == ownId && it.displayId == displayId } ?: return@runCatching false
            val ownRoot = ownWindow.root ?: return@runCatching false
            val rootVisible = try { ownRoot.refresh() && ownRoot.isVisibleToUser } finally { ownRoot.recycle() }
            if (!rootVisible) return@runCatching false
            val visibleRegion = Region().also { ownWindow.getRegionInScreen(it) }
            visibleRegion.op(textBounds, Region.Op.INTERSECT)
            // Window regions are touchable geometry, not an occlusion mask. Subtract higher windows.
            for (window in windows) if (window.layer > ownWindow.layer) {
                val coveringRegion = Region().also { window.getRegionInScreen(it) }
                visibleRegion.op(coveringRegion, Region.Op.DIFFERENCE)
            }
            !visibleRegion.isEmpty
        }.getOrDefault(false)

    private fun windowVisible(): Boolean {
        val container = root ?: return false
        val position = IntArray(2)
        container.getLocationOnScreen(position)
        return isRegionVisible(Rect(position[0], position[1], position[0] + container.width, position[1] + container.height))
    }
    /** ADJUST_RESIZE already places the bottom frame above IME; keep no second resting offset. */
    private fun updateImePosition(editing: Boolean) {
        val container = root ?: return
        val bottom = if (editing) {
            if (restingBottomBeforeIme == null) restingBottomBeforeIme = windowParams.y
            0
        } else {
            val resting = restingBottomBeforeIme ?: return
            restingBottomBeforeIme = null
            resting
        }
        if (windowParams.y == bottom) return
        windowParams.y = bottom
        runCatching { manager.updateViewLayout(container, windowParams) }
            .onFailure { inputError = "OVERLAY_WINDOW_UPDATE_FAILED" }
    }

    private fun updateExpanded(value: Boolean) {
        finishVoiceRecording(false)
        val container = root ?: return
        if (!value) {
            updateImePosition(false)
            content?.clearFocus()
            container.clearFocus()
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(container.windowToken, 0)
        }
        windowParams.flags = if (value) windowParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            else windowParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        runCatching {
            manager.updateViewLayout(container, windowParams)
            expanded = value
            if (value) container.requestApplyInsets()
        }
            .onFailure { inputError = "OVERLAY_WINDOW_UPDATE_FAILED" }
    }
    private fun beginVoiceRecording() {
        voiceGestureStarted = true
        if (ownsRecording) return
        val state = app.avsr.state.value
        if (!windowVisible()) { inputError = "OVERLAY_NOT_VISIBLE"; return }
        if (state.connection != "ready") { inputError = "PHONE_NOT_READY"; return }
        if (!state.permissions.microphone) { inputError = "MICROPHONE_PERMISSION_REQUIRED"; return }
        if (state.voice == "listening") { inputError = "ALREADY_RECORDING"; return }
        try {
            startForeground(12120, foregroundNotification,
                normalServiceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            app.avsr.startVoiceInput(false)
            ownsRecording = app.avsr.state.value.voice == "listening"
            if (!ownsRecording) { inputError = app.avsr.state.value.error.ifBlank { "VOICE_INPUT_NOT_STARTED" }; lowerMicrophoneService(); return }
            inputError = ""
            voiceMonitor = lifecycleScope.launch {
                while (ownsRecording) {
                    delay(250)
                    if (!windowVisible() || app.avsr.state.value.connection != "ready" || app.avsr.state.value.voice != "listening")
                        finishVoiceRecording(false)
                }
            }
        } catch (_: Exception) {
            inputError = "MICROPHONE_FOREGROUND_START_FAILED"
            finishVoiceRecording(false); lowerMicrophoneService()
        }
    }
    private fun finishVoiceRecording(send: Boolean) {
        if (!ownsRecording) return
        val maySend = send && app.avsr.state.value.connection == "ready" && app.avsr.state.value.voice == "listening" && windowVisible()
        ownsRecording = false
        voiceMonitor?.cancel(); voiceMonitor = null
        try { app.avsr.endVoiceInput(maySend) } finally { lowerMicrophoneService() }
    }
    private fun lowerMicrophoneService() {
        runCatching { startForeground(12120, foregroundNotification, normalServiceType) }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        recordLifecycle(if (intent == null) "restarted" else "started")
        return START_STICKY
    }
    override fun onDestroy() {
        recordLifecycle("destroyed", "enabled=${app.avsr.state.value.overlayEnabled}; permission=${Settings.canDrawOverlays(this)}")
        finishVoiceRecording(false)
        updateImePosition(false)
        app.avsr.overlayServiceChanged(false)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        content?.disposeComposition()
        root?.let { runCatching { manager.removeViewImmediate(it) } }; root = null
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStore.clear(); super.onDestroy()
    }
}

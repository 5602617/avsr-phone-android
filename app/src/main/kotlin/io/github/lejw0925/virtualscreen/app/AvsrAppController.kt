package io.github.lejw0925.virtualscreen.app

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import io.github.lejw0925.virtualscreen.VirtualScreenApplication
import io.github.lejw0925.virtualscreen.core.ScreenAccessibilityService
import io.github.lejw0925.virtualscreen.core.failure
import io.github.lejw0925.virtualscreen.core.runtime
import io.github.lejw0925.virtualscreen.phone.PhoneAudio
import io.github.lejw0925.virtualscreen.phone.PhoneHandsFreeRecorder
import io.github.lejw0925.virtualscreen.phone.embedded.EmbeddedPrivilege
import io.github.lejw0925.virtualscreen.ui.PhoneProductActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.lang.ref.WeakReference

/** UI-independent actions. State is derived from the same execution and transport objects used by MCP. */
class AvsrAppController(private val app: VirtualScreenApplication) : AvsrActions, AvsrLiveSignals {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val login = PhoneLogin(app)
    val avatars = AvatarSets(app)
    private val prefs = app.getSharedPreferences("avsr_product", 0)
    private val mutable = MutableStateFlow(AvsrAppState())
    private var applicationLabelCache: Pair<String?, String> = null to ""
    val state = mutable.asStateFlow()
    internal val humanViewerVisible = MutableStateFlow(false)
    internal val appUiVisible = MutableStateFlow(false)
    private val startedActivities = mutableSetOf<Activity>()
    private var backgroundTransition: Job? = null
    private var overlayServiceRequested = false
    private var overlayServiceReady = false
    private var minimizeAfterOverlay = WeakReference<Activity>(null)
    private var activity = WeakReference<Activity>(null)
    private var recorder: MediaRecorder? = null
    private var recording: File? = null
    private var recordingMainScreen = false
    private var recordingCallback: AudioManager.AudioRecordingCallback? = null
    // This is a user-enabled session, deliberately not a persisted boot setting.
    private var handsFreeEnabled = false
    private var microphoneServiceRequested = false
    private var microphoneServiceReady = false
    private var microphoneSession = 0L
    private val handsFreeRecorder = PhoneHandsFreeRecorder(app,
        send = { file, released ->
            if (handsFreeEnabled && app.phoneConnection.state.value == "ready") {
                if (!app.phoneSession.flow.value.executionEnabled) app.phoneConnection.resume()
                app.phoneConnection.sendRecording(file, released, false)
            } else file.delete()
        },
        failed = { scope.launch { disableHandsFree("HANDS_FREE_RECORDING_FAILED") } })
    init {
        app.phoneConnection.audio.beforePlayback = { handsFreeRecorder.stop() }
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { syncHandsFree() }
        }, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }, Context.RECEIVER_NOT_EXPORTED)
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(owner: Activity) {
                backgroundTransition?.cancel()
                startedActivities.add(owner)
                appUiVisible.value = true
                if (prefs.getBoolean("overlay_enabled", false)) action { ensureOverlayService() }
            }
            override fun onActivityStopped(owner: Activity) {
                startedActivities.remove(owner)
                syncHandsFree()
                if (startedActivities.isEmpty()) backgroundTransition = scope.launch {
                    // Do not flash the bubble during rotation or an internal page transition.
                    delay(180)
                    if (startedActivities.isEmpty()) appUiVisible.value = false
                }
            }
            override fun onActivityCreated(owner: Activity, state: Bundle?) = Unit
            override fun onActivityResumed(owner: Activity) = Unit
            override fun onActivityPaused(owner: Activity) = Unit
            override fun onActivitySaveInstanceState(owner: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(owner: Activity) { startedActivities.remove(owner); syncHandsFree() }
        })
        scope.launch { login.state.collect { rebuild() } }
        scope.launch { app.phoneConnection.messages.collect { rebuild() } }
        scope.launch { app.phoneConnection.taskResult.collect { rebuild() } }
        scope.launch { app.phoneConnection.state.collect { rebuild(); syncHandsFree() } }
        scope.launch { app.phoneConnection.audio.playbackActive.collect { syncHandsFree() } }
        scope.launch { appUiVisible.collect { syncHandsFree() } }
        scope.launch { app.phoneConnection.personaName.collect { rebuild() } }
        scope.launch { app.phoneConnection.personaId.collect { rebuild() } }
        scope.launch { io.github.lejw0925.virtualscreen.systemtools.AlarmRequests.state.collect { requests ->
            requests.forEach { app.phoneConnection.publishNativeConfirmation(it) }
            rebuild()
        } }
        scope.launch { app.mainScreen.indicatorVisible.collect { rebuild() } }
        scope.launch { app.mainScreen.indicatorHeight.collect { rebuild() } }
        scope.launch { app.mainScreen.policy.state.collect {
            io.github.lejw0925.virtualscreen.systemtools.AlarmRequests.reconcileBinding(
                io.github.lejw0925.virtualscreen.systemtools.AlarmRequests.currentBinding(app), it.live)
            rebuild()
            if (app.phoneSession.flow.value.connectionId != null) app.phoneConnection.publishUsage(org.json.JSONObject(app.mainScreen.policy.receipt()))
        } }
        scope.launch { app.mcp.activity.collect { rebuild() } }
        scope.launch { app.wirelessAdb.state.collect { rebuild() } }
        scope.launch { EmbeddedPrivilege.state.collect { rebuild() } }
        rebuild()
    }
    fun attach(activity: Activity) { this.activity = WeakReference(activity); refresh() }
    fun detach(activity: Activity) { if (this.activity.get() === activity) this.activity.clear(); endVoiceInput(false) }
    private fun granted(permission: String) = app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    private fun applicationLabel(component: String?): String {
        if (applicationLabelCache.first == component) return applicationLabelCache.second
        val label = runCatching {
            val packageName = component?.let(ComponentName::unflattenFromString)?.packageName ?: return@runCatching ""
            app.packageManager.getApplicationInfo(packageName, 0).loadLabel(app.packageManager).toString()
        }.getOrDefault("")
        applicationLabelCache = component to label
        return label
    }
    private fun rebuild() {
        val usage = app.mainScreen.policy.state.value
        val conversation = app.phoneConnection.messages.value.map { message -> ConversationItem(message.id,
            if (message.incoming) "assistant" else "user", message.text, message.timestampMs, message.delivery,
            message.audioStatus, message.speechEmotion, message.voiceInput, message.audio.any { it.complete && it.file.exists() },
            message.transcriptStatus, message.transcriptReason, message.personaId) }
        val permission = PermissionState(app.wirelessAdb.paired, EmbeddedPrivilege.isReady(), app.wirelessAdb.state.value,
            ScreenAccessibilityService.current != null, Settings.canDrawOverlays(app), granted(Manifest.permission.RECORD_AUDIO),
            granted(Manifest.permission.POST_NOTIFICATIONS), app.wirelessAdb.enabled)
        val sets = avatars.read()
        val personaId = app.phoneConnection.personaId.value
        val nativeConfirmations = io.github.lejw0925.virtualscreen.systemtools.AlarmRequests.state.value.filter {
            it.phase in setOf("pending", "dispatching") && it.binding.isNotEmpty()
                && it.binding["connection_id"] == app.phoneSession.flow.value.connectionId
        }.map { request -> NativeConfirmationState(taskId = request.binding["task_id"].orEmpty(),
            executionId = request.binding["execution_id"].orEmpty(), requestId = request.id, phase = request.phase,
            hour = request.hour, minute = request.minute, label = request.label, days = request.days,
            message = io.github.lejw0925.virtualscreen.systemtools.systemToolMessage(
                io.github.lejw0925.virtualscreen.ui.uiLanguage(app.resources.configuration.locales[0]),
                if (request.phase == "pending") "ALARM_PHONE_CONFIRMATION_REQUIRED" else "ALARM_DISPATCH_IN_PROGRESS").orEmpty()) }
        val profiles = (conversation.map { it.personaId } + personaId + mutable.value.avatarProfiles.keys + avatars.configuredPersonaIds())
            .filter(String::isNotBlank).distinct()
            .associateWith { avatars.profile(it, sets) }
        val authenticated = app.phoneConnection.savedToken().isNotBlank()
        val setup = when {
            !authenticated -> "login"
            !permission.paired || !permission.privilegeReady -> "pairing"
            !permission.accessibility || !permission.overlay || !permission.microphone -> "permissions"
            !prefs.getBoolean("onboarding_complete", false) -> "mode"
            else -> "complete"
        }
        mutable.update { it.copy(auth = login.state.value, connection = app.phoneConnection.state.value,
            mode = usage.mode, borrowTimeoutSeconds = usage.borrowTimeoutSeconds, pendingInputGrant = usage.pendingInputGrant,
            permissions = permission, onboardingStep = setup, onboardingComplete = setup == "complete",
            task = TaskState(usage.taskId.orEmpty(), usage.inputId.orEmpty(), usage.phase,
                if (usage.phase == "running_main") "main_screen" else "virtual_screen", applicationLabel(usage.application),
                "", usage.failureCategory.orEmpty(), usage.failureCode.orEmpty(),
                result = app.phoneConnection.taskResult.value.takeIf { result -> result.first == usage.taskId }?.second.orEmpty(),
                decision = usage.decision, deadlineMs = usage.deadlineMs ?: 0L, countdownSeconds = usage.countdownSeconds,
                restoreStatus = usage.restoreStatus, retryUsed = usage.retryUsed,
                handoffReason = usage.handoffReason, handoffSessionId = usage.handoffSessionId, handoffId = usage.handoffId),
            conversation = conversation, voice = if (recorder != null) "listening" else if (conversation.any { it.audioStatus == "playing" }) "speaking" else "idle",
            overlayEnabled = prefs.getBoolean("overlay_enabled", false),
            mainScreenIndicatorVisible = app.mainScreen.indicatorVisible.value,
            mainScreenIndicatorHeightPx = app.mainScreen.indicatorHeight.value,
            nativeConfirmations = nativeConfirmations,
            avatarSets = sets, selectedAvatarSet = profiles[personaId]?.baseSetId ?: avatars.selected(sets),
            personaId = personaId, speechEmotions = avatars.emotions, avatarProfiles = profiles,
            replyPreference = prefs.getString("reply_preference", "auto") ?: "auto",
            personaName = app.phoneConnection.personaName.value,
            handsFree = handsFreeEnabled, handsFreeAvailable = true) }
    }
    private fun action(block: () -> Unit) { runCatching { block(); mutable.update { it.copy(error = "") }; rebuild(); syncHandsFree() }
        .onFailure { failure -> mutable.update { it.copy(error = failure.message?.takeIf { code -> code.matches(Regex("[A-Z0-9_]+")) } ?: "ACTION_FAILED") } } }
    override fun login(host: String, username: String, password: String) = login.login(host, username, password)
    override fun logout() = action { handsFreeEnabled = false; syncHandsFree(); endVoiceInput(false); setOverlay(false); login.logout() }
    override fun retryRegistration() = action { login.retry() }
    override fun setUsageMode(mode: String) = action { app.mainScreen.policy.setMode(mode) }
    override fun setBorrowTimeoutSeconds(seconds: Int) = action { app.mainScreen.policy.setBorrowTimeoutSeconds(seconds) }
    override fun respondBorrow(taskId: String, choice: String) = action { app.mainScreen.policy.respondBorrow(taskId, choice) }
    override fun cancelCountdown(taskId: String) = action { app.mainScreen.policy.cancelCountdown(taskId) }
    override fun acceptHumanHandoff(taskId: String, handoffId: String) = action {
        val usage = app.mainScreen.policy.state.value
        check(usage.taskId == taskId && usage.handoffId == handoffId) { "PHONE_HANDOFF_STALE" }
        val sessionId = usage.handoffSessionId ?: error("PHONE_HANDOFF_STALE")
        if (usage.phase == "waiting_human") app.phoneSession.acceptHumanHandoff(taskId, sessionId, handoffId)
        else check(usage.phase == "human_control") { "PHONE_HANDOFF_STALE" }
        val options = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY)
        app.startActivity(Intent(app, io.github.lejw0925.virtualscreen.ui.HumanHandoffActivity::class.java)
            .putExtra("task_id", taskId).putExtra("session_id", sessionId).putExtra("handoff_id", handoffId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options.toBundle())
    }
    override fun returnHumanHandoff(taskId: String, handoffId: String) = action {
        val usage = app.mainScreen.policy.state.value
        check(usage.taskId == taskId && usage.handoffId == handoffId) { "PHONE_HANDOFF_STALE" }
        app.phoneSession.returnHumanHandoff(taskId, usage.handoffSessionId ?: error("PHONE_HANDOFF_STALE"), handoffId)
    }
    override fun sendText(text: String, useMainScreen: Boolean) = action {
        check(app.phoneConnection.state.value == "ready") { "PHONE_NOT_READY" }
        check(text.isNotBlank()) { "EMPTY_INPUT" }
        if (!app.phoneSession.flow.value.executionEnabled) app.phoneConnection.resume()
        check(app.phoneConnection.sendText(text, useMainScreen)) { "INPUT_NOT_SENT" }
    }
    override fun startVoiceInput(useMainScreen: Boolean) = action {
        check(granted(Manifest.permission.RECORD_AUDIO)) { "MICROPHONE_PERMISSION_REQUIRED" }
        check(app.phoneConnection.state.value == "ready") { "PHONE_NOT_READY" }
        check(recorder == null) { "ALREADY_RECORDING" }
        handsFreeRecorder.stop()
        if (!app.phoneSession.flow.value.executionEnabled) app.phoneConnection.resume()
        recordingMainScreen = useMainScreen
        app.phoneConnection.audio.recording(true)
        recording = File.createTempFile("phone-record-", ".m4a", app.cacheDir)
        try {
            val active = MediaRecorder(app)
            recorder = active
            active.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC); setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC); setAudioSamplingRate(16000); setAudioEncodingBitRate(64000)
                setMaxDuration(60_000); setOutputFile(recording!!.absolutePath)
                setOnInfoListener { source, what, _ -> if (recorder === source && what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) endVoiceInput(false) }
                setOnErrorListener { source, _, _ -> if (recorder === source) endVoiceInput(false) }
                val callback = object : AudioManager.AudioRecordingCallback() {
                    override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                        if (recorder === active && configs.any { it.isClientSilenced }) endVoiceInput(false)
                    }
                }
                recordingCallback = callback
                registerAudioRecordingCallback(app.mainExecutor, callback)
                prepare(); start()
            }
        } catch (failure: Exception) { endVoiceInput(false); throw failure }
    }
    override fun endVoiceInput(send: Boolean) {
        val active = recorder ?: return
        recorder = null
        val file = recording; recording = null
        recordingCallback?.let { runCatching { active.unregisterAudioRecordingCallback(it) } }; recordingCallback = null
        val valid = runCatching { active.stop() }.isSuccess
        runCatching { active.release() }; app.phoneConnection.audio.recording(false)
        if (send && valid && file != null) app.phoneConnection.sendRecording(file, PhoneAudio.nowMs(), recordingMainScreen) else file?.delete()
        recordingMainScreen = false
        rebuild()
        syncHandsFree()
    }
    override fun stopTask() = action { app.phoneConnection.stopTask() }
    override fun cancelTask(taskId: String) = action { app.phoneConnection.cancelTask(taskId) }
    override fun setOverlay(enabled: Boolean) = action {
        if (enabled) check(Settings.canDrawOverlays(app)) { "OVERLAY_PERMISSION_REQUIRED" }
        prefs.edit().putBoolean("overlay_enabled", enabled).apply()
        if (enabled) ensureOverlayService() else {
            minimizeAfterOverlay.clear()
            overlayServiceRequested = false
            overlayServiceReady = false
            overlayVisibilityChanged(false)
            app.stopService(Intent(app, PhoneFloatingService::class.java))
        }
    }
    private fun ensureOverlayService() {
        check(Settings.canDrawOverlays(app)) { "OVERLAY_PERMISSION_REQUIRED" }
        if (overlayServiceRequested) return
        app.startForegroundService(Intent(app, PhoneFloatingService::class.java))
        overlayServiceRequested = true
    }
    /** Restore the saved bubble when the runtime returns without opening an Activity. */
    fun restoreOverlayAfterRuntimeStart() {
        if (!prefs.getBoolean("overlay_enabled", false) || overlayServiceRequested) return
        app.activityLog.runtime("floating_service", "restore_requested")
        android.util.Log.i("PhoneOverlay", "event=restore_requested")
        runCatching { ensureOverlayService() }.onFailure {
            app.activityLog.failure("floating_service_restore", it)
            android.util.Log.w("PhoneOverlay", "event=restore_failed type=${it.javaClass.simpleName}")
        }
    }
    override fun minimizeToOverlay() = action {
        val owner = activity.get() ?: error("OPEN_APP_FOR_PERMISSION")
        check(Settings.canDrawOverlays(app)) { "OVERLAY_PERMISSION_REQUIRED" }
        prefs.edit().putBoolean("overlay_enabled", true).apply()
        minimizeAfterOverlay = WeakReference(owner)
        ensureOverlayService()
        if (overlayServiceReady) finishMinimizeToOverlay()
    }
    private fun finishMinimizeToOverlay() {
        val owner = minimizeAfterOverlay.get() ?: return
        minimizeAfterOverlay.clear()
        if (!owner.isFinishing && !owner.isDestroyed && owner.hasWindowFocus()) {
            check(owner.moveTaskToBack(true)) { "APP_MINIMIZE_FAILED" }
        }
    }
    fun overlayServiceChanged(ready: Boolean) {
        overlayServiceReady = ready
        overlayServiceRequested = ready
        if (ready) action { finishMinimizeToOverlay() }
        else { minimizeAfterOverlay.clear(); overlayVisibilityChanged(false) }
    }
    fun overlayVisibilityChanged(visible: Boolean) {
        mutable.update { it.copy(overlayVisible = visible) }
        syncHandsFree()
    }
    private var pairingAfterPermission = false
    fun permissionResult() {
        refresh()
        if (pairingAfterPermission) {
            pairingAfterPermission = false
            if (granted(Manifest.permission.POST_NOTIFICATIONS)) openPermission("pairing")
            else mutable.update { it.copy(error = "PAIRING_NOTIFICATION_PERMISSION_REQUIRED") }
        }
    }
    override fun openPermission(permission: String) = action {
        when (permission) {
            "system_tools" -> app.startActivity(Intent(app,
                io.github.lejw0925.virtualscreen.systemtools.SystemToolsPermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            "microphone", "notifications", "calendar" -> {
                val owner = activity.get() ?: error("OPEN_APP_FOR_PERMISSION")
                owner.requestPermissions(when (permission) {
                    "microphone" -> arrayOf(Manifest.permission.RECORD_AUDIO)
                    "notifications" -> arrayOf(Manifest.permission.POST_NOTIFICATIONS)
                    else -> arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
                }, 1212)
            }
            else -> {
                if (permission == "pairing" && Settings.Global.getInt(app.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 1) {
                    app.startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    error("ENABLE_DEVELOPER_OPTIONS_FIRST")
                }
                if (permission == "pairing") {
                    if (!granted(Manifest.permission.POST_NOTIFICATIONS)) {
                        pairingAfterPermission = true
                        val owner = activity.get() ?: error("OPEN_APP_FOR_PERMISSION")
                        owner.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1212)
                        return@action
                    }
                    app.settings.update { it.copy(backend = io.github.lejw0925.virtualscreen.backend.BackendKind.SHIZUKU) }
                    app.wirelessAdb.startNotificationPairing().getOrThrow()
                }
                val intent = when (permission) {
                    "pairing" -> Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                    "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    "overlay" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${app.packageName}"))
                    "battery" -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${app.packageName}"))
                    "assistant" -> Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
                    else -> error("UNKNOWN_PERMISSION")
                }
                app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            }
        }
    }
    override fun pair(host: String, pairingPort: Int, code: String, connectionPort: Int?) {
        app.settings.update { it.copy(backend = io.github.lejw0925.virtualscreen.backend.BackendKind.SHIZUKU) }
        app.wirelessAdb.pair(host, pairingPort, code, connectionPort)
    }
    override fun setBootRecovery(enabled: Boolean) = action { app.wirelessAdb.setEnabled(enabled) }
    override fun restorePrivilege() { app.wirelessAdb.startPaired() }
    override fun completeOnboarding() = action {
        val p = state.value.permissions
        check(app.phoneConnection.savedToken().isNotBlank() && p.paired && p.privilegeReady && p.accessibility && p.overlay && p.microphone) { "SETUP_INCOMPLETE" }
        prefs.edit().putBoolean("onboarding_complete", true).apply()
    }
    override fun playVoice(messageId: String) = action { app.phoneConnection.messages.value.firstOrNull { it.id == messageId }?.let { app.phoneConnection.audio.replay(it.id, it.audio) } }
    override fun stopVoice(messageId: String?) = action { app.phoneConnection.audio.stop(messageId) }
    override fun selectAvatarSet(id: String) = action {
        val personaId = app.phoneConnection.personaId.value
        if (personaId.isBlank()) avatars.select(id) else avatars.selectForPersona(personaId, id)
    }
    override fun selectPersonaAvatarSet(personaId: String, setId: String) = action { avatars.selectForPersona(personaId, setId) }
    override fun addAvatarSlot(personaId: String, name: String) = action { avatars.addSlot(personaId, name) }
    override fun renameAvatarSlot(personaId: String, slotId: String, name: String) = action { avatars.renameSlot(personaId, slotId, name) }
    override fun removeAvatarSlot(personaId: String, slotId: String) = action { avatars.removeSlot(personaId, slotId) }
    override fun setAvatarSlotEmotions(personaId: String, slotId: String, emotions: List<String>) = action { avatars.mapSlot(personaId, slotId, emotions) }
    override fun restoreAvatarSlotImage(personaId: String, slotId: String) = action { avatars.restoreImage(personaId, slotId) }
    override fun restoreAvatarDefaultImage(personaId: String) = action { avatars.restoreImage(personaId, null) }
    override fun resetAvatarProfile(personaId: String) = action { avatars.reset(personaId) }
    override fun pickAvatarSlotImage(personaId: String, slotId: String) = pickAvatarImage(personaId, slotId)
    override fun pickAvatarDefaultImage(personaId: String) = pickAvatarImage(personaId, null)
    private fun pickAvatarImage(personaId: String, slotId: String?) = action {
        avatars.prepareImageSelection(personaId, slotId)
        val intent = Intent(app, AvatarImagePickerActivity::class.java)
            .putExtra(AvatarImagePickerActivity.PERSONA_ID, personaId)
            .putExtra(AvatarImagePickerActivity.SLOT_ID, slotId)
        activity.get()?.startActivity(intent) ?: app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    internal fun importAvatarImage(personaId: String, slotId: String?, uri: Uri, retained: Boolean = false) {
        scope.launch {
            try {
                runCatching { withContext(Dispatchers.IO) { avatars.importImage(personaId, slotId, uri) } }
                    .onSuccess { action {} }
                    .onFailure { failure -> action { throw failure } }
            } finally {
                if (retained) runCatching { app.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
        }
    }
    override fun setReplyPreference(value: String) = action {
        require(value in setOf("auto", "voice", "text_only")) { "UNKNOWN_REPLY_PREFERENCE" }
        prefs.edit().putString("reply_preference", value).apply()
    }
    override fun setHandsFree(enabled: Boolean) = action {
        if (enabled) check(granted(Manifest.permission.RECORD_AUDIO)) { "MICROPHONE_PERMISSION_REQUIRED" }
        handsFreeEnabled = enabled
    }

    internal fun handsFreeServiceRequested(session: Long) =
        microphoneServiceRequested && handsFreeEnabled && session == microphoneSession

    internal fun handsFreeServiceChanged(session: Long, ready: Boolean) {
        if (session != microphoneSession) return
        microphoneServiceReady = ready
        if (ready) syncHandsFree()
        else {
            handsFreeRecorder.stop()
            if (microphoneServiceRequested) disableHandsFree("MICROPHONE_SERVICE_STOPPED")
        }
    }

    internal fun handsFreeServiceFailed(session: Long) {
        if (session == microphoneSession) disableHandsFree("MICROPHONE_FOREGROUND_START_FAILED")
    }

    private fun disableHandsFree(reason: String) {
        handsFreeEnabled = false
        syncHandsFree()
        rebuild()
        mutable.update { it.copy(error = reason) }
    }

    private fun syncHandsFree() {
        val screen = app.getSystemService(PowerManager::class.java).isInteractive &&
            !app.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val visible = startedActivities.isNotEmpty() || mutable.value.overlayVisible
        val wanted = handsFreeEnabled && visible && screen &&
            app.phoneConnection.state.value == "ready" && granted(Manifest.permission.RECORD_AUDIO)
        if (!wanted) {
            handsFreeRecorder.stop()
            if (microphoneServiceRequested) {
                microphoneServiceRequested = false
                microphoneServiceReady = false
                app.stopService(Intent(app, PhoneHandsFreeService::class.java))
            }
            return
        }
        if (!microphoneServiceRequested) {
            microphoneServiceRequested = true
            microphoneSession++
            try { app.startForegroundService(Intent(app, PhoneHandsFreeService::class.java)
                .putExtra("microphone_session", microphoneSession)) }
            catch (_: Exception) { disableHandsFree("MICROPHONE_FOREGROUND_START_FAILED"); return }
        }
        if (microphoneServiceReady && recorder == null && !app.phoneConnection.audio.playbackActive.value)
            handsFreeRecorder.start()
        else handsFreeRecorder.stop()
    }
    override val voiceLevel: StateFlow<Float> get() = app.phoneConnection.audio.level
    override fun refresh() { rebuild(); syncHandsFree() }
    override fun openApp() = action { app.startActivity(Intent(app, PhoneProductActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
    override fun openVirtualScreen() = action {
        val owner = activity.get() ?: error("OPEN_APP_FOR_PERMISSION")
        owner.startActivity(Intent(owner, io.github.lejw0925.virtualscreen.ui.VirtualScreenPreviewActivity::class.java))
    }
    override fun acknowledgeMessage(id: String, surface: String) { if (surface in setOf("chat", "overlay")) app.phoneConnection.acknowledge(id, surface, surface == "chat") }
}

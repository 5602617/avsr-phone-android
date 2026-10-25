package io.github.lejw0925.virtualscreen.app

import io.github.lejw0925.virtualscreen.phone.HostCapabilities
import io.github.lejw0925.virtualscreen.phone.ExpressionCatalog

/** Public UI contract. All state is observational; only AvsrActions may change it. */
data class AvsrAppState(
    val auth: AuthState = AuthState(),
    val connection: String = "disconnected",
    val hostCapabilities: HostCapabilities = HostCapabilities(),
    val mode: String = "background",
    val borrowTimeoutSeconds: Int = 30,
    val pendingInputGrant: String? = null,
    val permissions: PermissionState = PermissionState(),
    val onboardingStep: String = "login",
    val onboardingComplete: Boolean = false,
    val task: TaskState = TaskState(),
    val conversation: List<ConversationItem> = emptyList(),
    val voice: String = "idle",
    val overlayEnabled: Boolean = false,
    val overlayVisible: Boolean = false,
    /** The grant owner has attached the designed main-screen controls. */
    val mainScreenIndicatorVisible: Boolean = false,
    val mainScreenIndicatorHeightPx: Int = 0,
    val nativeConfirmations: List<NativeConfirmationState> = emptyList(),
    val avatarSets: List<AvatarSet> = emptyList(),
    val selectedAvatarSet: String = "",
    val personaId: String = "",
    val avatarProfiles: Map<String, PersonaAvatarProfile> = emptyMap(),
    val editableAvatarPersonaIds: List<String> = emptyList(),
    /** auto | voice | text_only; sent with every input as phone_reply_preference. */
    val replyPreference: String = "auto",
    /** Name of the persona the host is running, sent by the host; blank on hosts that do not send it. */
    val personaName: String = "",
    /** Hands-free listening: no hold-to-talk; she listens and sends when you stop speaking. */
    val handsFree: Boolean = false,
    val handsFreeAvailable: Boolean = false,
    val error: String = "",
)

data class AuthState(val phase: String = "signed_out", val host: String = "", val deviceId: String = "",
    val reason: String = "", val retryable: Boolean = false)
data class PermissionState(val paired: Boolean = false, val privilegeReady: Boolean = false,
    val privilegeStatus: String = "idle", val accessibility: Boolean = false, val overlay: Boolean = false,
    val microphone: Boolean = false, val notifications: Boolean = false, val bootRecovery: Boolean = false,
    val accessibilityAuthorized: Boolean = false)
data class TaskState(val id: String = "", val inputId: String = "", val phase: String = "idle",
    val display: String = "virtual_screen", val appName: String = "", val step: String = "",
    val failureCategory: String = "", val reason: String = "", val result: String = "",
    val decision: String = "none", val deadlineMs: Long = 0, val countdownSeconds: Int = 0,
    val restoreStatus: String = "", val retryUsed: Boolean = false,
    val handoffReason: String = "", val handoffSessionId: String? = null, val handoffId: String? = null,
    val borrowRequestSource: String = "", val borrowRequestReason: String = "")
data class ConversationItem(val id: String, val role: String, val text: String,
    val timestampMs: Long, val delivery: String, val audioStatus: String,
    val speechEmotion: String = "", val voiceInput: Boolean = false, val canReplay: Boolean = false,
    val transcriptStatus: String = "", val transcriptReason: String = "", val personaId: String = "")
/** personas: persona names this set belongs to; a host persona with no matching set shows the default mark. */
data class AvatarSet(val id: String, val name: String, val images: Map<String, String>, val source: String,
    val personas: List<String> = emptyList(), val personaIds: List<String> = emptyList())

data class AvatarSlot(val id: String, val name: String, val emotions: List<String> = emptyList(),
    val imageUri: String? = null, val hasCustomImage: Boolean = false)
data class PersonaAvatarProfile(val personaId: String, val baseSetId: String = "",
    val slots: List<AvatarSlot> = emptyList(), val defaultImageUri: String? = null,
    val images: Map<String, String> = emptyMap(), val hasOverride: Boolean = false,
    val hasCustomDefaultImage: Boolean = false, val expressionCatalog: ExpressionCatalog? = null)

data class NativeConfirmationState(val kind: String = "alarm", val taskId: String = "", val executionId: String = "",
    val requestId: String = "", val phase: String = "pending", val hour: Int = 0, val minute: Int = 0,
    val label: String = "", val days: List<Int> = emptyList(), val message: String = "")

/** High-rate signals kept out of AvsrAppState so they do not rebuild the whole state. */
interface AvsrLiveSignals {
    /** 0..1 loudness of her voice at the play head; 0 when silent. */
    val voiceLevel: kotlinx.coroutines.flow.StateFlow<Float>
}

/** Calls return immediately. Errors are reported in AvsrAppState.error/auth/task; never contain credentials. */
interface AvsrActions {
    fun login(host: String, username: String, password: String)
    fun logout()
    fun retryRegistration()
    fun setUsageMode(mode: String)
    fun setBorrowTimeoutSeconds(seconds: Int)
    fun respondBorrow(taskId: String, choice: String)
    fun cancelCountdown(taskId: String)
    fun acceptHumanHandoff(taskId: String, handoffId: String)
    fun returnHumanHandoff(taskId: String, handoffId: String)
    fun sendText(text: String, useMainScreen: Boolean = false)
    fun startVoiceInput(useMainScreen: Boolean = false)
    fun endVoiceInput(send: Boolean = true)
    fun stopTask()
    fun cancelTask(taskId: String)
    fun setOverlay(enabled: Boolean)
    fun minimizeToOverlay()
    fun openVirtualScreen()
    fun openPermission(permission: String)
    fun pair(host: String, pairingPort: Int, code: String, connectionPort: Int?)
    fun setBootRecovery(enabled: Boolean)
    fun restorePrivilege()
    fun completeOnboarding()
    fun playVoice(messageId: String)
    fun stopVoice(messageId: String? = null)
    fun selectAvatarSet(id: String)
    fun selectPersonaAvatarSet(personaId: String, setId: String)
    fun addAvatarSlot(personaId: String, name: String)
    fun renameAvatarSlot(personaId: String, slotId: String, name: String)
    fun removeAvatarSlot(personaId: String, slotId: String)
    fun setAvatarSlotEmotions(personaId: String, slotId: String, emotions: List<String>)
    fun pickAvatarSlotImage(personaId: String, slotId: String)
    fun restoreAvatarSlotImage(personaId: String, slotId: String)
    fun pickAvatarDefaultImage(personaId: String)
    fun restoreAvatarDefaultImage(personaId: String)
    fun resetAvatarProfile(personaId: String)
    fun deleteAvatarProfile(personaId: String)
    fun setReplyPreference(value: String)
    fun setHandsFree(enabled: Boolean) {}
    fun refresh()
    fun openApp()
    fun acknowledgeMessage(id: String, surface: String)
}

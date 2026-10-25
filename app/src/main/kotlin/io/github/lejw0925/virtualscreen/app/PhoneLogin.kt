package io.github.lejw0925.virtualscreen.app

import io.github.lejw0925.virtualscreen.VirtualScreenApplication
import io.github.lejw0925.virtualscreen.core.CredentialVault
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Credential exchange uses the plugin's original device bearer, never a second agent session. */
class PhoneLogin(private val app: VirtualScreenApplication) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder().callTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private val prefs = app.getSharedPreferences("phone_connection", 0)
    private val vault = CredentialVault(app)
    private val mutable = MutableStateFlow(AuthState(host = prefs.getString("host_base", "").orEmpty(), deviceId = app.phoneConnection.savedDevice))
    val state = mutable.asStateFlow()
    private var loginJob: Job? = null
    private val authLock = Any()
    private var authEpoch = 0L
    init {
        scope.launch {
            app.phoneConnection.state.collect { phase ->
                if (phase == "auth_required") {
                    mutable.update { it.copy(phase = "signed_out", reason = "DEVICE_CREDENTIAL_REJECTED", retryable = false) }
                    return@collect
                }
                if (app.phoneConnection.savedToken().isBlank()) return@collect
                mutable.update { it.copy(
                    phase = when (phase) {
                        "ready" -> "ready"
                        "mcp_unavailable", "connection_failed" -> "registration_failed"
                        "disconnected" -> if (it.phase == "signed_out") "signed_out" else "offline"
                        else -> if (phase.startsWith("host_error")) "registration_failed" else "registering"
                    },
                    reason = app.phoneConnection.registrationReason.value,
                    retryable = phase != "ready",
                    deviceId = app.phoneConnection.savedDevice,
                ) }
            }
        }
    }
    fun login(host: String, username: String, password: String) = synchronized(authLock) {
        if (loginJob?.isActive == true) return
        val epoch = ++authEpoch
        loginJob = scope.launch {
            try {
                val base = normalizeHost(host)
                require(username.isNotBlank() && password.isNotEmpty()) { "LOGIN_FIELDS_REQUIRED" }
                synchronized(authLock) {
                    if (epoch != authEpoch) return@launch
                    mutable.value = AuthState("logging_in", base)
                }
                val id = prefs.getString("login_device_id", null) ?: "phone-${UUID.randomUUID()}".also {
                    prefs.edit().putString("login_device_id", it).commit()
                }
                val body = JSONObject().put("username", username).put("password", password).put("device_id", id)
                val request = Request.Builder().url("$base/auth/login")
                    .post(body.toString().toRequestBody("application/json".toMediaType())).build()
                val result = runInterruptible {
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) error(when (response.code) {
                            401, 403 -> "LOGIN_REJECTED"
                            429 -> "LOGIN_TRY_LATER"
                            else -> "LOGIN_HTTP_${response.code}"
                        })
                        JSONObject(response.body.string())
                    }
                }
                val token = result.getString("device_token")
                val device = result.getString("device_id")
                require(device == id && token.length >= 24) { "LOGIN_RESPONSE_INVALID" }
                val ws = base.replaceFirst("http://", "ws://").replaceFirst("https://", "wss://") + "/ws"
                synchronized(authLock) {
                    // A response received before logout must never recreate its local credential afterwards.
                    if (epoch != authEpoch) return@launch
                    if (!isActive) return@launch
                    prefs.edit().putString("host_base", base).apply()
                    mutable.value = AuthState("registering", base, device)
                    app.phoneConnection.connect(ws, token, device)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (error: Exception) {
                // Do not include network response bodies, request objects, credentials or untrusted error messages.
                val code = error.message?.takeIf { it.matches(Regex("[A-Z0-9_]{1,80}")) } ?: "LOGIN_UNREACHABLE"
                synchronized(authLock) {
                    if (epoch == authEpoch) mutable.update { it.copy(phase = "registration_failed", reason = code, retryable = true) }
                }
            }
        }
    }
    fun retry() {
        if (app.phoneConnection.savedToken().isBlank()) {
            mutable.update { it.copy(phase = "signed_out", reason = "LOGIN_REQUIRED") }; return
        }
        mutable.update { it.copy(phase = "registering", reason = "") }
        app.phoneConnection.retryRegistration()
    }
    fun restore() {
        val token = app.phoneConnection.savedToken()
        if (token.isBlank()) {
            val stored = app.phoneConnection.hasSavedToken()
            android.util.Log.i("PhoneConnection", "stage=restore credential_present=$stored credential_readable=false")
            if (stored) mutable.update { it.copy(phase = "registration_failed", reason = "DEVICE_CREDENTIAL_READ_FAILED", retryable = true) }
            return
        }
        android.util.Log.i("PhoneConnection", "stage=restore credential_present=true credential_readable=true")
        mutable.update { it.copy(phase = "registering") }
        runCatching { app.phoneConnection.connect(app.phoneConnection.savedUrl, token, app.phoneConnection.savedDevice, allowExecution = true) }
            .onFailure { mutable.update { it.copy(phase = "registration_failed", reason = "CONNECTION_RESTORE_FAILED", retryable = true) } }
    }
    fun logout() = synchronized(authLock) {
        val epoch = ++authEpoch
        loginJob?.cancel()
        loginJob = null
        val token = app.phoneConnection.savedToken()
        val base = prefs.getString("host_base", "").orEmpty()
        app.phoneConnection.disconnect()
        vault.remove("phone-host-token")
        app.phoneConnection.clearConversation()
        mutable.value = AuthState("signed_out", base)
        scope.launch {
            if (base.isBlank() || token.isBlank()) return@launch
            val revoked = runCatching {
                client.newCall(Request.Builder().url("$base/auth/logout").header("Authorization", "Bearer $token")
                    .post("{}".toRequestBody("application/json".toMediaType())).build()).execute().use { it.isSuccessful || it.code == 401 }
            }.getOrDefault(false)
            synchronized(authLock) {
                if (!revoked && epoch == authEpoch && mutable.value.phase == "signed_out")
                    mutable.update { it.copy(reason = "LOCAL_LOGOUT_REMOTE_REVOCATION_UNCONFIRMED") }
            }
        }
        Unit
    }
    companion object {
        fun normalizeHost(value: String): String {
            val raw = value.trim().removeSuffix("/ws").trimEnd('/')
                .replaceFirst("ws://", "http://").replaceFirst("wss://", "https://")
            val uri = URI(if ("://" in raw) raw else "https://$raw")
            require(uri.scheme in setOf("http", "https") && uri.host != null && uri.userInfo == null && uri.rawQuery == null && uri.fragment == null && uri.path.orEmpty().isEmpty()) { "INVALID_HOST_URL" }
            val local = uri.host in setOf("localhost", "127.0.0.1", "::1") || uri.host.matches(Regex("(?:10\\.\\d+\\.\\d+\\.\\d+|192\\.168\\.\\d+\\.\\d+|172\\.(?:1[6-9]|2\\d|3[01])\\.\\d+\\.\\d+)"))
            require(uri.scheme == "https" || local) { "HTTPS_REQUIRED_FOR_PUBLIC_HOST" }
            return uri.toString()
        }
    }
}

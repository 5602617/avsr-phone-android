package io.github.lejw0925.virtualscreen.phone

import io.github.lejw0925.virtualscreen.BuildConfig
import io.github.lejw0925.virtualscreen.VirtualScreenApplication
import io.github.lejw0925.virtualscreen.core.CredentialVault
import io.github.lejw0925.virtualscreen.core.VirtualScreenSessionManager
import io.github.lejw0925.virtualscreen.tunnel.FrpcConfig
import io.github.lejw0925.virtualscreen.tunnel.TunnelMode
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import okhttp3.*
import org.json.JSONObject
import android.util.Base64
import android.util.Log
import java.io.File

data class PhoneMessage(val id: String, val text: String, val incoming: Boolean,
    val replyTo: String? = null, val extras: String = "", val delivery: String = "received",
    val audio: List<PhoneAudioClip> = emptyList(), val voiceInput: Boolean = false,
    val audioStatus: String = "", val receivedMs: Double? = null,
    val speechEmotion: String = "neutral", val timestampMs: Long = System.currentTimeMillis(),
    val transcriptStatus: String = "", val transcriptReason: String = "", val personaId: String = "")

data class PhoneAuthorizationRequest(val id: String, val question: String, val choices: List<String>,
    val connectionId: String, internal val binding: String)

/** A transport/presentation client only; Persona owns conversation and task state. */
class HostSocketClient(private val app: VirtualScreenApplication) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private val vault = CredentialVault(app)
    private val prefs = app.getSharedPreferences("phone_connection", 0)
    private val mutableMessages = MutableStateFlow<List<PhoneMessage>>(emptyList())
    val messages = mutableMessages.asStateFlow()
    private val mutableState = MutableStateFlow("disconnected")
    val state = mutableState.asStateFlow()
    private val mutablePersonaName = MutableStateFlow("")
    val personaName = mutablePersonaName.asStateFlow()
    private val mutablePersonaId = MutableStateFlow("")
    val personaId = mutablePersonaId.asStateFlow()
    private val mutableRegistrationReason = MutableStateFlow("")
    val registrationReason = mutableRegistrationReason.asStateFlow()
    private val mutableTaskResult = MutableStateFlow("" to "")
    val taskResult = mutableTaskResult.asStateFlow()
    private val mutableAuthorizations = MutableStateFlow<List<PhoneAuthorizationRequest>>(emptyList())
    val authorizations = mutableAuthorizations.asStateFlow()
    val presentation = PhonePresentation(app, this)
    private data class PendingRecording(val file: File, val releasedMs: Double)
    private val pendingAudio = java.util.concurrent.ConcurrentHashMap<String, PendingRecording>()
    val audio = PhoneAudio(app) { frame ->
        val id = frame.getString("id")
        val status = frame.getString("status")
        mutableMessages.update { list -> list.map { if (it.id == id)
            if (it.audioStatus == "stopped" && !frame.optBoolean("replay") && status != "stopped") it
            else it.copy(audioStatus = if (status == "buffered" && it.audioStatus == "playing") "playing" else status,
                audio = if (status == "buffered") it.audio.map { clip -> clip.copy(complete = true) } else it.audio)
            else it } }
        send(frame)
    }
    @Volatile private var socket: WebSocket? = null
    private var connectionJob: Job? = null
    @Volatile private var connectionEpoch = 0L
    private var tunnelJob: Job? = null
    @Volatile private var resumeOnReady = false
    val savedUrl get() = prefs.getString("url", "ws://10.0.2.2:12420/ws").orEmpty()
    val savedDevice get() = prefs.getString("device", "mumu-phone-0").orEmpty()
    fun hasSavedToken() = vault.contains("phone-host-token")
    fun savedToken() = try { vault.get("phone-host-token").orEmpty() } catch (failure: Exception) {
        Log.w("PhoneConnection", "credential_read_failed type=${failure.javaClass.simpleName} cause=${failure.cause?.javaClass?.simpleName}")
        mutableRegistrationReason.value = "DEVICE_CREDENTIAL_READ_FAILED"
        ""
    }

    init {
        // Recordings and received voices are temporary, never saved as a second history.
        app.cacheDir.listFiles()?.filter { it.name.startsWith("phone-record-") || it.name.startsWith("phone-reply-") }?.forEach { it.delete() }
        scope.launch {
            app.sessions.state.map { it.lease?.owner }.distinctUntilChanged().collect { owner ->
                if(owner == VirtualScreenSessionManager.PHONE_CLIENT && app.phoneSession.flow.value.executionEnabled &&
                    !app.mainScreen.policy.state.value.humanHandoff)
                    pause("phone_takeover")
            }
        }
        scope.launch {
            combine(state, app.phoneSession.flow, app.mcp.activity) { connection, binding, activity ->
                listOf(connection, if(binding.executionEnabled) "remote enabled" else binding.reason.ifBlank { "paused" },
                    activity?.let { "${it.tool}: ${it.phase}" }.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
            }.collect { presentation.status(it) }
        }
    }

    @Synchronized fun connect(url: String, token: String, deviceId: String, allowExecution: Boolean = true) {
        val uri = URI(url)
        require(uri.scheme in setOf("ws", "wss") && uri.host != null && uri.userInfo == null && uri.path == "/ws") { "INVALID_HOST_URL" }
        // Cleartext is limited to the local demo network. Internet hosts must use WSS.
        val local = uri.host == "localhost" || uri.host == "127.0.0.1" ||
            uri.host.matches(Regex("(?:10\\.\\d+\\.\\d+\\.\\d+|192\\.168\\.\\d+\\.\\d+|172\\.(?:1[6-9]|2\\d|3[01])\\.\\d+\\.\\d+)"))
        require(uri.scheme == "wss" || local) { "WSS_REQUIRED_FOR_PUBLIC_HOST" }
        require(token.length >= 24 && deviceId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "INVALID_PHONE_CREDENTIALS" }
        val previous = connectionJob
        disconnect()
        val epoch = connectionEpoch
        prefs.edit().putString("url", url).putString("device", deviceId).apply()
        if (savedToken() != token) vault.put("phone-host-token", token)
        mutableRegistrationReason.value = ""
        connectionJob = scope.launch {
            try {
                Log.i("PhoneConnection", "epoch=$epoch stage=previous_cleanup")
                // A cancelled intermediate reconnect must still carry its predecessor's cleanup barrier.
                withContext(NonCancellable) { previous?.join() }
                ensureActive()
                resumeOnReady = allowExecution // A new Task still needs its own binding and screen permission.
                mutableState.value = "starting_local_mcp"
                Log.i("PhoneConnection", "epoch=$epoch stage=local_mcp_start")
                app.setMcpEnabled(true)
                val localServer = withTimeout(60_000) {
                    combine(app.mcp.flow, app.mcpChanging) { server, starting ->
                        // A retry may still have the previous error until its startup coroutine runs.
                        server.takeIf { it.running || (!starting && it.lastError != null) }
                    }.filterNotNull().first()
                }
                check(localServer.running) { safeConnectionError(localServer.lastError, "LOCAL_MCP_START_FAILED") }
                var attempt = 0
                while(isActive) {
                    attempt++
                    val attemptNumber = attempt
                    val stage = java.util.concurrent.atomic.AtomicReference("websocket_connect")
                    fun trace(event: String) {
                        // Only locally generated phase names/codes; never URLs, frames or credentials.
                        Log.i("PhoneConnection", "epoch=$epoch attempt=$attemptNumber stage=${stage.get()} $event")
                    }
                    trace("start")
                    mutableState.value = "connecting"
                    val frames = Channel<JSONObject>(64)
                    val closed = CompletableDeferred<Unit>()
                    val credentialRejected = java.util.concurrent.atomic.AtomicBoolean(false)
                    val localProtocolFailure = java.util.concurrent.atomic.AtomicBoolean(false)
                    val ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            stage.set("await_host_ready")
                            trace("opened http=${response.code}")
                            webSocket.send(JSONObject().put("type", "auth").put("token", token).toString())
                        }
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            // The listener can receive ready before newWebSocket() returns.
                            // Its private channel still belongs to this attempt.
                            if(connectionEpoch != epoch) return
                            val receivedMs = PhoneAudio.nowMs()
                            val frame = runCatching { JSONObject(text).put("_received_ms", receivedMs) }.getOrNull()
                            if(frame == null || !frames.trySend(frame).isSuccess) {
                                localProtocolFailure.set(true)
                                webSocket.close(1008, "Invalid host frame")
                            }
                        }
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            if (!localProtocolFailure.get() && (code == 4401 || (code == 1008 && reason in setOf(
                                "Authentication failed", "Credential replaced", "Logged out", "Device credential revoked"))))
                                credentialRejected.set(true)
                            trace("closing code=$code credential_rejected=${credentialRejected.get()}")
                            webSocket.close(code, null)
                        }
                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                            trace("closed code=$code")
                            closed.complete(Unit); frames.close()
                        }
                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            val code = "HOST_" + t.javaClass.simpleName.uppercase().replace(Regex("[^A-Z0-9_]"), "")
                            trace("failure=$code http=${response?.code ?: 0}")
                            if (connectionEpoch == epoch && socket === webSocket) mutableRegistrationReason.value = code
                            closed.complete(Unit); frames.close()
                        }
                    })
                    socket = ws
                    try {
                        for(frame in frames) {
                            when (frame.optString("type")) {
                                "ready" -> stage.set("device_register")
                                "device.bound" -> stage.set("tunnel_start")
                                "device.registering" -> stage.set("mcp_register")
                                "device.ready" -> stage.set("ready")
                                "device.unavailable" -> stage.set("mcp_unavailable")
                            }
                            if (frame.optString("type") in setOf("ready", "device.bound", "device.registering", "device.ready", "device.unavailable"))
                                trace("received")
                            handle(frame, deviceId, uri.host)
                        }
                        closed.await()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        val code = safeConnectionError(failure.message, "HOST_FRAME_FAILED")
                        trace("handler_failure=$code type=${failure.javaClass.simpleName}")
                        mutableRegistrationReason.value = code
                    } finally {
                        val reconnectExecution = resumeOnReady || app.phoneSession.flow.value.executionEnabled
                        if(socket === ws) socket = null
                        if (connectionEpoch == epoch) {
                            mutableAuthorizations.value = emptyList()
                            presentation.clearAuthorization()
                        }
                        if (connectionEpoch == epoch) mutableState.value = "disconnected"
                        audio.disconnected()
                        ws.cancel()
                        withContext(NonCancellable) {
                            tunnelJob?.cancelAndJoin(); tunnelJob = null
                            app.tunnels.stop()
                            app.phoneSession.disconnect("connection_lost")
                            synchronized(this@HostSocketClient) {
                                if (connectionEpoch == epoch) {
                                    if (credentialRejected.get()) {
                                        resumeOnReady = false
                                        mutableRegistrationReason.value = "DEVICE_CREDENTIAL_REJECTED"
                                        mutableState.value = "auth_required"
                                    } else {
                                        // Restore availability, never replay the disconnected Task.
                                        resumeOnReady = reconnectExecution
                                        mutableState.value = "disconnected"
                                    }
                                }
                            }
                        }
                    }
                    if (credentialRejected.get()) break
                    delay(3000)
                }
            } catch(_: TimeoutCancellationException) {
                reportConnectionFailure(epoch, "LOCAL_MCP_START_TIMEOUT")
            } catch(c: CancellationException) { throw c }
              catch(failure: Exception) { reportConnectionFailure(epoch, safeConnectionError(failure.message, "PHONE_CONNECTION_FAILED")) }
        }
    }

    private fun safeConnectionError(value: String?, fallback: String): String =
        value?.takeIf { it.matches(Regex("[A-Z][A-Z0-9_]{0,95}")) } ?: fallback

    @Synchronized private fun reportConnectionFailure(epoch: Long, code: String) {
        if (connectionEpoch != epoch) return
        resumeOnReady = false
        mutableRegistrationReason.value = code
        mutableState.value = "connection_failed"
        app.phoneSession.disconnect(code)
    }

    private suspend fun handle(frame: JSONObject, deviceId: String, host: String) {
        when(frame.optString("type")) {
            "ready" -> {
                mutablePersonaId.value = frame.optString("persona_id", "")
                mutablePersonaName.value = frame.optString("persona_name", "")
                send(JSONObject().put("type", "client.capabilities").put("audio_stream", 1).put("voice_timing", 1))
                send(JSONObject().put("type", "device.register").put("device_protocol", 1).put("device_id", deviceId)
                .put("apk", JSONObject().put("package_name", app.packageName).put("version_code", BuildConfig.VERSION_CODE))
                .put("adapter", JSONObject().put("id", "virtual-screen-qqmusic").put("version", BuildConfig.VERSION_NAME)))
            }
            "device.bound" -> {
                mutableAuthorizations.value = emptyList()
                presentation.clearAuthorization()
                require(frame.getString("device_id") == deviceId) { "DEVICE_MISMATCH" }
                app.phoneSession.bind(deviceId, frame.getString("connection_id"), frame.optString("glance_bearer"))
                app.mcp.provisionPhoneToken(frame.getString("mcp_bearer"))
                val tunnel = frame.getJSONObject("tunnel")
                require(tunnel.getString("mode") == "frpc")
                app.tunnelWanted = TunnelMode.OFF // Host connection owns restoration; never restore an unbound proxy.
                tunnelJob?.cancelAndJoin()
                app.tunnels.start(TunnelMode.FRPC, FrpcConfig(host, tunnel.getInt("port"), tunnel.getString("token"),
                    "phone-$deviceId", remotePort = tunnel.getInt("remote_port")))
                mutableState.value = "tunnel_connecting"
                tunnelJob = scope.launch {
                    var hadConnection = false
                    app.tunnels.flow.map(PhoneTunnelStatus::from).distinctUntilChanged().collect { status ->
                        mutableRegistrationReason.value = status.reason()
                        mutableState.value = status.connectionPhase(hadConnection)
                        if(status.connected) {
                            hadConnection = true
                            send(boundFrame("device.channel_ready"))
                        } else if(hadConnection || status.failed) {
                            pauseTransport("tunnel_lost")
                        }
                    }
                }
            }
            "device.ready" -> {
                require(frame.getString("connection_id") == app.phoneSession.flow.value.connectionId)
                // A probe started before tunnel loss cannot mark the broken transport ready again.
                if (!PhoneTunnelStatus.from(app.tunnels.state()).connected) return
                app.phoneSession.ready()
                mutableState.value = "ready"
                mutableRegistrationReason.value = ""
                if(resumeOnReady) { resumeOnReady = false; resume() }
            }
            "device.registering" -> mutableState.value = "registering"
            "device.unavailable" -> {
                pauseTransport("mcp_unavailable")
                mutableRegistrationReason.value = frame.optString("reason", "mcp_unavailable")
                mutableState.value = "mcp_unavailable"
                if (frame.optBoolean("retryable", false)) error("HOST_MCP_RETRYABLE")
            }
            "task.started" -> {
                require(frame.getString("connection_id") == app.phoneSession.flow.value.connectionId) { "PHONE_CONNECTION_STALE" }
                val executionId = frame.optString("execution_id").takeIf { it.isNotBlank() }
                app.phoneSession.bindTask(frame.getString("task_id"), frame.optString("input_id").takeIf { it.isNotBlank() }, executionId)
                send(boundFrame("task.bound").put("task_id", frame.getString("task_id")).put("execution_id", executionId))
            }
            "authorization.request" -> {
                require(frame.getString("connection_id") == app.phoneSession.flow.value.connectionId &&
                    frame.getString("device_id") == app.phoneSession.flow.value.deviceId) { "PHONE_CONNECTION_STALE" }
                val choices = frame.getJSONArray("choices").let { values -> (0 until values.length()).map(values::getString) }
                require(choices == listOf("允许", "拒绝")) { "PHONE_AUTHORIZATION_CHOICES_INVALID" }
                val request = PhoneAuthorizationRequest(frame.getString("id"), frame.getString("question"), choices,
                    frame.getString("connection_id"), frame.toString())
                mutableAuthorizations.update { pending -> pending.filterNot { old ->
                    JSONObject(old.binding).getString("waiter_id") == frame.getString("waiter_id")
                } + request }
                presentation.authorization(request)
            }
            "authorization.accepted" -> {
                mutableAuthorizations.update { pending -> pending.filterNot { it.id == frame.optString("id") } }
                presentation.clearAuthorization()
                mutableAuthorizations.value.firstOrNull()?.let(presentation::authorization)
            }
            "task.finished" -> {
                require(frame.getString("connection_id") == app.phoneSession.flow.value.connectionId) { "PHONE_CONNECTION_STALE" }
                val executionId = frame.optString("execution_id").takeIf { it.isNotBlank() }
                val reason = frame.optString("reason")
                if (reason != "task_terminal" && executionId != app.mainScreen.policy.state.value.executionId) return
                // Keep an active main-screen grant and its last execution routing identity between segments.
                // Virtual execution and pending local decisions retain their existing finish lifecycle.
                if (reason != "execution_segment_ended" || !app.mainScreen.policy.state.value.mainScreenAllowed) {
                    app.phoneSession.finishTask(frame.getString("task_id"), frame.optString("status", "completed"), reason)
                }
                if (frame.has("result")) {
                    mutableTaskResult.value = frame.getString("task_id") to frame.optString("result")
                }
            }
            "input.accepted" -> {
                pendingAudio.remove(frame.optString("id"))?.file?.delete()
                mutableMessages.update { list -> list.map { if(it.id == frame.optString("id")) it.copy(
                    delivery = "accepted", transcriptStatus = if (it.voiceInput && it.transcriptStatus == "uploading") "recognizing" else it.transcriptStatus) else it } }
            }
            "input.transcript" -> {
                // Update the originating voice row, never add a reply or run a task.
                if (frame.optString("connection_id") != app.phoneSession.flow.value.connectionId) return
                val id = frame.optString("id")
                val status = frame.optString("status")
                if (status == "ignored") {
                    app.mainScreen.policy.discardInput(id)
                    pendingAudio.remove(id)?.file?.delete()
                    mutableMessages.update { list -> list.filterNot {
                        it.id == id && !it.incoming && it.voiceInput
                    } }
                    return
                }
                if (status !in setOf("recognized", "failed")) return
                val text = frame.optString("text")
                val recognized = status == "recognized" && text.isNotBlank()
                mutableMessages.update { list -> list.map { message ->
                    if (message.id != id || message.incoming || !message.voiceInput || message.delivery == "discarded" ||
                        (!recognized && message.transcriptStatus == "recognized")) message
                    else message.copy(text = if (recognized) text else "",
                        transcriptStatus = if (recognized) "recognized" else "failed",
                        transcriptReason = if (recognized) "" else frame.optString("reason").ifBlank { "ASR_RESULT_UNAVAILABLE" })
                } }
            }
            "clock.ping" -> send(JSONObject().put("type", "clock.reply").put("id", frame.getString("id"))
                .put("received_ms", frame.getDouble("_received_ms")).put("sent_ms", PhoneAudio.nowMs()))
            "output.text" -> {
                val id = frame.getString("id")
                val previous = messages.value.firstOrNull { it.id == id }
                if (previous != null) { presentation.output(previous); return }
                val extras = frame.optJSONObject("platform_extras")
                val outputPersonaId = frame.optString("persona_id").ifBlank { extras?.optString("persona_id").orEmpty() }
                if (outputPersonaId.isNotBlank()) {
                    mutablePersonaId.value = outputPersonaId
                    mutablePersonaName.value = frame.optString("persona_name").ifBlank { outputPersonaId }
                }
                val message = PhoneMessage(id, frame.getString("text"), true,
                    frame.optString("reply_to"), extras?.toString().orEmpty(),
                    audioStatus = if (extras?.optBoolean("phone_voice_stream") == true) "waiting" else "",
                    receivedMs = frame.getDouble("_received_ms"),
                    speechEmotion = frame.optString("speech_emotion").ifBlank { extras?.optString("speech_emotion").orEmpty().ifBlank { "neutral" } },
                    personaId = outputPersonaId)
                mutableMessages.update { (it + message).takeLast(200) }
                presentation.output(message)
            }
            "output.audio.start", "output.audio.chunk", "output.audio.end", "output.audio.error" -> {
                val id = frame.getString("id")
                if (messages.value.firstOrNull { it.id == id }?.audioStatus == "stopped") return
                try {
                    when (frame.getString("type")) {
                        "output.audio.start" -> {
                            require(frame.getString("format") == "pcm_s16le" && frame.getInt("sample_rate") == 24000 && frame.getInt("channels") == 1)
                            val message = messages.value.firstOrNull { it.id == id } ?: error("PHONE_AUDIO_TEXT_MISSING")
                            val clip = audio.start(id, message.text, frame.optString("reply_group"))
                            mutableMessages.update { list -> list.map { if (it.id == id) it.copy(audio = listOf(clip),
                                speechEmotion = frame.optString("speech_emotion").ifBlank { it.speechEmotion }) else it } }
                        }
                        "output.audio.chunk" -> {
                            val encoded = frame.getString("pcm_base64")
                            require(encoded.length <= 87384) { "PHONE_AUDIO_SIZE" }
                            audio.chunk(id, frame.getInt("seq"), Base64.decode(encoded, Base64.DEFAULT), frame.getDouble("_received_ms"))
                        }
                        "output.audio.end" -> audio.end(id, frame.getInt("chunks"), frame.getInt("bytes"))
                        "output.audio.error" -> audio.fail(id)
                    }
                } catch (_: Exception) {
                    audio.fail(id)
                    // An audio failure must not tear down the phone's task tunnel.
                    mutableMessages.update { list -> list.map { if (it.id == id) it.copy(audioStatus = "playback_failed") else it } }
                    send(JSONObject().put("type", "output.audio.ack").put("id", id).put("status", "playback_failed")
                        .put("device_ms", PhoneAudio.nowMs()).put("replay", false))
                }
            }
            "error" -> {
                val id = frame.optString("id")
                mutableMessages.update { list -> list.map { if (it.id == id) it.copy(delivery = "failed",
                    transcriptStatus = if (it.voiceInput && it.transcriptStatus != "recognized") "failed" else it.transcriptStatus,
                    transcriptReason = if (it.voiceInput && it.transcriptStatus != "recognized") frame.optString("code", "INPUT_NOT_ACCEPTED") else it.transcriptReason) else it } }
                mutableRegistrationReason.value = frame.optString("code", "HOST_ERROR").take(100)
                mutableState.value = "host_error"
            }
        }
    }

    private fun send(frame: JSONObject): Boolean = socket?.send(frame.toString()) == true
    fun answerAuthorization(id: String, reply: String): Boolean {
        val request = mutableAuthorizations.value.firstOrNull { it.id == id } ?: return false
        if (request.connectionId != app.phoneSession.flow.value.connectionId || reply !in request.choices) return false
        val frame = JSONObject(request.binding)
        frame.remove("question"); frame.remove("choices"); frame.remove("_received_ms")
        return send(frame.put("type", "authorization.reply").put("reply", reply))
    }
    fun publishUsage(frame: JSONObject): Boolean = send(frame.put("type", "device.usage").put("connection_id", app.phoneSession.flow.value.connectionId))
    internal fun publishNativeConfirmation(request: io.github.lejw0925.virtualscreen.systemtools.AlarmRequests.Request): Boolean {
        if (request.binding.isEmpty() || request.binding["connection_id"] != app.phoneSession.flow.value.connectionId) return false
        val frame = JSONObject(io.github.lejw0925.virtualscreen.systemtools.AlarmRequests.fact(request))
        val receipt = frame.getJSONObject("native_receipt")
        val language = io.github.lejw0925.virtualscreen.ui.uiLanguage(app.resources.configuration.locales[0])
        (io.github.lejw0925.virtualscreen.mcp.SystemToolModelMessages.message(language, receipt.optString("code"))
            ?: io.github.lejw0925.virtualscreen.systemtools.systemToolMessage(language, receipt.optString("code")))
            ?.let { receipt.put("message", it) }
        return send(frame.put("type", "device.native_confirmation"))
    }
    fun retryRegistration() {
        mutableRegistrationReason.value = ""
        if (socket != null && app.phoneSession.flow.value.connectionId != null &&
            PhoneTunnelStatus.from(app.tunnels.state()).connected) {
            mutableState.value = "registering"
            if (send(boundFrame("device.retry"))) return
        }
        // Rebind with a fresh proxy, waiting for the predecessor's cleanup in connect().
        connect(savedUrl, savedToken(), savedDevice, allowExecution = false)
    }
    fun clearConversation() {
        audio.stop()
        pendingAudio.values.forEach { it.file.delete() }
        pendingAudio.clear()
        mutableMessages.value = emptyList()
        mutableTaskResult.value = "" to ""
    }
    private fun boundFrame(type: String) = JSONObject().put("type", type)
        .put("connection_id", app.phoneSession.flow.value.connectionId ?: JSONObject.NULL)
    /** auto: she decides per reply; voice: user asks for voice with every reply; text_only: never voice. Set in app settings. */
    private fun replyPreference() = app.getSharedPreferences("avsr_product", 0).getString("reply_preference", "auto") ?: "auto"
    fun sendText(text: String, useMainScreen: Boolean = false): Boolean {
        if(text.isBlank() || text.length > 8000) return false
        val id = UUID.randomUUID().toString()
        app.mainScreen.prepareInput(id, useMainScreen)
        if(!send(JSONObject().put("type", "input.text").put("id", id).put("text", text)
            .put("phone_reply_preference", replyPreference()).put("phone_use_main_screen", useMainScreen)
            .put("released_ms", PhoneAudio.nowMs()))) {
            app.mainScreen.policy.discardInput(id)
            return false
        }
        mutableMessages.update { (it + PhoneMessage(id, text, false, delivery = "sent")).takeLast(200) }
        return true
    }
    fun acknowledge(id: String, presentation: String, detailRead: Boolean) {
        val frame = JSONObject().put("type", "output.ack").put("id", id).put("presentation", presentation).put("detail_read", detailRead)
        messages.value.firstOrNull { it.id == id }?.receivedMs?.let { frame.put("received_ms", it) }
        frame.put("presented_ms", PhoneAudio.nowMs())
        send(frame)
    }
    fun sendRecording(file: File, releasedMs: Double, useMainScreen: Boolean = false) {
        val id = UUID.randomUUID().toString()
        app.mainScreen.prepareInput(id, useMainScreen)
        pendingAudio[id] = PendingRecording(file, releasedMs)
        mutableMessages.update { (it + PhoneMessage(id, "", false, delivery = "pending", voiceInput = true,
            transcriptStatus = "uploading")).takeLast(200) }
        retryRecording(id)
    }
    fun retryRecording(id: String) {
        val pending = pendingAudio[id] ?: return
        val file = pending.file
        mutableMessages.update { list -> list.map { if (it.id == id && it.transcriptStatus != "recognized")
            it.copy(transcriptStatus = "uploading", transcriptReason = "") else it } }
        scope.launch {
            val sent = runCatching {
                check(file.length() in 12..(8 * 1024 * 1024))
                send(JSONObject().put("type", "input.audio").put("id", id).put("mime", "audio/mp4")
                    .put("audio_base64", Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)).put("released_ms", pending.releasedMs)
                    .put("phone_reply_preference", replyPreference())
                    .put("phone_use_main_screen", app.mainScreen.policy.state.value.pendingInputGrant == id))
            }.getOrDefault(false)
            mutableMessages.update { list -> list.map { if (it.id == id && it.delivery != "accepted")
                it.copy(delivery = if (sent) "sent" else "failed",
                    transcriptStatus = if (!sent && it.transcriptStatus != "recognized") "failed" else it.transcriptStatus,
                    transcriptReason = if (!sent && it.transcriptStatus != "recognized") "INPUT_NOT_SENT" else it.transcriptReason) else it } }
        }
    }
    fun discardRecording(id: String) {
        app.mainScreen.policy.discardInput(id)
        pendingAudio.remove(id)?.file?.delete()
        mutableMessages.update { list -> list.map { if (it.id == id) it.copy(delivery = "discarded",
            transcriptStatus = "failed", transcriptReason = "INPUT_DISCARDED") else it } }
    }
    fun pause(reason: String = "user_stop") {
        resumeOnReady = false
        app.phoneSession.pause(reason)
        send(boundFrame("device.state").put("execution_enabled", false).put("reason", reason))
    }
    private fun pauseTransport(reason: String) {
        val available = resumeOnReady || app.phoneSession.flow.value.executionEnabled
        app.phoneSession.pause(reason)
        resumeOnReady = available
        send(boundFrame("device.state").put("execution_enabled", false).put("reason", reason))
    }
    fun stopTask() {
        pause()
    }
    fun cancelTask(taskId: String) {
        val usage = app.mainScreen.policy.state.value
        val connectionId = app.phoneSession.flow.value.connectionId
        check(taskId.isNotBlank() && usage.live && usage.taskId == taskId &&
            connectionId != null && usage.connectionId == connectionId) { "PHONE_TASK_BINDING_REQUIRED" }
        resumeOnReady = false
        app.phoneSession.pause("user_cancelled")
        check(publishUsage(JSONObject(app.mainScreen.policy.receipt()))) { "CANCEL_RECEIPT_NOT_SENT" }
        send(boundFrame("device.state").put("execution_enabled", false).put("reason", "user_cancelled"))
    }
    fun resume() {
        app.phoneSession.resume()
        send(boundFrame("device.state").put("execution_enabled", true).put("reason", "user_resume"))
    }
    @Synchronized fun disconnect() {
        // A foreground-service teardown after local MCP failure must retain the cause for the login UI.
        val localFailure = if (mutableState.value == "starting_local_mcp" && !app.mcp.state().running)
            app.mcp.state().lastError?.let { safeConnectionError(it, "LOCAL_MCP_START_FAILED") }
            else registrationReason.value.takeIf { mutableState.value == "connection_failed" && it.isNotBlank() }
        connectionEpoch++
        mutableAuthorizations.value = emptyList()
        presentation.clearAuthorization()
        mutableState.value = "disconnected"
        audio.disconnected()
        resumeOnReady = false
        send(boundFrame("device.unregister"))
        val old = connectionJob
        // Retain the cancelled job so the next connection waits for its non-cancellable cleanup.
        old?.cancel()
        socket?.cancel(); socket = null
        tunnelJob?.cancel(); tunnelJob = null
        app.tunnels.stop()
        app.phoneSession.disconnect("user_disconnect")
        if (localFailure != null) {
            mutableRegistrationReason.value = localFailure
            mutableState.value = "connection_failed"
        } else mutableState.value = "disconnected"
    }
}

package io.github.lejw0925.virtualscreen.backend

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import io.github.lejw0925.virtualscreen.core.ScreenAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject
import java.util.UUID
import io.github.lejw0925.virtualscreen.phone.embedded.EmbeddedPrivilege

/** Embedded shell Binder transport. The legacy class/kind keep existing saved settings compatible. */
class ShizukuBackend(context: Context) : CommandBackend(context) {
    @Volatile override var liveChannel: LiveDisplayChannel? = null
        private set
    @Volatile private var remote: IBinder? = null
    private var reader: ImageReader? = null
    private var frames: HandlerThread? = null
    private var capture: VirtualScreenFrameCapture? = null
    private val bindingLock = Mutex()
    private val bindingEpoch = AtomicLong()
    override val kind = BackendKind.SHIZUKU

    override suspend fun probe(): BackendCapabilities {
        val ready = EmbeddedPrivilege.isReady()
        return BackendCapabilities(
            displayCreation = if (ready) CapabilityStatus.UNKNOWN else CapabilityStatus.REQUIRES_AUTHORIZATION,
            frameCapture = if (ready) CapabilityStatus.UNKNOWN else CapabilityStatus.REQUIRES_AUTHORIZATION,
            inputInjection = if (ready) CapabilityStatus.UNKNOWN else CapabilityStatus.REQUIRES_AUTHORIZATION,
            accessibilityNodes = if (ScreenAccessibilityService.current != null) CapabilityStatus.AVAILABLE else CapabilityStatus.REQUIRES_AUTHORIZATION,
            details = if (ready) "EMBEDDED_PRIVILEGE_READY" else EmbeddedPrivilege.state.value.error ?: "EMBEDDED_PAIRING_REQUIRED")
    }

    suspend fun requestPermission() { EmbeddedPrivilege.requireBinder() }

    private suspend fun bind() = bindingLock.withLock {
        if (remote?.isBinderAlive == true) return@withLock
        val service = EmbeddedPrivilege.requireBinder()
        val expected = bindingEpoch.incrementAndGet()
        service.linkToDeath({
            if (bindingEpoch.get() == expected && remote === service) { remote = null; active = null; observation = null }
        }, 0)
        remote = service
    }
    suspend fun enableAccessibility(rebind: Boolean = false) = withContext(Dispatchers.IO) {
        bind()
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ShizukuDisplayService.DESCRIPTOR)
            data.writeBoolean(rebind)
            check(remote!!.transact(ShizukuDisplayService.AUTHORIZE_ACCESSIBILITY, data, reply, 0)) { "EMBEDDED_SERVICE_TRANSACTION_FAILED" }
            reply.readException()
        } finally {
            data.recycle(); reply.recycle()
            if(active == null) withContext(NonCancellable) { disconnect() }
        }
    }

    override suspend fun connect(config: VirtualScreenConfig): JSONObject {
        bind()
        frames = HandlerThread("embedded-capture").apply { start() }
        reader = ImageReader.newInstance(config.width, config.height, PixelFormat.RGBA_8888, 3)
        capture = VirtualScreenFrameCapture(reader!!, Handler(frames!!.looper)) { active?.displayId?.let { context.getSystemService(DisplayManager::class.java).getDisplay(it) } }
        return invoke(ShizukuDisplayService.CREATE, configPacket(config), true).also { if(it.optBoolean("ok")) liveChannel=LiveDisplayChannel(it.getString("handleId"),remote!!) }
    }

    override suspend fun observe(handle: VirtualScreenHandle): Result<ScreenObservation> = withContext(Dispatchers.IO) {
        runCatching {
            check(active?.id == handle.id) { "STALE_VIRTUAL_SCREEN" }
            val status = transact(packet(handle, "validate")).checked()
            val frame = capture?.capture(2000) ?: error("DISPLAY_FRAME_PENDING")
            ScreenObservation(UUID.randomUUID().toString(), handle.id, 0, frame.width, frame.height, frame.rotation,
                BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.size), frameFingerprint = frame.fingerprint.toString(),deviceInteractive = if(status.has("interactive")) status.getBoolean("interactive") else null).also { observation = it }
        }
    }
    override suspend fun transact(request: JSONObject) = withContext(Dispatchers.IO) { invoke(ShizukuDisplayService.EXECUTE, request) }
    override fun cancelActive() { runCatching { invoke(ShizukuDisplayService.CANCEL, JSONObject()) } }
    private fun invoke(code: Int, request: JSONObject, surface: Boolean = false): JSONObject {
        val binder = remote ?: error("EMBEDDED_SERVICE_DIED")
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ShizukuDisplayService.DESCRIPTOR)
            if (code != ShizukuDisplayService.CANCEL) data.writeString(request.toString())
            if (surface) data.writeTypedObject(reader!!.surface, 0)
            check(binder.transact(code, data, reply, 0)) { "EMBEDDED_SERVICE_TRANSACTION_FAILED" }
            reply.readException()
            return if (code == ShizukuDisplayService.CANCEL) JSONObject().put("ok", true) else JSONObject(reply.readString() ?: error("INVALID_REPLY"))
        } finally { data.recycle(); reply.recycle() }
    }
    override suspend fun disconnect() = bindingLock.withLock {
        capture?.close(); capture = null; reader?.close(); reader = null; frames?.quitSafely(); frames = null
        liveChannel = null
        bindingEpoch.incrementAndGet()
        val service = remote
        remote = null
        if (service != null && service.isBinderAlive) withContext(NonCancellable + Dispatchers.IO) {
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(ShizukuDisplayService.DESCRIPTOR)
                check(service.transact(ShizukuDisplayService.RELEASE, data, reply, 0)) { "EMBEDDED_SERVICE_TRANSACTION_FAILED" }
                reply.readException()
            } finally { data.recycle(); reply.recycle() }
        }
    }

    companion object {
        /** Kept as a compatibility symbol for existing UI; external managers are never selected. */
        fun stellarAvailable(context: Context): Boolean = false
        fun isPrivilegeReady(context: Context): Boolean = EmbeddedPrivilege.isReady()
    }
}

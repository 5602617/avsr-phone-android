package io.github.lejw0925.virtualscreen.backend

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.view.Surface
import org.json.JSONObject

/** Shared display protocol, hosted by our embedded shell process. Legacy class name preserves IPC. */
class ShizukuDisplayService(private val ownerUid: Int = -1) : Binder() {
    @Volatile private var currentEngine: PrivilegedDisplayEngine? = null
    private val engine: PrivilegedDisplayEngine
        @Synchronized get() = currentEngine ?: PrivilegedDisplayEngine().also { currentEngine = it }
    @Synchronized fun shutdown() { currentEngine?.cancel(); currentEngine?.close(); currentEngine = null }
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == INTERFACE_TRANSACTION) { reply?.writeString(DESCRIPTOR); return true }
        if (code !in CREATE..RELEASE) return super.onTransact(code, data, reply, flags)
        data.enforceInterface(DESCRIPTOR)
        val caller = getCallingUid()
        check(ownerUid >= 10000 && caller == ownerUid) { "DISPLAY_OWNER_MISMATCH" }
        try {
            if (code == IDENTITY) {
                data.enforceNoDataAvail()
                reply?.writeNoException(); reply?.writeInt(android.os.Process.myUid()); reply?.writeInt(ownerUid)
                return true
            }
            if (code == RELEASE) {
                data.enforceNoDataAvail()
                val identity = clearCallingIdentity()
                try { shutdown(); reply?.writeNoException() } finally { restoreCallingIdentity(identity) }
                return true
            }
            if (code == AUTHORIZE_ACCESSIBILITY) {
                val rebind=data.readBoolean()
                data.enforceNoDataAvail()
                val identity = clearCallingIdentity()
                try {
                    AccessibilityAuthorization.enable(ownerUid/100000,rebind)
                    reply?.writeNoException()
                } finally { restoreCallingIdentity(identity) }
                return true
            }
            if(code >= LiveDisplayChannel.ATTACH) {
                val identity=clearCallingIdentity()
                try { engine.live(code,data,reply) } finally { restoreCallingIdentity(identity) }
                return true
            }
            if (code == CANCEL) {
                val identity = clearCallingIdentity()
                try { currentEngine?.cancel(); reply?.writeNoException() } finally { restoreCallingIdentity(identity) }
                return true
            }
            val request = JSONObject(data.readString() ?: error("INVALID_REQUEST"))
            val surface = if (code == CREATE) data.readTypedObject(Surface.CREATOR) else null
            data.enforceNoDataAvail()
            val identity = clearCallingIdentity()
            val result = try { if (code == CREATE) engine.create(request, surface) else engine.execute(request) }
                finally { restoreCallingIdentity(identity); surface?.release() }
            reply?.writeNoException(); reply?.writeString(result.toString())
        } catch (failure: Throwable) {
            if(code >= LiveDisplayChannel.ATTACH) { reply?.writeException(IllegalStateException(RootDisplayCommandMain.rootCause(failure).message ?: "DISPLAY_BRIDGE_UNAVAILABLE")); return true }
            reply?.writeNoException()
            reply?.writeString(JSONObject().put("ok", false).put("code", RootDisplayCommandMain.rootCause(failure).message ?: "EMBEDDED_OPERATION_FAILED").toString())
        }
        return true
    }
    companion object {
        const val DESCRIPTOR = "io.github.lejw0925.virtualscreen.DisplayService"
        const val CREATE = IBinder.FIRST_CALL_TRANSACTION
        const val EXECUTE = CREATE + 1
        const val CANCEL = CREATE + 2
        const val AUTHORIZE_ACCESSIBILITY = LiveDisplayChannel.SETTLE + 1
        const val IDENTITY = AUTHORIZE_ACCESSIBILITY + 1
        const val RELEASE = IDENTITY + 1
    }
}

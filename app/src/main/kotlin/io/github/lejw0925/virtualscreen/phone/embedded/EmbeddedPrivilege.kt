package io.github.lejw0925.virtualscreen.phone.embedded

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import io.github.lejw0925.virtualscreen.backend.ShizukuDisplayService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class EmbeddedPrivilegeState(val phase: String = "not_started", val error: String? = null, val uid: Int? = null)

/** App-process owner of its own shell service. No global Shizuku/Stellar binder is accepted. */
object EmbeddedPrivilege {
    private val mutable = MutableStateFlow(EmbeddedPrivilegeState())
    val state = mutable.asStateFlow()
    private val lifetime = Binder()
    @Volatile private var remote: IBinder? = null
    private var pending: Startup? = null
    class Startup internal constructor(val nonce: String, val result: CompletableDeferred<IBinder>)

    fun isReady(): Boolean = remote?.isBinderAlive == true && state.value.phase == "ready"
    fun requireBinder(): IBinder = remote?.takeIf { isReady() } ?: error(state.value.error ?: "EMBEDDED_PRIVILEGE_NOT_READY")

    @Synchronized fun begin(): Startup {
        pending?.result?.cancel()
        return Startup(UUID.randomUUID().toString(), CompletableDeferred()).also {
            pending = it
            mutable.value = EmbeddedPrivilegeState("starting")
        }
    }

    @Synchronized fun finish(start: Startup, failure: String? = null) {
        if (pending !== start) return
        pending = null
        start.result.cancel()
        if (failure != null && !isReady()) mutable.value = EmbeddedPrivilegeState("failed", failure)
    }

    /** UID and nonce are both checked because this endpoint transfers privileged authority. */
    @Synchronized internal fun accept(nonce: String?, service: IBinder): IBinder {
        check(Binder.getCallingUid() == 2000) { "ADB_SHELL_IDENTITY_MISMATCH" }
        val start = pending?.takeIf { it.nonce == nonce && it.result.isActive } ?: error("EMBEDDED_START_EXPIRED")
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ShizukuDisplayService.DESCRIPTOR)
            check(service.transact(ShizukuDisplayService.IDENTITY, data, reply, 0)) { "EMBEDDED_SERVICE_PROTOCOL_MISMATCH" }
            reply.readException()
            check(reply.readInt() == 2000 && reply.readInt() == Process.myUid()) { "EMBEDDED_SERVICE_IDENTITY_MISMATCH" }
        } finally { data.recycle(); reply.recycle() }
        service.linkToDeath({ died(service) }, 0)
        remote = service
        mutable.value = EmbeddedPrivilegeState("ready", uid = 2000)
        start.result.complete(service)
        return lifetime
    }

    @Synchronized private fun died(service: IBinder) {
        if (remote !== service) return
        remote = null
        mutable.value = EmbeddedPrivilegeState("failed", "EMBEDDED_SERVICE_DIED")
    }
}

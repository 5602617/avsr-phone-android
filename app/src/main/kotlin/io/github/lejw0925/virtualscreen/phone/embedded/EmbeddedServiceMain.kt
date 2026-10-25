package io.github.lejw0925.virtualscreen.phone.embedded

import android.content.AttributionSource
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.Process
import io.github.lejw0925.virtualscreen.backend.ShizukuDisplayService
import kotlin.system.exitProcess

/** Fixed app_process entry point, started by this APK's paired local ADB identity. */
object EmbeddedServiceMain {
    @JvmStatic fun main(args: Array<String>) {
        check(Process.myUid() == 2000) { "ADB_SHELL_IDENTITY_MISMATCH" }
        require(args.size == 3) { "EMBEDDED_START_ARGUMENTS_INVALID" }
        val ownerUid = args[0].toInt()
        require(ownerUid >= 10000) { "EMBEDDED_OWNER_INVALID" }
        val authority = args[1]
        val nonce = args[2]
        Looper.prepareMainLooper()
        org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("L")
        val service = ShizukuDisplayService(ownerUid)
        val token: IBinder? = null
        val manager = Class.forName("android.app.ActivityManager").getDeclaredMethod("getService").invoke(null)
        val type = Class.forName("android.app.IActivityManager")
        try {
            val holder = requireNotNull(type.getMethod("getContentProviderExternal", String::class.java,
                Int::class.javaPrimitiveType, IBinder::class.java, String::class.java)
                .invoke(manager, authority, ownerUid / 100000, token, "AVSR:embedded"))
            val provider = holder.javaClass.getField("provider").get(holder)
            val response = Class.forName("android.content.IContentProvider").getMethod("call", AttributionSource::class.java,
                String::class.java, String::class.java, String::class.java, Bundle::class.java)
                .invoke(provider, AttributionSource.Builder(2000).setPackageName("com.android.shell").build(), authority,
                    "register", null, Bundle().apply { putString("nonce", nonce); putBinder("service", service) }) as Bundle
            val owner = requireNotNull(response.getBinder("owner"))
            owner.linkToDeath({ service.shutdown(); exitProcess(0) }, 0)
            check(owner.isBinderAlive) { "EMBEDDED_OWNER_GONE" }
        } finally {
            runCatching { type.getMethod("removeContentProviderExternal", String::class.java, IBinder::class.java)
                .invoke(manager, authority, token) }
        }
        try { Looper.loop() } finally { service.shutdown() }
    }
}

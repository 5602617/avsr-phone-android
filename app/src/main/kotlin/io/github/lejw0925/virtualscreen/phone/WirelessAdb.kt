package io.github.lejw0925.virtualscreen.phone

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.provider.Settings
import io.github.lejw0925.virtualscreen.VirtualScreenApplication
import io.github.lejw0925.virtualscreen.VirtualScreenForegroundService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.github.lejw0925.virtualscreen.phone.embedded.EmbeddedPrivilege
import io.github.lejw0925.virtualscreen.phone.embedded.EmbeddedServiceMain
import io.github.lejw0925.virtualscreen.phone.pairing.WirelessPairingService
import java.net.InetAddress
import java.net.NetworkInterface

/** Native Android pairing, with the DSHA secure-settings boot strategy.
 * This transport exposes no shell tool. Only local identity, own permission and embedded startup commands exist. */
class WirelessAdb(private val app: VirtualScreenApplication) {
    private val prefs = app.getSharedPreferences("wireless_adb", 0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val mutable = MutableStateFlow("idle")
    val state = mutable.asStateFlow()
    private val mutableDiagnostic = MutableStateFlow<String?>(null)
    val lastDiagnostic = mutableDiagnostic.asStateFlow()
    private val mutableEnabled = MutableStateFlow(prefs.getBoolean("boot_enabled", false))
    val enabledState = mutableEnabled.asStateFlow()
    val enabled get() = mutableEnabled.value
    val paired get() = prefs.getBoolean("paired", false)
    val privilegeState = EmbeddedPrivilege.state
    fun reportBootFailure(failure: Exception) { mutable.value = failureState(failure) }
    private var restoreJob: Job? = null
    private var manager: NativeAdbIdentity? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun startNotificationPairing(): Result<Unit> = runCatching { WirelessPairingService.start(app) }
        .onFailure { mutable.value = "manual_action_required:" + safeCode(it as? Exception ?: IllegalStateException("PAIRING_NOTIFICATION_START_FAILED")) }
    fun stopNotificationPairing() = WirelessPairingService.stop(app)
    internal fun reportPairingNotificationState(value: String) { mutable.value = value }

    init {
        scope.launch {
            EmbeddedPrivilege.state.collect { status ->
                if (status.phase == "failed") mutable.value = "manual_action_required:${status.error}"
            }
        }
    }

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean("boot_enabled", value).apply()
        mutableEnabled.value = value
        if (value) {
            watchNetwork()
        } else {
            restoreJob?.cancel()
            callback?.let { runCatching { app.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
            callback = null
            mutable.value = "automatic_recovery_off"
        }
    }

    private fun client(): NativeAdbIdentity = manager ?: NativeAdbIdentity(app).also { manager = it }
    private fun secureGranted() = app.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
    private fun localHost(host: String) {
        val address = InetAddress.getByName(host)
        require(address.isLoopbackAddress || NetworkInterface.getNetworkInterfaces().toList().any { address in it.inetAddresses.toList() }) { "PAIR_THIS_PHONE_ONLY" }
    }

    fun pair(host: String, port: Int, code: String, connectPort: Int?) = scope.launch {
        mutex.withLock {
            var phase = "PAIRING_VALIDATE"
            mutableDiagnostic.value = null
            try {
                localHost(host)
                require(port in 1..65535 && code.matches(Regex("[0-9]{6}"))) { "PAIRING_PARAMETERS_INVALID" }
                mutable.value = "pairing"
                phase = "IDENTITY_LOAD"
                val adb = client()
                phase = "PAIRING_PROTOCOL"
                check(runInterruptible { adb.pair(host, port, code) }) { "PAIRING_FAILED" }
                prefs.edit().putBoolean("paired", true).apply()
                mutable.value = "paired_connecting"
                connectAndStart(host, connectPort, grantPermission = true) { phase = it }
                // Pairing completes the one-app setup, including recover-after-reboot. The user can turn it off.
                setEnabled(true)
            } catch (failure: TimeoutCancellationException) { recordFailure(phase, failure) }
              catch (cancelled: CancellationException) { throw cancelled }
              catch (failure: Exception) { recordFailure(phase, failure) }
        }
    }

    fun restore() = recover(manual = false)
    fun startPaired() = recover(manual = true)

    private fun recover(manual: Boolean) {
        if ((!manual && !enabled) || restoreJob?.isActive == true) return
        if (!manual && EmbeddedPrivilege.isReady()) return
        if (!paired) { mutable.value = "pairing_required"; return }
        restoreJob = scope.launch {
          app.runtimeWork {
            mutex.withLock {
                var phase = "WIRELESS_ENABLE"
                mutableDiagnostic.value = null
                try {
                    mutable.value = "restoring"
                    if (secureGranted()) {
                        check(Settings.Global.putInt(app.contentResolver, "adb_wifi_enabled", 1)) { "WIRELESS_DEBUGGING_NOT_ENABLED" }
                    } else if (Settings.Global.getInt(app.contentResolver, "adb_wifi_enabled", 0) != 1) {
                        mutable.value = "enable_wireless_debugging_manually"
                        return@withLock
                    }
                    // A successful pair can precede a failed first startup. Explicit recovery
                    // may finish this app's setup; background recovery never expands grants.
                    connectAndStart(null, null, grantPermission = manual) { phase = it }
                    phase = "HOST_RECONNECT"
                    if (app.phoneConnection.savedToken().isNotBlank() && app.phoneConnection.state.value !in setOf("ready", "connecting", "registering", "tunnel_connecting")) app.phoneConnection.connect(
                        app.phoneConnection.savedUrl, app.phoneConnection.savedToken(), app.phoneConnection.savedDevice, allowExecution = true)
                } catch (failure: TimeoutCancellationException) { recordFailure(phase, failure) }
                  catch (cancelled: CancellationException) { throw cancelled }
                  catch (failure: Exception) { recordFailure(phase, failure) }
            }
          }
        }
    }

    fun watchNetwork() {
        if (!enabled || callback != null) return
        val listener = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { restore() }
        }
        callback = listener
        app.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(listener)
    }

    private suspend fun connectAndStart(host: String?, port: Int?, grantPermission: Boolean, phase: (String) -> Unit) {
        if (EmbeddedPrivilege.isReady()) { mutable.value = "embedded_ready"; return }
        phase("IDENTITY_LOAD")
        val adb = client()
        try {
            phase(if (host != null && port != null) "ADB_CONNECT" else "ADB_DISCOVERY_CONNECT")
            withTimeout(25_000) {
                runInterruptible {
                    if (host != null && port != null) {
                        localHost(host)
                        check(adb.connect(host, port)) { "ADB_CONNECT_FAILED" }
                    } else check(adb.connectTls(app, 15_000)) { "ADB_DISCOVERY_FAILED" }
                    // Library discovery is local-device mDNS; independently enforce same-device peer.
                    localHost(adb.hostAddress)
                }
            }
            phase("ADB_SHELL_IDENTITY")
            check(shell("id -u").trim() == "2000") { "ADB_SHELL_IDENTITY_MISMATCH" }
            if (grantPermission && !secureGranted()) {
                phase("SECURE_SETTINGS_GRANT")
                shell("pm grant ${app.packageName} android.permission.WRITE_SECURE_SETTINGS")
            }
            val startup = EmbeddedPrivilege.begin()
            var failureCode: String? = "EMBEDDED_START_FAILED"
            try {
                mutable.value = "starting_embedded_service"
                phase("EMBEDDED_LAUNCH")
                val command = "CLASSPATH=${quote(app.applicationInfo.sourceDir)} /system/bin/nohup /system/bin/app_process /system/bin " +
                    "--nice-name=${quote(app.packageName + ":privileged")} ${EmbeddedServiceMain::class.java.name} " +
                    "${android.os.Process.myUid()} ${quote(app.packageName + ".embedded-bridge")} ${quote(startup.nonce)} " +
                    ">/dev/null 2>&1 </dev/null &"
                shell(command)
                phase("EMBEDDED_BINDER_WAIT")
                check(withTimeoutOrNull(15_000) { startup.result.await() } != null) { "EMBEDDED_START_TIMEOUT" }
                check(EmbeddedPrivilege.isReady()) { "EMBEDDED_SERVICE_DIED" }
                failureCode = null
            } catch (failure: Exception) {
                failureCode = safeCode(failure)
                throw failure
            } finally { EmbeddedPrivilege.finish(startup, failureCode) }
            mutable.value = if (secureGranted()) "embedded_ready" else "embedded_ready_manual_wireless_after_reboot"
        } finally { runCatching { adb.disconnect() } }
    }

    private fun safeCode(failure: Exception): String = WirelessAdbDiagnostics.pairingError(failure)
        ?: failure.message?.takeIf { it.matches(Regex("[A-Z0-9_]+")) }
        ?: when (failure) {
            is java.net.ConnectException -> "ADB_CONNECTION_REFUSED"
            is java.net.SocketTimeoutException, is TimeoutCancellationException -> "ADB_CONNECTION_TIMEOUT"
            is SecurityException -> "WIRELESS_DEBUGGING_PERMISSION_DENIED"
            else -> "WIRELESS_ADB_${failure.javaClass.simpleName.uppercase()}"
            }
    private fun failureState(failure: Exception) = "manual_action_required:${safeCode(failure)}"

    private fun recordFailure(phase: String, failure: Exception) {
        val diagnostic = WirelessAdbDiagnostics.describe(phase, failure)
        mutableDiagnostic.value = diagnostic
        // Do not pass the Throwable to Log: its message/cause may contain sensitive transport data.
        android.util.Log.w("AVSRWirelessAdb", diagnostic)
        mutable.value = failureState(failure)
    }

    private suspend fun shell(command: String): String = withTimeout(20_000) {
        runInterruptible {
            client().openStream("shell,v2,raw:$command").use { stream ->
                // libadb's AdbInputStream checks isClosed before draining buffered bytes.
                // A peer CLSE can arrive after read() dequeues the exit packet but before
                // the parser consumes it. AdbStream.read itself drains that buffer first.
                val input = object : java.io.InputStream() {
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                        if (length == 0) 0 else stream.read(bytes, offset, length)
                    override fun read(): Int {
                        val byte = ByteArray(1)
                        return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 0xff
                    }
                }
                NativeAdbShell.read(input).successfulOutput()
            }
        }
    }
    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
}

class WirelessAdbBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val app = context.applicationContext as VirtualScreenApplication
        if (!app.wirelessAdb.enabled) return
        // Runs after unlock. ROM denial remains visible; no root or locked-storage fallback.
        runCatching { context.startForegroundService(Intent(context, VirtualScreenForegroundService::class.java)
            .setAction("io.github.lejw0925.virtualscreen.RESTORE_WIRELESS")) }.onFailure {
                app.wirelessAdb.reportBootFailure(it as? Exception ?: IllegalStateException("BOOT_START_FAILED"))
            }
    }
}

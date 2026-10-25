package io.github.lejw0925.virtualscreen.phone.pairing

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.net.InetAddress
import java.net.NetworkInterface

internal data class PairingEndpoint(val name: String, val host: String, val port: Int)

/** Discover pairing endpoints only on this phone. No port probing or remote-device pairing. */
internal class LocalPairingDiscovery(
    context: Context,
    private val changed: (PairingEndpoint?) -> Unit,
    private val failed: (String) -> Unit,
) : AutoCloseable {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val pending = ArrayDeque<NsdServiceInfo>()
    private val present = mutableSetOf<String>()
    private var resolving = false
    private var listener: NsdManager.DiscoveryListener? = null
    private var endpoint: PairingEndpoint? = null
    private var generation = 0

    fun start() {
        if (listener != null) return
        val expected = ++generation
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, code: Int) { handler.post {
                if (generation == expected) { close(); failed("PAIRING_DISCOVERY_FAILED_$code") }
            } }
            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) { handler.post {
                if (generation != expected || !present.add(info.serviceName)) return@post
                pending.add(info); resolveNext(expected)
            } }
            override fun onServiceLost(info: NsdServiceInfo) { handler.post {
                if (generation != expected) return@post
                present.remove(info.serviceName)
                pending.removeAll { it.serviceName == info.serviceName }
                if (endpoint?.name == info.serviceName) { endpoint = null; changed(null) }
            } }
        }
        listener = discovery
        try { nsd.discoverServices("_adb-tls-pairing._tcp.", NsdManager.PROTOCOL_DNS_SD, discovery) }
        catch (_: Exception) { close(); failed("PAIRING_DISCOVERY_UNAVAILABLE") }
    }

    @Suppress("DEPRECATION")
    private fun resolveNext(expected: Int) {
        if (resolving || generation != expected || pending.isEmpty()) return
        val info = pending.removeFirst()
        resolving = true
        val callback = object : NsdManager.ResolveListener {
            override fun onResolveFailed(service: NsdServiceInfo, code: Int) { handler.post {
                if (generation != expected) return@post
                resolving = false
                present.remove(service.serviceName)
                // Other devices may announce the same type; a failure must not erase a valid local endpoint.
                if (endpoint == null) failed("PAIRING_RESOLVE_FAILED_$code")
                resolveNext(expected)
            } }
            override fun onServiceResolved(service: NsdServiceInfo) { handler.post {
                if (generation != expected) return@post
                resolving = false
                if (service.serviceName in present && service.port in 1..65535) {
                    val addresses = if (Build.VERSION.SDK_INT >= 34) service.hostAddresses else listOfNotNull(service.host)
                    addresses.firstOrNull(::isLocal)?.hostAddress?.let { host ->
                        endpoint = PairingEndpoint(service.serviceName, host, service.port)
                        changed(endpoint)
                    }
                }
                resolveNext(expected)
            } }
        }
        try { nsd.resolveService(info, callback) }
        catch (_: Exception) {
            resolving = false
            present.remove(info.serviceName)
            failed("PAIRING_RESOLVE_UNAVAILABLE")
            resolveNext(expected)
        }
    }

    private fun isLocal(address: InetAddress): Boolean = address.isLoopbackAddress || runCatching {
        NetworkInterface.getNetworkInterfaces().toList().any { address in it.inetAddresses.toList() }
    }.getOrDefault(false)

    override fun close() {
        generation++
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null; endpoint = null; resolving = false
        pending.clear(); present.clear()
    }
}

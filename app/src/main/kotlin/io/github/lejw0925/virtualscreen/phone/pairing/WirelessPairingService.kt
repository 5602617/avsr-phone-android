package io.github.lejw0925.virtualscreen.phone.pairing

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import io.github.lejw0925.virtualscreen.R
import io.github.lejw0925.virtualscreen.VirtualScreenApplication
import io.github.lejw0925.virtualscreen.ui.localizedError
import io.github.lejw0925.virtualscreen.ui.systemLanguage
import kotlinx.coroutines.*

/** The user replies from the notification shade while the system pairing dialog stays in front. */
class WirelessPairingService : Service() {
    private val app get() = application as VirtualScreenApplication
    private val manager get() = getSystemService(NotificationManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var discovery: LocalPairingDiscovery? = null
    private var discoveryGeneration = 0
    private var endpoint: PairingEndpoint? = null
    private var pairingJob: Job? = null
    private var busy = false
    private val l get() = systemLanguage(this)

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
        startForeground(ID, notification(searchingText()))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopDiscovery(); stopSelf(); return START_NOT_STICKY }
            ACTION_REPLY -> {
                // Keep the code in memory only, and remove RemoteInput's ClipData as soon as it is read.
                val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(INPUT_CODE)?.toString().orEmpty().trim()
                intent.clipData = null
                val host = intent.getStringExtra(EXTRA_HOST).orEmpty()
                val port = intent.getIntExtra(EXTRA_PORT, 0)
                if (!busy) pair(host, port, code)
            }
            ACTION_START -> if (!busy) discover()
        }
        // System restarts must not replay a pairing code or an old endpoint.
        return START_NOT_STICKY
    }

    private fun discover() {
        stopDiscovery()
        val expected = discoveryGeneration
        endpoint = null
        app.wirelessAdb.reportPairingNotificationState("pairing_notification_searching")
        manager.notify(ID, notification(searchingText()))
        val session = LocalPairingDiscovery(this, changed = { found ->
            if (!busy && discoveryGeneration == expected && endpoint == null) {
                endpoint = found
                // Freeze the input action before publishing it. NSD loss/re-resolution must not
                // replace RemoteInput while the user is typing; a new search is explicit.
                if (found != null) stopDiscovery()
                app.wirelessAdb.reportPairingNotificationState(if (found == null) "pairing_notification_searching" else "pairing_notification_code_required")
                manager.notify(ID, notification(if (found == null) searchingText() else l.text(
                    "保持系统配对页打开，点下方输入配对码。", "Keep the system pairing dialog open and enter its code below.", "保持系統配對頁開啟，點下方輸入配對碼。"), found))
            }
        }, failed = { error ->
            if (!busy && discoveryGeneration == expected && endpoint == null) {
                app.wirelessAdb.reportPairingNotificationState("manual_action_required:$error")
                manager.notify(ID, notification(l.text("未发现本机配对服务，请保持系统配对页打开后重试。", "Could not find this phone's pairing service. Keep the system pairing dialog open and retry.", "未找到本機配對服務，請保持系統配對頁開啟後重試。") + " ($error)", endpoint))
            }
        })
        // Assign before start, including its synchronous failure callback.
        discovery = session
        session.start()
    }

    private fun stopDiscovery() {
        discoveryGeneration++
        discovery?.close()
        discovery = null
    }

    private fun pair(host: String, port: Int, code: String) {
        if (!code.matches(Regex("[0-9]{6}")) || port !in 1..65535 || host.isBlank()) {
            app.wirelessAdb.reportPairingNotificationState("manual_action_required:PAIRING_PARAMETERS_INVALID")
            manager.notify(ID, notification(localizedError(l, "PAIRING_PARAMETERS_INVALID"), endpoint))
            return
        }
        busy = true
        stopDiscovery()
        endpoint = null
        manager.notify(ID, notification(l.text("正在配对并启动内置服务…", "Pairing and starting the built-in service…", "正在配對並啟動內建服務…")))
        // This is the same checked local-device path as the in-app form. No shell or external manager is used for pairing.
        val work = app.wirelessAdb.pair(host, port, code, null)
        pairingJob = work
        scope.launch {
            work.join()
            pairingJob = null
            busy = false
            val result = app.wirelessAdb.state.value
            val ready = result.startsWith("embedded_ready")
            val text = if (ready) {
                if (result == "embedded_ready_manual_wireless_after_reboot") l.text(
                    "内置服务已就绪；重启后需手动开启无线调试。可返回应用继续。", "Built-in service ready. Enable wireless debugging manually after reboot. Return to the app to continue.", "內建服務已就緒；重新啟動後需手動開啟無線偵錯。可返回應用程式繼續。")
                else l.text("配对完成，内置服务已就绪。可返回应用继续。", "Paired; the built-in service is ready. Return to the app to continue.", "配對完成，內建服務已就緒。可返回應用程式繼續。")
            } else localizedError(l, result.removePrefix("manual_action_required:")) + " (" + result.removePrefix("manual_action_required:") + ")"
            // Detach a non-foreground result notification. The runtime service remains independently owned.
            stopForeground(STOP_FOREGROUND_DETACH)
            manager.notify(ID, notification(text, result = true))
            stopSelf()
        }
    }

    private fun searchingText() = l.text(
        "在开发者选项打开“使用配对码配对设备”，保持该页打开。发现后可直接在此通知输入配对码。",
        "Open Pair device with pairing code in developer settings and keep it open. Enter the code in this notification when discovered.",
        "在開發人員選項開啟「使用配對碼配對裝置」，保持該頁開啟。找到後可直接在此通知輸入配對碼。")

    private fun notification(text: String, target: PairingEndpoint? = null, result: Boolean = false): Notification {
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_status_approval)
            .setContentTitle(l.text("本机无线配对", "Pair this phone", "本機無線配對"))
            .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(!result).setAutoCancel(result).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        if (target != null) {
            val reply = Intent(this, WirelessPairingService::class.java).setAction(ACTION_REPLY)
                .setData(Uri.parse("avsr-pairing://local/${target.port}"))
                .putExtra(EXTRA_HOST, target.host).putExtra(EXTRA_PORT, target.port)
            val pending = PendingIntent.getForegroundService(this, REPLY_REQUEST, reply, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            val input = RemoteInput.Builder(INPUT_CODE).setLabel(l.text("六位配对码", "Six-digit pairing code", "六位配對碼"))
                .setAllowFreeFormInput(true).build()
            builder.addAction(Notification.Action.Builder(null, l.text("输入配对码", "Enter pairing code", "輸入配對碼"), pending)
                .addRemoteInput(input).setAllowGeneratedReplies(false).setAuthenticationRequired(true).build())
        }
        if (!busy) {
            val retry = PendingIntent.getForegroundService(this, START_REQUEST, startIntent(this), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(Notification.Action.Builder(null, l.text("重新发现", "Discover again", "重新尋找"), retry).build())
        }
        if (!result) {
            val stop = PendingIntent.getService(this, STOP_REQUEST, Intent(this, WirelessPairingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(Notification.Action.Builder(null, l.text("取消", "Cancel", "取消"), stop).build())
        }
        return builder.build()
    }

    override fun onDestroy() {
        stopDiscovery()
        val pairingCancelled = pairingJob?.isActive == true
        pairingJob?.cancel(); pairingJob = null
        scope.cancel()
        if (pairingCancelled || app.wirelessAdb.state.value.startsWith("pairing_notification_")) app.wirelessAdb.reportPairingNotificationState("pairing_notification_stopped")
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL = "avsr-wireless-pairing"
        private const val ID = 28901
        private const val START_REQUEST = 28902
        private const val REPLY_REQUEST = 28903
        private const val STOP_REQUEST = 28904
        private const val ACTION_START = "avsr.phone.pairing.START"
        private const val ACTION_REPLY = "avsr.phone.pairing.REPLY"
        private const val ACTION_STOP = "avsr.phone.pairing.STOP"
        private const val INPUT_CODE = "pairing_code"
        private const val EXTRA_HOST = "local_host"
        private const val EXTRA_PORT = "pairing_port"
        private fun startIntent(context: Context) = Intent(context, WirelessPairingService::class.java).setAction(ACTION_START)
        private fun createChannel(context: Context) {
            val l = systemLanguage(context)
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(
                CHANNEL, l.text("无线配对", "Wireless pairing", "無線配對"), NotificationManager.IMPORTANCE_HIGH
            ).apply { setSound(null, null); setShowBadge(false) })
        }
        fun start(context: Context) {
            check(context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) { "PAIRING_NOTIFICATION_PERMISSION_REQUIRED" }
            val manager = context.getSystemService(NotificationManager::class.java)
            check(manager.areNotificationsEnabled()) { "PAIRING_NOTIFICATION_PERMISSION_REQUIRED" }
            createChannel(context)
            check(manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE) { "PAIRING_NOTIFICATION_CHANNEL_DISABLED" }
            context.startForegroundService(startIntent(context))
        }
        fun stop(context: Context) { context.stopService(Intent(context, WirelessPairingService::class.java)) }
    }
}

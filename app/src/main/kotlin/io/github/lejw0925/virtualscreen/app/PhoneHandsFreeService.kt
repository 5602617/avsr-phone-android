package io.github.lejw0925.virtualscreen.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import io.github.lejw0925.virtualscreen.R
import io.github.lejw0925.virtualscreen.VirtualScreenApplication
import io.github.lejw0925.virtualscreen.ui.PhoneProductActivity

/** Holds the foreground microphone session; the controller owns the sole recorder. */
class PhoneHandsFreeService : Service() {
    private val app get() = application as VirtualScreenApplication
    private var session = 0L
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requestedSession = intent?.getLongExtra("microphone_session", 0L) ?: 0L
        if (!app.avsr.handsFreeServiceRequested(requestedSession)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        session = requestedSession
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(
                "phone-listening", getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(this, 12121,
                Intent(this, PhoneProductActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val notification = Notification.Builder(this, "phone-listening")
                .setSmallIcon(R.drawable.ic_status_approval).setContentTitle(getString(R.string.app_name))
                .setContentIntent(open).setOngoing(true).build()
            startForeground(12121, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            app.avsr.handsFreeServiceChanged(session, true)
        } catch (_: Exception) {
            app.avsr.handsFreeServiceFailed(session)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        app.avsr.handsFreeServiceChanged(session, false)
        super.onDestroy()
    }
}

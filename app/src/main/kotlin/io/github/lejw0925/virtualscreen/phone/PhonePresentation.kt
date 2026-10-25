package io.github.lejw0925.virtualscreen.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import io.github.lejw0925.virtualscreen.R
import io.github.lejw0925.virtualscreen.VirtualScreenApplication
import io.github.lejw0925.virtualscreen.ui.PhoneProductActivity

/** Never launches an activity on delivery. Receipts describe the surface actually presented. */
class PhonePresentation(private val app: VirtualScreenApplication, private val client: HostSocketClient) {
    private val main = Handler(Looper.getMainLooper())
    private val windows = app.getSystemService(WindowManager::class.java)
    private val notifications = app.getSystemService(NotificationManager::class.java)
    private var overlay: TextView? = null
    private var status = ""
    private var lastMessage = ""
    @Volatile var chatVisible = false
    init {
        notifications.createNotificationChannel(NotificationChannel("phone-replies", "Persona", NotificationManager.IMPORTANCE_DEFAULT))
    }
    fun authorization(request: PhoneAuthorizationRequest) { main.post {
        val open = PendingIntent.getActivity(app, 1201, Intent(app, PhoneProductActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(app, "phone-replies").setSmallIcon(R.drawable.ic_status_approval)
            .setContentTitle(app.getString(R.string.app_name)).setContentText(request.question)
            .setStyle(Notification.BigTextStyle().bigText(request.question)).setContentIntent(open)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setAutoCancel(true).build()
        runCatching { notifications.notify(1201, notification) }
    } }
    fun clearAuthorization() { main.post { notifications.cancel(1201) } }
    fun status(text: String) { main.post { status = text; render() } }
    fun setOverlay(enabled: Boolean) { main.post { app.avsr.setOverlay(enabled) } }
    private fun render() { overlay?.text = "$status\n$lastMessage".trim() }
    fun output(message: PhoneMessage) { main.post {
        lastMessage = message.text.take(140)
        render()
        if(chatVisible) return@post // Compose acknowledges after the next rendered frame.
        if(app.avsr.state.value.overlayEnabled) {
            // The Compose panel acknowledges only after rendering the actual assistant bubble.
        } else if(notifications.areNotificationsEnabled() && notifications.getNotificationChannel("phone-replies")?.importance != NotificationManager.IMPORTANCE_NONE) {
            val open = PendingIntent.getActivity(app, 0, Intent(app, PhoneProductActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = Notification.Builder(app, "phone-replies").setSmallIcon(R.drawable.ic_status_approval)
                .setContentTitle("Persona").setContentText(message.text.take(160))
                .setStyle(Notification.BigTextStyle().bigText(message.text.take(8000)))
                .setContentIntent(open).setVisibility(Notification.VISIBILITY_PRIVATE).setAutoCancel(true).build()
            runCatching { notifications.notify(message.id.hashCode(), notification) }
                .onSuccess { client.acknowledge(message.id, "notification", false) }
        }
    } }
}

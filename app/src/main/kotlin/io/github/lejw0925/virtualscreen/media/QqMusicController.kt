package io.github.lejw0925.virtualscreen.media

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import io.github.lejw0925.virtualscreen.core.OperationEnvelope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import kotlin.math.abs

class QqMusicNotificationListener : NotificationListenerService()

class QqMusicController(private val context: Context) {
    companion object { const val PACKAGE = "com.tencent.qqmusic" }
    private val listener = ComponentName(context, QqMusicNotificationListener::class.java)
    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private val ids = mutableMapOf<MediaSession.Token, String>()
    private val commands = Mutex()

    fun hasPermission(): Boolean = context.getSystemService(NotificationManager::class.java)
        .isNotificationListenerAccessGranted(listener)

    @Synchronized private fun controllers(): List<Pair<String, MediaController>> {
        check(hasPermission()) { "NOTIFICATION_ACCESS_REQUIRED" }
        val current = sessions.getActiveSessions(listener).filter { it.packageName == PACKAGE }
        ids.keys.retainAll(current.map { it.sessionToken }.toSet())
        return current.map { controller ->
            ids.getOrPut(controller.sessionToken) { UUID.randomUUID().toString() } to controller
        }
    }

    fun status(): OperationEnvelope {
        if (!hasPermission()) return OperationEnvelope.failure("NOTIFICATION_ACCESS_REQUIRED", null,
            "package" to PACKAGE, "notification_access" to false, "sessions" to emptyList<Any>())
        val current = controllers()
        return OperationEnvelope.success("package" to PACKAGE, "notification_access" to true,
            "available" to current.isNotEmpty(), "sessions" to current.map { (id, c) -> snapshot(id, c) })
    }

    private fun position(state: PlaybackState): Long {
        if (state.position < 0) return state.position
        val elapsed = if (state.state == PlaybackState.STATE_PLAYING && state.lastPositionUpdateTime > 0)
            ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime).coerceAtLeast(0) * state.playbackSpeed).toLong() else 0L
        return (state.position + elapsed).coerceAtLeast(0)
    }

    private fun snapshot(id: String, controller: MediaController): Map<String, Any?> {
        val state = controller.playbackState
        val metadata = controller.metadata
        val actions = state?.actions ?: 0L
        return mapOf("session_id" to id, "package" to controller.packageName,
            "observed_at_ms" to System.currentTimeMillis(), "playback_state" to state?.state,
            "playing" to state?.let { it.state == PlaybackState.STATE_PLAYING },
            "position_ms" to state?.let(::position), "state_updated_elapsed_ms" to state?.lastPositionUpdateTime,
            "media_id" to metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
            "title" to metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
            "artist" to metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST),
            "duration_ms" to metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION),
            "supported_commands" to listOf("play" to PlaybackState.ACTION_PLAY,
                "pause" to PlaybackState.ACTION_PAUSE, "seek" to PlaybackState.ACTION_SEEK_TO)
                .filter { (_, bit) -> actions and bit != 0L }.map { it.first })
    }

    suspend fun control(sessionId: String, command: String, positionMs: Long? = null): OperationEnvelope = commands.withLock {
        require(command in setOf("play", "pause", "seek")) { "INVALID_MEDIA_COMMAND" }
        require((command == "seek") == (positionMs != null)) { "INVALID_MEDIA_POSITION" }
        val controller = controllers().singleOrNull { it.first == sessionId }?.second
            ?: return@withLock OperationEnvelope.failure("MEDIA_SESSION_NOT_FOUND")
        val state = controller.playbackState ?: return@withLock OperationEnvelope.failure("MEDIA_STATE_UNAVAILABLE")
        val action = when (command) { "play" -> PlaybackState.ACTION_PLAY; "pause" -> PlaybackState.ACTION_PAUSE; else -> PlaybackState.ACTION_SEEK_TO }
        if (state.actions and action == 0L) return@withLock OperationEnvelope.failure("MEDIA_ACTION_UNSUPPORTED")
        val duration = controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        if (positionMs != null && (positionMs < 0 || (duration > 0 && positionMs > duration)))
            return@withLock OperationEnvelope.failure("MEDIA_POSITION_OUT_OF_RANGE")
        val before = snapshot(sessionId, controller)
        val sentAt = SystemClock.elapsedRealtime()
        when (command) {
            "play" -> controller.transportControls.play()
            "pause" -> controller.transportControls.pause()
            "seek" -> controller.transportControls.seekTo(positionMs!!)
        }
        var after: Map<String, Any?>? = null
        repeat(30) {
            delay(100)
            val fresh = controllers().singleOrNull { it.first == sessionId }?.second
                ?: return@withLock OperationEnvelope.failure("MEDIA_SESSION_LOST", null,
                    "execution_status" to "accepted", "effect_status" to "unconfirmed", "before" to before)
            after = snapshot(sessionId, fresh)
            val playback = fresh.playbackState
            val identified = !(before["media_id"] as? String).isNullOrBlank() ||
                !(before["title"] as? String).isNullOrBlank()
            val sameTrack = identified && before["media_id"] == after["media_id"] &&
                before["title"] == after["title"] && before["artist"] == after["artist"]
            val confirmed = when (command) {
                "play" -> playback?.state == PlaybackState.STATE_PLAYING
                "pause" -> playback?.state == PlaybackState.STATE_PAUSED
                else -> playback != null && sameTrack && position(playback) >= 0 &&
                    abs(position(playback) - positionMs!! - if (playback.state == PlaybackState.STATE_PLAYING)
                        ((SystemClock.elapsedRealtime() - sentAt) * playback.playbackSpeed).toLong() else 0L) <= 3000
            }
            if (confirmed) return@withLock OperationEnvelope.success("execution_status" to "accepted",
                "effect_status" to "observed", "command" to command, "before" to before, "after" to after)
        }
        OperationEnvelope.failure("MEDIA_STATE_UNCONFIRMED", null, "execution_status" to "accepted",
            "effect_status" to "unconfirmed", "command" to command, "before" to before, "after" to after)
    }
}

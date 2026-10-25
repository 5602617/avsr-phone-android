package io.github.lejw0925.virtualscreen.phone

import android.content.Context
import android.media.*
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

data class PhoneAudioClip(val file: File, val text: String, val complete: Boolean = false)

/** PCM playback only: no Activity, display, lease or task manipulation. */
class PhoneAudio(private val context: Context, private val changed: (JSONObject) -> Unit) {
    companion object { fun nowMs() = SystemClock.elapsedRealtimeNanos() / 1_000_000.0 }
    private data class Progress(val bytes: Int = 0, val ended: Boolean = false, val failed: Boolean = false)
    private class Stream(val id: String, val clip: PhoneAudioClip, val replay: Boolean = false, val epoch: Long,
                         val replyGroup: String = "") {
        val progress = MutableStateFlow(Progress())
        var sequence = 0
        @Volatile var cancelled = false
        @Volatile var stopReason = ""
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val streams = ConcurrentHashMap<String, Stream>()
    private val stoppedIds = ConcurrentHashMap.newKeySet<String>()
    private val queue = Channel<Stream>(8)
    private val epoch = java.util.concurrent.atomic.AtomicLong()
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener({ change ->
            if (change <= AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) stop(reason = "audio_focus_loss")
        }, Handler(Looper.getMainLooper())).build()
    @Volatile private var active: Stream? = null
    /** 0..1 loudness at the current play head (not the write head), for the speaking ring. */
    val level = MutableStateFlow(0f)
    /** Includes queued/buffering speech; false only after playback resources are released. */
    val playbackActive = MutableStateFlow(false)
    @Volatile var beforePlayback: (() -> Unit)? = null
    private val playback = mutableSetOf<Stream>()
    @Volatile private var recording = false

    init { scope.launch { for (stream in queue) play(stream) } }

    private fun enqueuePlayback(stream: Stream): Boolean {
        synchronized(playback) { playback.add(stream); playbackActive.value = true }
        beforePlayback?.invoke()
        if (queue.trySend(stream).isSuccess) return true
        playbackFinished(stream)
        return false
    }

    private fun playbackFinished(stream: Stream) {
        synchronized(playback) {
            playback.remove(stream)
            playbackActive.value = playback.isNotEmpty()
        }
    }

    private fun emit(stream: Stream, status: String, underruns: Int? = null, at: Double = nowMs()) {
        if (status != "stopped" && (stream.epoch != epoch.get() || stream.stopReason.isNotBlank() ||
                stream.cancelled && status != "playback_failed")) return
        val frame = JSONObject().put("type", "output.audio.ack").put("id", stream.id)
            .put("status", status).put("device_ms", at).put("replay", stream.replay)
        if (underruns != null) frame.put("underruns", underruns)
        if (status == "stopped" && stream.stopReason.isNotBlank()) frame.put("reason", stream.stopReason)
        changed(frame)
    }

    fun start(id: String, text: String, replyGroup: String = ""): PhoneAudioClip {
        require(!streams.containsKey(id)) { "PHONE_AUDIO_DUPLICATE_START" }
        if (streams.size >= 200 || streams.values.sumOf { it.clip.file.length() } > 64L * 1024 * 1024) {
            streams.values.filter { it.id != active?.id && (it.progress.value.ended || it.progress.value.failed || it.cancelled) }.forEach {
                if (streams.remove(it.id, it)) it.clip.file.delete()
            }
        }
        val stream = Stream(id, PhoneAudioClip(File.createTempFile("phone-reply-", ".pcm", context.cacheDir), text),
            epoch = epoch.get(), replyGroup = replyGroup)
        streams[id] = stream
        val stopReason = when {
            recording -> "recording_active"
            stoppedIds.contains(id) -> "already_stopped"
            replyGroup.isNotBlank() && streams.values.any { it !== stream && it.replyGroup == replyGroup && it.stopReason.isNotBlank() } -> "earlier_reply_segment_stopped"
            !enqueuePlayback(stream) -> "playback_queue_full"
            else -> null
        }
        if (stopReason != null) {
            stream.cancelled = true
            stream.stopReason = stopReason
            emit(stream, "stopped")
        }
        return stream.clip
    }

    fun chunk(id: String, sequence: Int, bytes: ByteArray, receivedMs: Double) {
        val stream = streams[id] ?: return
        if (stream.cancelled) return
        val progress = stream.progress.value
        require(!progress.ended && !progress.failed && sequence == stream.sequence && bytes.isNotEmpty() && bytes.size % 2 == 0 && bytes.size <= 65536) { "PHONE_AUDIO_SEQUENCE" }
        require(progress.bytes + bytes.size <= 32 * 1024 * 1024) { "PHONE_AUDIO_SIZE" }
        stream.clip.file.appendBytes(bytes)
        stream.sequence++
        if (sequence == 0) emit(stream, "received", at = receivedMs)
        stream.progress.value = progress.copy(bytes = progress.bytes + bytes.size)
    }

    fun end(id: String, chunks: Int, bytes: Int) {
        val stream = streams[id] ?: return
        if (stream.cancelled) return
        require(chunks == stream.sequence && bytes == stream.progress.value.bytes && bytes > 0) { "PHONE_AUDIO_TRUNCATED" }
        emit(stream, "buffered")
        stream.progress.value = stream.progress.value.copy(ended = true)
    }

    fun fail(id: String) {
        val stream = streams[id] ?: return
        if (stream.cancelled || stream.epoch != epoch.get()) return
        stream.progress.value = stream.progress.value.copy(failed = true)
        if (stream !== active) { stream.cancelled = true; emit(stream, "playback_failed"); playbackFinished(stream) }
    }

    fun replay(id: String, clips: List<PhoneAudioClip>) {
        val clip = clips.singleOrNull()?.takeIf { it.complete && it.file.exists() } ?: return
        stop(reason = "replay_started")
        val stream = Stream(id, clip, replay = true, epoch = epoch.get())
        stream.progress.value = Progress(bytes = clip.file.length().toInt(), ended = true)
        if (!enqueuePlayback(stream)) emit(stream, "playback_failed")
    }

    fun recording(active: Boolean) { recording = active; if (active) stop(reason = "recording_started") }
    fun stop(id: String? = null, reason: String = "stop_requested") {
        if (id != null) {
            if (stoppedIds.size >= 200) stoppedIds.clear()
            stoppedIds.add(id)
            val stream = streams[id]
            if (stream?.replyGroup?.isNotBlank() == true) {
                streams.values.filter { it.id != id && it.replyGroup == stream.replyGroup &&
                    synchronized(playback) { it in playback } }.forEach { sibling ->
                    sibling.cancelled = true
                    sibling.stopReason = reason
                    if (sibling !== active) { emit(sibling, "stopped"); playbackFinished(sibling) }
                }
            }
            stream?.cancelled = true
            stream?.stopReason = reason
            if (active?.id == id) { active?.cancelled = true; active?.stopReason = reason }
            if (stream != null && stream !== active) { emit(stream, "stopped"); playbackFinished(stream) }
            return
        }
        epoch.incrementAndGet()
        active?.cancelled = true
        active?.stopReason = reason
        while (true) {
            val stream = queue.tryReceive().getOrNull() ?: break
            stream.cancelled = true
            stream.stopReason = reason
            emit(stream, "stopped")
            playbackFinished(stream)
        }
    }
    fun disconnected() {
        stop(reason = "disconnected")
        streams.values.filter { !it.progress.value.ended }.forEach { fail(it.id) }
    }

    /** RMS of 20 ms at byte offset [at], read from the clip file; the next write seeks again. */
    private fun levelAt(input: RandomAccessFile, at: Long, limit: Int, probe: ByteArray): Float {
        val count = (minOf(at + probe.size, limit.toLong()) - at).toInt() and 1.inv()
        if (at < 0 || count <= 0) return 0f
        input.seek(at); input.readFully(probe, 0, count)
        var sum = 0.0
        for (i in 0 until count step 2) { val s = (probe[i].toInt() and 0xff) or (probe[i + 1].toInt() shl 8); sum += s.toDouble() * s }
        val rms = kotlin.math.sqrt(sum / (count / 2)) / 32768.0
        return kotlin.math.sqrt(rms * 4).coerceIn(0.0, 1.0).toFloat() // perceptual lift for quiet speech
    }

    private suspend fun play(stream: Stream) {
        if (stream.cancelled) { playbackFinished(stream); return }
        if (stream.epoch != epoch.get()) { stream.cancelled = true; emit(stream, "stopped"); playbackFinished(stream); return }
        active = stream
        var track: AudioTrack? = null
        var completed = false
        try {
            if (recording) { stream.cancelled = true; return }
            withTimeout(120_000) {
                while (stream.progress.value.bytes == 0 && !stream.progress.value.failed && !stream.cancelled && stream.epoch == epoch.get()) delay(10)
            }
            if (stream.epoch != epoch.get()) stream.cancelled = true
            if (stream.cancelled) return
            check(!stream.progress.value.failed) { "PHONE_AUDIO_STREAM_FAILED" }
            check(audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "PHONE_AUDIO_FOCUS" }
            val output = AudioTrack.Builder().setAudioAttributes(attributes)
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(24000 * 2 * 3,
                    AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)))
                .build()
            track = output
            check(output.state == AudioTrack.STATE_INITIALIZED)
            // Capacity is 3 seconds; the first chunk does not wait for a full buffer.
            output.setStartThresholdInFrames(1)
            var written = 0
            var playing = false
            var started = false
            var lastProgress = nowMs()
            var lastHead = 0L
            val buffer = ByteArray(16384)
            val probe = ByteArray(960) // 20 ms of 24 kHz mono PCM16
            var lastLevelAt = 0.0
            RandomAccessFile(stream.clip.file, "r").use { input ->
                while (!stream.cancelled && stream.epoch == epoch.get()) {
                    val progress = stream.progress.value
                    check(!progress.failed) { "PHONE_AUDIO_STREAM_FAILED" }
                    val available = progress.bytes - written
                    if (available > 0) {
                        val count = minOf(available, buffer.size)
                        input.seek(written.toLong()); input.readFully(buffer, 0, count)
                        val copied = output.write(buffer, 0, count, AudioTrack.WRITE_NON_BLOCKING)
                        check(copied >= 0) { "PHONE_AUDIO_WRITE_FAILED" }
                        written += copied
                        if (copied > 0 && !playing) { output.play(); playing = true }
                    }
                    val head = output.playbackHeadPosition.toLong() and 0xffffffffL
                    if (head > 0 && !started) { started = true; emit(stream, "playing") }
                    if (head != lastHead) { lastHead = head; lastProgress = nowMs() }
                    if (nowMs() - lastLevelAt >= 40) { lastLevelAt = nowMs(); level.value = levelAt(input, head * 2, minOf(written, progress.bytes), probe) }
                    if (progress.ended && written == progress.bytes && head >= written / 2) {
                        completed = true
                        emit(stream, "completed", output.underrunCount)
                        return
                    }
                    check(nowMs() - lastProgress < 120_000) { "PHONE_AUDIO_STALLED" }
                    delay(10)
                }
            }
        } catch (_: Exception) {
            emit(stream, "playback_failed", track?.underrunCount)
        } finally {
            if (stream.epoch != epoch.get()) stream.cancelled = true
            if (stream.cancelled && !completed) emit(stream, "stopped", track?.underrunCount)
            runCatching { track?.pause(); track?.flush(); track?.release() }
            audio.abandonAudioFocusRequest(focus)
            active = null
            level.value = 0f
            playbackFinished(stream)
        }
    }
}

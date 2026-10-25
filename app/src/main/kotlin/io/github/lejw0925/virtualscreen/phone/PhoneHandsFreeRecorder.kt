package io.github.lejw0925.virtualscreen.phone

import android.content.Context
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Local speech endpointing only. Recognition stays on the original host ASR path. */
class PhoneHandsFreeRecorder(
    private val context: Context,
    private val send: (File, Double) -> Unit,
    private val failed: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val mutableListening = MutableStateFlow(false)
    val listening = mutableListening.asStateFlow()
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var enabled = false
    private var openedAt = 0L
    private var lastSpeechAt = 0L
    private var heardSpeech = false
    private var noiseFloor = 200.0
    private val silenceMs = 1_200L

    private val poll = object : Runnable {
        override fun run() { synchronized(lock) {
            val active = recorder ?: return@synchronized
            if (!enabled) return@synchronized
            try {
                val now = SystemClock.elapsedRealtime()
                val amplitude = active.maxAmplitude.toDouble()
                if (amplitude >= maxOf(700.0, noiseFloor * 2.8)) {
                    heardSpeech = true
                    lastSpeechAt = now
                } else if (!heardSpeech) {
                    noiseFloor = noiseFloor * 0.92 + amplitude * 0.08
                }
                if (now - (if (heardSpeech) lastSpeechAt else openedAt) >= silenceMs) {
                    // Empty listening periods are discarded locally. Rotating
                    // them keeps idle silence out of the next upload.
                    finishLocked(sendSpeech = heardSpeech)
                    if (enabled) openLocked()
                } else {
                    handler.postDelayed(this, 80)
                }
            } catch (_: Exception) {
                failLocked()
            }
        } }
    }

    fun start() = synchronized(lock) {
        if (enabled) return@synchronized
        enabled = true
        try { openLocked() } catch (_: Exception) { failLocked() }
    }

    /** Synchronous release also makes TTS's playback-before-capture boundary exact. */
    fun stop() = synchronized(lock) {
        enabled = false
        handler.removeCallbacks(poll)
        finishLocked(sendSpeech = false)
        mutableListening.value = false
    }

    private fun openLocked() {
        val output = File.createTempFile("phone-record-", ".m4a", context.cacheDir)
        file = output
        val active = MediaRecorder(context)
        recorder = active
        active.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(16000)
            setAudioEncodingBitRate(64000)
            setOutputFile(output.absolutePath)
            setOnErrorListener { source, _, _ -> synchronized(lock) {
                if (recorder === source) failLocked()
            } }
            prepare()
            start()
        }
        heardSpeech = false
        openedAt = SystemClock.elapsedRealtime()
        lastSpeechAt = openedAt
        mutableListening.value = true
        handler.postDelayed(poll, 80)
    }

    private fun finishLocked(sendSpeech: Boolean) {
        val active = recorder
        val output = file
        recorder = null
        file = null
        val valid = active != null && runCatching { active.stop() }.isSuccess
        runCatching { active?.release() }
        if (sendSpeech && valid && output != null) send(output, PhoneAudio.nowMs())
        else output?.delete()
    }

    private fun failLocked() {
        enabled = false
        handler.removeCallbacks(poll)
        finishLocked(sendSpeech = false)
        mutableListening.value = false
        failed()
    }
}

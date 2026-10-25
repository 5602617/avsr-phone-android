package io.github.lejw0925.virtualscreen.ui

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.lejw0925.virtualscreen.phone.HostSocketClient
import io.github.lejw0925.virtualscreen.phone.PhoneAudio
import java.io.File

@Composable
fun PhoneVoiceInput(phone: HostSocketClient, enabled: Boolean, onSent: () -> Unit = {}) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val l = LocalLanguage.current
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var file by remember { mutableStateOf<File?>(null) }
    var hint by remember { mutableStateOf("") }
    val sent by rememberUpdatedState(onSent)
    fun cancel() {
        recorder?.run { runCatching { stop() }; release() }; recorder = null
        file?.delete(); file = null; phone.audio.recording(false)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hint = if (granted) l.text("已允许，再按住即可说话。", "Microphone allowed. Hold to speak.", "已允許，再按住即可說話。")
        else l.text("未允许麦克风，可以继续输入文字。", "Microphone denied. You can still type.", "未允許麥克風，可以繼續輸入文字。")
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) cancel() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); cancel() }
    }
    Surface(color = if (recorder != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.pointerInput(enabled, l) {
            detectTapGestures(onPress = {
                if (!enabled) return@detectTapGestures
                if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    permission.launch(Manifest.permission.RECORD_AUDIO)
                    return@detectTapGestures
                }
                try {
                    hint = ""
                    phone.audio.recording(true)
                    file = File.createTempFile("phone-record-", ".m4a", context.cacheDir)
                    recorder = MediaRecorder(context).apply {
                        setAudioSource(MediaRecorder.AudioSource.MIC)
                        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                        setAudioSamplingRate(16000); setAudioEncodingBitRate(64000)
                        setMaxDuration(60_000); setOutputFile(file!!.absolutePath)
                        setOnInfoListener { _, what, _ ->
                            if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                                cancel()
                                hint = l.text("本次超过一分钟，请分段说。", "Please speak in clips under one minute.", "本次超過一分鐘，請分段說。")
                            }
                        }
                        prepare(); start()
                    }
                    val released = tryAwaitRelease()
                    val releasedMs = PhoneAudio.nowMs()
                    if (released && recorder != null) {
                        recorder!!.stop(); recorder!!.release(); recorder = null
                        val recording = file!!; file = null
                        phone.audio.recording(false)
                        phone.sendRecording(recording, releasedMs)
                        sent()
                    } else cancel()
                } catch (_: Exception) {
                    cancel()
                    hint = l.text("录音未完成，请按住说话后松开重试，或输入文字。", "Recording did not finish. Hold, speak and release, or type instead.", "錄音未完成，請按住說話後鬆開重試，或輸入文字。")
                }
            })
        }) {
        Text(if (recorder != null) l.text("松开发送", "Release to send", "鬆開傳送")
            else l.text("按住说话", "Hold to speak", "按住說話"), Modifier.padding(14.dp))
    }
    if (hint.isNotBlank()) Text(hint, style = MaterialTheme.typography.bodySmall)
}

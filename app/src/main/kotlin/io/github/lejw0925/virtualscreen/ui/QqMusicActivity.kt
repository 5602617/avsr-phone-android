package io.github.lejw0925.virtualscreen.ui

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.github.lejw0925.virtualscreen.VirtualScreenApplication
import io.github.lejw0925.virtualscreen.core.OperationEnvelope
import io.github.lejw0925.virtualscreen.core.code
import io.github.lejw0925.virtualscreen.media.QqMusicNotificationListener
import kotlinx.coroutines.*

class QqMusicActivity : ComponentActivity() {
    private val app get() = application as VirtualScreenApplication
    private var current by mutableStateOf(OperationEnvelope.success())
    private var outcome by mutableStateOf<OperationEnvelope?>(null)
    private var busy by mutableStateOf(false)
    private var polling: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { VirtualScreenTheme(app) {
            val l = LocalLanguage.current
            var seconds by remember { mutableStateOf("90") }
            @Suppress("UNCHECKED_CAST")
            val sessions = current.values["sessions"] as? List<Map<String, Any?>> ?: emptyList()
            val selected = sessions.singleOrNull()
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.statusBarsPadding().navigationBarsPadding().padding(20.dp)
                    .verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(14.dp)) {
                    Text(l.text("QQ 音乐控制","QQ Music controls","QQ 音樂控制"),style=MaterialTheme.typography.headlineSmall)
                    Text(l.text("先在 QQ 音乐播放一首歌曲，再返回这里。通知访问用于读取和控制它的播放会话。",
                        "Start a song in QQ Music, then return here. Notification access lets this app read and control its playback session.",
                        "先在 QQ 音樂播放一首歌曲，再返回這裡。通知存取用於讀取及控制它的播放工作階段。"))
                    Button(onClick={
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                                ComponentName(this@QqMusicActivity,QqMusicNotificationListener::class.java).flattenToString()))
                    }) { Text(l.text("通知访问设置","Notification access","通知存取設定")) }
                    if(!current.ok) Text(current.code.orEmpty())
                    if(sessions.isEmpty()) Text(l.text("暂无 QQ 音乐播放会话","No QQ Music session","暫無 QQ 音樂播放工作階段"))
                    if(sessions.size>1) Text(l.text("发现多个会话，请通过 MCP 指定目标会话。","Multiple sessions: select the session through MCP.","發現多個工作階段，請透過 MCP 指定目標。"))
                    sessions.forEach { session ->
                        Text("${session["title"] ?: "—"} · ${session["artist"] ?: "—"}")
                        Text("${playbackTime(session["position_ms"])} / ${playbackTime(session["duration_ms"])}")
                        Text(when(session["playback_state"]) {
                            android.media.session.PlaybackState.STATE_PLAYING -> l.text("播放中","Playing","播放中")
                            android.media.session.PlaybackState.STATE_PAUSED -> l.text("已暂停","Paused","已暫停")
                            android.media.session.PlaybackState.STATE_BUFFERING -> l.text("正在缓冲","Buffering","正在緩衝")
                            else -> l.text("未播放","Not playing","未播放")
                        })
                    }
                    Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        Button(onClick={ command(selected,"pause") },enabled=!busy && selected!=null) { Text(l.text("暂停","Pause","暫停")) }
                        Button(onClick={ command(selected,"play") },enabled=!busy && selected!=null) { Text(l.text("继续","Play","繼續")) }
                    }
                    OutlinedTextField(value=seconds,onValueChange={ seconds=it },singleLine=true,
                        label={ Text(l.text("跳转位置（秒）","Position (seconds)","跳轉位置（秒）")) })
                    val seek=seconds.toLongOrNull()?.takeIf { it in 0..Int.MAX_VALUE/1000 }?.times(1000)
                    Button(onClick={ command(selected,"seek",seek) },enabled=!busy && selected!=null && seek!=null) { Text(l.text("跳转","Seek","跳轉")) }
                    outcome?.let { Text(if(it.ok) l.text("已读回播放状态","Playback state observed","已讀回播放狀態") else it.code.orEmpty()) }
                    TextButton(onClick={ finish() }) { Text(l.text("返回虚拟屏","Back to virtual screen","返回虛擬螢幕")) }
                }
            }
        } }
    }

    override fun onStart() {
        super.onStart()
        polling=lifecycleScope.launch {
            while(isActive) {
                current=withContext(Dispatchers.IO) { runCatching { app.qqMusic.status() }
                    .getOrElse { OperationEnvelope.failure(it.code()) } }
                delay(1000)
            }
        }
    }
    override fun onStop() { polling?.cancel(); super.onStop() }

    private fun playbackTime(value: Any?): String {
        val millis=(value as? Number)?.toLong()?.takeIf { it >= 0 } ?: return "—"
        val seconds=millis/1000
        return "${seconds/60}:${(seconds%60).toString().padStart(2,'0')}"
    }

    private fun command(session: Map<String,Any?>?, action: String, position: Long?=null) {
        val id=session?.get("session_id") as? String ?: return
        lifecycleScope.launch {
            busy=true
            try { outcome=withContext(Dispatchers.IO) { app.qqMusic.control(id,action,position) } }
            catch(c: CancellationException) { throw c }
            catch(e: Exception) { outcome=OperationEnvelope.failure(e.code()) }
            finally { busy=false }
        }
    }
}

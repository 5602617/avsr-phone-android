package io.github.lejw0925.virtualscreen.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.lejw0925.virtualscreen.NotificationApproval
import io.github.lejw0925.virtualscreen.R
import io.github.lejw0925.virtualscreen.VirtualScreenApplication

class PhoneTaskActivity : ComponentActivity() {
    private val app get() = application as VirtualScreenApplication
    private val phone get() = app.phoneConnection
    private var chatResumed by mutableStateOf(false)
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    override fun onResume() { super.onResume(); chatResumed = true; phone.presentation.chatVisible = true }
    override fun onPause() { chatResumed = false; phone.presentation.chatVisible = false; super.onPause() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { VirtualScreenTheme(app) {
            val l = LocalLanguage.current
            val state by phone.state.collectAsState()
            val binding by app.phoneSession.flow.collectAsState()
            val messages by phone.messages.collectAsState()
            val activity by app.mcp.activity.collectAsState()
            val restartRequests by app.sessions.restartApprovals.pending.collectAsState()
            var url by remember { mutableStateOf(phone.savedUrl) }
            var device by remember { mutableStateOf(phone.savedDevice) }
            var token by remember { mutableStateOf(phone.savedToken()) }
            var input by rememberSaveablePhoneText()
            var useMainScreen by remember { mutableStateOf(false) }
            val mainScreenGrant by app.mainScreen.grant.collectAsState()
            var config by remember { mutableStateOf(state != "ready") }
            var error by remember { mutableStateOf("") }
            val scroll = rememberScrollState()
            LaunchedEffect(messages, chatResumed) {
                if(chatResumed) {
                    scroll.animateScrollTo(scroll.maxValue)
                    withFrameNanos { }; withFrameNanos { }
                    messages.filter { it.incoming }.takeLast(1).forEach { phone.acknowledge(it.id, "chat", true) }
                }
            }
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.statusBarsPadding().navigationBarsPadding().imePadding().padding(16.dp)) {
                    Text(l.text("Persona 手机任务", "Persona phone tasks", "Persona 手機任務"), style = MaterialTheme.typography.headlineSmall)
                    Text("$state · ${if(binding.executionEnabled) l.text("允许执行", "Execution enabled", "允許執行") else l.text("已暂停", "Paused", "已暫停")}")
                    if(binding.reason.isNotBlank()) Text(binding.reason)
                    activity?.let { Text("${it.tool} · ${it.phase}\n${it.purpose}", maxLines = 3) }
                    restartRequests.firstOrNull()?.let { request ->
                        val approval = NotificationApproval.restart(this@PhoneTaskActivity, request)
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(approval.title, style = MaterialTheme.typography.titleMedium)
                                Text(approval.detail)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { app.sessions.restartApprovals.deny(request.id) }) {
                                        Text(stringResource(R.string.control_deny))
                                    }
                                    Button(onClick = {
                                        if (!app.sessions.restartApprovals.approve(request.id)) {
                                            error = localizedError(l, "APP_RESTART_TIMEOUT")
                                        }
                                    }) { Text(stringResource(R.string.app_restart_allow)) }
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = { config = !config }) { Text(l.text("连接", "Connection", "連線")) }
                        TextButton(onClick = { startActivity(Intent(this@PhoneTaskActivity, MainActivity::class.java)) }) { Text(l.text("查看虚拟屏", "Watch screen", "查看虛擬螢幕")) }
                        TextButton(onClick = {
                            if(Settings.canDrawOverlays(this@PhoneTaskActivity)) phone.presentation.setOverlay(true)
                            else startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                        }) { Text(l.text("悬浮状态", "Overlay", "懸浮狀態")) }
                    }
                    if(config) Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                        TextButton(onClick = { startActivity(Intent(this@PhoneTaskActivity, WirelessAdbActivity::class.java)) }) {
                            Text(l.text("无线配对与开机恢复", "Wireless pairing and boot recovery", "無線配對與開機恢復"))
                        }
                        TextButton(onClick = {
                            runCatching { startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
                                .onFailure { error = l.text("请在系统设置中选择默认数字助理。", "Choose the default digital assistant in system settings.", "請在系統設定中選擇預設數位助理。") }
                        }) { Text(l.text("默认数字助理", "Default digital assistant", "預設數位助理")) }
                        TextButton(onClick = { startActivity(Intent(this@PhoneTaskActivity,
                            io.github.lejw0925.virtualscreen.systemtools.SystemToolsPermissionActivity::class.java)) }) {
                            Text(l.text("系统工具权限", "System tool permissions", "系統工具權限"))
                        }
                        OutlinedTextField(url, { url = it }, label = { Text("Host WS URL") }, singleLine = true)
                        OutlinedTextField(device, { device = it }, label = { Text("Device ID") }, singleLine = true)
                        OutlinedTextField(token, { token = it }, label = { Text("Token") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                        Row {
                            Button(onClick = {
                                error = ""
                                runCatching { phone.connect(url.trim(), token.trim(), device.trim()) }.onFailure { error = it.message.orEmpty() }
                                notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }) { Text(l.text("连接主机", "Connect", "連線主機")) }
                            TextButton(onClick = { phone.disconnect() }) { Text(l.text("断开", "Disconnect", "中斷")) }
                            TextButton(onClick = { phone.presentation.setOverlay(false) }) { Text(l.text("隐藏悬浮窗", "Hide overlay", "隱藏懸浮窗")) }
                        }
                        Text(l.text("局域网演示可用 ws；公网连接使用 wss。", "Use ws only on a trusted demo LAN; use wss for Internet access.", "區域網路演示可用 ws；公網連線使用 wss。"))
                    }
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        messages.forEach { message ->
                            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                                Text(if(message.incoming) "Persona" else l.text("我", "Me", "我"), style = MaterialTheme.typography.labelMedium)
                                Text(voiceInputDisplayText(message.text, message.voiceInput, message.transcriptStatus))
                                if(!message.incoming) Text(message.delivery, style = MaterialTheme.typography.labelSmall)
                                if (message.voiceInput && message.delivery !in setOf("accepted", "discarded")) Row {
                                    TextButton(onClick = { phone.retryRecording(message.id) }) { Text(l.text("重发这条", "Retry this message", "重送這則")) }
                                    TextButton(onClick = { phone.discardRecording(message.id) }) { Text(l.text("丢弃录音", "Discard recording", "捨棄錄音")) }
                                }
                                if (message.audioStatus.isNotBlank()) Row {
                                    TextButton(enabled = message.audio.any { it.complete && it.file.exists() }, onClick = { phone.audio.replay(message.id, message.audio) }) { Text(l.text("重放语音", "Replay voice", "重播語音")) }
                                    TextButton(onClick = { phone.audio.stop(message.id) }) { Text(l.text("停止播放", "Stop playback", "停止播放")) }
                                    Text(when (message.audioStatus) {
                                        "waiting" -> l.text("等待语音", "Waiting for voice", "等待語音")
                                        "received" -> l.text("接收中", "Receiving", "接收中")
                                        "buffered" -> l.text("已收齐", "Received", "已收齊")
                                        "playing" -> l.text("播放中", "Playing", "播放中")
                                        "completed" -> l.text("已播完", "Finished", "已播完")
                                        "stopped" -> l.text("已停止", "Stopped", "已停止")
                                        else -> l.text("语音播放失败", "Voice playback failed", "語音播放失敗")
                                    }, style = MaterialTheme.typography.labelSmall)
                                }
                            } }
                        }
                    }
                    if(error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                    PhoneVoiceInput(phone, state == "ready")
                    Row {
                        Checkbox(checked = useMainScreen, onCheckedChange = { useMainScreen = it }, enabled = mainScreenGrant == null)
                        Text(l.text("这次文字任务交给她用主屏", "Hand over the main screen for this text task", "這次文字任務交給她用主螢幕"))
                    }
                    OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth(), label = { Text(l.text("告诉 Persona 要做什么", "Tell Persona what to do", "告訴 Persona 要做什麼")) }, maxLines = 3)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = {
                            runCatching {
                                if (phone.sendText(input, useMainScreen)) { input = ""; useMainScreen = false }
                                else { if (useMainScreen) phone.pause("input_not_sent"); error = "PHONE_OFFLINE_OR_EMPTY_INPUT" }
                            }.onFailure { error = it.message.orEmpty() }
                        }, enabled = state == "ready") { Text(l.text("发送", "Send", "傳送")) }
                        TextButton(onClick = { phone.sendText(l.text("当前手机任务进展如何？", "What is the progress of the current phone task?", "目前手機任務進展如何？")) }, enabled = state == "ready") { Text(l.text("进度", "Progress", "進度")) }
                        TextButton(onClick = { phone.stopTask() }) { Text(l.text("停止", "Stop", "停止")) }
                        TextButton(onClick = { runCatching { phone.resume() }.onFailure { error = it.message.orEmpty() } }, enabled = state == "ready" && !binding.executionEnabled) { Text(l.text("允许新操作", "Enable actions", "允許新操作")) }
                    }
                }
            }
        } }
    }
}

@Composable
private fun rememberSaveablePhoneText() = androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }

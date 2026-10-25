package io.github.lejw0925.virtualscreen.ui

import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.lejw0925.virtualscreen.VirtualScreenApplication
import io.github.lejw0925.virtualscreen.app.*

/** Hosts PhoneUiContent.App (Claude's screens). The functional screen below is kept as a fallback for diagnosis. */
class PhoneProductActivity : ComponentActivity() {
    private val app get() = application as VirtualScreenApplication
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Compose owns system-bar and IME insets; don't also pan the whole window.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent { VirtualScreenTheme(app) {
            val state by app.avsr.state.collectAsState()
            PhoneUiContent.App(state, app.avsr)
            DeviceAuthorizationDialog(app)
        } }
    }
    override fun onResume() { super.onResume(); app.avsr.attach(this); app.phoneConnection.presentation.chatVisible = true }
    override fun onPause() { app.phoneConnection.presentation.chatVisible = false; app.avsr.detach(this); super.onPause() }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults); app.avsr.permissionResult()
    }
}

@Composable
internal fun FunctionalPhoneScreen(state: AvsrAppState, actions: AvsrActions) {
    val l = LocalLanguage.current
    var settings by remember { mutableStateOf(false) }
    var host by remember(state.auth.host) { mutableStateOf(state.auth.host) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var input by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var mainScreen by remember { mutableStateOf(false) }
    var submittedText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.conversation) {
        val last = state.conversation.lastOrNull { it.role == "user" }
        if (submittedText != null && last != null && last.text == submittedText && last.delivery in setOf("sent", "accepted")) {
            input = ""; mainScreen = false; submittedText = null
        }
    }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Persona", style = MaterialTheme.typography.headlineMedium)
            Text("${state.auth.phase} · ${state.connection} · ${state.mode}")
            if (state.auth.reason.isNotBlank()) Text(state.auth.reason, color = MaterialTheme.colorScheme.error)
            if (state.error.isNotBlank()) Text(state.error, color = MaterialTheme.colorScheme.error)
            Row {
                TextButton(onClick = { settings = !settings }) { Text(l.text("设置与引导", "Settings and setup", "設定與引導")) }
                TextButton(onClick = actions::stopTask) { Text(l.text("停止", "Stop", "停止")) }
            }
            Row {
                TextButton(onClick = actions::minimizeToOverlay) { Text(l.text("切换为悬浮窗", "Switch to floating panel", "切換為懸浮窗")) }
                TextButton(onClick = actions::openVirtualScreen) { Text(l.text("查看虚拟屏", "View virtual screen", "查看虛擬螢幕")) }
            }
            if (settings) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Switch(state.overlayEnabled, actions::setOverlay)
                    Spacer(Modifier.width(8.dp))
                    Text(l.text("离开应用后显示悬浮窗", "Show floating panel when leaving the app", "離開應用程式後顯示懸浮窗"))
                }
                Text(l.text("关闭悬浮窗不停止后台连接或任务。", "Turning off the panel keeps the background connection and tasks running.", "關閉懸浮窗不停止背景連線或任務。"))
            }
            if (settings || state.onboardingStep == "login") {
                Card { Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(l.text("登录宿主", "Sign in to host", "登入宿主"))
                    OutlinedTextField(host, { host = it }, label = { Text(l.text("宿主地址", "Host address", "宿主位址")) }, singleLine = true)
                    OutlinedTextField(username, { username = it }, label = { Text(l.text("账号", "Account", "帳號")) }, singleLine = true)
                    OutlinedTextField(password, { password = it }, label = { Text(l.text("密码", "Password", "密碼")) }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                    Row {
                        Button(enabled = state.auth.phase != "logging_in", onClick = { actions.login(host, username, password); password = "" }) { Text(l.text("登录", "Sign in", "登入")) }
                        TextButton(onClick = actions::retryRegistration) { Text(l.text("重试注册", "Retry registration", "重試註冊")) }
                        TextButton(onClick = actions::logout) { Text(l.text("退出登录", "Sign out", "登出")) }
                    }
                } }
            }
            if (settings || state.onboardingStep == "pairing") PairingPanel(state, actions)
            if (settings || state.onboardingStep == "permissions") {
                Text(l.text("其他权限", "Permissions", "其他權限"), style = MaterialTheme.typography.titleMedium)
                listOf("accessibility" to state.permissions.accessibility, "overlay" to state.permissions.overlay,
                    "microphone" to state.permissions.microphone, "notifications" to state.permissions.notifications).forEach { (name, allowed) ->
                    TextButton(onClick = { actions.openPermission(name) }) { Text("$name · ${if (allowed) "✓" else "—"}") }
                }
                TextButton(onClick = { actions.openPermission("assistant") }) { Text(l.text("设为默认数字助理", "Default digital assistant", "設為預設數位助理")) }
            }
            if (settings || state.onboardingStep == "mode") {
                Text(l.text("使用模式（之后的任务生效）", "Usage mode (future tasks)", "使用模式（之後的任務生效）"))
                Row {
                    FilterChip(state.mode == "background", { actions.setUsageMode("background") }, { Text(l.text("后台", "Background", "背景")) })
                    Spacer(Modifier.width(12.dp))
                    FilterChip(state.mode == "global", { actions.setUsageMode("global") }, { Text(l.text("全局", "Global", "全域")) })
                }
                Text(l.text("后台只用虚拟屏；需要主屏时等待你选择。全局模式允许使用主屏。", "Background uses a virtual screen and asks before using your main screen. Global mode permits the main screen.", "背景只用虛擬螢幕；需要主螢幕時等待你選擇。全域模式允許使用主螢幕。"))
                if (!state.onboardingComplete) Button(onClick = actions::completeOnboarding) { Text(l.text("完成引导", "Finish setup", "完成引導")) }
                state.avatarSets.forEach { set -> FilterChip(state.selectedAvatarSet == set.id, { actions.selectAvatarSet(set.id) }, { Text(set.name) }) }
            }
            BorrowScreenCard(state, actions)
            HumanHandoffCard(state, actions)
            if (state.task.phase != "idle") Card { Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text("${state.task.phase} · ${state.task.display}")
                Text(state.task.step)
                if (state.task.reason.isNotBlank()) Text(state.task.reason)
                if (state.task.result.isNotBlank()) Text(state.task.result)
                if (state.task.phase == "running_main") Text(l.text("遥正在用你的屏幕", "Yao is using your screen", "遙正在用你的螢幕"))
                TextButton(onClick = actions::stopTask) { Text(l.text("停止", "Stop", "停止")) }
            } }
            state.conversation.forEach { message -> Card { Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text(if (message.role == "assistant") "Persona" else l.text("我", "Me", "我"))
                Text(voiceInputDisplayText(message.text, message.voiceInput, message.transcriptStatus),
                    if (message.role == "assistant") chatPresentationModifier(message.id, actions) else Modifier)
                if (message.audioStatus.isNotBlank()) {
                    Text(message.audioStatus)
                    Row {
                        TextButton(enabled = message.canReplay, onClick = { actions.playVoice(message.id) }) { Text(l.text("重放", "Replay", "重播")) }
                        TextButton(onClick = { actions.stopVoice(message.id) }) { Text(l.text("停止语音", "Stop voice", "停止語音")) }
                    }
                }
            } } }
            OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth(), label = { Text(l.text("告诉她要做什么", "Tell her what to do", "告訴她要做什麼")) })
            Row { Checkbox(mainScreen, { mainScreen = it }); Text(l.text("这件事用主屏", "Use the main screen for this task", "這件事用主螢幕")) }
            Row {
                Button(enabled = state.connection == "ready", onClick = { submittedText = input; actions.sendText(input, mainScreen) }) { Text(l.text("发送", "Send", "傳送")) }
                TextButton(enabled = state.connection == "ready", onClick = {
                    if (state.voice == "listening") { actions.endVoiceInput(); mainScreen = false } else actions.startVoiceInput(mainScreen)
                }) { Text(if (state.voice == "listening") l.text("结束并发送", "Finish and send", "結束並傳送") else l.text("语音输入", "Voice input", "語音輸入")) }
            }
        }
    }
}

/** Composition can survive onPause; only visible foreground text establishes chat presentation. */
@Composable
internal fun chatPresentationModifier(messageId: String, actions: AvsrActions): Modifier {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val view = LocalView.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    var coordinates by remember(messageId) { mutableStateOf<LayoutCoordinates?>(null) }
    var textVisible by remember(messageId) { mutableStateOf(false) }
    var acknowledged by remember(messageId) { mutableStateOf(false) }
    val foreground = lifecycleState == Lifecycle.State.RESUMED && windowFocused

    LaunchedEffect(messageId, foreground, textVisible) {
        if (!foreground || !textVisible || acknowledged) return@LaunchedEffect
        withFrameNanos { }; withFrameNanos { }
        // Check live values again: pause/focus/scroll changes can precede recomposition.
        val bounds = coordinates?.takeIf { it.isAttached }?.boundsInWindow()
        if (lifecycle.currentState == Lifecycle.State.RESUMED && view.hasWindowFocus() &&
            view.isShown && view.windowVisibility == View.VISIBLE && bounds != null && !bounds.isEmpty) {
            actions.acknowledgeMessage(messageId, "chat")
            acknowledged = true
        }
    }
    return Modifier.onGloballyPositioned {
        coordinates = it
        // boundsInWindow clips to ancestors, including this screen's scroll viewport.
        textVisible = it.isAttached && !it.boundsInWindow().isEmpty
    }
}

@Composable
private fun PairingPanel(state: AvsrAppState, actions: AvsrActions) {
    val l = LocalLanguage.current
    var host by remember { mutableStateOf("127.0.0.1") }
    var port by remember { mutableStateOf("") }
    var connectPort by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    Card { Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Text(l.text("配对与内置特权服务", "Pairing and embedded privilege", "配對與內置特權服務"))
        Text(state.permissions.privilegeStatus)
        Text(l.text("首次使用需在系统“关于手机”中连续点按版本号开启开发者选项，再开启无线调试。系统授权需由手机使用者确认。", "First enable Developer options by tapping Build number in About phone, then enable Wireless debugging. System permissions need the phone user's confirmation.", "首次使用需在系統「關於手機」中連續點按版本號開啟開發者選項，再開啟無線偵錯。系統授權需由手機使用者確認。"))
        Text(l.text("开启配对引导后，在系统无线调试中选择“使用配对码配对设备”。保持该页面打开，下拉通知栏，在本应用的配对通知里输入六位码。无需另装应用。", "Start pairing setup, then choose Pair with code in Wireless debugging. Keep that system page open, pull down notifications, and enter the six-digit code in this app's pairing notification. No other app is needed.", "開啟配對引導後，在系統無線偵錯中選擇「使用配對碼配對裝置」。保持該頁面開啟，下拉通知欄，在本應用程式的配對通知裡輸入六位碼。無需另裝應用程式。"))
        TextButton(onClick = { actions.openPermission("pairing") }) { Text(l.text("开启通知配对引导", "Start notification pairing", "開啟通知配對引導")) }
        Text(l.text("手动填写（仅适用于切换应用后仍保持配对页的系统）", "Manual entry (only if the system keeps its pairing page active)", "手動填寫（僅適用於切換應用程式後仍保持配對頁的系統）"))
        OutlinedTextField(host, { host = it }, label = { Text(l.text("本机地址", "This phone's address", "本機位址")) }, singleLine = true)
        OutlinedTextField(port, { port = it }, label = { Text(l.text("配对端口", "Pairing port", "配對連接埠")) }, singleLine = true)
        OutlinedTextField(code, { code = it }, label = { Text(l.text("六位配对码", "Six-digit pairing code", "六位配對碼")) }, singleLine = true, visualTransformation = PasswordVisualTransformation())
        OutlinedTextField(connectPort, { connectPort = it }, label = { Text(l.text("连接端口（可留空自动发现）", "Connection port (optional)", "連線連接埠（可留空自動探索）")) }, singleLine = true)
        Button(enabled = port.toIntOrNull() != null && code.length == 6, onClick = { actions.pair(host, port.toInt(), code, connectPort.toIntOrNull()); code = "" }) { Text(l.text("配对并启动", "Pair and start", "配對並啟動")) }
        TextButton(onClick = actions::restorePrivilege) { Text(l.text("恢复已配对服务", "Restore paired service", "恢復已配對服務")) }
        Row { Checkbox(state.permissions.bootRecovery, actions::setBootRecovery); Text(l.text("开机自动恢复", "Restore after restart", "開機自動恢復")) }
    } }
}

package io.github.lejw0925.virtualscreen.ui

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import io.github.lejw0925.virtualscreen.app.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.floor

/** A layout candidate is not presentation proof. Only the owning window service may confirm it. */
val LocalOverlayPresentation = staticCompositionLocalOf<(String, android.graphics.Rect) -> Boolean> { { _, _ -> false } }

/** Internal functional overlay contract; recording ownership and window flags stay in the service. */
interface FloatingInteraction {
    val expanded: Boolean
    val recording: Boolean
    val error: String
    /** True when the window is docked on the right half; side content then opens toward the left. */
    val alignEnd: Boolean get() = false
    /** When the orb last became visible; words older than this are not replayed in a bubble. */
    val shownAt: Long get() = 0L
    fun expand(value: Boolean)
    fun openApp()
    fun beginVoice()
    fun finishVoice(send: Boolean)
}
val LocalFloatingInteraction = staticCompositionLocalOf<FloatingInteraction?> { null }

internal fun Modifier.floatingGestures(interaction: FloatingInteraction?) = if (interaction == null) this else
    pointerInput(interaction) {
        detectTapGestures(onTap = { interaction.expand(true) }, onDoubleTap = { interaction.openApp() }, onLongPress = { interaction.beginVoice() },
            onPress = {
                var released = false
                try { released = tryAwaitRelease() } finally { interaction.finishVoice(released) }
            })
    }

/** Single assembly point for Claude's screens (ui/screens). The functional panel remains as a fallback delegate. */
object PhoneUiContent {
    var appContent: @Composable (AvsrAppState, AvsrActions) -> Unit = { state, actions -> io.github.lejw0925.virtualscreen.ui.screens.YaoPhoneApp(state, actions) }
    var floatingContent: @Composable (AvsrAppState, AvsrActions) -> Unit = { state, actions -> io.github.lejw0925.virtualscreen.ui.screens.YaoFloatingContent(state, actions) }
    @Composable fun App(state: AvsrAppState, actions: AvsrActions) = appContent(state, actions)
    @Composable fun FloatingPanel(state: AvsrAppState, actions: AvsrActions) = floatingContent(state, actions)
    @Composable fun MainScreenPanel(state: AvsrAppState, actions: AvsrActions) =
        io.github.lejw0925.virtualscreen.ui.design.YaoTheme {
            io.github.lejw0925.virtualscreen.ui.screens.MainScreenBanner(state, actions)
        }
    @Composable fun FunctionalFloating(state: AvsrAppState, actions: AvsrActions) = FunctionalFloatingPanel(state, actions)
}

@Composable
fun BorrowScreenCard(state: AvsrAppState, actions: AvsrActions) {
    val l = LocalLanguage.current
    val task = state.task
    if (task.phase !in setOf("waiting_borrow", "countdown")) return
    Card { Column(Modifier.padding(12.dp)) {
        if (task.phase == "waiting_borrow") {
            Text(l.text("后台暂时做不了，借用主屏吗？", "This cannot run in the background. May I use your screen?", "背景暫時做不了，借用主螢幕嗎？"))
            Text(task.reason)
            var remaining by remember(task.deadlineMs) { mutableStateOf(30L) }
            LaunchedEffect(task.deadlineMs) { while (true) { remaining = ((task.deadlineMs - System.currentTimeMillis()) / 1000).coerceAtLeast(0); kotlinx.coroutines.delay(250) } }
            Text(l.text("${remaining} 秒后结束", "Ends in $remaining seconds", "${remaining} 秒後結束"))
            Button(onClick = { actions.respondBorrow(task.id, "this_once") }) { Text(l.text("这次借给你", "This time", "這次借給你")) }
            TextButton(onClick = { actions.respondBorrow(task.id, "always") }) { Text(l.text("以后都可以用", "Always allow", "以後都可以用")) }
            TextButton(onClick = { actions.respondBorrow(task.id, "decline") }) { Text(l.text("先不用", "Not now", "先不用")) }
        } else {
            Text(l.text("${task.countdownSeconds} 秒后开始使用主屏", "Using your screen in ${task.countdownSeconds} seconds", "${task.countdownSeconds} 秒後開始使用主螢幕"))
            Button(onClick = { actions.cancelCountdown(task.id) }) { Text(l.text("取消", "Cancel", "取消")) }
        }
    } }
}

@Composable
fun HumanHandoffCard(state: AvsrAppState, actions: AvsrActions) {
    val task = state.task
    if (task.phase !in setOf("waiting_human", "human_control")) return
    val l = LocalLanguage.current
    Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(l.text("这一步需要你处理", "This step needs you", "這一步需要你處理"))
        Text(task.handoffReason)
        Text(l.text("她已暂停操作。打开原虚拟屏处理，完成后再交还。", "She has paused. Open the current virtual screen, then hand it back when done.", "她已暫停操作。開啟原虛擬螢幕處理，完成後再交還。"))
        Button(onClick = { actions.acceptHumanHandoff(task.id, task.handoffId.orEmpty()) }) {
            Text(if (task.phase == "waiting_human") l.text("我来处理", "Let me handle it", "我來處理")
                else l.text("回到接管页面", "Return to the screen", "回到接管頁面"))
        }
        TextButton(onClick = { actions.cancelTask(task.id) }) { Text(l.text("结束这件事", "End this task", "結束這件事")) }
    } }
}

@Composable
private fun FunctionalFloatingPanel(state: AvsrAppState, actions: AvsrActions) {
    val l = LocalLanguage.current
    val interaction = LocalFloatingInteraction.current
    var draft by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.conversation, state.error, submitted) {
        val last = state.conversation.lastOrNull { it.role == "user" }
        if (submitted != null && state.error.isNotBlank()) {
            submitted = null
        } else if (submitted != null && last != null && last.text == submitted && last.delivery in setOf("sent", "accepted")) {
            draft = ""; submitted = null
        }
    }
    Surface(tonalElevation = 4.dp, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Persona · ${state.connection}", Modifier.fillMaxWidth().floatingGestures(interaction))
            Text(if (interaction?.recording == true) l.text("松开发送，移开取消", "Release to send; move away to cancel", "鬆開傳送，移開取消")
                else l.text("单击聊天，双击回应用；长按说话，拖动移动", "Tap to chat; double-tap for app; hold to talk; drag to move", "單擊聊天，雙擊回應用；長按說話，拖動移動"),
                Modifier.fillMaxWidth().floatingGestures(interaction))
            if (interaction?.expanded == true) {
                FloatingConversation(state.conversation)
            } else state.conversation.lastOrNull { it.role == "assistant" }?.let { message ->
                OverlayMessageText(message.id, message.text.take(600), maxLines = 8,
                    modifier = Modifier.floatingGestures(interaction), acknowledge = true)
            }
            if (interaction?.error?.isNotBlank() == true) Text(interaction.error, color = MaterialTheme.colorScheme.error)
            if (interaction?.expanded == true) {
                OutlinedTextField(draft, { draft = it }, Modifier.fillMaxWidth(), maxLines = 3,
                    label = { Text(l.text("聊两句", "Message", "聊兩句")) })
                Row {
                    TextButton(enabled = state.connection == "ready" && draft.isNotBlank() && submitted == null, onClick = send@{
                        if (submitted != null) return@send
                        submitted = draft; actions.sendText(draft, false)
                    }) { Text(l.text("发送", "Send", "傳送")) }
                    TextButton(onClick = { interaction.expand(false) }) { Text(l.text("收起聊天", "Collapse chat", "收起聊天")) }
                    TextButton(onClick = interaction::openApp) { Text(l.text("打开应用", "Open app", "開啟應用程式")) }
                }
            }
            if (state.voice == "speaking") Text(l.text("正在说话", "Speaking", "正在說話"))
            BorrowScreenCard(state, actions)
            HumanHandoffCard(state, actions)
            if (state.task.phase in setOf("running_virtual", "running_main", "countdown", "waiting_borrow")) {
                Text(if (state.task.phase == "running_main") l.text("遥正在用你的屏幕", "Yao is using your screen", "遙正在用你的螢幕") else state.task.phase)
                Button(onClick = actions::stopTask) { Text(l.text("停止", "Stop", "停止")) }
            }
            TextButton(onClick = { interaction?.expand(false); actions.setOverlay(false) }) { Text(l.text("关闭悬浮窗", "Close overlay", "關閉懸浮窗")) }
        }
    }
}

/** Stable keys preserve the user's reading position when a new message arrives. */
@Composable
private fun FloatingConversation(messages: List<ConversationItem>) {
    val l = LocalLanguage.current
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var followingLatest by remember { mutableStateOf(true) }
    var viewport by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val latestAssistantId = messages.lastOrNull { it.role == "assistant" }?.id
    LaunchedEffect(list) {
        var wasScrolling = false
        snapshotFlow { Triple(list.isScrollInProgress, list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset) }
            .collect { (scrolling, index, offset) ->
                // Layout changes alone must not change whether the reader chose to follow new messages.
                if (scrolling || wasScrolling) followingLatest = index == 0 && offset == 0
                wasScrolling = scrolling
            }
    }
    val latest = messages.lastOrNull()
    LaunchedEffect(latest?.id, latest?.text) {
        if (followingLatest && !list.isScrollInProgress && messages.isNotEmpty()) list.scrollToItem(0)
    }
    if (messages.isEmpty()) Text(l.text("还没有聊天记录", "No messages yet", "還沒有聊天記錄"))
    LazyColumn(state = list, reverseLayout = true,
        modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 220.dp).onGloballyPositioned { viewport = it },
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(messages.asReversed(), key = { it.id }) { message ->
            Column(Modifier.fillMaxWidth()) {
                Text(if (message.role == "assistant") "Persona" else l.text("我", "Me", "我"), style = MaterialTheme.typography.labelMedium)
                OverlayMessageText(message.id, voiceInputDisplayText(message.text, message.voiceInput, message.transcriptStatus),
                    acknowledge = message.role == "assistant" && message.id == latestAssistantId,
                    viewport = viewport, clipToViewport = true)
                if (message.delivery.isNotBlank()) Text(l.text("投递：", "Delivery: ", "投遞：") + message.delivery,
                    style = MaterialTheme.typography.labelSmall)
                if (message.audioStatus.isNotBlank()) Text(l.text("语音：", "Audio: ", "語音：") + message.audioStatus,
                    style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    if (!followingLatest) TextButton(onClick = {
        scope.launch { list.scrollToItem(0); followingLatest = true }
    }) { Text(l.text("回到最新消息", "Jump to latest", "回到最新訊息")) }
}

/** All chat surfaces use the same recognized voice text. */
@Composable
internal fun voiceInputDisplayText(text: String, voiceInput: Boolean, transcriptStatus: String): String {
    val l = LocalLanguage.current
    if (!voiceInput || text.isNotBlank()) return text
    return when (transcriptStatus) {
        "uploading" -> l.text("发送语音中…", "Sending voice…", "傳送語音中…")
        "recognizing" -> l.text("正在识别…", "Transcribing…", "正在辨識…")
        else -> l.text("未取得识别结果", "No transcription available", "未取得辨識結果")
    }
}

/** Coordinates are only candidates: clip to the list, then ask the service for actual window visibility. */
@Composable
internal fun OverlayMessageText(messageId: String, text: String, modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE, acknowledge: Boolean = false,
    viewport: LayoutCoordinates? = null, clipToViewport: Boolean = false) {
    val view = LocalView.current
    val confirmPresentation = LocalOverlayPresentation.current
    var coordinates by remember(messageId) { mutableStateOf<LayoutCoordinates?>(null) }
    val currentViewport by rememberUpdatedState(viewport)
    Text(text, maxLines = maxLines, modifier = modifier.onGloballyPositioned { coordinates = it })
    LaunchedEffect(messageId, text, acknowledge, confirmPresentation, clipToViewport) {
        if (!acknowledge || text.isBlank()) return@LaunchedEffect
        withFrameNanos { }; withFrameNanos { }
        while (true) {
            val textBounds = coordinates?.takeIf { it.isAttached }?.boundsInWindow()
            val viewportBounds = currentViewport?.takeIf { it.isAttached }?.boundsInWindow()
            val bounds = if (clipToViewport) {
                if (textBounds != null && viewportBounds != null) textBounds.intersect(viewportBounds) else null
            } else textBounds
            if (bounds != null && !bounds.isEmpty) {
                val screen = IntArray(2); val window = IntArray(2)
                view.getLocationOnScreen(screen); view.getLocationInWindow(window)
                val dx = screen[0] - window[0]; val dy = screen[1] - window[1]
                val region = android.graphics.Rect(floor(bounds.left + dx).toInt(), floor(bounds.top + dy).toInt(),
                    ceil(bounds.right + dx).toInt(), ceil(bounds.bottom + dy).toInt())
                if (confirmPresentation(messageId, region)) break
            }
            delay(250)
        }
    }
}

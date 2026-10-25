package io.github.lejw0925.virtualscreen.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import io.github.lejw0925.virtualscreen.app.AvsrActions
import io.github.lejw0925.virtualscreen.app.AvsrAppState
import io.github.lejw0925.virtualscreen.app.AvsrLiveSignals
import io.github.lejw0925.virtualscreen.app.ConversationItem
import io.github.lejw0925.virtualscreen.ui.LocalLanguage
import io.github.lejw0925.virtualscreen.ui.chatPresentationModifier
import io.github.lejw0925.virtualscreen.ui.design.*
import io.github.lejw0925.virtualscreen.ui.voiceInputDisplayText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun HomeScreen(state: AvsrAppState, actions: AvsrActions, onSettings: () -> Unit, onFinishSetup: (() -> Unit)?) {
    val l = LocalLanguage.current
    val p = LocalYao.current
    val latestHer = state.conversation.lastOrNull { it.role == "assistant" }
    val portrait = herPortrait(state, latestHer?.speechEmotion ?: "neutral")
    Column(Modifier.fillMaxSize().background(p.paper)) {
        if (state.mainScreenIndicatorVisible && state.task.phase == "running_main") {
            val height = with(androidx.compose.ui.platform.LocalDensity.current) { state.mainScreenIndicatorHeightPx.toDp() }
            Spacer(Modifier.statusBarsPadding().height(height))
        } else MainScreenBanner(state, actions, Modifier.statusBarsPadding().align(Alignment.CenterHorizontally))
        Column(Modifier.background(p.card).then(if (state.task.phase == "running_main") Modifier else Modifier.statusBarsPadding())) {
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                val level = (actions as? AvsrLiveSignals)?.voiceLevel?.collectAsState()?.value ?: 0f
                VoiceOrb(portrait, 42.dp, level, speaking = state.voice == "speaking", listening = state.voice == "listening")
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(herName(state), style = YaoType.title, maxLines = 1)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        YaoPill(YaoWords.connection(l, state.connection), dot = true, strong = state.connection == "ready",
                            danger = YaoWords.connectionFailed(state.connection))
                        YaoPill(YaoWords.mode(l, state.mode))
                    }
                }
                // Always reachable, not only during running_virtual: the screen may outlive the task. Read-only preview.
                YaoIconButton(Icons.Rounded.Visibility, l.text("看看虚拟屏", "View virtual screen", "看看虛擬螢幕"), actions::openVirtualScreen,
                    enabled = state.permissions.privilegeReady)
                YaoIconButton(OrbIcon, l.text("收成悬浮球", "Fold into the orb", "收成懸浮球"), actions::minimizeToOverlay)
            }
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(p.hairline))
        Column(Modifier.padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (onFinishSetup != null) YaoNotice(l.text("准备还没做完，她暂时用不了手机。", "Setup isn't finished; she can't use the phone yet.", "準備還沒做完，她暫時用不了手機。"),
                action = l.text("继续准备", "Continue", "繼續準備"), onAction = onFinishSetup)
            if (state.auth.phase == "registration_failed" || YaoWords.connectionFailed(state.connection)) {
                val code = state.auth.reason.ifBlank { state.connection }
                YaoNotice(YaoWords.error(l, code).takeIf { state.auth.reason.isNotBlank() } ?: YaoWords.connection(l, state.connection), code,
                    action = l.text("重试", "Retry", "重試"), onAction = actions::retryRegistration)
            }
            VirtualTaskStrip(state, actions)
            BorrowCard(state, actions)
            HandoffCard(state, actions)
            NativeConfirmationCard(state, actions)
        }
        Conversation(state, actions, Modifier.weight(1f))
        if (state.error.isNotBlank()) YaoNotice(YaoWords.error(l, state.error), state.error, Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
        Composer(state, actions, onSettings)
    }
}

@Composable
private fun Conversation(state: AvsrAppState, actions: AvsrActions, modifier: Modifier) {
    val l = LocalLanguage.current
    val p = LocalYao.current
    val list = rememberLazyListState()
    val latest = state.conversation.lastOrNull()
    LaunchedEffect(latest?.id, latest?.text) { if (state.conversation.isNotEmpty() && list.firstVisibleItemIndex <= 1) list.animateScrollToItem(0) }
    if (state.conversation.isEmpty()) {
        Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            // Her face already sits in the header; the empty page carries only her mark.
            RibbonW(Modifier.width(44.dp), color = p.inkFaint)
            Spacer(Modifier.height(18.dp))
            Text(l.text("想让我做点什么？", "What should I do?", "想讓我做點什麼？"), style = YaoType.title)
            Spacer(Modifier.height(6.dp))
            Text(l.text("打字或点麦克风说话。手机上的事我会在后台做，不打扰你。", "Type or tap the mic. I'll do phone things in the background without getting in your way.",
                "打字或點麥克風說話。手機上的事我會在背景做，不打擾你。"), style = YaoType.small, color = p.inkSoft)
        }
        return
    }
    val herIds = state.conversation.filter { it.role == "assistant" }.map { it.id }.toSet()
    LazyColumn(modifier.fillMaxWidth(), state = list, reverseLayout = true,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val messages = state.conversation.asReversed()
        items(messages.size, key = { messages[it].id }) { i ->
            val message = messages[i]
            // In reverse order the next older message decides whether her portrait repeats.
            val olderSame = messages.getOrNull(i + 1)?.role == message.role
            if (message.id in herIds) HerMessage(message, state, actions, showPortrait = !olderSame)
            else MyMessage(message)
        }
    }
}

private fun clock(ms: Long) = if (ms <= 0) "" else SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(ms))

@Composable
private fun HerMessage(message: ConversationItem, state: AvsrAppState, actions: AvsrActions, showPortrait: Boolean) {
    val l = LocalLanguage.current
    val p = LocalYao.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(Modifier.width(36.dp)) {
            // Each reply keeps the face of the persona that actually sent it.
            if (showPortrait) YaoAvatar(portraitFor(state, message.personaId, message.speechEmotion), 32.dp,
                speaking = message.audioStatus == "playing")
        }
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f, fill = false).widthIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.clip(YaoShapes.herBubble).background(p.herBubble).border(1.dp, p.hairline, YaoShapes.herBubble)
                .padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(message.text, style = YaoType.body, modifier = chatPresentationModifier(message.id, actions))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(clock(message.timestampMs), style = YaoType.number.copy(fontSize = YaoType.small.fontSize * 0.9f), color = p.inkFaint)
                if (message.audioStatus.isNotBlank()) {
                    Text(YaoWords.audio(l, message.audioStatus), style = YaoType.small, color = if (YaoWords.audioFailed(message.audioStatus)) p.rose else p.inkFaint)
                    if (message.audioStatus == "playing") YaoIconButton(Icons.Rounded.Stop, l.text("停止语音", "Stop voice", "停止語音"), { actions.stopVoice(message.id) }, size = 28.dp)
                    else if (message.canReplay) YaoIconButton(Icons.Rounded.PlayArrow, l.text("重放", "Replay", "重播"), { actions.playVoice(message.id) }, size = 28.dp)
                }
            }
        }
    }
}

@Composable
private fun MyMessage(message: ConversationItem) {
    val l = LocalLanguage.current
    val p = LocalYao.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.widthIn(max = 300.dp).clip(YaoShapes.myBubble).background(p.myBubble).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top) {
            if (message.voiceInput) { Icon(Icons.Rounded.GraphicEq, l.text("语音", "Voice", "語音"), Modifier.size(16.dp).padding(top = 3.dp), tint = p.onMyBubble.copy(alpha = 0.7f)); Spacer(Modifier.width(6.dp)) }
            Text(voiceInputDisplayText(message.text, message.voiceInput, message.transcriptStatus), style = YaoType.body, color = p.onMyBubble)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (message.delivery.isNotBlank()) Text(YaoWords.delivery(l, message.delivery), style = YaoType.small,
                color = if (YaoWords.deliveryFailed(message.delivery)) p.rose else p.inkFaint)
            Text(clock(message.timestampMs), style = YaoType.number.copy(fontSize = YaoType.small.fontSize * 0.9f), color = p.inkFaint)
        }
    }
}

@Composable
private fun Composer(state: AvsrAppState, actions: AvsrActions, onSettings: () -> Unit) {
    val l = LocalLanguage.current
    val p = LocalYao.current
    var input by rememberSaveable { mutableStateOf("") }
    var mainScreen by rememberSaveable { mutableStateOf(false) }
    var submitted by remember { mutableStateOf<String?>(null) }
    // Clear only after the controller reports the message as sent, so a failed send keeps the draft.
    LaunchedEffect(state.conversation, state.error) {
        val last = state.conversation.lastOrNull { it.role == "user" }
        if (submitted != null && state.error.isNotBlank()) submitted = null
        else if (submitted != null && last != null && last.text == submitted && last.delivery in setOf("sent", "accepted")) {
            input = ""; mainScreen = false; submitted = null
        }
    }
    val ready = state.connection == "ready"
    val listening = state.voice == "listening"
    Column(Modifier.fillMaxWidth().background(p.card).navigationBarsPadding().imePadding()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.hairline))
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
            Box(Modifier.size(42.dp).clip(CircleShape).background(p.paper).border(1.dp, p.hairline, CircleShape)
                .clickableNoRipple(onSettings), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Tune, l.text("设置", "Settings", "設定"), Modifier.size(20.dp), tint = p.inkSoft)
            }
            Spacer(Modifier.width(8.dp))
            AnimatedContent(listening, Modifier.weight(1f), label = "composer") { recording ->
                if (recording) ListeningBar()
                else Row(Modifier.fillMaxWidth().heightIn(min = 42.dp).clip(YaoShapes.field).background(p.paper)
                    .border(1.dp, if (mainScreen) p.ink else p.hairline, YaoShapes.field).padding(start = 14.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).padding(vertical = 10.dp), contentAlignment = Alignment.CenterStart) {
                        if (input.isEmpty()) Text(if (mainScreen) l.text("这件事她会用你的屏幕做", "She'll use your screen for this", "這件事她會用你的螢幕做")
                            else l.text("告诉她要做什么", "Tell her what to do", "告訴她要做什麼"), style = YaoType.body, color = p.inkFaint)
                        BasicTextField(input, { input = it }, Modifier.fillMaxWidth(), textStyle = YaoType.body.copy(color = p.ink),
                            cursorBrush = SolidColor(p.ink), maxLines = 5)
                    }
                    // Per-message grant to use the main screen; lit when on.
                    Box(Modifier.size(34.dp).clip(CircleShape).background(if (mainScreen) p.ink else p.paper)
                        .clickableNoRipple { mainScreen = !mainScreen }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.PhoneAndroid, l.text("这件用主屏", "Use main screen", "這件用主螢幕"), Modifier.size(18.dp),
                            tint = if (mainScreen) p.onInk else p.inkFaint)
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            when {
                listening -> Row {
                    YaoIconButton(Icons.Rounded.Close, l.text("取消录音", "Cancel", "取消錄音"), { actions.endVoiceInput(false); mainScreen = false })
                    YaoIconButton(Icons.Rounded.ArrowUpward, l.text("说完了，发送", "Send", "說完了，傳送"), { actions.endVoiceInput(true); mainScreen = false }, filled = true)
                }
                input.isNotBlank() -> YaoIconButton(Icons.Rounded.ArrowUpward, l.text("发送", "Send", "傳送"), {
                    if (submitted == null) { submitted = input; actions.sendText(input, mainScreen) }
                }, filled = true, enabled = ready && submitted == null)
                else -> YaoIconButton(Icons.Rounded.Mic, l.text("说话", "Talk", "說話"), { actions.startVoiceInput(mainScreen) }, filled = true, enabled = ready)
            }
        }
    }
}

@Composable
private fun ListeningBar() {
    val l = LocalLanguage.current
    val p = LocalYao.current
    val t = rememberInfiniteTransition(label = "listen")
    Row(Modifier.fillMaxWidth().heightIn(min = 42.dp).clip(YaoShapes.field).background(p.layer).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        repeat(5) { i ->
            val h by t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(520, delayMillis = i * 90, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bar$i")
            Box(Modifier.padding(horizontal = 1.5.dp).width(3.dp).height(18.dp * h).clip(CircleShape).background(p.ink))
        }
        Spacer(Modifier.width(10.dp))
        Text(l.text("在听…说完点发送", "Listening… tap send when done", "在聽…說完點傳送"), style = YaoType.small, color = p.inkSoft)
    }
}

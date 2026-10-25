package io.github.lejw0925.virtualscreen.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.lejw0925.virtualscreen.app.AvsrActions
import io.github.lejw0925.virtualscreen.app.AvsrAppState
import io.github.lejw0925.virtualscreen.app.AvsrLiveSignals
import io.github.lejw0925.virtualscreen.ui.*
import io.github.lejw0925.virtualscreen.ui.design.*
import kotlinx.coroutines.delay

/**
 * Round orb overlay. The window is anchored at its bottom edge on the docked side, so everything
 * here grows upward and toward the screen middle while the orb stays put: requests and her words
 * stack above the orb, and a tap opens one translucent input line beside it.
 *
 * No size animations: each animated frame would resize the overlay window and stutter.
 * Window flags, dragging and recording ownership stay in PhoneFloatingService.
 */
@Composable
internal fun YaoFloatingPanel(state: AvsrAppState, actions: AvsrActions) {
    val l = LocalLanguage.current
    val p = LocalYao.current
    val interaction = LocalFloatingInteraction.current
    val end = interaction?.alignEnd == true
    val expanded = interaction?.expanded == true
    val recording = interaction?.recording == true
    val speaking = state.voice == "speaking"
    val level = (actions as? AvsrLiveSignals)?.voiceLevel?.collectAsState()?.value ?: 0f
    val latestHer = state.conversation.lastOrNull { it.role == "assistant" }

    // Only words that arrive while the orb is showing get a bubble: leaving the app must not
    // replay her last sentence. A bubble stays long enough to read (10 s, plus time for longer text)
    // and the clock restarts whenever the user scrolls it.
    var hiddenId by remember { mutableStateOf<String?>(null) }
    var touchedAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(latestHer?.id, speaking, touchedAt) {
        if (latestHer == null || speaking) return@LaunchedEffect
        delay((10_000L + latestHer.text.length * 150L).coerceAtMost(45_000L)); hiddenId = latestHer.id
    }
    val fresh = latestHer != null && latestHer.timestampMs >= (interaction?.shownAt ?: 0L)
    // User decision 2026-10-25: a voice reply is heard, not shown; its full text stays in the app's chat.
    // audioStatus is set when the text arrives, so this hides before playback instead of flashing first.
    val heard = latestHer != null && latestHer.audioStatus.isNotBlank() && latestHer.audioStatus != "playback_failed"
    val error = interaction?.error.orEmpty()
    val needsYou = state.task.phase in setOf("waiting_borrow", "countdown", "waiting_human", "human_control")

    Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = if (end) Alignment.End else Alignment.Start) {
        FloatingDeviceAuthorization(Modifier.width(272.dp))
        NativeConfirmationCard(state, actions, Modifier.width(272.dp), compact = true)
        if (needsYou || state.task.phase == "running_virtual" || state.task.phase == "running_main")
            Column(Modifier.width(272.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (state.task.phase == "running_main" && !state.mainScreenIndicatorVisible) MainScreenPill(actions)
                BorrowCard(state, actions, compact = true)
                HandoffCard(state, actions, compact = true)
                VirtualTaskStrip(state, actions, compact = true)
            }
        // The tail lands on the orb's centre: orb box is 58 dp + 20 % padding a side.
        val toOrb = Modifier.padding(start = if (end) 0.dp else 30.dp, end = if (end) 30.dp else 0.dp)
        when {
            recording || state.voice == "listening" -> YaoTalkBubble(toOrb, tailEnd = end) {
                Text(if (recording) l.text("在听…松开发送，移开取消", "Listening… release to send, move away to cancel", "在聽…鬆開傳送，移開取消")
                    else l.text("在听…", "Listening…", "在聽…"), style = YaoType.small, color = p.inkSoft)
            }
            error.isNotBlank() -> YaoTalkBubble(toOrb, tailEnd = end, danger = true) {
                Text(YaoWords.error(l, error), style = YaoType.small, color = p.onRoseLayer)
            }
            fresh && latestHer != null && latestHer.id != hiddenId && !heard -> HerWords(latestHer.id, latestHer.text, toOrb, end,
                onTap = { interaction?.expand(true) }, onReadAll = { interaction?.openApp() },
                onScroll = { touchedAt = System.currentTimeMillis() })
            state.connection != "ready" -> YaoTalkBubble(toOrb, tailEnd = end) {
                Text(YaoWords.connection(l, state.connection), style = YaoType.small, color = p.inkSoft)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val orb = @Composable {
                VoiceOrb(herCurrentPortrait(state), 58.dp, level, Modifier.orbGestures(interaction), speaking = speaking,
                    listening = recording || state.voice == "listening", dimmed = state.connection != "ready")
            }
            val line = @Composable { if (expanded) InputLine(state, actions) }
            if (end) { line(); orb() } else { orb(); line() }
        }
    }
}

/**
 * Her written reply above the orb. Nothing is cut off: past about seven lines the text scrolls inside the bubble,
 * the lower edge fades to show there is more, and a link under it opens the whole reply in the app.
 * The presentation receipt covers only the part actually on screen (clipToViewport).
 */
@Composable
private fun HerWords(id: String, text: String, modifier: Modifier, tailEnd: Boolean,
    onTap: () -> Unit, onReadAll: () -> Unit, onScroll: () -> Unit) {
    val l = LocalLanguage.current
    val p = LocalYao.current
    val scroll = rememberScrollState()
    var viewport by remember(id) { mutableStateOf<LayoutCoordinates?>(null) }
    val long = scroll.maxValue > 0
    LaunchedEffect(scroll.isScrollInProgress) { if (scroll.isScrollInProgress) onScroll() }
    YaoTalkBubble(modifier.clickableNoRipple(onTap), tailEnd = tailEnd, wide = long) {
        Column {
            Box(Modifier.heightIn(max = 128.dp).onGloballyPositioned { viewport = it }) {
                CompositionLocalProvider(LocalTextStyle provides YaoType.small, LocalContentColor provides p.ink) {
                    OverlayMessageText(id, text.take(4_000), Modifier.verticalScroll(scroll), acknowledge = true,
                        viewport = viewport, clipToViewport = true)
                }
                if (long && scroll.value < scroll.maxValue) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(28.dp)
                    .background(Brush.verticalGradient(listOf(p.card.copy(alpha = 0f), p.card))))
            }
            if (long) Row(Modifier.padding(top = 6.dp).clip(YaoShapes.pill).clickableNoRipple(onReadAll)
                .padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(l.text("往下滑，或打开看全部", "Scroll, or open to read all", "往下滑，或打開看全部"), style = YaoType.small, color = p.inkSoft)
                Icon(Icons.Rounded.ChevronRight, null, Modifier.size(16.dp), tint = p.inkSoft)
            }
        }
    }
}

/** Tap toggles the input line, double-tap opens the app, hold to talk. Drag is handled by the window. */
private fun Modifier.orbGestures(interaction: FloatingInteraction?) = if (interaction == null) this else
    pointerInput(interaction) {
        detectTapGestures(onTap = { interaction.expand(!interaction.expanded) }, onDoubleTap = { interaction.openApp() },
            onLongPress = { interaction.beginVoice() },
            onPress = {
                var released = false
                try { released = tryAwaitRelease() } finally { interaction.finishVoice(released) }
            })
    }

@Composable
private fun MainScreenPill(actions: AvsrActions) {
    val l = LocalLanguage.current
    val p = LocalYao.current
    Row(Modifier.clip(YaoShapes.pill).background(p.ink).padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(l.text("正在用你的屏幕", "Using your screen", "正在用你的螢幕"), style = YaoType.small, color = p.onInk)
        TextButton(actions::stopTask, colors = ButtonDefaults.textButtonColors(contentColor = p.onInk), contentPadding = PaddingValues(horizontal = 8.dp)) {
            Icon(Icons.Rounded.Stop, null, Modifier.size(15.dp)); Text(l.text("停止", "Stop", "停止"), style = YaoType.label)
        }
    }
}

/** One translucent line: type and send, or tap the mic to talk without holding. */
@Composable
private fun InputLine(state: AvsrAppState, actions: AvsrActions) {
    val l = LocalLanguage.current
    val p = LocalYao.current
    val interaction = LocalFloatingInteraction.current
    var draft by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf<String?>(null) }
    // Same per-message grant as the app's composer: this one message may use the screen in your hand.
    // Only offered in background mode; global mode already allows it. Resets after each send.
    var mainScreen by remember { mutableStateOf(false) }
    LaunchedEffect(state.conversation, state.error, submitted) {
        val last = state.conversation.lastOrNull { it.role == "user" }
        if (submitted != null && state.error.isNotBlank()) submitted = null
        else if (submitted != null && last != null && last.text == submitted && last.delivery in setOf("sent", "accepted")) { draft = ""; submitted = null; mainScreen = false }
    }
    val ready = state.connection == "ready"
    val recording = interaction?.recording == true
    val offerMainScreen = state.mode != "global" && !recording
    fun send() { if (submitted == null && draft.isNotBlank() && ready) { submitted = draft; actions.sendText(draft, mainScreen && offerMainScreen) } }
    Row(Modifier.padding(horizontal = 4.dp).width(236.dp).height(46.dp).clip(YaoShapes.pill)
        .background(p.card.copy(alpha = 0.86f)).border(1.dp, if (mainScreen && offerMainScreen) p.ink else p.hairline, YaoShapes.pill)
        .padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (draft.isEmpty()) Text(if (recording) l.text("在听…再点一下发送", "Listening… tap again to send", "在聽…再點一下傳送")
                else if (mainScreen && offerMainScreen) l.text("这件可以用你的屏幕", "This one may use your screen", "這件可以用你的螢幕")
                else l.text("和她说…", "Say something…", "和她說…"), style = YaoType.small, color = p.inkFaint, maxLines = 1)
            BasicTextField(draft, { draft = it }, Modifier.fillMaxWidth(), textStyle = YaoType.small.copy(color = p.ink),
                cursorBrush = SolidColor(p.ink), singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }))
        }
        if (offerMainScreen) YaoIconButton(Icons.Rounded.PhoneAndroid, l.text("这件用主屏", "Use main screen", "這件用主螢幕"),
            { mainScreen = !mainScreen }, filled = mainScreen, size = 32.dp)
        when {
            draft.isNotBlank() -> YaoIconButton(Icons.Rounded.ArrowUpward, l.text("发送", "Send", "傳送"), { send() },
                filled = true, enabled = ready && submitted == null, size = 38.dp)
            recording -> YaoIconButton(Icons.Rounded.ArrowUpward, l.text("说完了，发送", "Send", "說完了，傳送"),
                { interaction?.finishVoice(true) }, filled = true, size = 38.dp)
            else -> YaoIconButton(Icons.Rounded.Mic, l.text("说话", "Talk", "說話"), { interaction?.beginVoice() },
                filled = true, enabled = ready, size = 38.dp)
        }
    }
}

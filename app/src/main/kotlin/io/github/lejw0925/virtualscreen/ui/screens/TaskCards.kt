package io.github.lejw0925.virtualscreen.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.lejw0925.virtualscreen.app.AvsrActions
import io.github.lejw0925.virtualscreen.app.AvsrAppState
import io.github.lejw0925.virtualscreen.ui.LocalLanguage
import io.github.lejw0925.virtualscreen.ui.design.*
import kotlinx.coroutines.delay

/**
 * Persistent while she holds the main screen. A small pill like the virtual-screen strip, not a full-width bar,
 * so the top of the app underneath stays usable. One line only; the step text is left to the app's own screen.
 * It wraps its own width: in the global window the window itself should wrap too, or its transparent sides
 * would still take the touches meant for the app below. Stop acts immediately; no confirmation in between.
 */
@Composable
internal fun MainScreenBanner(state: AvsrAppState, actions: AvsrActions, modifier: Modifier = Modifier) {
    if (state.task.phase != "running_main") return
    val l = LocalLanguage.current
    val p = LocalYao.current
    Box(modifier.padding(top = 4.dp, start = 8.dp, end = 8.dp, bottom = 4.dp)) {
        Row(Modifier.widthIn(max = 280.dp).height(32.dp).clip(YaoShapes.pill).background(p.ink)
            .padding(start = 12.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(p.onInk))
            Spacer(Modifier.width(8.dp))
            val who = herName(state)
            val label = listOf(l.text("$who 在用你的屏幕", "$who is using your screen", "$who 在用你的螢幕"), state.task.appName)
                .filter { it.isNotBlank() }.joinToString(" · ")
            Text(label, style = YaoType.small, color = p.onInk, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            TextButton(actions::stopTask, colors = ButtonDefaults.textButtonColors(contentColor = p.onInk),
                contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.height(32.dp)) {
                Icon(Icons.Rounded.Stop, null, Modifier.size(15.dp)); Spacer(Modifier.width(2.dp))
                Text(l.text("停止", "Stop", "停止"), style = YaoType.label)
            }
        }
    }
}

/** Quiet strip for background work on the virtual screen. */
@Composable
internal fun VirtualTaskStrip(state: AvsrAppState, actions: AvsrActions, modifier: Modifier = Modifier, compact: Boolean = false) {
    if (state.task.phase != "running_virtual") return
    val l = LocalLanguage.current
    val p = LocalYao.current
    Row(modifier.fillMaxWidth().clip(YaoShapes.field).background(p.layer).padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(14.dp), color = p.inkSoft, strokeWidth = 1.5.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(listOf(state.task.appName.ifBlank { l.text("虚拟屏", "Virtual screen", "虛擬螢幕") }, state.task.step)
                .filter { it.isNotBlank() }.joinToString(" · "), style = YaoType.small, maxLines = if (compact) 1 else 2)
        }
        if (!compact) YaoIconButton(Icons.Rounded.Visibility, l.text("看看虚拟屏", "View virtual screen", "看看虛擬螢幕"), actions::openVirtualScreen, size = 36.dp)
        YaoIconButton(Icons.Rounded.Stop, l.text("停止", "Stop", "停止"), actions::stopTask, size = 36.dp)
    }
}

/** "等你决定": three answers, a visible deadline, and nothing happens on the main screen without a tap. */
@Composable
internal fun BorrowCard(state: AvsrAppState, actions: AvsrActions, modifier: Modifier = Modifier, compact: Boolean = false) {
    val task = state.task
    if (task.phase !in setOf("waiting_borrow", "countdown")) return
    val l = LocalLanguage.current
    val p = LocalYao.current
    YaoCard(modifier, lace = !compact, padding = if (compact) 14.dp else 18.dp) {
        if (task.phase == "waiting_borrow") {
            val total = state.borrowTimeoutSeconds.coerceAtLeast(1)
            var remaining by remember(task.deadlineMs) { mutableLongStateOf(total.toLong()) }
            LaunchedEffect(task.deadlineMs) {
                while (true) { remaining = ((task.deadlineMs - System.currentTimeMillis()) / 1000).coerceAtLeast(0); delay(250) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(l.text("后台做不了，借你的屏幕一下？", "Can't do this in the background. Borrow your screen?", "背景做不了，借你的螢幕一下？"),
                        style = if (compact) YaoType.bodyStrong else YaoType.section)
                    val why = listOf(task.appName, task.reason).filter { it.isNotBlank() }.joinToString(" · ")
                    if (why.isNotBlank()) Text(why, style = YaoType.small, color = p.inkSoft, maxLines = 2)
                }
                Spacer(Modifier.width(12.dp))
                CountdownRing(remaining.toInt(), total, if (compact) 44.dp else 56.dp)
            }
            YaoButton(l.text("这次借给你", "Just this once", "這次借給你"), { actions.respondBorrow(task.id, "this_once") }, Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                YaoOutlineButton(l.text("以后都可以用", "Always allow", "以後都可以用"), { actions.respondBorrow(task.id, "always") }, Modifier.weight(1f))
                YaoOutlineButton(l.text("先不用", "Not now", "先不用"), { actions.respondBorrow(task.id, "decline") }, Modifier.weight(1f))
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(l.text("马上开始用你的屏幕", "About to use your screen", "馬上開始用你的螢幕"), style = YaoType.bodyStrong)
                    Text(l.text("现在取消还来得及", "You can still cancel", "現在取消還來得及"), style = YaoType.small, color = p.inkSoft)
                }
                Text("${task.countdownSeconds}", style = YaoType.bigNumber)
            }
            YaoButton(l.text("取消", "Cancel", "取消"), { actions.cancelCountdown(task.id) }, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun CountdownRing(remaining: Int, total: Int, size: Dp) {
    val p = LocalYao.current
    val fraction by animateFloatAsState((remaining.toFloat() / total).coerceIn(0f, 1f), label = "countdown")
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val w = 2.5.dp.toPx()
            drawArc(p.layer, 0f, 360f, false, style = Stroke(w))
            drawArc(p.ink, -90f, 360f * fraction, false, style = Stroke(w, cap = StrokeCap.Round))
        }
        Text("$remaining", style = YaoType.number.copy(fontSize = YaoType.number.fontSize * 1.25f))
    }
}

/**
 * A device permission her task is waiting on. Only these taps answer it; what is said in chat never does,
 * so the card says so. Allow and deny get equal width: neither is the default.
 */
@Composable
internal fun DeviceAuthorizationCard(question: String, choices: List<String>, sending: Boolean, onAnswer: (String) -> Unit,
    modifier: Modifier = Modifier, compact: Boolean = false) {
    val l = LocalLanguage.current
    val p = LocalYao.current
    YaoCard(modifier, lace = !compact, padding = if (compact) 14.dp else 20.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(if (compact) 30.dp else 36.dp).clip(CircleShape).background(p.ink), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.VerifiedUser, null, Modifier.size(if (compact) 16.dp else 19.dp), tint = p.onInk)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(l.text("要你点头的事", "Needs your OK", "要你點頭的事"), style = if (compact) YaoType.bodyStrong else YaoType.section)
                Text(l.text("只认这里的点按，聊天里说的不算", "Only a tap here counts, not chat", "只認這裡的點按，聊天裡說的不算"),
                    style = YaoType.small, color = p.inkSoft)
            }
        }
        Text(question, style = if (compact) YaoType.small else YaoType.body, maxLines = if (compact) 4 else Int.MAX_VALUE)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            choices.sortedBy { it == "允许" }.forEach { choice ->
                if (choice == "允许") YaoButton(choice, { onAnswer(choice) }, Modifier.weight(1f), busy = sending)
                else YaoOutlineButton(choice, { onAnswer(choice) }, Modifier.weight(1f), enabled = !sending)
            }
        }
    }
}

/** She paused and needs the user's hands on the original virtual screen. */
@Composable
internal fun HandoffCard(state: AvsrAppState, actions: AvsrActions, modifier: Modifier = Modifier, compact: Boolean = false) {
    val task = state.task
    if (task.phase !in setOf("waiting_human", "human_control")) return
    val l = LocalLanguage.current
    val p = LocalYao.current
    YaoCard(modifier, lace = !compact, padding = if (compact) 14.dp else 18.dp) {
        Text(l.text("这一步需要你来", "This step needs you", "這一步需要你來"), style = if (compact) YaoType.bodyStrong else YaoType.section)
        if (task.handoffReason.isNotBlank()) Text(task.handoffReason, style = YaoType.body)
        Text(l.text("她已经停手等你。打开原来的虚拟屏处理，好了再交还给她，她会重新看一遍。",
            "She has paused. Open the same virtual screen, then hand it back; she will look again.",
            "她已經停手等你。開啟原來的虛擬螢幕處理，好了再交還給她，她會重新看一遍。"), style = YaoType.small, color = p.inkSoft)
        YaoButton(if (task.phase == "waiting_human") l.text("我来处理", "I'll handle it", "我來處理") else l.text("回到接管画面", "Back to the screen", "回到接管畫面"),
            { actions.acceptHumanHandoff(task.id, task.handoffId.orEmpty()) }, Modifier.fillMaxWidth())
        YaoTextButton(l.text("这件事不做了", "Drop this task", "這件事不做了"), { actions.cancelTask(task.id) }, danger = true)
    }
}

/**
 * Phone actions waiting for the user's own tap on the system-tools page (today: alarms). This card only
 * shows what is waiting and opens that page; sending or declining still happens there, never here.
 * Internal ids stay out of the wording.
 */
@Composable
internal fun NativeConfirmationCard(state: AvsrAppState, actions: AvsrActions, modifier: Modifier = Modifier, compact: Boolean = false) {
    val items = state.nativeConfirmations
    if (items.isEmpty()) return
    val l = LocalLanguage.current
    val p = LocalYao.current
    YaoCard(modifier, padding = if (compact) 14.dp else 18.dp) {
        Text(l.text("等你在手机上确认", "Waiting for your confirmation", "等你在手機上確認"), style = if (compact) YaoType.bodyStrong else YaoType.section)
        items.forEach { item ->
            val time = "%02d:%02d".format(item.hour, item.minute)
            val what = listOf(l.text("闹钟 ", "Alarm ", "鬧鐘 ") + time, item.label).filter { it.isNotBlank() }.joinToString(" · ")
            val phase = if (item.phase == "dispatching") l.text("正在交给时钟", "Handing to the clock", "正在交給時鐘")
                else l.text("还没发送", "Not sent yet", "還沒傳送")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(what, style = YaoType.body, modifier = Modifier.weight(1f), maxLines = 1)
                Text(phase, style = YaoType.small, color = p.inkSoft)
            }
        }
        Text(l.text("要不要设，由你在确认页决定；她不能替你点。", "You decide on the confirmation page; she can't tap it for you.",
            "要不要設，由你在確認頁決定；她不能替你點。"), style = YaoType.small, color = p.inkFaint)
        if (items.any { it.phase == "pending" })
            YaoButton(l.text("去确认", "Review", "去確認"), { actions.openPermission("system_tools") }, Modifier.fillMaxWidth())
    }
}

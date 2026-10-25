package io.github.lejw0925.virtualscreen.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.github.lejw0925.virtualscreen.app.AvsrActions
import io.github.lejw0925.virtualscreen.app.AvsrAppState
import io.github.lejw0925.virtualscreen.ui.LocalLanguage
import io.github.lejw0925.virtualscreen.ui.design.*

private val steps = listOf("login", "pairing", "permissions", "mode")

/** Order fixed by the package: login → pairing and privilege → other permissions → usage mode. */
@Composable
internal fun OnboardingScreen(state: AvsrAppState, actions: AvsrActions, onSkipToChat: (() -> Unit)?) {
    val l = LocalLanguage.current
    val p = LocalYao.current
    val step = state.onboardingStep.takeIf { it in steps } ?: "mode"
    val index = steps.indexOf(step)
    Column(Modifier.fillMaxSize().background(p.paper).statusBarsPadding().navigationBarsPadding().imePadding()
        .verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RibbonW(Modifier.width(40.dp))
            Spacer(Modifier.weight(1f))
            StepDots(index, steps.size)
        }
        AnimatedContent(step, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "step") { current ->
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stepNumber(index), style = YaoType.number, color = p.inkFaint)
                    Text(when (current) {
                        "login" -> l.text("先认识一下", "Let's meet", "先認識一下")
                        "pairing" -> l.text("让她能用手机", "Let her use the phone", "讓她能用手機")
                        "permissions" -> l.text("还差几项许可", "A few more permissions", "還差幾項許可")
                        else -> l.text("她该怎么用你的屏幕", "How she may use your screen", "她該怎麼用你的螢幕")
                    }, style = YaoType.display)
                    Text(when (current) {
                        "login" -> l.text("填宿主地址和账号。账号在宿主的手机插件设置里创建。", "Enter the host address and the account created in the host's phone plugin settings.", "填宿主位址和帳號。帳號在宿主的手機外掛設定裡建立。")
                        "pairing" -> l.text("用系统自带的无线调试配对一次，内置服务就能启动，不用另装应用。", "Pair once with the system's Wireless debugging to start the built-in service. No other app needed.", "用系統內建的無線偵錯配對一次，內置服務就能啟動，不用另裝應用程式。")
                        "permissions" -> l.text("无障碍、悬浮窗和麦克风是必需的，通知用于配对和提醒。", "Accessibility, overlay and microphone are required; notifications are used for pairing and alerts.", "無障礙、懸浮窗和麥克風是必需的，通知用於配對和提醒。")
                        else -> l.text("之后随时能在设置里改，只影响之后的任务。", "You can change this later; it applies to future tasks.", "之後隨時能在設定裡改，只影響之後的任務。")
                    }, style = YaoType.body, color = p.inkSoft)
                }
                LaceDivider()
                when (current) {
                    "login" -> LoginPanel(state, actions)
                    "pairing" -> PairingPanel(state, actions)
                    "permissions" -> PermissionPanel(state, actions)
                    else -> {
                        ModePanel(state, actions)
                        AvatarSetPanel(state, actions)
                        YaoButton(l.text("好了，开始吧", "Done, let's start", "好了，開始吧"), actions::completeOnboarding, Modifier.fillMaxWidth())
                    }
                }
            }
        }
        if (state.error.isNotBlank()) YaoNotice(YaoWords.error(l, state.error), state.error)
        if (onSkipToChat != null && step != "login") {
            YaoTextButton(l.text("先去聊天，稍后再准备", "Chat first, finish setup later", "先去聊天，稍後再準備"), onSkipToChat,
                Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

private fun stepNumber(i: Int) = "0${i + 1} / 04"

@Composable
private fun StepDots(current: Int, count: Int) {
    val p = LocalYao.current
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i ->
            Box(Modifier.size(if (i == current) 9.dp else 7.dp).clip(CircleShape)
                .background(if (i <= current) p.ink else p.paper).border(1.dp, if (i <= current) p.ink else p.hairline, CircleShape))
        }
    }
}

@Composable
internal fun LoginPanel(state: AvsrAppState, actions: AvsrActions) {
    val l = LocalLanguage.current
    val p = LocalYao.current
    var host by remember(state.auth.host) { mutableStateOf(state.auth.host) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val busy = state.auth.phase == "logging_in" || state.auth.phase == "registering"
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        YaoField(host, { host = it }, l.text("宿主地址", "Host address", "宿主位址"), placeholder = "https://")
        YaoField(username, { username = it }, l.text("账号", "Account", "帳號"))
        YaoField(password, { password = it }, l.text("密码", "Password", "密碼"), password = true)
        if (state.auth.phase !in setOf("signed_out", "")) Text(YaoWords.auth(l, state.auth.phase), style = YaoType.small, color = p.inkSoft)
        if (state.auth.reason.isNotBlank()) YaoNotice(YaoWords.error(l, state.auth.reason), state.auth.reason,
            action = if (state.auth.retryable) l.text("重试注册", "Retry", "重試註冊") else null, onAction = actions::retryRegistration)
        YaoButton(l.text("登录", "Sign in", "登入"), {
            // The password never outlives the tap; the host issues a device token instead.
            actions.login(host.trim(), username.trim(), password); password = ""
        }, Modifier.fillMaxWidth(), enabled = host.isNotBlank() && username.isNotBlank() && password.isNotBlank(), busy = busy)
    }
}

@Composable
internal fun PairingPanel(state: AvsrAppState, actions: AvsrActions) {
    val l = LocalLanguage.current
    val p = LocalYao.current
    var manual by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        YaoCard {
            YaoPill(YaoWords.privilege(l, state.permissions.privilegeStatus), dot = true, strong = state.permissions.privilegeReady)
            NumberedLine(1, l.text("在“关于手机”里连续点按版本号，打开开发者选项。", "In About phone, tap Build number repeatedly to enable Developer options.", "在「關於手機」裡連續點按版本號，開啟開發者選項。"))
            NumberedLine(2, l.text("点下面的按钮，系统会打开无线调试；选“使用配对码配对设备”。", "Tap below; in Wireless debugging choose Pair device with pairing code.", "點下面的按鈕，系統會開啟無線偵錯；選「使用配對碼配對裝置」。"))
            NumberedLine(3, l.text("保持那一页别关，下拉通知栏，在本应用的通知里填六位配对码。", "Keep that page open, pull down notifications and enter the six-digit code in this app's notification.", "保持那一頁別關，下拉通知欄，在本應用程式的通知裡填六位配對碼。"))
            YaoButton(l.text("开始配对", "Start pairing", "開始配對"), { actions.openPermission("pairing") }, Modifier.fillMaxWidth())
        }
        YaoSwitchRow(l.text("开机后自动恢复", "Restore after restart", "開機後自動恢復"),
            l.text("同一 Wi-Fi 下重启后不用接 USB。", "No USB needed after a restart on the same Wi-Fi.", "同一 Wi-Fi 下重開機後不用接 USB。"),
            state.permissions.bootRecovery, actions::setBootRecovery)
        if (state.permissions.paired && !state.permissions.privilegeReady)
            YaoOutlineButton(l.text("恢复已配对的服务", "Restore paired service", "恢復已配對的服務"), actions::restorePrivilege, Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth().clip(YaoShapes.field).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            YaoTextButton(l.text("系统切走后配对页会关闭？手动填写", "Pairing page closes when switching apps? Enter manually", "系統切走後配對頁會關閉？手動填寫"), { manual = !manual })
            Icon(if (manual) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = p.inkSoft)
        }
        AnimatedVisibility(manual) { ManualPairing(actions) }
    }
}

@Composable
private fun ManualPairing(actions: AvsrActions) {
    val l = LocalLanguage.current
    var host by remember { mutableStateOf("127.0.0.1") }
    var port by remember { mutableStateOf("") }
    var connectPort by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    YaoCard {
        YaoField(host, { host = it }, l.text("本机地址", "This phone's address", "本機位址"))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            YaoField(port, { port = it.filter(Char::isDigit) }, l.text("配对端口", "Pairing port", "配對連接埠"), Modifier.weight(1f), number = true)
            YaoField(code, { code = it.filter(Char::isDigit).take(6) }, l.text("六位配对码", "6-digit code", "六位配對碼"), Modifier.weight(1f), password = true, number = true)
        }
        YaoField(connectPort, { connectPort = it.filter(Char::isDigit) }, l.text("连接端口（可留空）", "Connection port (optional)", "連線連接埠（可留空）"), number = true)
        YaoButton(l.text("配对并启动", "Pair and start", "配對並啟動"), {
            actions.pair(host, port.toInt(), code, connectPort.toIntOrNull()); code = ""
        }, Modifier.fillMaxWidth(), enabled = port.toIntOrNull() != null && code.length == 6)
    }
}

@Composable
private fun NumberedLine(n: Int, text: String) {
    val p = LocalYao.current
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.size(22.dp).clip(CircleShape).border(1.dp, p.hairline, CircleShape), contentAlignment = Alignment.Center) {
            Text("$n", style = YaoType.number, color = p.inkSoft)
        }
        Spacer(Modifier.width(10.dp))
        Text(text, style = YaoType.body, modifier = Modifier.weight(1f))
    }
}

@Composable
internal fun PermissionPanel(state: AvsrAppState, actions: AvsrActions, includeOptional: Boolean = true) {
    val l = LocalLanguage.current
    val ps = state.permissions
    val go = l.text("去开启", "Turn on", "去開啟")
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        YaoStatusRow(l.text("无障碍", "Accessibility", "無障礙"), l.text("让她读懂和操作虚拟屏上的界面", "Lets her read and operate the virtual screen", "讓她讀懂和操作虛擬螢幕上的介面"),
            ps.accessibility, go.takeUnless { ps.accessibility }) { actions.openPermission("accessibility") }
        YaoStatusRow(l.text("悬浮窗", "Floating panel", "懸浮窗"), l.text("离开应用后也能看到她的回复", "See her replies after leaving the app", "離開應用程式後也能看到她的回覆"),
            ps.overlay, go.takeUnless { ps.overlay }) { actions.openPermission("overlay") }
        YaoStatusRow(l.text("麦克风", "Microphone", "麥克風"), l.text("按住说话", "Talk to her", "按住說話"),
            ps.microphone, go.takeUnless { ps.microphone }) { actions.openPermission("microphone") }
        YaoStatusRow(l.text("通知", "Notifications", "通知"), l.text("配对码输入和任务提醒", "Pairing code entry and task alerts", "配對碼輸入和任務提醒"),
            ps.notifications, go.takeUnless { ps.notifications }) { actions.openPermission("notifications") }
        if (includeOptional) {
            YaoStatusRow(l.text("默认数字助理（可选）", "Default assistant (optional)", "預設數位助理（可選）"),
                l.text("长按电源键或手势直接叫她", "Summon her with the assistant gesture", "長按電源鍵或手勢直接叫她"),
                false, l.text("设置", "Set", "設定")) { actions.openPermission("assistant") }
            YaoStatusRow(l.text("不限制后台（建议）", "Unrestricted battery (suggested)", "不限制背景（建議）"),
                l.text("避免系统在后台断开她", "Keeps the system from cutting her off", "避免系統在背景斷開她"),
                false, l.text("设置", "Set", "設定")) { actions.openPermission("battery") }
        }
    }
}

@Composable
internal fun ModePanel(state: AvsrAppState, actions: AvsrActions) {
    val l = LocalLanguage.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        YaoChoice(l.text("后台", "Background", "背景"),
            l.text("只在看不见的虚拟屏上做事。需要你的屏幕时会先问你，${state.borrowTimeoutSeconds} 秒没回应就算了。",
                "Works only on an invisible virtual screen. Asks before using your screen; gives up after ${state.borrowTimeoutSeconds}s without an answer.",
                "只在看不見的虛擬螢幕上做事。需要你的螢幕時會先問你，${state.borrowTimeoutSeconds} 秒沒回應就算了。"),
            state.mode != "global", { actions.setUsageMode("background") }, badge = l.text("默认", "Default", "預設"))
        YaoChoice(l.text("全局", "Global", "全域"),
            l.text("可以直接用你的屏幕。用的时候顶部会一直提示，随时能停。", "May use your screen directly. A banner stays on top and you can stop any time.", "可以直接用你的螢幕。用的時候頂部會一直提示，隨時能停。"),
            state.mode == "global", { actions.setUsageMode("global") })
    }
}

@Composable
internal fun AvatarSetPanel(state: AvsrAppState, actions: AvsrActions) {
    if (state.avatarSets.size < 2) return
    val l = LocalLanguage.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        YaoSectionTitle(l.text("头像套组", "Portrait set", "頭像套組"))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            state.avatarSets.forEach { set ->
                val selected = set.id == state.selectedAvatarSet
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    YaoAvatar(set.images["neutral"], 56.dp, Modifier.clip(CircleShape)
                        .then(if (selected) Modifier.border(2.dp, LocalYao.current.ink, CircleShape) else Modifier)
                        .clickableNoRipple { actions.selectAvatarSet(set.id) })
                    Text(set.name, style = YaoType.small)
                }
            }
        }
    }
}

@Composable
internal fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.clickable(remember { MutableInteractionSource() }, null, onClick = onClick)

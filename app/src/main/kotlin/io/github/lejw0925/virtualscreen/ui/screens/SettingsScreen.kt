package io.github.lejw0925.virtualscreen.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.lejw0925.virtualscreen.app.AvsrActions
import io.github.lejw0925.virtualscreen.app.AvsrAppState
import io.github.lejw0925.virtualscreen.ui.LocalLanguage
import io.github.lejw0925.virtualscreen.ui.design.*

@Composable
internal fun SettingsScreen(state: AvsrAppState, actions: AvsrActions, onBack: () -> Unit) {
    val l = LocalLanguage.current
    val p = LocalYao.current
    var pairing by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(p.paper).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            YaoIconButton(Icons.AutoMirrored.Rounded.ArrowBack, l.text("返回", "Back", "返回"), onBack)
            Spacer(Modifier.width(6.dp))
            Text(l.text("设置", "Settings", "設定"), style = YaoType.title, modifier = Modifier.weight(1f))
            RibbonW(Modifier.width(30.dp).padding(end = 6.dp), color = p.inkFaint)
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (state.error.isNotBlank()) YaoNotice(YaoWords.error(l, state.error), state.error)

            // Simple switches as one-line segmented rows; the longer explanations live in onboarding.
            SettingRow(l.text("用你的屏幕", "Your screen", "用你的螢幕"),
                if (state.mode == "global") l.text("她可以直接用主屏", "She may use the main screen directly", "她可以直接用主螢幕")
                else l.text("平时只在后台，要用主屏先问你", "Background only; asks before using the main screen", "平時只在背景，要用主螢幕先問你")) {
                YaoSegmented(listOf("background" to l.text("先问我", "Ask first", "先問我"), "global" to l.text("直接用", "Allowed", "直接用")),
                    if (state.mode == "global") "global" else "background", actions::setUsageMode)
            }
            if (state.mode != "global") SettingRow(l.text("等你回答", "Wait for you", "等你回答"), null) {
                YaoSegmented(listOf(15, 30, 60).map { it to l.text("$it 秒", "${it}s", "$it 秒") }, state.borrowTimeoutSeconds, actions::setBorrowTimeoutSeconds)
            }
            SettingRow(l.text("她怎么回你", "How she replies", "她怎麼回你"), null) {
                YaoSegmented(listOf("auto" to l.text("她来定", "Her call", "她來定"), "voice" to l.text("要语音", "Voice", "要語音"),
                    "text_only" to l.text("只要文字", "Text", "只要文字")), state.replyPreference, actions::setReplyPreference)
            }
            YaoSwitchRow(l.text("不用按住说话", "Hands-free", "不用按住說話"),
                if (state.handsFreeAvailable) l.text("她一直听着，你说完停一下就发出去", "She keeps listening and sends when you pause", "她一直聽著，你說完停一下就送出去")
                else l.text("这一版还没有，更新后可用", "Not in this version yet", "這一版還沒有，更新後可用"),
                state.handsFree, actions::setHandsFree, enabled = state.handsFreeAvailable)
            YaoSwitchRow(l.text("离开应用后显示她", "Show her when you leave the app", "離開應用程式後顯示她"),
                l.text("单击打字，双击回应用，按住说话", "Tap to type, double-tap for the app, hold to talk", "單擊打字，雙擊回應用程式，按住說話"),
                state.overlayEnabled, actions::setOverlay)
            LaceDivider()
            // Faces are kept per persona; hosts that send no persona id keep the old set picker.
            AvatarEditor(state, actions)
            LaceDivider()

            YaoSectionTitle(l.text("手机能力", "Phone access", "手機能力"))
            YaoCard {
                YaoStatusRow(l.text("内置特权服务", "Built-in privileged service", "內置特權服務"), YaoWords.privilege(l, state.permissions.privilegeStatus),
                    state.permissions.privilegeReady, l.text(if (pairing) "收起" else "配对", if (pairing) "Hide" else "Pair", if (pairing) "收起" else "配對")) { pairing = !pairing }
                if (pairing) PairingPanel(state, actions)
                else if (state.permissions.paired && !state.permissions.privilegeReady)
                    YaoOutlineButton(l.text("恢复已配对的服务", "Restore paired service", "恢復已配對的服務"), actions::restorePrivilege, Modifier.fillMaxWidth())
                YaoOutlineButton(l.text("看看虚拟屏", "View the virtual screen", "看看虛擬螢幕"), actions::openVirtualScreen, Modifier.fillMaxWidth(), icon = Icons.Rounded.Visibility)
            }
            PermissionPanel(state, actions)
            LaceDivider()

            YaoSectionTitle(l.text("账号", "Account", "帳號"))
            YaoCard {
                Text(state.auth.host.ifBlank { "—" }, style = YaoType.number.copy(fontSize = YaoType.body.fontSize))
                if (state.auth.deviceId.isNotBlank()) Text(l.text("设备 ", "Device ", "裝置 ") + state.auth.deviceId, style = YaoType.small, color = p.inkSoft)
                YaoPill(YaoWords.auth(l, state.auth.phase), dot = true, danger = state.auth.phase == "registration_failed")
                if (state.auth.reason.isNotBlank()) YaoNotice(YaoWords.error(l, state.auth.reason), state.auth.reason,
                    action = if (state.auth.retryable) l.text("重试", "Retry", "重試") else null, onAction = actions::retryRegistration)
                YaoOutlineButton(l.text("退出登录", "Sign out", "登出"), { confirmLogout = true }, Modifier.fillMaxWidth(), icon = Icons.AutoMirrored.Rounded.Logout, danger = true)
            }
            Column(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                RibbonW(Modifier.width(26.dp), color = p.inkFaint)
                Text(l.text("我想给她生命 · 手机插件篇", "I Want to Give Her Life · Phone", "我想給她生命 · 手機插件篇"), style = YaoType.small, color = p.inkSoft)
                Text("v${io.github.lejw0925.virtualscreen.BuildConfig.VERSION_NAME} · " + l.text("开发者 white", "by white", "開發者 white"),
                    style = YaoType.number.copy(fontSize = YaoType.small.fontSize), color = p.inkFaint)
            }
        }
    }
    // Signing out withdraws her phone tools on the host; worth one confirmation.
    if (confirmLogout) AlertDialog(onDismissRequest = { confirmLogout = false },
        title = { Text(l.text("退出登录？", "Sign out?", "登出？"), style = YaoType.section) },
        text = { Text(l.text("退出后她就用不了这台手机，宿主上的手机工具会撤下。", "She will lose access to this phone and its tools will be removed from the host.", "登出後她就用不了這台手機，宿主上的手機工具會撤下。")) },
        confirmButton = { YaoTextButton(l.text("退出", "Sign out", "登出"), { confirmLogout = false; actions.logout() }, danger = true) },
        dismissButton = { YaoTextButton(l.text("算了", "Cancel", "算了"), { confirmLogout = false }) },
        containerColor = p.card, shape = YaoShapes.card)
}

/** Title (and an optional one-line hint) above a full-width control. */
@Composable
private fun SettingRow(title: String, hint: String?, control: @Composable () -> Unit) {
    val p = LocalYao.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(title, style = YaoType.bodyStrong)
            if (hint != null) Text("  " + hint, style = YaoType.small, color = p.inkFaint, maxLines = 1)
        }
        Box(Modifier.fillMaxWidth()) { control() }
    }
}

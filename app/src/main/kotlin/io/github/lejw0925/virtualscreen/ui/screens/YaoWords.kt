package io.github.lejw0925.virtualscreen.ui.screens

import io.github.lejw0925.virtualscreen.ui.UiLanguage
import io.github.lejw0925.virtualscreen.ui.localizedError

/** Product wording for contract states; raw diagnostics remain in the underlying state. */
internal object YaoWords {
    const val NAME_HANS = "望月遥"

    fun connection(l: UiLanguage, value: String) = when (value) {
        "ready" -> l.text("在线", "Online", "在線")
        "connecting", "tunnel_connecting", "starting_local_mcp" -> l.text("连接中", "Connecting", "連線中")
        "tunnel_reconnecting" -> l.text("正在重新连接", "Reconnecting", "正在重新連線")
        "registering" -> l.text("正在注册工具", "Registering tools", "正在註冊工具")
        "auth_required" -> l.text("需要登录", "Sign-in needed", "需要登入")
        "", "disconnected" -> l.text("未连接", "Offline", "未連線")
        "connection_failed", "host_error", "mcp_unavailable" -> l.text("连接失败", "Connection failed", "連線失敗")
        else -> l.text("连接状态待确认", "Connection status unavailable", "連線狀態待確認")
    }
    fun connectionFailed(value: String) = value in setOf("connection_failed", "host_error", "mcp_unavailable")

    fun mode(l: UiLanguage, value: String) = when (value) {
        "global" -> l.text("全局", "Global", "全域")
        else -> l.text("后台", "Background", "背景")
    }

    fun auth(l: UiLanguage, phase: String) = when (phase) {
        "logging_in" -> l.text("正在登录…", "Signing in…", "正在登入…")
        "registering" -> l.text("登录成功，正在注册手机工具…", "Signed in. Registering phone tools…", "登入成功，正在註冊手機工具…")
        "registration_failed" -> l.text("工具注册没有成功", "Tool registration failed", "工具註冊沒有成功")
        "signed_out" -> l.text("未登录", "Signed out", "未登入")
        else -> l.text("已登录", "Signed in", "已登入")
    }

    fun privilege(l: UiLanguage, value: String) = when (value) {
        "embedded_ready" -> l.text("内置特权服务已运行", "Built-in privileged service running", "內置特權服務已執行")
        "embedded_ready_manual_wireless_after_reboot" -> l.text("内置服务已运行；手机重启后需手动打开无线调试", "Built-in service running; enable Wireless debugging manually after restarting the phone", "內建服務已執行；手機重新啟動後需手動開啟無線偵錯")
        "pairing_notification_searching" -> l.text("正在查找配对窗口，请保持系统配对码页面打开", "Looking for the pairing dialog; keep the system pairing-code page open", "正在尋找配對視窗，請保持系統配對碼頁面開啟")
        "pairing_notification_code_required" -> l.text("等待你在通知里输入配对码", "Waiting for the pairing code in the notification", "等待你在通知裡輸入配對碼")
        "pairing_notification_stopped" -> l.text("配对引导已关闭，可点“开始配对”重新打开", "Pairing setup closed; tap Start pairing to reopen it", "配對引導已關閉，可點「開始配對」重新開啟")
        "pairing" -> l.text("正在配对…", "Pairing…", "正在配對…")
        "pairing_required" -> l.text("需要先完成无线调试配对", "Wireless debugging pairing needed", "需要先完成無線偵錯配對")
        "paired_connecting", "restoring" -> l.text("已配对，正在连接…", "Paired, connecting…", "已配對，正在連線…")
        "starting_embedded_service" -> l.text("正在启动内置特权服务…", "Starting the built-in service…", "正在啟動內置特權服務…")
        "enable_wireless_debugging_manually" -> l.text("请在开发者选项里打开“无线调试”", "Turn on Wireless debugging in Developer options", "請在開發者選項裡開啟「無線偵錯」")
        "automatic_recovery_off" -> l.text("开机自动恢复已关闭", "Restore after restart is off", "開機自動恢復已關閉")
        "", "idle" -> l.text("尚未开始", "Not started", "尚未開始")
        else -> if (value.startsWith("manual_action_required:"))
            error(l, value.removePrefix("manual_action_required:"))
        else l.text("内置服务状态待确认", "Built-in service status unavailable", "內建服務狀態待確認")
    }

    fun taskPhase(l: UiLanguage, phase: String) = when (phase) {
        "running_virtual" -> l.text("在后台的虚拟屏上做事", "Working on the virtual screen", "在背景的虛擬螢幕上做事")
        "running_main" -> l.text("遥正在用你的屏幕", "Yao is using your screen", "遙正在用你的螢幕")
        "waiting_borrow" -> l.text("等你决定", "Waiting for you", "等你決定")
        "countdown" -> l.text("即将使用主屏", "About to use your screen", "即將使用主螢幕")
        "waiting_human", "human_control" -> l.text("这一步需要你", "This step needs you", "這一步需要你")
        else -> phase
    }

    fun delivery(l: UiLanguage, value: String) = when (value) {
        "pending" -> l.text("发送中", "Sending", "傳送中")
        "sent" -> l.text("已发送", "Sent", "已傳送")
        "accepted" -> l.text("她收到了", "Received", "她收到了")
        "failed" -> l.text("没发出去", "Not sent", "沒發出去")
        "discarded" -> l.text("已作废", "Discarded", "已作廢")
        else -> value
    }
    fun deliveryFailed(value: String) = value == "failed" || value == "discarded"

    fun audio(l: UiLanguage, value: String) = when (value) {
        "playing" -> l.text("正在说", "Speaking", "正在說")
        "pending" -> l.text("语音准备中", "Voice loading", "語音準備中")
        "stopped" -> l.text("语音已停", "Voice stopped", "語音已停")
        "failed", "playback_failed" -> l.text("语音没能播放", "Voice failed", "語音沒能播放")
        "complete", "completed", "played" -> l.text("说完了", "Played", "說完了")
        else -> value
    }
    fun audioFailed(value: String) = value == "failed" || value == "playback_failed"

    /** Product-level codes get plain sentences; MCP codes reuse the shared table. */
    fun error(l: UiLanguage, code: String): String = when (code) {
        "PHONE_NOT_READY" -> l.text("还没连上宿主，等连接恢复再发。", "Not connected to the host yet.", "還沒連上宿主，等連線恢復再傳。")
        "EMPTY_INPUT" -> l.text("先写点什么吧。", "Write something first.", "先寫點什麼吧。")
        "INPUT_NOT_SENT" -> l.text("这条没有发出去，可以再试一次。", "This message was not sent. Try again.", "這則沒有發出去，可以再試一次。")
        "MICROPHONE_PERMISSION_REQUIRED" -> l.text("需要麦克风权限才能说话。", "Microphone permission is needed.", "需要麥克風權限才能說話。")
        "OVERLAY_PERMISSION_REQUIRED" -> l.text("需要悬浮窗权限。", "Overlay permission is needed.", "需要懸浮窗權限。")
        "OPEN_APP_FOR_PERMISSION" -> l.text("请先回到应用里再授权。", "Open the app to grant this.", "請先回到應用程式裡再授權。")
        "ENABLE_DEVELOPER_OPTIONS_FIRST" -> l.text("先在“关于手机”里连续点按版本号，打开开发者选项。", "Tap Build number in About phone to enable Developer options first.", "先在「關於手機」裡連續點按版本號，開啟開發者選項。")
        "PAIRING_NOTIFICATION_PERMISSION_REQUIRED" -> l.text("配对码要在通知里输入，需要通知权限。", "Pairing uses a notification; allow notifications.", "配對碼要在通知裡輸入，需要通知權限。")
        "SETUP_INCOMPLETE" -> l.text("还有准备步骤没完成。", "Some setup steps are not finished.", "還有準備步驟沒完成。")
        "LOGIN_REQUIRED" -> l.text("需要重新登录。", "Please sign in again.", "需要重新登入。")
        "DEVICE_CREDENTIAL_REJECTED" -> l.text("这台设备的登录已被宿主撤销，请重新登录。", "The host revoked this device. Sign in again.", "這台裝置的登入已被宿主撤銷，請重新登入。")
        "CONNECTION_RESTORE_FAILED" -> l.text("没能恢复和宿主的连接。", "Could not restore the host connection.", "沒能恢復和宿主的連線。")
        "ALREADY_RECORDING" -> l.text("正在录音中。", "Already recording.", "正在錄音中。")
        "PHONE_HANDOFF_STALE" -> l.text("这次交接已经变了，请看最新状态。", "This handoff changed; check the latest state.", "這次交接已經變了，請看最新狀態。")
        "APP_MINIMIZE_FAILED" -> l.text("没能切到悬浮窗。", "Could not switch to the floating panel.", "沒能切到懸浮窗。")
        "OVERLAY_NOT_VISIBLE" -> l.text("悬浮窗被挡住了，没有开始录音。", "The panel is covered; recording did not start.", "懸浮窗被擋住了，沒有開始錄音。")
        "VOICE_INPUT_NOT_STARTED", "MICROPHONE_FOREGROUND_START_FAILED" -> l.text("录音没有开始。", "Recording did not start.", "錄音沒有開始。")
        "OVERLAY_WINDOW_UPDATE_FAILED" -> l.text("悬浮窗没能展开。", "The panel could not expand.", "懸浮窗沒能展開。")
        "ACTION_FAILED" -> l.text("这一步没有完成。", "That did not complete.", "這一步沒有完成。")
        else -> localizedError(l, code)
    }
}

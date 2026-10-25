package io.github.lejw0925.virtualscreen.systemtools

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.lejw0925.virtualscreen.ui.localizedError
import io.github.lejw0925.virtualscreen.ui.uiLanguage

/** Opened only by a phone user's APK button. onCreate/onResume never request or dispatch. */
class SystemToolsPermissionActivity : Activity() {
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 24, 32, 24) }
        setContentView(ScrollView(this).apply { addView(content) })
    }

    override fun onResume() { super.onResume(); render() }

    private fun render() {
        content.removeAllViews()
        text(t("系统工具权限", "System tool permissions", "系統工具權限"))
        text(t("日历权限只在点击下方按钮后申请。待确认的闹钟只在你点击该条的发送按钮后交给系统时钟；系统仍可能打开时钟界面。", "Calendar permission is requested only when you tap below. An alarm is sent to the system clock only when you tap its Send button. The clock may still open its interface.", "日曆權限只在點按下方按鈕後申請。待確認的鬧鐘只在你點按該筆的傳送按鈕後交給系統時鐘；系統仍可能開啟時鐘介面。"))
        permissionButton(Manifest.permission.READ_CALENDAR, t("允许读取日历", "Allow reading calendars", "允許讀取日曆"), 410)
        permissionButton(Manifest.permission.WRITE_CALENDAR, t("允许创建日程", "Allow creating events", "允許建立日程"), 411)
        button(t("打开应用权限设置", "Open app permission settings", "開啟應用程式權限設定")) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        button(t("刷新待确认请求", "Refresh pending requests", "重新整理待確認請求")) { render() }
        val pending = AlarmRequests.pending()
        text(t("等待你确认的闹钟", "Alarms awaiting your approval", "等待你確認的鬧鐘"))
        if (pending.isEmpty()) text(t("没有待确认请求。重启或断线后，旧的待确认请求失效。", "No pending requests. Pending requests expire after process restart or disconnection.", "沒有待確認請求。重新啟動或斷線後，舊的待確認請求失效。"))
        for ((token, request) in pending) {
            text("%02d:%02d  %s".format(request.hour, request.minute, request.label))
            text(t("重复日（1周日—7周六；空表示单次）：", "Repeat days (1 Sunday–7 Saturday; empty means once): ", "重複日（1週日—7週六；空表示單次）：") + request.days.joinToString())
            button(t("发送这个闹钟到系统时钟", "Send this alarm to the system clock", "傳送這個鬧鐘到系統時鐘")) {
                // Disabling immediately and the module's atomic consume both prevent double dispatch.
                it.isEnabled = false
                val result = AlarmRequests.dispatchFromUserClick(this, token)
                render()
                val language = uiLanguage(resources.configuration.locales[0])
                text(systemToolMessage(language, result.code) ?: localizedError(language, result.code ?: "BACKEND_OPERATION_FAILED"))
            }
            button(t("拒绝这个闹钟", "Decline this alarm", "拒絕這個鬧鐘")) { AlarmRequests.cancel(token); render() }
        }
    }

    private fun permissionButton(permission: String, label: String, requestCode: Int) {
        val granted = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        button(label + if (granted) t("（已允许）", " (allowed)", "（已允許）") else "") { requestPermissions(arrayOf(permission), requestCode) }.isEnabled = !granted
    }

    @Deprecated("Android callback")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
        if (grantResults.any { it != PackageManager.PERMISSION_GRANTED }) text(t("权限未开启；可以继续用其他功能，也可在应用权限设置中开启。", "Permission is not enabled. Other features remain available; you can enable it in app permission settings.", "權限未開啟；可以繼續使用其他功能，也可在應用程式權限設定中開啟。"))
    }

    private fun text(value: String) { content.addView(TextView(this).apply { text = value; setPadding(0, 12, 0, 12) }) }
    private fun button(label: String, action: (Button) -> Unit): Button = Button(this).also { button -> button.text = label; button.setOnClickListener { action(button) }; content.addView(button) }
    private fun t(zh: String, en: String, tw: String): String {
        val locale = resources.configuration.locales[0]
        return if (locale.language != "zh") en else if (locale.script == "Hant" || locale.country in setOf("TW", "HK", "MO")) tw else zh
    }
}

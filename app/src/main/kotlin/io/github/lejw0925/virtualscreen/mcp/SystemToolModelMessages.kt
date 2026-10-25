package io.github.lejw0925.virtualscreen.mcp

import io.github.lejw0925.virtualscreen.ui.UiLanguage

/** Model-facing receipts, separate from the phone UI copy. */
object SystemToolModelMessages {
    fun message(l: UiLanguage, code: String?): String? = when (code) {
        "ALARM_PHONE_CONFIRMATION_REQUIRED" -> l.text(
            "还没发送。需要用户本人在手机 APK 的系统工具页确认发送；执行端不能代点，也不用去打开那个页面。",
            "Not sent yet. The user must confirm sending in the phone APK's system tools page; the executor cannot tap for the user and does not need to open that page.",
            "還沒傳送。需要使用者本人在手機 APK 的系統工具頁確認傳送；執行端不能代點，也不用去開啟那個頁面。")
        "ALARM_REQUEST_UNKNOWN" -> l.text(
            "查不到原请求记录，无法确认是否已发出。工具只能读取下一次响铃，读不到完整闹钟列表，不能据此确认这个闹钟不存在。需要用户在系统时钟里核对；用户确认之前，不要重新登记同一个闹钟。",
            "The original request record is unavailable, so whether it was sent cannot be confirmed. The tool reads only the next alarm, not the complete alarm list; this cannot establish that this alarm is absent. The user needs to check the system clock. Do not register the same alarm again before the user confirms.",
            "查不到原請求記錄，無法確認是否已發出。工具只能讀取下一次響鈴，讀不到完整鬧鐘清單，不能據此確認這個鬧鐘不存在。需要使用者在系統時鐘裡核對；使用者確認之前，不要重新登記同一個鬧鐘。")
        "ALARM_DISPATCHED_UNCONFIRMED" -> l.text(
            "已发给系统时钟，但尚未确认目标闹钟已保存。工具只能读取下一次响铃，读不到完整闹钟列表，不能据此确认这个闹钟存在或不存在。需要用户在系统时钟里核对；用户确认之前，不要重新登记同一个闹钟。",
            "Sent to the system clock, but saving the target alarm has not been confirmed. The tool reads only the next alarm, not the complete alarm list; this cannot establish whether this alarm exists. The user needs to check the system clock. Do not register the same alarm again before the user confirms.",
            "已發給系統時鐘，但尚未確認目標鬧鐘已儲存。工具只能讀取下一次響鈴，讀不到完整鬧鐘清單，不能據此確認這個鬧鐘存在或不存在。需要使用者在系統時鐘裡核對；使用者確認之前，不要重新登記同一個鬧鐘。")
        "ALARM_DISPATCH_UNCONFIRMED" -> l.text(
            "这次派发结果未知，无法确认是否已发出或系统时钟是否已接收。工具只能读取下一次响铃，读不到完整闹钟列表，不能据此确认这个闹钟不存在。需要用户在系统时钟里核对；用户确认之前，不要重新登记同一个闹钟。",
            "The dispatch outcome is unknown; whether it was sent or received by the system clock cannot be confirmed. The tool reads only the next alarm, not the complete alarm list; this cannot establish that this alarm is absent. The user needs to check the system clock. Do not register the same alarm again before the user confirms.",
            "這次派發結果未知，無法確認是否已發出或系統時鐘是否已接收。工具只能讀取下一次響鈴，讀不到完整鬧鐘清單，不能據此確認這個鬧鐘不存在。需要使用者在系統時鐘裡核對；使用者確認之前，不要重新登記同一個鬧鐘。")
        else -> null
    }
}

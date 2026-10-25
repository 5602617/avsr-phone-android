package io.github.lejw0925.virtualscreen.systemtools

import io.github.lejw0925.virtualscreen.ui.UiLanguage

/** Specific system-tool receipt reasons; other domains retain their existing localization. */
fun systemToolMessage(l: UiLanguage, code: String?): String? = when (code) {
    "ALARM_PHONE_CONFIRMATION_REQUIRED" -> l.text(
        "还没发送。请在 APK 的系统工具权限页查看这个闹钟并点击发送，系统可能打开时钟界面。",
        "Not sent yet. Review this alarm in the APK's system tool permissions page and tap Send. The clock may open its interface.",
        "還沒傳送。請在 APK 的系統工具權限頁查看這個鬧鐘並點按傳送，系統可能開啟時鐘介面。")
    "ALARM_DISPATCHED_UNCONFIRMED" -> l.text(
        "已派发，未确认。只能读取下一次响铃，无法确认对应条目；请在系统时钟核对，不要自动重发。",
        "Dispatched, unconfirmed. Only the next alarm can be read; this alarm's identity cannot be confirmed. Check the system clock without automatically sending again.",
        "已派發，未確認。只能讀取下一次響鈴，無法確認對應項目；請在系統時鐘核對，不要自動重傳。")
    "ALARM_REQUEST_UNKNOWN" -> l.text(
        "无法回查这个请求，不能确定是否已派发。请核对系统时钟，不要自动重发。",
        "The request is unavailable; dispatch is unknown. Check the system clock before sending again.",
        "無法回查這個請求，不能確定是否已派發。請核對系統時鐘，不要自動重傳。")
    "ALARM_DISPATCH_IN_PROGRESS" -> l.text(
        "这次闹钟正在派发，结果尚未确认。请回查同一请求，不要重复发送。",
        "This alarm is being dispatched; the outcome is unconfirmed. Check the same request without sending again.",
        "這次鬧鐘正在派發，結果尚未確認。請回查同一請求，不要重複傳送。")
    "ALARM_DISPATCH_UNCONFIRMED" -> l.text(
        "这次派发结果未知，不能确定系统时钟是否已接收。请先核对系统时钟，不要自动重发。",
        "Dispatch is unknown; the clock may have received it. Check the system clock before sending again.",
        "這次派發結果未知，不能確定系統時鐘是否已接收。請先核對系統時鐘，不要自動重傳。")
    "ALARM_REQUEST_INVALIDATED" -> l.text(
        "这个待确认请求已因连接或手机操作状态变化失效，没有发送。",
        "This pending request expired after a connection or phone execution state change; it was not sent.",
        "這個待確認請求已因連線或手機操作狀態變更失效，沒有傳送。")
    "ALARM_CLIENT_DISCONNECTED" -> l.text(
        "原请求的连接已断开，这个闹钟没有发送。",
        "The original request's client disconnected; this alarm was not sent.",
        "原請求的連線已中斷，這個鬧鐘沒有傳送。")
    "ALARM_USER_DECLINED" -> l.text(
        "用户在手机上拒绝了这个闹钟，没有发送。",
        "The user declined this alarm on the phone; it was not sent.",
        "使用者在手機上拒絕了這個鬧鐘，沒有傳送。")
    "ALARM_APP_UNAVAILABLE" -> l.text(
        "没有可接收设置闹钟请求的时钟应用，这个闹钟没有发送。",
        "No clock application can handle the alarm request; it was not sent.",
        "沒有可接收設定鬧鐘請求的時鐘應用程式，這個鬧鐘沒有傳送。")
    "ALARM_INTENT_DENIED" -> l.text(
        "系统拒绝了设置闹钟请求，没有发送。请检查应用的设置闹钟权限。",
        "Android denied the alarm request; it was not sent. Check the app's alarm-setting permission.",
        "系統拒絕了設定鬧鐘請求，沒有傳送。請檢查應用程式的設定鬧鐘權限。")
    "ALARM_AUTHORIZATION_UNAVAILABLE" -> l.text(
        "无法核对这个请求当前的手机授权，没有发送。",
        "The request's current phone authorization could not be checked; it was not sent.",
        "無法核對這個請求目前的手機授權，沒有傳送。")
    "ALARM_REQUEST_ID_CONFLICT" -> l.text(
        "这个请求 ID 已用于另一组闹钟参数，本次没有发送。请先回查原请求。",
        "This request ID already has different alarm parameters; nothing was sent. Check the original request first.",
        "這個請求 ID 已用於另一組鬧鐘參數，本次沒有傳送。請先回查原請求。")
    "ALARM_CONFIRMATION_QUEUE_FULL" -> l.text(
        "待用户确认的闹钟请求已满，本次没有登记或发送。请先处理手机上的待确认请求。",
        "Pending alarm confirmations are full; this request was not registered or sent. Review the pending requests on the phone first.",
        "待使用者確認的鬧鐘請求已滿，本次沒有登記或傳送。請先處理手機上的待確認請求。")
    "INVALID_ALARM_TIME" -> l.text(
        "闹钟时分或重复星期无效，没有发送。重复星期为 1 周日至 7 周六。",
        "The alarm time or repeating weekdays are invalid; nothing was sent. Weekdays run from 1 Sunday through 7 Saturday.",
        "鬧鐘時分或重複星期無效，沒有傳送。重複星期為 1 週日至 7 週六。")
    "NEXT_ALARM_READ_FAILED" -> l.text(
        "这次无法读取系统报告的下一次闹钟，没有修改闹钟。",
        "The next system alarm could not be read. No alarm was changed.",
        "這次無法讀取系統回報的下一次鬧鐘，沒有修改鬧鐘。")
    "CALENDAR_PERMISSION_REQUIRED" -> l.text(
        "日历权限尚不可用，操作没有执行。请用户在 APK 的系统工具权限页点击开启，拒绝后可到应用权限设置调整。",
        "Calendar permission is unavailable; the operation was not executed. The user can enable it from the APK's system tool permissions page or app permission settings.",
        "日曆權限尚不可用，操作沒有執行。請使用者在 APK 的系統工具權限頁點按開啟，拒絕後可至應用程式權限設定調整。")
    "CALENDAR_READ_FAILED" -> l.text(
        "这次无法读取手机日历，没有修改日程。请检查系统日历是否可用。",
        "The phone calendar could not be read. No event was changed. Check whether the system calendar is available.",
        "這次無法讀取手機日曆，沒有修改日程。請檢查系統日曆是否可用。")
    "CALENDAR_EVENT_UNCONFIRMED" -> l.text(
        "系统已返回条目 URI，但读回未能确认全部字段。请按 URI 核对，不要自动再建一条。",
        "An event URI was returned, but its stored fields could not be confirmed. Check that URI without creating again.",
        "系統已傳回項目 URI，但讀回未能確認全部欄位。請按 URI 核對，不要自動再建立一筆。")
    "CALENDAR_INSERT_UNCONFIRMED" -> l.text(
        "这次写入结果未知，不能确定是否已创建。请先核对日历，不要自动重建。",
        "The write outcome is unknown. Check the calendar before creating again.",
        "這次寫入結果未知，不能確定是否已建立。請先核對日曆，不要自動重建。")
    "INVALID_EVENT_INTERVAL" -> l.text(
        "日程结束时间须晚于开始时间，本次没有执行。请核对毫秒时间值。",
        "The event end must be after its start; nothing was executed. Check the millisecond timestamps.",
        "日程結束時間須晚於開始時間，本次沒有執行。請核對毫秒時間值。")
    "INVALID_EVENT_TIME_ZONE" -> l.text(
        "日程时区名称无效，本次没有创建。请使用有效的 IANA 时区名称。",
        "The time zone name is invalid; no event was created. Use a valid IANA time zone name.",
        "日程時區名稱無效，本次沒有建立。請使用有效的 IANA 時區名稱。")
    "ALL_DAY_REQUIRES_UTC_MIDNIGHT" -> l.text(
        "全天日程须使用 UTC 时区和 UTC 零点边界，结束日期不包含在内；本次没有创建。",
        "All-day events require the UTC time zone and UTC midnight boundaries with an exclusive end date; no event was created.",
        "全天日程須使用 UTC 時區和 UTC 零點邊界，結束日期不包含在內；本次沒有建立。")
    "PHONE_DISCONNECTED" -> l.text(
        "手机连接已断开，操作没有执行。请在 APK 重新连接后查看当前状态。",
        "The phone is disconnected; the operation was not executed. Reconnect in the APK and check its current state.",
        "手機連線已中斷，操作沒有執行。請在 APK 重新連線後查看目前狀態。")
    else -> null
}

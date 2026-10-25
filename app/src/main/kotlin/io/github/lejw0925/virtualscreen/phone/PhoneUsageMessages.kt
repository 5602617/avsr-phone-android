package io.github.lejw0925.virtualscreen.phone

import io.github.lejw0925.virtualscreen.ui.UiLanguage

/** Three-language tool/receipt drafts; visual wording may be refined with the UI. */
fun phoneUsageMessage(language: UiLanguage, code: String?): String? = when (code) {
    "PHONE_TASK_BINDING_REQUIRED" -> language.text(
        "缺少宿主确认的当前任务标识，未操作手机。", "The host has not bound this call to the current task; no phone operation was performed.", "缺少宿主確認的目前任務識別，未操作手機。")
    "PHONE_TASK_BUSY" -> language.text(
        "手机仍在处理前一件事，请先结束它。", "The phone is still handling the previous task; finish it first.", "手機仍在處理前一件事，請先結束它。")
    "BACKGROUND_MAIN_SCREEN_NOT_ALLOWED" -> language.text(
        "后台模式下，这件事尚未获准使用主屏。", "This background task has no permission to use the main screen.", "背景模式下，這件事尚未獲准使用主螢幕。")
    "MAIN_SCREEN_BORROW_PENDING" -> language.text(
        "这一步在后台虚拟屏做不了，原因见 original_failure_code；常见的 APP_RESTART_CONFIRMATION_REQUIRED 是这个应用正开在用户的屏幕上，不能同时在虚拟屏再开一份，并不是应用不能用。手机上已经在问用户要不要借用主屏，还没操作主屏；用 phone_get_status 查用户的选择，选之前不要说做成或做不成，也不要改用电脑。",
        "This step cannot run on the background virtual screen; see original_failure_code. The common APP_RESTART_CONFIRMATION_REQUIRED means the app is open on the user's screen and cannot open a second copy on the virtual screen; the app itself still works. The phone is asking the user whether to lend the main screen, and nothing has been done on it yet. Check the choice with phone_get_status; until then do not report success or failure, and do not switch to the computer.",
        "這一步在背景虛擬螢幕做不了，原因見 original_failure_code；常見的 APP_RESTART_CONFIRMATION_REQUIRED 是這個應用正開在使用者的螢幕上，不能同時在虛擬螢幕再開一份，並不是應用不能用。手機上已經在問使用者要不要借用主螢幕，還沒操作主螢幕；用 phone_get_status 查使用者的選擇，選之前不要說做成或做不成，也不要改用電腦。")
    "NOTIFICATION_ACCESS_REQUIRED" -> language.text(
        "QQ 音乐的专用工具需要“通知使用权”，现在没开，所以这两个工具用不了；这不是手机没有操作权限，屏幕上操作 QQ 音乐仍然可以。这次委托没有说可以改在屏幕上做时，先结束这一步，把缺这项权限交回：在系统设置里给本应用打开通知使用权就能用，或者由用户决定改在屏幕上操作。已经说可以走屏幕时就继续，结果里仍写明缺这项权限。不要改用电脑。",
        "QQ Music's dedicated tools need notification access, which is off, so they cannot be used; this is not a lack of permission to operate the phone, and operating QQ Music on the screen still works. If this delegation did not say the screen may be used instead, end this step and report the missing permission: granting this app notification access in system settings enables the tools, or the user can choose screen operation. If the screen was already allowed, continue and still state the missing permission in the result. Do not switch to the computer.",
        "QQ 音樂的專用工具需要「通知使用權」，現在沒開，所以這兩個工具用不了；這不是手機沒有操作權限，螢幕上操作 QQ 音樂仍然可以。這次委託沒有說可以改在螢幕上做時，先結束這一步，把缺這項權限交回：在系統設定裡給本應用開啟通知使用權就能用，或者由使用者決定改在螢幕上操作。已經說可以走螢幕時就繼續，結果裡仍寫明缺這項權限。不要改用電腦。")
    "MAIN_SCREEN_COUNTDOWN" -> language.text(
        "你已同意借用主屏，三秒倒数结束后才能操作；现在仍可取消。", "You agreed to lend the main screen. Operation begins after the three-second countdown; you can still cancel.", "你已同意借用主螢幕，三秒倒數結束後才能操作；現在仍可取消。")
    "MAIN_SCREEN_DECLINED" -> language.text(
        "你没有借出主屏，这件事已停止。", "You did not lend the main screen; this task has stopped.", "你沒有借出主螢幕，這件事已停止。")
    "MAIN_SCREEN_NO_RESPONSE" -> language.text(
        "等待期间没有收到你的选择，这件事已停止，不再追问。", "No choice arrived within the waiting period. This task has stopped and will not ask again.", "等待期間沒有收到你的選擇，這件事已停止，不再追問。")
    "USER_INTERRUPTED" -> language.text(
        "你已中途停止这件事，主屏授权已收回。", "You stopped this task; its main-screen permission has been revoked.", "你已中途停止這件事，主螢幕授權已收回。")
    "MAIN_SCREEN_MODE_ACTIVE" -> language.text(
        "本任务已获准使用主屏，请通过主屏工具继续并重新观察。", "This task is authorized for the main screen. Continue through the main-screen tools and observe again.", "本任務已獲准使用主螢幕，請透過主螢幕工具繼續並重新觀察。")
    "PHONE_BORROW_REQUEST_STALE", "PHONE_TASK_LEASE_CHANGED", "PHONE_TASK_CHANGED", "LEASE_ENDED" -> language.text(
        "这次任务或控制权已结束，旧的主屏同意已失效。", "This task or control lease has ended; its previous main-screen consent is no longer valid.", "這次任務或控制權已結束，舊的主螢幕同意已失效。")
    else -> null
}

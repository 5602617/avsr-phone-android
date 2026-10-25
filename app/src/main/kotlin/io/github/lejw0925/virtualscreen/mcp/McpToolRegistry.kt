package io.github.lejw0925.virtualscreen.mcp

import android.graphics.Bitmap
import android.util.Base64
import io.github.lejw0925.virtualscreen.backend.*
import io.github.lejw0925.virtualscreen.core.*
import io.github.lejw0925.virtualscreen.ui.UiLanguage
import io.github.lejw0925.virtualscreen.ui.localizedError
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

data class McpTool(val name: String,val description: String,val schema: JSONObject,val readOnly: Boolean = false)
data class ToolActivity(val id: String,val owner: String,val tool: String,val purpose: String,val phase: String,val code: String? = null)

class McpToolRegistry(private val sessions: VirtualScreenSessionManager,
    private val approvals: ControlApprovalManager = ControlApprovalManager(sessions),
    private val clientName: (String) -> String = { "MCP client" },
    private val qqMusic: io.github.lejw0925.virtualscreen.media.QqMusicController? = null,
    private val phone: io.github.lejw0925.virtualscreen.phone.PhoneSession? = null,
    private val glance: io.github.lejw0925.virtualscreen.phone.PhoneGlance? = null,
    private val mainScreen: io.github.lejw0925.virtualscreen.phone.MainScreenControl? = null,
    private val systemTools: io.github.lejw0925.virtualscreen.systemtools.PhoneSystemTools? = null,
    private val language: () -> UiLanguage = { sessions.systemLanguage }) {
    private val defaultOperationObservation = "tree"
    private val current=MutableStateFlow<ToolActivity?>(null)
    val activity=current.asStateFlow()
    private val receipts=RequestReceipts()
    fun forgetClient(owner: String) { receipts.forget(owner); approvals.forgetClient(owner); systemTools?.invalidatePendingAlarmRequests(owner) }
    private val lease=mapOf("lease_token" to string())
    private val observed=lease+("observation_id" to string())
    private val after=mapOf("observe_after" to choice("none","tree","screenshot","both").put("default",defaultOperationObservation),"max_image_width" to integer(320,2048))
    private val createAfter=after+("observe_after" to choice("none","tree","screenshot","both").put("default","none"))
    private val selector=JSONObject().put("type","object").put("minProperties",1).put("additionalProperties",false)
        .put("properties",JSONObject(mapOf("resource_id" to string(),"text" to string(),"description" to string(),"class" to string())))
    private val display=mapOf("width" to integer(320,4096),"height" to integer(480,4096),"density" to integer(120,640),"rotation" to integer(0,3),
        "allow_screen_off" to boolean(),"idle_timeout_minutes" to integer(0,240),"match_main_display" to boolean())
    private val xy=mapOf("x" to integer(0,4095),"y" to integer(0,4095))
    // Generate descriptions for every tools/list so a system language change takes effect immediately.
    val tools: List<McpTool> get() {
        val l=language()
        fun t(h: String,e: String,z: String)=l.text(h,e,z)
        return listOf(
            tool(l,"acquire_control",t("申请独占控制权，无需 lease_token。每个带租约的操作自动续期，闲置五分钟释放控制并收起远程画中画。身份绑定当前 MCP 会话。","Acquire exclusive control for this MCP session. No lease_token is needed. Each lease-authenticated operation refreshes the five-minute idle deadline; expiry releases control and hides the remote PiP.","申請獨佔控制權，無需 lease_token。每個帶租約的操作自動續期，閒置五分鐘釋放控制並收起遠端子母畫面。身分繫結目前 MCP 工作階段。")),
            tool(l,"release_control",t("释放当前会话的控制权并收起远程画中画，使旧租约和观察失效。","Release this session's control, hide the remote PiP, and invalidate its lease and observations.","釋放目前工作階段的控制權並收起遠端子母畫面，使舊租約和觀察失效。"),lease,lease.keys),
            tool(l,"request_control",t("控制权被手机或其他 Agent 占用时，发送通知申请接管。用户可允许一次或始终允许；立即返回 pending，不阻塞服务。用 action=status 和 approval_id 每秒查询，获批后使用新 lease_token 并重新观察。始终允许仅自动接管手机占用，不能绕过其他 Agent 的审批。它只处理虚拟屏控制权被占用的情况；获批不等于借到了主屏，主屏能否使用以 phone_get_status 的 main_screen_allowed 为准。","Request occupied control through a phone notification. The user can allow once or always. Returns pending immediately; poll action=status with approval_id once per second. Approval returns a new lease_token; observe again. Always allow applies only to phone occupancy; competing agents still require approval. This only covers occupied virtual-screen control; approval does not lend the main screen. Whether the main screen may be used is given by main_screen_allowed in phone_get_status.","控制權被手機或其他 Agent 佔用時，傳送通知申請接管。使用者可允許一次或始終允許；立即傳回 pending。用 action=status 和 approval_id 每秒查詢，獲批後使用新 lease_token 並重新觀察。始終允許僅自動接管手機佔用，其他 Agent 仍需審批。它只處理虛擬螢幕控制權被佔用的情況；獲批不等於借到了主螢幕，主螢幕能否使用以 phone_get_status 的 main_screen_allowed 為準。"),
                mapOf("action" to choice("request","status","cancel"),"approval_id" to string(128)),setOf("action"),
                branches=mapOf("status" to setOf("approval_id"),"cancel" to setOf("approval_id"))),
            tool(l,"get_status",t("查看状态、后端能力和当前控制方，无需租约。","Read status, backend capabilities and the current controller without a lease.","檢視狀態、後端能力和目前控制方，無需租約。"),readOnly=true),
            tool(l,"get_request_receipt",t("用原 JSON-RPC id 字符串查询当前 MCP 会话最近 32 个请求的回执，无需租约。查询不会重发输入。","Look up one of this MCP session's last 32 request receipts by the original JSON-RPC id string. No lease required; never replays input.","用原 JSON-RPC id 字串查詢目前 MCP 工作階段最近 32 個請求的回執，無需租約。查詢不會重播輸入。"),mapOf("request_id" to string(128).put("minLength",1)),setOf("request_id"),readOnly=true),
            tool(l,"create_screen",t("创建唯一虚拟屏，默认匹配手机可用分辨率和 DPI。只指定宽或高时按比例计算另一边及 DPI。需要控制租约。","Create the isolated display using the phone viewport and DPI by default. A width or height override scales the other dimension and density. Requires control.","建立唯一虛擬螢幕，預設符合手機可用解析度和 DPI。只指定寬或高時按比例計算另一邊及 DPI。需要控制租約。"),lease+display+createAfter,lease.keys),
            tool(l,"configure_screen",t("重新配置已有虚拟屏。保留未指定参数；重建显示并使旧观察失效，默认返回重建后的新观察。","Reconfigure an existing display, retaining unspecified settings. Rebuilds the display, invalidates old observations, and returns a fresh post-rebuild observation by default.","重新設定現有虛擬螢幕。保留未指定參數；重建顯示並使舊觀察失效，預設傳回重建後的新觀察。"),lease+display+after,lease.keys),
            tool(l,"close_screen",t("关闭虚拟屏并使旧观察失效。需要当前控制租约。","Close the isolated display and invalidate its observations. Requires the current control lease.","關閉虛擬螢幕並使舊觀察失效。需要目前控制租約。"),lease,lease.keys),
            tool(l,"list_apps",t("查询可启动应用，可按 query 或 package 过滤。需要控制租约，不会启动应用。","List launchable apps, optionally filtering by query or package. Requires control; does not launch apps.","查詢可啟動應用程式，可按 query 或 package 篩選。需要控制租約，不會啟動應用程式。"),lease+mapOf("query" to string(),"package" to string()),lease.keys,readOnly=true),
            tool(l,"launch_app",t("在虚拟屏启动 component，可传 uri。启动会使旧观察失效，默认直接返回启动后的新观察；跨屏冲突时等待手机通知确认重启，最长两分钟。","Launch component on the isolated display with an optional uri. Launching invalidates the old observation and returns a fresh post-launch observation by default. Cross-display conflicts await phone restart approval for up to two minutes.","在虛擬螢幕啟動 component，可傳 uri。啟動會使舊觀察失效，預設直接傳回啟動後的新觀察；跨螢幕衝突時等待手機通知確認重新啟動，最長兩分鐘。"),lease+after+mapOf("component" to string().put("minLength",1),"uri" to string()),lease.keys+"component"),
            tool(l,"observe",t("取得新观察，默认只返回 compact 节点树，不采集截图。mode=screenshot/both 显式请求图片；format=full 返回预算内全部采集节点及完整字段。compact 保留可读内容和交互目标，并省略默认状态键。节点含窗口归属、hint 和 stateDescription；树上限为 5000 节点、100 层、每节点 1000 个子项。","Get a fresh observation. Defaults to a compact node tree without image capture. Set mode=screenshot/both to request an image; format=full returns every collected node and field within budget. Compact keeps readable content and interaction targets while omitting default state keys. Nodes include window ownership, hints and state descriptions. Tree limits: 5,000 nodes, depth 100 and 1,000 children per node.","取得新觀察，預設只傳回 compact 節點樹，不擷取截圖。mode=screenshot/both 可明確要求圖片；format=full 傳回預算內所有已擷取節點與完整欄位。compact 保留可讀內容和互動目標，並省略預設狀態鍵。節點包含視窗歸屬、hint 和 stateDescription；樹上限為 5000 個節點、100 層、每節點 1000 個子項。"),
                lease+mapOf("include_nodes" to boolean().put("description",t("旧参数：true 等同 both，false 等同 screenshot。省略 mode 时优先采用此参数。","Legacy parameter: true maps to both and false maps to screenshot when mode is omitted.","舊參數：true 等同 both，false 等同 screenshot；省略 mode 時優先採用此參數。")),"mode" to choice("tree","screenshot","both").put("default","tree"),"format" to choice("compact","full").put("default","compact"),"max_image_width" to integer(320,2048)),lease.keys,readOnly=true),
            tool(l,"tap_at",t("使用最新未消耗观察，在虚拟屏像素坐标 x/y 点击。","Tap display pixel coordinates x/y using a fresh unconsumed observation.","使用最新未消耗觀察，在虛擬螢幕像素座標 x/y 點按。"),observed+after+xy,observed.keys+xy.keys),
            tool(l,"long_press_at",t("在虚拟屏 x/y 长按，duration_ms 为200–2000，默认600。消耗当前观察。","Long press display coordinates x/y for 200–2000 ms (default 600). Consumes the observation.","在虛擬螢幕 x/y 長按，duration_ms 為200–2000，預設600。消耗目前觀察。"),observed+after+xy+("duration_ms" to integer(200,2000)),observed.keys+xy.keys),
            tool(l,"swipe_path",t("沿2–64个 [x,y] 虚拟屏像素坐标滑动，duration_ms 默认300。消耗当前观察。","Swipe along 2–64 display-pixel [x,y] points. duration_ms defaults to 300. Consumes the observation.","沿2–64個 [x,y] 虛擬螢幕像素座標滑動，duration_ms 預設300。消耗目前觀察。"),observed+after+mapOf("duration_ms" to integer(100,2000),
                "points" to JSONObject().put("type","array").put("minItems",2).put("maxItems",64).put("items",JSONObject().put("type","array").put("minItems",2).put("maxItems",2).put("items",integer(0,4095)))),observed.keys+"points"),
            tool(l,"scroll_at",t("在虚拟屏 x/y 滚动。delta 为-20至20的非零整数，正数向下。消耗当前观察。","Scroll at display coordinates x/y. delta is a nonzero integer from -20 to 20; positive scrolls down. Consumes the observation.","在虛擬螢幕 x/y 捲動。delta 為-20至20的非零整數，正數向下。消耗目前觀察。"),observed+after+xy+("delta" to integer(-20,20).put("not",JSONObject().put("const",0))),observed.keys+xy.keys+"delta"),
            tool(l,"input_text",t("向虚拟屏焦点编辑框输入 Unicode 文字。replace=true 替换全部。","Insert Unicode into the virtual display's focused editor. replace=true replaces all text.","向虛擬螢幕焦點編輯框輸入 Unicode 文字。replace=true 取代全部。"),observed+after+mapOf("text" to string(20000),"replace" to boolean()),observed.keys+"text"),
            tool(l,"node_action",t("对当前观察中的节点操作。node_id 和 selector 二选一；selector 字段为精确匹配，必须唯一，歧义或截断会拒绝。set_text 必填 value，可设为空。不会降级到主屏。observe_after 可返回新观察，接受输入后观察失败不会重放输入。","Act on the current observation. Supply exactly one of node_id/selector. Selector fields match exactly and must identify one node; ambiguity/truncation is rejected. set_text requires value and allows empty text. No phone-display fallback. observe_after returns a fresh observation; a failed observation never replays accepted input.","對目前觀察中的節點操作。node_id 和 selector 二選一；selector 欄位為精確比對，必須唯一，歧義或截斷會拒絕。set_text 必填 value，可設為空。不會降級到主螢幕。observe_after 可傳回新觀察，接受輸入後觀察失敗不會重播輸入。"),
                observed+after+mapOf("node_id" to string(),"selector" to selector,"action" to choice("click","long_click","focus","scroll_forward","scroll_backward","set_text"),"value" to string(20000)),observed.keys+"action",branches=mapOf("set_text" to setOf("value"))),
            tool(l,"press_key",t("发送虚拟屏导航或编辑按键。默认返回键 4；禁止电源、主屏等全局按键。","Send a display-scoped navigation or editing key. Default: Back (4). Global power/home keys are rejected.","傳送虛擬螢幕導覽或編輯按鍵。預設返回鍵 4；禁止電源、主螢幕等全域按鍵。"),observed+after+("key_code" to integer(4,123).put("enum",JSONArray(DisplayKeys.codes))),observed.keys),
            tool(l,"wait",t("最多等待 30 秒。delay 返回 compact 节点树且不截图；frame_changed 需 fingerprint；node_text 需 text；node_present/absent 需 selector；app_foreground 需 package；ui_stable 要求画面持续稳定 500 毫秒，正在播放、进度在走的页面可能一直等不到；要确认在播放，用 frame_changed 或隔一会儿再观察。节点条件不截图。返回新观察及 condition_matched；超时返回当前状态，取消后重新观察。","Wait up to 30 seconds. delay returns a compact node tree without an image; frame_changed requires fingerprint; node_text requires text; node_present/absent requires selector; app_foreground requires package; ui_stable requires 500 ms of unchanged frames, which a playing page with moving progress may never reach; to confirm playback, use frame_changed or observe again a little later. Node conditions avoid screenshots. Return a fresh observation and condition_matched; timeout returns current state, and cancellation requires re-observation.","最多等待 30 秒。delay 傳回 compact 節點樹且不截圖；frame_changed 需 fingerprint；node_text 需 text；node_present/absent 需 selector；app_foreground 需 package；ui_stable 要求畫面持續穩定 500 毫秒，正在播放、進度在走的頁面可能一直等不到；要確認在播放，用 frame_changed 或隔一會兒再觀察。節點條件不截圖。傳回新觀察及 condition_matched；逾時傳回目前狀態，取消後重新觀察。"),
                lease+mapOf("condition" to choice("delay","frame_changed","node_text","node_present","node_absent","app_foreground","ui_stable"),"text" to string(),"case_sensitive" to boolean(),"fingerprint" to string(),"selector" to selector,"package" to string(),"timeout_ms" to integer(0,30000)),lease.keys,readOnly=true)
        ).plus(if (phone == null) emptyList() else listOf(
            tool(l,"phone_get_status",t("查看手机这一端的现状：是否连着、现在能不能操作、不能操作的原因（例如用户接管或手动停止），以及虚拟屏和各项能力是否可用。包含使用模式、本任务授权、借屏等待或倒数、拒绝、未回应、中断及归还状态。开始手机任务前，或操作被拒绝后，先看这里；等待借屏时保留原任务并查询此状态，不重新托事或重放输入。","Check this phone's connection, whether it can currently be operated, reasons it cannot (such as user takeover or manual stop), and availability of the virtual screen and capabilities. Includes usage mode, task grant, borrow wait/countdown, refusal, no response, interruption and return status. Check before a task or after a rejection. Keep the same task while awaiting consent and query this status; do not resubmit the task or replay input.","查看手機這一端的現況：是否連著、現在能不能操作、不能操作的原因（例如使用者接管或手動停止），以及虛擬螢幕和各項能力是否可用。包含使用模式、本任務授權、借螢幕等待或倒數、拒絕、未回應、中斷及歸還狀態。開始手機任務前或操作被拒絕後先看這裡；等待借螢幕時保留原任務並查詢狀態，不重新託事或重播輸入。"),readOnly=true)
        )).plus(if (phone == null) emptyList() else listOf(
            tool(l,"phone_request_main_screen",t(
                "请求本任务的主屏许可。手机显示原借屏卡，只有卡片点按才生效；立即返回当前许可或等待状态，不操作主屏。",
                "Request main-screen permission for this task. The phone shows its borrow card; only a card tap grants permission. Returns the current permission or waiting state immediately without operating the main screen.",
                "請求本任務的主螢幕許可。手機顯示原借螢幕卡，只有卡片點按才生效；立即傳回目前許可或等待狀態，不操作主螢幕。")),
            tool(l,"phone_request_handoff",t(
                "让用户亲眼看或亲手处理当前虚拟屏页面。用户说想看看、切过来、给我看，或想自己操作，或这一步需要用户确认时调用。保留同一任务和页面，立即暂停远程操作，在手机上显示请求卡片；用户点“我来处理”后才会在主屏全屏显示这个页面。返回 handoff_requested 只表示卡片已发出，不表示用户已经看到；之后用 phone_get_status 查看是否已打开（human_control）或已交还。交还后重新取得控制并观察，不重放之前的操作。没有调用它之前，不要说已经切过去或已经给用户看了。",
                "Let the user see or handle the current virtual-screen page themselves. Call it when the user asks to look, switch over or be shown, wants to operate it, or this step needs the user's confirmation. Keeps the same task and page, immediately pauses remote operations and shows a request card on the phone; the page opens full screen on the main display only after the user taps \"I'll handle it\". handoff_requested only means the card was sent, not that the user has seen it; then use phone_get_status to see whether it was opened (human_control) or handed back. After hand-back, acquire control again and observe; never replay earlier actions. Before calling it, never say you have switched over or shown the user.",
                "讓使用者親眼看或親手處理目前虛擬螢幕頁面。使用者說想看看、切過來、給我看，或想自己操作，或這一步需要使用者確認時呼叫。保留同一任務和頁面，立即暫停遠端操作，在手機上顯示請求卡片；使用者點「我來處理」後才會在主螢幕全螢幕顯示這個頁面。傳回 handoff_requested 只表示卡片已送出，不表示使用者已經看到；之後用 phone_get_status 查看是否已開啟（human_control）或已交還。交還後重新取得控制並觀察，不重播之前的操作。沒有呼叫它之前，不要說已經切過去或已經給使用者看了。"),
                lease + mapOf("reason" to string(1000).put("minLength", 1)), lease.keys + "reason")
        )).plus(if (qqMusic == null) emptyList() else listOf(
            tool(l,"qqmusic_get_playback",t("查看 QQ 音乐正在播放什么：曲目、歌手、播放还是暂停、当前进度和总时长，以及能执行的操作。控制播放前先用它取得 session_id。","Check QQ Music's track, artist, playing or paused state, position, duration, and available operations. Use it to obtain session_id before controlling playback.","查看 QQ 音樂正在播放什麼：曲目、歌手、播放還是暫停、目前進度和總時長，以及能執行的操作。控制播放前先用它取得 session_id。"),readOnly=true),
            tool(l,"qqmusic_control_playback",t("控制 QQ 音乐播放：play 继续、pause 暂停、seek 跳到 position_ms。在后台执行，不打开 QQ 音乐。返回操作前后的状态；没能确认生效时会如实标明。用户要继续播放、暂停或跳进度时先用它；搜歌、切下一首、歌单它做不了，这些在屏幕上操作 QQ 音乐。","Control QQ Music playback: play resumes, pause pauses, and seek moves to position_ms. Runs in the background without opening QQ Music. Returns the states before and after the operation and indicates when its effect could not be confirmed. Use it first to resume, pause or seek; it cannot search, skip to the next track or open playlists, so do those on the screen in QQ Music.","控制 QQ 音樂播放：play 繼續、pause 暫停、seek 跳到 position_ms。在背景執行，不開啟 QQ 音樂。傳回操作前後的狀態；沒能確認生效時會如實標明。使用者要繼續播放、暫停或跳進度時先用它；搜歌、切下一首、歌單它做不了，這些在螢幕上操作 QQ 音樂。"),
                mapOf("session_id" to string(), "command" to choice("play","pause","seek"),
                    "position_ms" to integer(0,Int.MAX_VALUE)),setOf("session_id","command"))
        )).plus(if (glance == null) emptyList() else listOf(
            tool(l,"phone_glance_status",t("只读查询主屏截屏能力；不取图、不打开应用。", "Read main-screen capture availability without taking a screenshot or opening an app.", "唯讀查詢主螢幕截圖能力；不取圖、不開啟應用。"),readOnly=true),
            tool(l,"phone_glance_capture",t("只读查看当前主屏，不输入、不切应用；受保护或不可读时返回原因。", "Read the current main screen without input or app switching; report protected or unreadable content.", "唯讀查看目前主螢幕，不輸入、不切換應用；受保護或不可讀時傳回原因。"),readOnly=true)
        )).plus(if (mainScreen == null) emptyList() else listOf(
            tool(l,"main_screen_observe",t("观察本任务获准使用的主屏（全局模式、明确托事授权或借屏同意）。返回节点与截图；等你决定或倒数期间不能操作。", "Observe the main screen authorized for this task through global mode, explicit submission, or borrow consent. Returns nodes and image. Input is blocked while awaiting a decision or countdown.", "觀察本任務獲准使用的主螢幕（全域模式、明確託事授權或借螢幕同意）。傳回節點與截圖；等待決定或倒數期間不能操作。"), lease+mapOf("mode" to choice("tree","screenshot","both")),lease.keys,readOnly=true),
            tool(l,"main_screen_node_action",t("在已交出的主屏操作当前观察中的节点。动作后重新观察。", "Act on a node from the current observation of the handed-over main screen, then observe again.", "在已交出的主螢幕操作目前觀察中的節點。動作後重新觀察。"), observed+mapOf("node_id" to string(),"action" to choice("click","long_click","focus","scroll_forward","scroll_backward","set_text"),"value" to string()),observed.keys+setOf("node_id","action")),
            tool(l,"main_screen_gesture",t("在已交出的主屏按观察坐标点击或滑动；一个点为点击，多个点为滑动。", "Tap or swipe observed coordinates on the handed-over main screen; one point taps and multiple points swipe.", "在已交出的主螢幕按觀察座標點按或滑動；一個點為點按，多個點為滑動。"), observed+mapOf("points" to JSONObject().put("type","array").put("minItems",1).put("maxItems",64).put("items",JSONObject().put("type","array").put("minItems",2).put("maxItems",2).put("items",integer(0,8191))),"duration_ms" to integer(1,1000)),observed.keys+"points"),
            tool(l,"main_screen_back",t("在已交出的主屏返回上一页。", "Go back on the handed-over main screen.", "在已交出的主螢幕返回上一頁。"),observed,observed.keys),
            tool(l,"main_screen_launch_app",t("在用户明确交出的主屏打开应用；必须重新观察确认实际前台。", "Open an app on the explicitly handed-over main screen; observe again to verify the actual foreground.", "在使用者明確交出的主螢幕開啟應用；必須重新觀察確認實際前景。"),observed+mapOf("component" to string()),observed.keys+"component"),
            tool(l,"main_screen_finish",t("结束本任务，归还原应用并撤销本次授权。归还是否成功以 restore_status 为准。工具不能重新开启本任务。", "Finish this task, return to the original app and revoke its grant. Check restore_status for the return outcome. Tools cannot re-enable this task.", "結束本任務，歸還原應用程式並撤銷本次授權。歸還結果以 restore_status 為準。工具不能重新開啟本任務。"),lease,lease.keys)
        )).plus(if (systemTools == null) emptyList() else listOf(
            tool(l,"alarm_get_next",t("读取系统下一次闹钟，不提供完整列表。", "Read the next system alarm; a complete list is unavailable.", "讀取系統下一次鬧鐘，不提供完整列表。"),readOnly=true),
            tool(l,"alarm_set",t("登记一个闹钟请求；需用户在 APK 系统工具权限页点击后交给系统时钟，可能打开时钟界面。派发不等于创建已确认，勿重复提交。", "Register an alarm request. The user must tap it in the APK system tools page before dispatch to the clock, which may open its UI. Dispatch is not confirmed creation; do not repeat.", "登記鬧鐘請求；需使用者在 APK 系統工具權限頁點按後交給系統時鐘，可能開啟時鐘介面。派發不等於建立已確認，勿重複提交。"),
                mapOf("hour" to integer(0,23).put("description",t("手机本地时区小时。","Hour in the phone's local time zone.","手機本地時區小時。")),"minute" to integer(0,59),"label" to string(200),"days" to JSONObject().put("type","array").put("minItems",0).put("maxItems",7).put("items",integer(1,7))
                    .put("description",t("1=周日、2=周一、3=周二、4=周三、5=周四、6=周五、7=周六；省略或空列表为单次。","1=Sunday, 2=Monday, 3=Tuesday, 4=Wednesday, 5=Thursday, 6=Friday, 7=Saturday; omitted/empty means once.","1=週日、2=週一、3=週二、4=週三、5=週四、6=週五、7=週六；省略或空清單為單次。"))),setOf("hour","minute")),
            tool(l,"alarm_get_request",t("按 alarm_request_id 查询同一 MCP 会话的闹钟回执；仅有限读回，不重发。", "Look up alarm_request_id in this MCP session; limited readback only, never resend.", "按 alarm_request_id 查詢同一 MCP 工作階段的鬧鐘回執；僅有限讀回，不重送。"),mapOf("alarm_request_id" to string(128)),setOf("alarm_request_id"),readOnly=true),
            tool(l,"calendar_list",t("列出手机日历及可写权限；缺权限时返回待用户手动授权。", "List device calendars and write access; missing permission requires the user's manual grant.", "列出手機日曆及可寫權限；缺權限時傳回待使用者手動授權。"),readOnly=true),
            tool(l,"calendar_read",t("读取指定毫秒时间范围的日程，包含重复日程实例。", "Read events in a millisecond time interval, including recurring instances.", "讀取指定毫秒時間範圍的日程，包含重複日程實例。"),
                mapOf("start_ms" to longInteger(),"end_ms" to longInteger(),"calendar_id" to longInteger(),"limit" to integer(1,500)),setOf("start_ms","end_ms"),readOnly=true),
            tool(l,"calendar_create",t("创建一次本机日程并按返回 URI 独立读回；未确认时不要重建。先 list 选择日历，时间为毫秒；全天日程须 UTC 零点且结束时间不包含当天。", "Create one local event and read back its returned URI. Do not recreate an unconfirmed write. First list calendars; times are milliseconds. All-day boundaries must be UTC midnight with an exclusive end.", "建立一次本機日程並按傳回 URI 獨立讀回；未確認時不要重建。先 list 選擇日曆，時間為毫秒；全天日程須 UTC 零點且結束時間不包含當天。"),
                mapOf("calendar_id" to longInteger(),"title" to string(500),"start_ms" to longInteger(),"end_ms" to longInteger(),"time_zone" to string(100),"description" to string(4000),"location" to string(500),"all_day" to boolean()),setOf("calendar_id","title","start_ms","end_ms","time_zone"))
        )).onEach { spec ->
            if(spec.name=="node_action") spec.schema.put("oneOf",JSONArray(listOf(
                JSONObject().put("required",JSONArray(listOf("node_id"))).put("not",JSONObject().put("required",JSONArray(listOf("selector")))),
                JSONObject().put("required",JSONArray(listOf("selector"))).put("not",JSONObject().put("required",JSONArray(listOf("node_id")))))))
            if(spec.name=="node_action") spec.schema.getJSONArray("allOf").getJSONObject(0)
                .put("else",JSONObject().put("not",JSONObject().put("required",JSONArray(listOf("value")))))
            if(spec.name=="observe") spec.schema.put("not",JSONObject().put("required",JSONArray(listOf("include_nodes","mode"))))
            if(spec.name=="request_control") spec.schema.getJSONArray("allOf").put(
                conditional("action","request",emptyList()).also { branch ->
                    branch.getJSONObject("then").put("not",JSONObject().put("required",JSONArray(listOf("approval_id"))))
                })
            if(spec.name=="wait") {
                val fields=setOf("text","fingerprint","selector","package","case_sensitive")
                val branches=listOf("delay","frame_changed","node_text","node_present","node_absent","app_foreground","ui_stable").map { condition ->
                    val applicable=when(condition) {
                        "frame_changed" -> setOf("fingerprint")
                        "node_text" -> setOf("text","case_sensitive")
                        "node_present","node_absent" -> setOf("selector")
                        "app_foreground" -> setOf("package")
                        else -> emptySet()
                    }
                    val required=applicable-"case_sensitive"
                    val branch=conditional("condition",condition,required.toList())
                    branch.getJSONObject("then").put("not",JSONObject().put("anyOf",JSONArray((fields-applicable).map {
                        JSONObject().put("required",JSONArray(listOf(it)))
                    })))
                    if(condition=="delay") branch.getJSONObject("if").remove("required")
                    branch
                }
                spec.schema.put("allOf",JSONArray(branches))
            }
        }
    }
    suspend fun call(name: String,args: JSONObject,owner: String,requestId: String = UUID.randomUUID().toString(), taskId: String? = null): JSONObject {
        val l=language()
        val purpose=args.optString("purpose").trim()
        var activity: ToolActivity?=null
        return try {
            coroutineContext.ensureActive()
            val spec=tools.firstOrNull { it.name == name } ?: error("UNKNOWN_TOOL")
            require(purpose.isNotBlank()) { "PURPOSE_REQUIRED" }
            validate(spec.schema,args)
            validateAction(name,args)
            phone?.checkTaskHeader(name, taskId)
            phone?.checkOperation(name)
            if (phone?.required == true && name in io.github.lejw0925.virtualscreen.phone.PhoneSession.SCREEN_TOOLS) {
                mainScreen?.bindLease(owner, args.getString("lease_token"))
                mainScreen?.policy?.describeStep(args.optString("component").takeIf { it.isNotBlank() }, purpose)
            }
            receipts.record(owner,requestId,mapOf("tool" to name,"phase" to "running","execution_status" to "unknown"))
            activity=ToolActivity(UUID.randomUUID().toString(),owner,name,purpose,"running")
            // Other clients' status queries and rejected lease requests must not
            // replace the purpose of the operation currently controlling the screen.
            current.update { previous ->
                if(previous?.phase != "running" || (previous.owner == owner && name !in setOf("get_status","get_request_receipt"))) activity else previous
            }
            val token=args.optString("lease_token"); val observation=args.optString("observation_id")
            val result=when(name) {
                "main_screen_observe", "main_screen_node_action", "main_screen_gesture", "main_screen_back", "main_screen_launch_app", "main_screen_finish" ->
                    checkNotNull(mainScreen).call(name,owner,args,taskId)
                "phone_get_status" -> checkNotNull(phone).status()
                "phone_request_main_screen" -> {
                    val binding = checkNotNull(phone)
                    val created = binding.requestMainScreen(checkNotNull(taskId) { "PHONE_TASK_BINDING_REQUIRED" }, purpose)
                    val usage = binding.usageReceipt()
                    val result = when (usage["task_phase"]) {
                        "waiting_borrow" -> OperationEnvelope.failure(
                            if (usage["borrow_request_source"] == "direct_request") "MAIN_SCREEN_REQUEST_PENDING" else "MAIN_SCREEN_BORROW_PENDING")
                        "countdown" -> OperationEnvelope.failure("MAIN_SCREEN_COUNTDOWN")
                        else -> OperationEnvelope.success("message" to l.text(
                            "本任务已有主屏许可，未新增请求。", "This task already has main-screen permission; no new request was created.", "本任務已有主螢幕許可，未新增請求。"))
                    }
                    result.copy(values = result.values + usage + mapOf("main_screen_request_created" to created,
                        "retry_automatically" to false, "recovery_tools" to listOf("phone_get_status")))
                }
                "phone_request_handoff" -> {
                    val binding = checkNotNull(phone)
                    check(binding.required) { "PHONE_TASK_BINDING_REQUIRED" }
                    val screen = sessions.state.value
                    check(screen.displayId != null && screen.sessionId != null) { "NO_VIRTUAL_SCREEN" }
                    binding.requestHumanHandoff(checkNotNull(taskId) { "PHONE_TASK_BINDING_REQUIRED" },
                        checkNotNull(screen.sessionId), args.getString("reason").trim(), owner, requestId)
                    OperationEnvelope.success("handoff_requested" to true, "retry_automatically" to false,
                        "recovery_tools" to listOf("phone_get_status"),
                        "message" to l.text("请求卡片已发出，用户还没打开；远程操作已暂停，任务尚未完成。", "The request card was sent and the user has not opened it yet; remote operations are paused and the task is not complete.", "請求卡片已送出，使用者還沒開啟；遠端操作已暫停，任務尚未完成。"))
                }
                "phone_glance_status" -> checkNotNull(glance).status()
                "phone_glance_capture" -> checkNotNull(glance).capture()
                "alarm_get_next" -> checkNotNull(systemTools).getNextAlarm()
                "alarm_set" -> checkNotNull(systemTools).setAlarm(owner,requestId,args.getInt("hour"),args.getInt("minute"),args.optString("label"),
                    args.optJSONArray("days")?.let { days -> (0 until days.length()).map { days.getInt(it) } } ?: emptyList(),
                    nativeConfirmationBinding(taskId))
                "alarm_get_request" -> checkNotNull(systemTools).getAlarmRequest(owner,args.getString("alarm_request_id"))
                "calendar_list" -> withContext(Dispatchers.IO) { checkNotNull(systemTools).listCalendars() }
                "calendar_read" -> withContext(Dispatchers.IO) { checkNotNull(systemTools).readEvents(args.getLong("start_ms"),args.getLong("end_ms"),
                    if(args.has("calendar_id")) args.getLong("calendar_id") else null,args.optInt("limit",100)) }
                "calendar_create" -> withContext(Dispatchers.IO) { checkNotNull(systemTools).createEvent(args.getLong("calendar_id"),args.getString("title"),
                    args.getLong("start_ms"),args.getLong("end_ms"),args.getString("time_zone"),args.optString("description"),args.optString("location"),args.optBoolean("all_day")) }
                "qqmusic_get_playback" -> checkNotNull(qqMusic).status()
                "qqmusic_control_playback" -> checkNotNull(qqMusic).control(args.getString("session_id"),
                    args.getString("command"),if(args.has("position_ms")) args.getLong("position_ms") else null)
                "acquire_control" -> leaseResult(sessions.acquireLease(owner))
                "release_control" -> when(val decision=mainScreen?.releaseControl(owner,token) ?: sessions.releaseLease(owner,token)) {
                    is LeaseDecision.Granted -> OperationEnvelope.success("released" to true)
                    is LeaseDecision.Rejected -> OperationEnvelope.failure(decision.code)
                }
                "request_control" -> when(args.getString("action")) {
                    "request" -> approvals.request(owner,clientName(owner),purpose)
                    "status" -> approvals.status(owner,args.getString("approval_id"))
                    else -> approvals.cancel(owner,args.getString("approval_id"))
                }
                "get_status" -> {
                    val controller=sessions.leaseStatus(); val state=sessions.state.value; val c=state.capabilities
                    OperationEnvelope(true,sessionId=state.sessionId,values=mapOf("backend" to state.backend.wireName,"status" to state.status.name.lowercase(),"display_id" to state.displayId,
                        "controller" to controller?.owner,"preview_status" to state.previewStatus.name.lowercase(),"lease_remaining_ms" to controller?.let { (it.expiresAtMillis-System.nanoTime()/1000000).coerceAtLeast(0) },
                        "capabilities" to mapOf("display" to c.displayCreation.name,"capture" to c.frameCapture.name,"input" to c.inputInjection.name,"nodes" to c.accessibilityNodes.name,"screen_off" to c.screenOff.name),"error_code" to state.errorCode))
                }
                "get_request_receipt" -> {
                    val receipt=receipts.get(owner,args.getString("request_id"))
                    OperationEnvelope.success("receipt" to receipt,"receipt_found" to (receipt!=null))
                }
                "create_screen" -> withPostObservation(owner,token,sessions.create(owner,token,config(args)),args.optString("observe_after","none"))
                "configure_screen" -> {
                    check(sessions.state.value.displayId != null) { "NO_VIRTUAL_SCREEN" }
                    withPostObservation(owner,token,sessions.configure(owner,token,config(args)),args.optString("observe_after",defaultOperationObservation))
                }
                "close_screen" -> sessions.close(owner,token)
                    "launch_app" -> withPostObservation(owner,token,
                        sessions.launch(owner,token,args.getString("component"),args.optString("uri").takeIf(String::isNotBlank),purpose,clientName(owner),
                            allowRestartApproval = phone?.required != true),
                        args.optString("observe_after",defaultOperationObservation))
                "list_apps" -> {
                    check(sessions.touchLease(owner,token) is LeaseDecision.Granted) { "LEASE_REQUIRED" }
                    val query=args.optString("query")
                    OperationEnvelope.success("applications" to launchableApplications(args.optString("package"), query))
                }
                "observe" -> observe(owner,token,when {
                    args.has("mode") -> args.getString("mode")
                    args.has("include_nodes") -> if(args.getBoolean("include_nodes")) "both" else "screenshot"
                    else -> "tree"
                },args.optString("format","compact"))
                "wait" -> waitFor(owner,token,args)
                else -> {
                    val operationArgs=JSONObject(args.toString())
                    if(name=="node_action" && args.has("selector")) {
                        val snapshot=sessions.nodes(owner,token,observation).getOrThrow()
                        val matches=snapshot.nodes.filter { matches(it,args.getJSONObject("selector")) }
                        check(matches.isNotEmpty()) { "NODE_NOT_FOUND" }
                        check(matches.size==1 && !snapshot.truncated) { "AMBIGUOUS_NODE" }
                        operationArgs.put("node_id",matches.single().id)
                    }
                    val action=sessions.operate(owner,token,observation,operation(name,operationArgs))
                    val outcome=action.copy(values=action.values+mapOf("execution_status" to if(action.ok) "accepted" else if(action.code in setOf("UI_INPUT_TIMEOUT","UI_MANUAL_CONTROL","INPUT_DISPATCH_UNCONFIRMED")) "unknown" else "rejected","effect_status" to if(action.values["verified"]==true) "verified" else "unconfirmed","requires_observation" to true))
                    withPostObservation(owner,token,outcome,args.optString("observe_after",defaultOperationObservation))
                }
            }
            val evaluated = evaluatePhoneResult(name, taskId, result)
            val waitingForUser = evaluated.code in setOf("MAIN_SCREEN_BORROW_PENDING", "MAIN_SCREEN_REQUEST_PENDING", "MAIN_SCREEN_COUNTDOWN") ||
                (evaluated.values["native_confirmation_kind"] == "alarm" && evaluated.values["phase"] == "pending")
            val activityPhase = if (evaluated.ok) "completed" else if (waitingForUser) "waiting_user" else "failed"
            complete(activity,activityPhase,evaluated.code)
            receipts.record(owner,requestId,mapOf("tool" to name,"phase" to activityPhase,"ok" to evaluated.ok,"code" to evaluated.code,"session_id" to evaluated.sessionId)+
                evaluated.values.filterKeys { it in setOf("execution_status","effect_status","observation_status","observation_error","observation_id","requires_observation") + (mainScreen?.policy?.receipt()?.keys ?: emptySet()) })
            val values = if (name in setOf("alarm_set", "alarm_get_request")) evaluated.values + mapOf(
                "request_id" to result.values["request_id"], "alarm_request_id" to result.values["request_id"],
                "tool_request_id" to requestId)
            else evaluated.values + ("request_id" to requestId)
            encode(evaluated.copy(values=values),l,purpose,args.optInt("max_image_width",2048))
        } catch(c: CancellationException) {
            if(name !in setOf("phone_glance_status","phone_glance_capture")) sessions.cancelActive(owner)
            complete(activity,"cancelled","CANCELLED")
            receipts.record(owner,requestId,mapOf("tool" to name,"phase" to "cancelled","execution_status" to "unknown","requires_observation" to true)); throw c
        } catch(e: Exception) {
            complete(activity,"failed",e.code())
            receipts.record(owner,requestId,mapOf("tool" to name,"phase" to "failed","ok" to false,"code" to e.code(),"execution_status" to if(activity!=null) "unknown" else "rejected"))
            val unknownArguments = if(e.code()=="UNKNOWN_ARGUMENT") {
                val properties=tools.firstOrNull { it.name==name }?.schema?.optJSONObject("properties")
                val unknown=properties?.let { p -> args.keys().asSequence().filterNot(p::has).toList() }.orEmpty()
                if(unknown.isEmpty()) emptyMap() else mapOf("unknown_arguments" to unknown,
                    "accepted_arguments" to properties!!.keys().asSequence().toList())
            } else emptyMap()
            val failure = OperationEnvelope.failure(e.code(),sessions.state.value.sessionId,"request_id" to requestId,
                "execution_status" to if(activity==null || e.code() in setOf("NO_VIRTUAL_SCREEN","STALE_OBSERVATION","LEASE_REQUIRED")) "rejected" else "unknown")
                .let { it.copy(values=it.values+unknownArguments) }
            val evaluated = evaluatePhoneResult(name, taskId, failure)
            receipts.record(owner, requestId, mapOf("tool" to name, "phase" to if (evaluated.code in setOf("MAIN_SCREEN_BORROW_PENDING", "MAIN_SCREEN_REQUEST_PENDING", "MAIN_SCREEN_COUNTDOWN")) "waiting_user" else "failed",
                "ok" to false, "code" to evaluated.code) + evaluated.values.filterKeys { it in
                    setOf("execution_status", "effect_status", "requires_observation") + (mainScreen?.policy?.receipt()?.keys ?: emptySet()) })
            val paused = phone?.flow?.value?.takeIf { e.code() == "PHONE_EXECUTION_PAUSED" }
            encode(if(paused != null) evaluated.copy(values=evaluated.values+mapOf(
                "reason" to paused.reason, "execution_enabled" to false,
                "requires_user_action" to true, "execution_status" to "rejected")) else evaluated,l,purpose)
        }
    }
    private fun evaluatePhoneResult(name: String, taskId: String?, result: OperationEnvelope): OperationEnvelope {
        val control = mainScreen ?: return result
        val policy = control.policy
        if (taskId != null && taskId != policy.state.value.taskId && name in io.github.lejw0925.virtualscreen.phone.PhoneSession.TASK_BOUND_TOOLS) {
            return OperationEnvelope.failure("PHONE_TASK_CHANGED", result.sessionId).copy(values = result.values + mapOf(
                "task_id" to taskId, "execution_status" to "unknown", "effect_status" to "unconfirmed",
                "requires_observation" to true, "retry_automatically" to false))
        }
        val failureCode = result.code ?: result.values["observation_error"] as? String
        val borrowable = failureCode in setOf("APP_ALREADY_ON_MAIN_SCREEN", "APP_MOVED_TO_PHONE", "DISPLAY_LAUNCH_MISMATCH",
            "DISPLAY_LAUNCH_REJECTED", "SECURE_WINDOW", "APP_RESTART_CONFIRMATION_REQUIRED", "APP_RESTART_UNSUPPORTED", "APP_RESTART_REJECTED",
            "BACKEND_PERMISSION_OR_DISPLAY_LOST", "DISPLAY_CREATE_FAILED", "DISPLAY_CREATION_FAILED", "DISPLAY_UNSUPPORTED",
            "DISPLAY_GONE", "EMBEDDED_SERVICE_DIED", "EMBEDDED_SERVICE_TRANSACTION_FAILED", "EMBEDDED_PRIVILEGE_NOT_READY", "INPUT_UNAVAILABLE")
        if (taskId != null && name in io.github.lejw0925.virtualscreen.phone.PhoneSession.SCREEN_TOOLS &&
            !name.startsWith("main_screen_") && borrowable && policy.backgroundFailed(taskId, checkNotNull(failureCode))) {
            sessions.cancelActive()
            return OperationEnvelope.failure("MAIN_SCREEN_BORROW_PENDING", result.sessionId).copy(values =
                result.values + policy.receipt() + mapOf("original_failure_code" to failureCode,
                    "retry_automatically" to false, "recovery_tools" to listOf("phone_get_status")))
        }
        if (taskId != null && policy.state.value.taskId == taskId && policy.state.value.mainScreenAllowed &&
            name.startsWith("main_screen_") && !result.ok && result.code !in setOf("STALE_OBSERVATION", "OBSERVATION_REQUIRED",
                "MISSING_ARGUMENT", "INVALID_ARGUMENT", "UNKNOWN_ARGUMENT", "LEASE_REQUIRED", "PHONE_TASK_BINDING_REQUIRED",
                "NODE_ACTION_UNSUPPORTED",
                "SYSTEM_SCREENSHOT_INTERVAL", "NODE_WINDOW_NOT_READY", "ACCESSIBILITY_NODES_UNAVAILABLE", "SCREENSHOT_CALLBACK_TIMEOUT")) {
            control.finishTask(taskId, "failed", result.code ?: "MAIN_SCREEN_OPERATION_FAILED")
        }
        val values = result.values + policy.receipt()
        return result.copy(values = when {
            name == "main_screen_node_action" && result.code == "NODE_ACTION_UNSUPPORTED" ->
                values + mapOf("execution_status" to "rejected", "effect_status" to "not_applied", "requires_observation" to true)
            result.values["native_confirmation_kind"] != null -> values + ("requires_user_action" to result.values["requires_user_action"])
            else -> values
        })
    }
    private fun nativeConfirmationBinding(taskId: String?): Map<String, String> {
        if (taskId == null) return emptyMap()
        val usage = mainScreen?.policy?.state?.value
        val device = phone?.flow?.value
        check(usage?.taskId == taskId && !usage?.executionId.isNullOrBlank() && device?.connectionId != null
            && usage?.connectionId == device.connectionId) { "PHONE_TASK_BINDING_REQUIRED" }
        return mapOf("task_id" to taskId, "execution_id" to checkNotNull(usage?.executionId),
            "device_id" to checkNotNull(device).deviceId, "connection_id" to checkNotNull(device.connectionId))
    }
    private fun complete(a: ToolActivity?,phase: String,code: String?) { if(a != null) current.compareAndSet(a,a.copy(phase=phase,code=code)) }
    private fun launchableApplications(packageName: String = "", query: String = "") = sessions.discoverApplications()
        .filter { it.exported && it.packageName != io.github.lejw0925.virtualscreen.BuildConfig.APPLICATION_ID &&
            (packageName.isBlank() || it.packageName == packageName) &&
            (query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true)) }
        .map { mapOf("label" to it.label, "package" to it.packageName, "component" to it.component) }

    private suspend fun observe(owner: String,token: String,mode: String,format: String = "compact"): OperationEnvelope {
        val frame=sessions.observe(owner,token,captureFrame=mode!="tree").getOrElse { failure ->
            if (failure.code() !in setOf("NO_RUNNING_APP", "NO_VIRTUAL_SCREEN")) throw failure
            val hasDisplay = sessions.state.value.displayId != null
            return OperationEnvelope.failure(if(hasDisplay) "NO_RUNNING_APP" else "NO_VIRTUAL_SCREEN", sessions.state.value.sessionId,
                "application_running" to false, "nodes" to emptyList<Any>(), "windows" to emptyList<Any>(),
                "applications" to launchableApplications(), "requires_launch" to true,
                "requires_display" to !hasDisplay,
                "recovery_tools" to if (hasDisplay) listOf("launch_app", "observe") else listOf("create_screen", "launch_app", "observe"))
        }
        if(mode=="screenshot") return frame
        val observationId = frame.values.getValue("observation_id") as String
        var nodes = sessions.nodes(owner, token, observationId)
        repeat(3) {
            if (nodes.exceptionOrNull()?.code() in setOf("NODE_WINDOW_NOT_READY", "ACCESSIBILITY_NODES_UNAVAILABLE")) {
                delay(150)
                nodes = sessions.nodes(owner, token, observationId)
            }
        }
        if(mode=="tree") nodes.getOrThrow()
        return frame.copy(values=frame.values+nodes.fold({ snapshot -> nodeValues(snapshot,format) },{ failure ->
            mapOf("nodes" to emptyList<Any>(),"nodes_error" to failure.code(),"nodes_message" to localizedError(language(),failure.code())) }))
    }
    private suspend fun withPostObservation(owner: String,token: String,result: OperationEnvelope,mode: String): OperationEnvelope {
        if(!result.ok) return result
        if(mode=="none") {
            return if(result.values.containsKey("requires_observation")) result
            else result.copy(values=result.values+mapOf("requires_observation" to true))
        }
        // Never replay an accepted input if the subsequent observation fails.
        return try {
            val next=observe(owner,token,mode,"compact")
            if (next.ok) result.copy(values=result.values+next.values+mapOf("observation_status" to "available","requires_observation" to false))
            else result.copy(values=result.values+next.values+mapOf("observation_status" to "failed",
                "observation_error" to next.code, "observation_message" to localizedError(language(), next.code ?: "BACKEND_OPERATION_FAILED"),
                "requires_observation" to true))
        } catch(c: CancellationException) { throw c }
          catch(e: Exception) { result.copy(values=result.values+mapOf("observation_status" to "failed","observation_error" to e.code(),"requires_observation" to true)) }
    }
    private fun matches(n: NodeInfo,s: JSONObject): Boolean = n.visible &&
        (!s.has("resource_id") || n.resourceId==s.getString("resource_id")) &&
        (!s.has("text") || n.text==s.getString("text")) &&
        (!s.has("description") || n.contentDescription==s.getString("description")) &&
        (!s.has("class") || n.className==s.getString("class"))
    private fun nodeValues(n: NodeSnapshot,format: String = "compact"): Map<String,Any?> = NodeTreeFormatter.values(n,format)
    private suspend fun waitFor(owner: String,token: String,a: JSONObject): OperationEnvelope {
        val condition=a.optString("condition","delay")
        if(condition == "frame_changed") require(a.optString("fingerprint").isNotBlank()) { "FINGERPRINT_REQUIRED" }
        if(condition == "node_text") require(a.optString("text").isNotBlank()) { "TEXT_REQUIRED" }
        val timeout=a.optLong("timeout_ms",1000); val deadline=System.nanoTime()/1000000+timeout
        if(condition == "delay") {
            delay(timeout)
            val observed = observe(owner,token,"tree")
            if (!observed.ok) return observed.copy(values = observed.values + ("condition_matched" to false))
            return observed.copy(values=observed.values+("condition_matched" to true))
        }
        var previous: String?=null; var stableSince=System.nanoTime()/1000000
        while(true) {
            val observed=try { observe(owner,token,if(condition in setOf("frame_changed","ui_stable")) "screenshot" else "tree") }
                catch(c: CancellationException) { throw c }
                catch(e: Exception) {
                    if(e.code() !in setOf("ACCESSIBILITY_NODES_UNAVAILABLE","NODE_WINDOW_NOT_READY","DISPLAY_FRAME_PENDING") || System.nanoTime()/1000000>=deadline) throw e
                    delay(250); continue
                }
            val now=System.nanoTime()/1000000
            if (!observed.ok) return observed.copy(values = observed.values + ("condition_matched" to false))
            val matched=when(condition) {
                "frame_changed" -> observed.values["fingerprint"] != a.getString("fingerprint")
                "ui_stable" -> {
                    val fingerprint=observed.values["fingerprint"] as? String
                    if(fingerprint!=previous) { previous=fingerprint; stableSince=now }
                    fingerprint!=null && now-stableSince>=500
                }
                "app_foreground" -> observed.values["package"]==a.getString("package")
                else -> {
                    val snapshot=sessions.nodes(owner,token,observed.values.getValue("observation_id") as String).getOrThrow()
                    if(condition=="node_text") snapshot.nodes.any { it.visible && (it.text?.contains(a.getString("text"),ignoreCase=!a.optBoolean("case_sensitive"))==true || it.contentDescription?.contains(a.getString("text"),ignoreCase=!a.optBoolean("case_sensitive"))==true) }
                    else {
                        check(!snapshot.truncated) { "NODE_TREE_TRUNCATED" }
                        val present=snapshot.nodes.any { matches(it,a.getJSONObject("selector")) }
                        if(condition=="node_present") present else !present
                    }
                }
            }
            if(matched) return observed.copy(values=observed.values+("condition_matched" to true))
            if(now >= deadline) return observed.copy(ok=false,code="WAIT_TIMEOUT",values=observed.values+("condition_matched" to false))
            delay(250)
        }
    }
    private fun config(a: JSONObject): VirtualScreenConfig {
        val c=if(a.optBoolean("match_main_display")) sessions.physicalDisplayConfig else if(sessions.state.value.displayId==null) sessions.defaultDisplayConfig else sessions.state.value.config
        val width=a.optInt("width",if(a.has("height")) (c.width.toDouble()*a.getInt("height")/c.height).toInt().coerceIn(320,4096) else c.width)
        val height=a.optInt("height",if(a.has("width")) (c.height.toDouble()*width/c.width).toInt().coerceIn(480,4096) else c.height)
        val density=a.optInt("density",(c.density.toDouble()*width/c.width).toInt().coerceIn(120,640))
        return VirtualScreenConfig(width,height,density,a.optInt("rotation",c.rotation),a.optBoolean("allow_screen_off",c.allowScreenOff),a.optInt("idle_timeout_minutes",c.idleTimeoutMinutes))
    }
    private fun operation(name: String,a: JSONObject): BackendOperation = when(name) {
        "tap_at" -> BackendOperation.Tap(a.getInt("x"),a.getInt("y"))
        "long_press_at" -> BackendOperation.LongPress(a.getInt("x"),a.getInt("y"),a.optInt("duration_ms",600))
        "swipe_path" -> a.getJSONArray("points").let { p -> BackendOperation.Swipe(List(p.length()) { i -> p.getJSONArray(i).let { it.getInt(0) to it.getInt(1) } },a.optInt("duration_ms",300)) }
        "scroll_at" -> BackendOperation.Scroll(a.getInt("x"),a.getInt("y"),a.getInt("delta"))
        "input_text" -> BackendOperation.Text(a.getString("text"),a.optBoolean("replace"))
        "press_key" -> BackendOperation.Key(a.optInt("key_code",4))
        "node_action" -> BackendOperation.NodeAction(a.getString("node_id"),a.getString("action"),a.optString("value").takeIf { a.has("value") })
        else -> error("UNKNOWN_TOOL")
    }
    private fun validateAction(name: String,a: JSONObject) {
        val action=a.optString("action")
        val forbidden=when(name) {
            "request_control" -> if(action == "request") setOf("approval_id") else emptySet()
            "node_action" -> {
                require(a.has("node_id") xor a.has("selector")) { "INVALID_NODE_TARGET" }
                if(action != "set_text") setOf("value") else { require(a.has("value")) { "MISSING_ARGUMENT" }; emptySet() }
            }
            "observe" -> { require(!(a.has("include_nodes") && a.has("mode"))) { "ARGUMENT_NOT_APPLICABLE" }; emptySet() }
            "wait" -> setOf("text","fingerprint","selector","package","case_sensitive")-when(a.optString("condition","delay")) {
                "frame_changed" -> setOf("fingerprint")
                "node_text" -> setOf("text","case_sensitive")
                "node_present","node_absent" -> setOf("selector")
                "app_foreground" -> setOf("package")
                else -> emptySet()
            }
            else -> emptySet()
        }
        require(forbidden.none(a::has)) { "ARGUMENT_NOT_APPLICABLE" }
    }
    private fun leaseResult(d: LeaseDecision)=when(d) {
        is LeaseDecision.Granted -> OperationEnvelope.success("client_id" to d.lease.owner,"lease_token" to d.lease.token,"idle_timeout_ms" to 300000,"generation" to d.lease.generation)
        is LeaseDecision.Rejected -> OperationEnvelope.failure(d.code,null,"controller" to d.currentOwner)
    }
    companion object {
        fun encode(e: OperationEnvelope,l: UiLanguage = UiLanguage.EN,purpose: String = "",maxImageWidth: Int = 2048): JSONObject {
            val data=JSONObject().put("ok",e.ok).put("code",e.code ?: JSONObject.NULL).put("session_id",e.sessionId ?: JSONObject.NULL).put("language",l.tag).put("purpose",purpose)
            // Backend payloads carry their original status; the evaluated envelope owns protocol fields.
            e.values.filterKeys { it !in setOf("ok","code","session_id","language","purpose","frame","message","image_base64") }
                .forEach { (k,v) -> data.put(k,JSONObject.wrap(v)) }
            val frame=e.values["frame"] as? Bitmap
            val image=frame?.let { if(it.width>maxImageWidth) Bitmap.createScaledBitmap(it,maxImageWidth,(it.height.toDouble()*maxImageWidth/it.width).toInt().coerceAtLeast(1),true) else it }
            if(image!=null) data.put("image_width",image.width).put("image_height",image.height).put("coordinate_space","display")
                .put("image_to_display_scale",JSONArray(listOf(frame.width.toDouble()/image.width,frame.height.toDouble()/image.height)))
            data.put("message", SystemToolModelMessages.message(l,e.code) ?: e.values["message"] ?: io.github.lejw0925.virtualscreen.phone.phoneUsageMessage(l,e.code) ?: io.github.lejw0925.virtualscreen.systemtools.systemToolMessage(l,e.code) ?: when {
                e.values["confirmation"] == "requires_user_action" -> l.text("等待你在手机内授权；操作尚未执行。","Waiting for your action in the phone app; not executed.","等待你在手機內授權；操作尚未執行。")
                e.values["effect_status"] == "unconfirmed" -> l.text("结果尚未确认，请查看回执；不要自动重复执行。","The result is unconfirmed. Check the receipt; do not repeat automatically.","結果尚未確認，請查看回執；不要自動重複執行。")
                e.ok -> l.text("操作完成","Operation completed","操作完成")
                else -> localizedError(l,e.code ?: "BACKEND_OPERATION_FAILED")
            })
            if (e.code in setOf("MAIN_SCREEN_BORROW_PENDING", "MAIN_SCREEN_REQUEST_PENDING", "MAIN_SCREEN_COUNTDOWN")) {
                data.put("recovery_tools", JSONArray(listOf("phone_get_status"))).put("retry_automatically", false)
            } else if (e.values["main_screen_allowed"] == true && e.code in setOf("STALE_OBSERVATION", "OBSERVATION_REQUIRED", "MAIN_SCREEN_MODE_ACTIVE")) {
                data.put("requires_observation", true).put("recovery_tools", JSONArray(listOf("main_screen_observe")))
            } else if (e.code in setOf("LEASE_REQUIRED", "CONTROL_CHANGED", "LEASE_EXPIRED", "LEASE_TOKEN_INVALID")) {
                data.put("requires_control",true).put("requires_observation",true)
                    .put("recovery_tools",JSONArray(listOf("get_status","acquire_control","observe")))
            } else if (e.code == "APP_MOVED_TO_PHONE") {
                data.put("requires_relaunch",true).put("requires_observation",true)
                    .put("recovery_tools",JSONArray(listOf("launch_app","observe")))
            } else if (e.code in setOf("NO_VIRTUAL_SCREEN","DISPLAY_GONE") || e.values["observation_error"] in setOf("NO_VIRTUAL_SCREEN","DISPLAY_GONE")) {
                data.put("requires_display",true).put("requires_observation",true)
                    .put("recovery_tools",JSONArray(listOf("create_screen","launch_app","observe")))
            } else if (e.code in setOf("OBSERVATION_REQUIRED","STALE_OBSERVATION","STALE_VIRTUAL_SCREEN")) {
                data.put("requires_observation",true).put("recovery_tools",JSONArray(listOf("observe")))
            }
            val content=JSONArray().put(JSONObject().put("type","text").put("text",data.toString()))
            (e.values["image_base64"] as? String)?.takeIf { e.ok }?.let {
                content.put(JSONObject().put("type","image").put("mimeType","image/jpeg").put("data",it))
            }
            image?.let { bitmap -> val bytes=ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.JPEG,80,bytes)
                content.put(JSONObject().put("type","image").put("mimeType","image/jpeg").put("data",Base64.encodeToString(bytes.toByteArray(),Base64.NO_WRAP)))
                if(bitmap!==frame) bitmap.recycle() }
            return JSONObject().put("isError",!e.ok).put("structuredContent",data).put("content",content)
        }
        private fun string(max: Int = 8192)=JSONObject().put("type","string").put("maxLength",max)
        private fun choice(vararg values: String)=string().put("enum",JSONArray(values.toList()))
        private fun boolean()=JSONObject().put("type","boolean")
        private fun integer(min: Int,max: Int)=JSONObject().put("type","integer").put("minimum",min).put("maximum",max)
        private fun longInteger()=JSONObject().put("type","integer").put("minimum",0L).put("maximum",9007199254740991L)
        private fun conditional(field: String,value: String,required: List<String>,properties: Map<String,JSONObject> = emptyMap()) = JSONObject()
            .put("if",JSONObject().put("properties",JSONObject().put(field,JSONObject().put("const",value))).put("required",JSONArray(listOf(field))))
            .put("then",JSONObject().put("required",JSONArray(required)).put("properties",JSONObject(properties)))
        private fun tool(l: UiLanguage,name: String,description: String,properties: Map<String,JSONObject> = emptyMap(),required: Collection<String> = emptyList(),readOnly: Boolean = false,branches: Map<String,Set<String>> = emptyMap()): McpTool {
            val language=l.text("简体中文","English","繁體中文")
            val purpose=string(300).put("minLength",1).put("description",l.text("用手机系统语言（$language）说明此操作要完成什么，不要包含凭据。","Explain the goal of this operation in the phone's system language ($language). Do not include credentials.","用手機系統語言（$language）說明此操作要完成什麼，不要包含憑證。"))
            val schema=JSONObject().put("type","object").put("properties",JSONObject(properties+("purpose" to purpose))).put("required",JSONArray(required+"purpose")).put("additionalProperties",false)
            if(branches.isNotEmpty()) schema.put("allOf",JSONArray(branches.map { (action,fields) -> JSONObject().put("if",JSONObject().put("properties",JSONObject().put("action",JSONObject().put("const",action))))
                .put("then",JSONObject().put("required",JSONArray(fields.toList()))) }))
            return McpTool(name,description+" "+l.text("每次调用必须填写 purpose。","Every call requires purpose.","每次呼叫必須填寫 purpose。"),schema,readOnly)
        }
        private fun validate(schema: JSONObject,args: JSONObject) {
            val required=schema.getJSONArray("required"); repeat(required.length()) {
                val key=required.getString(it)
                if(key=="observation_id") require(args.optString(key).isNotBlank() && !args.isNull(key)) { "OBSERVATION_REQUIRED" }
                else require(args.has(key)) { "MISSING_ARGUMENT" }
            }
            val props=schema.getJSONObject("properties")
            args.keys().forEach { key -> require(props.has(key)) { "UNKNOWN_ARGUMENT" }; validateValue(props.getJSONObject(key),args.get(key)) }
            schema.optJSONArray("allOf")?.let { branches -> repeat(branches.length()) { i ->
                val branch=branches.getJSONObject(i)
                val conditions=branch.getJSONObject("if").getJSONObject("properties")
                if(conditions.keys().asSequence().all { args.optString(it)==conditions.getJSONObject(it).getString("const") }) {
                    val then=branch.getJSONObject("then")
                    val fields=then.getJSONArray("required"); repeat(fields.length()) { require(args.has(fields.getString(it))) { "MISSING_ARGUMENT" } }
                    then.optJSONObject("properties")?.let { p -> p.keys().forEach { if(args.has(it)) validateValue(p.getJSONObject(it),args.get(it)) } }
                }
            } }
        }
        private fun validateValue(s: JSONObject,v: Any) {
            if(s.has("not")) {
                val forbidden=s.getJSONObject("not").opt("const")
                require(if(v is Number && forbidden is Number) v.toDouble()!=forbidden.toDouble() else v!=forbidden) { "INVALID_ARGUMENT" }
            }
            when(s.getString("type")) {
                "string" -> require(v is String && v.length in s.optInt("minLength",0)..s.getInt("maxLength") && '\u0000' !in v && (!s.has("enum") || (0 until s.getJSONArray("enum").length()).any { s.getJSONArray("enum").getString(it)==v })) { "INVALID_ARGUMENT" }
                "integer" -> require(v is Number && v.toDouble().isFinite() && v.toDouble()==v.toLong().toDouble() && v.toLong() in s.getLong("minimum")..s.getLong("maximum") && (!s.has("enum") || (0 until s.getJSONArray("enum").length()).any { s.getJSONArray("enum").getInt(it)==v.toInt() })) { "INVALID_ARGUMENT" }
                "boolean" -> require(v is Boolean) { "INVALID_ARGUMENT" }
                "object" -> { require(v is JSONObject && v.length()>=s.optInt("minProperties",0)) { "INVALID_ARGUMENT" }; val props=s.getJSONObject("properties"); v.keys().forEach { require(props.has(it)) { "UNKNOWN_ARGUMENT" }; validateValue(props.getJSONObject(it),v.get(it)) } }
                "array" -> { require(v is JSONArray && v.length() in s.getInt("minItems")..s.getInt("maxItems")) { "INVALID_ARGUMENT" }; repeat(v.length()) { validateValue(s.getJSONObject("items"),v.get(it)) } }
            }
        }
    }
}

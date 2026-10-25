package io.github.lejw0925.virtualscreen.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.lejw0925.virtualscreen.VirtualScreenApplication

class WirelessAdbActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as VirtualScreenApplication
        setContent { VirtualScreenTheme(app) {
            val l = LocalLanguage.current
            val status by app.wirelessAdb.state.collectAsState()
            var host by remember { mutableStateOf("127.0.0.1") }
            var pairingPort by remember { mutableStateOf("") }
            var connectPort by remember { mutableStateOf("") }
            var code by remember { mutableStateOf("") }
            val automatic by app.wirelessAdb.enabledState.collectAsState()
            val busy = status in setOf("pairing", "paired_connecting", "restoring", "starting_embedded_service")
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.systemBarsPadding().imePadding().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(l.text("内置授权与开机恢复", "Built-in access and boot recovery", "內建授權與開機恢復"), style = MaterialTheme.typography.headlineSmall)
                    Text(l.text("在本机开发者选项开启无线调试，并打开“使用配对码配对设备”。填写其中的端口与配对码。配对码只在本次使用，不保存。", "Enable wireless debugging in this phone's developer settings and open Pair device with pairing code. Enter its port and code. The code is used once and is not saved.", "在本機開發人員選項開啟無線偵錯，並開啟「使用配對碼配對裝置」。填寫其中的連接埠與配對碼。配對碼只在本次使用，不儲存。"))
                    TextButton(onClick = { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }) { Text(l.text("打开开发者选项", "Open developer settings", "開啟開發人員選項")) }
                    OutlinedTextField(host, { host = it }, label = { Text(l.text("本机地址", "This phone's address", "本機位址")) })
                    OutlinedTextField(pairingPort, { pairingPort = it }, label = { Text(l.text("配对端口", "Pairing port", "配對連接埠")) })
                    OutlinedTextField(code, { code = it }, visualTransformation = PasswordVisualTransformation(), label = { Text(l.text("六位配对码", "Six-digit pairing code", "六位配對碼")) })
                    OutlinedTextField(connectPort, { connectPort = it }, label = { Text(l.text("连接端口（可留空自动发现）", "Connection port (optional discovery)", "連線連接埠（可留空自動探索）")) })
                    Text(l.text("配对后启动本应用的内置特权服务，并申请修改安全设置权限，以便重启后恢复无线调试。自动恢复可以关闭；若系统限制恢复，会在这里显示原因。", "Pairing starts this app's built-in privileged service and requests secure-settings permission for wireless debugging after reboot. You can disable automatic recovery. System restrictions are reported here.", "配對後啟動本應用程式的內建特權服務，並申請修改安全設定權限，以便重新啟動後恢復無線偵錯。自動恢復可以關閉；若系統限制恢復，會在此顯示原因。"))
                    Button(enabled = code.matches(Regex("[0-9]{6}")) && pairingPort.toIntOrNull() in 1..65535 && (connectPort.isBlank() || connectPort.toIntOrNull() in 1..65535) && !busy, onClick = {
                        app.wirelessAdb.pair(host.trim(), pairingPort.toInt(), code, connectPort.toIntOrNull())
                        code = ""
                    }) { Text(l.text("配对并启动内置服务", "Pair and start built-in service", "配對並啟動內建服務")) }
                    Row {
                        Switch(automatic, { app.wirelessAdb.setEnabled(it) })
                        Text(l.text("开机后自动恢复", "Recover automatically after reboot", "開機後自動恢復"))
                    }
                    TextButton(enabled = app.wirelessAdb.paired && !busy, onClick = { app.wirelessAdb.startPaired() }) { Text(l.text("恢复已配对的内置服务", "Restore paired built-in service", "恢復已配對的內建服務")) }
                    Text(when (status) {
                        "idle" -> if (app.wirelessAdb.paired) l.text("已配对，可恢复服务", "Paired; service can be restored", "已配對，可恢復服務") else l.text("尚未配对", "Not paired", "尚未配對")
                        "pairing", "paired_connecting", "restoring" -> l.text("正在连接…", "Connecting…", "正在連線…")
                        "starting_embedded_service" -> l.text("正在启动内置特权服务…", "Starting built-in privileged service…", "正在啟動內建特權服務…")
                        "embedded_ready" -> l.text("内置特权服务已就绪", "Built-in privileged service is ready", "內建特權服務已就緒")
                        "embedded_ready_manual_wireless_after_reboot" -> l.text("内置服务已就绪；重启后需手动开启无线调试", "Built-in service ready; enable wireless debugging manually after reboot", "內建服務已就緒；重新啟動後需手動開啟無線偵錯")
                        "pairing_required" -> l.text("请先完成本机配对", "Pair this phone first", "請先完成本機配對")
                        "enable_wireless_debugging_manually" -> l.text("请在开发者选项中开启无线调试，再点击恢复服务", "Enable wireless debugging in developer settings, then restore the service", "請在開發人員選項中開啟無線偵錯，再點擊恢復服務")
                        "automatic_recovery_off" -> l.text("已关闭自动恢复", "Automatic recovery disabled", "已關閉自動恢復")
                        else -> localizedError(l, status.removePrefix("manual_action_required:")) + " (" + status.removePrefix("manual_action_required:") + ")"
                    })
                    TextButton(onClick = { finish() }) { Text(l.text("返回", "Back", "返回")) }
                }
            }
        } }
    }
}

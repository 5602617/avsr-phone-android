package io.github.lejw0925.virtualscreen.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.lejw0925.virtualscreen.VirtualScreenApplication

/** ACTION_ASSIST is only an entry point. Assist extras/context are never read or sent. */
class AssistantActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Drop optional foreground-app context supplied by the system immediately.
        intent = Intent(Intent.ACTION_ASSIST)
        val app = application as VirtualScreenApplication
        setContent { VirtualScreenTheme(app) {
            val l = LocalLanguage.current
            val state by app.phoneConnection.state.collectAsState()
            var text by remember { mutableStateOf("") }
            Surface(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(l.text("和她说话", "Talk to her", "和她說話"), style = MaterialTheme.typography.headlineSmall)
                    if (state != "ready") {
                        Text(l.text("手机还未连上主机。", "The phone is not connected to the host.", "手機尚未連上主機。"))
                        TextButton(onClick = { startActivity(Intent(this@AssistantActivity, PhoneProductActivity::class.java)); finish() }) {
                            Text(l.text("打开连接", "Open connection", "開啟連線"))
                        }
                    }
                    PhoneVoiceInput(app.phoneConnection, state == "ready", onSent = { finish() })
                    OutlinedTextField(text, { text = it }, label = { Text(l.text("也可以输入文字", "Or type a message", "也可以輸入文字")) })
                    Row {
                        Button(enabled = state == "ready" && text.isNotBlank(), onClick = {
                            if (app.phoneConnection.sendText(text)) finish()
                        }) { Text(l.text("发送", "Send", "傳送")) }
                        TextButton(onClick = { finish() }) { Text(l.text("返回", "Back", "返回")) }
                    }
                }
            }
        } }
    }
}

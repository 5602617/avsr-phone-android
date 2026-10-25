package io.github.lejw0925.virtualscreen.ui

import android.os.Bundle
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import io.github.lejw0925.virtualscreen.VirtualScreenApplication
import io.github.lejw0925.virtualscreen.ui.design.*
import io.github.lejw0925.virtualscreen.ui.screens.herCurrentPortrait
import io.github.lejw0925.virtualscreen.backend.BackendOperation
import io.github.lejw0925.virtualscreen.backend.LiveGeometry
import io.github.lejw0925.virtualscreen.core.code
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import java.util.UUID

/** A local-only viewer of the same display. It never moves or relaunches the target app. */
class HumanHandoffActivity : ComponentActivity() {
    private val app get() = application as VirtualScreenApplication
    private val viewer = "human-" + UUID.randomUUID()
    private val taskId get() = intent.getStringExtra("task_id").orEmpty()
    private val sessionId get() = intent.getStringExtra("session_id").orEmpty()
    private val handoffId get() = intent.getStringExtra("handoff_id").orEmpty()
    private val localJobs = mutableSetOf<Job>()
    private var renderer: LiveDisplayView? = null
    private var resumed by mutableStateOf(false)
    private var returning by mutableStateOf(false)
    private var error by mutableStateOf("")

    private fun valid(): Boolean {
        val usage = app.mainScreen.policy.state.value
        return display?.displayId == Display.DEFAULT_DISPLAY && usage.phase == "human_control" &&
            usage.taskId == taskId && usage.handoffSessionId == sessionId && usage.handoffId == handoffId &&
            usage.connectionId == app.phoneSession.flow.value.connectionId &&
            app.sessions.state.value.sessionId == sessionId && app.sessions.state.value.displayId != null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!valid()) { finish(); return }
        // A separate local task preserves the user's previous foreground app in Android's task stack.
        setContent { VirtualScreenTheme(app) { io.github.lejw0925.virtualscreen.ui.design.YaoTheme { HandoffScreen() } } }
        lifecycleScope.launch {
            combine(app.mainScreen.policy.state, app.sessions.state, app.phoneSession.flow) { _, _, _ -> valid() }
                .collect { current -> if (!current) { renderer?.setResumed(false); finish() } }
        }
    }
    override fun onResume() {
        super.onResume()
        if (!valid()) { finish(); return }
        app.sessions.beginPhoneSession(viewer)
        resumed = true
        app.avsr.humanViewerVisible.value = true
        app.sessions.previewVisibility(true)
        renderer?.setResumed(true)
    }
    override fun onPause() {
        resumed = false
        app.avsr.humanViewerVisible.value = false
        renderer?.setResumed(false)
        localJobs.toList().forEach { it.cancel() }
        app.sessions.endPhoneSession(viewer, retainControl = true)
        app.sessions.previewVisibility(false)
        super.onPause()
    }
    override fun onDestroy() {
        renderer?.dispose(); renderer = null
        app.sessions.endPhoneSession(viewer, retainControl = true)
        super.onDestroy()
    }

    private fun localInput(operation: BackendOperation) {
        if (!valid() || returning) return
        val lease = app.sessions.beginPhoneGesture()
        val job = lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                withContext(Dispatchers.IO) {
                    check(valid()) { "PHONE_HANDOFF_STALE" }
                    val observation = app.sessions.observe(lease.owner, lease.token).getOrThrow()
                    val result = app.sessions.operate(lease.owner, lease.token,
                        observation.values.getValue("observation_id") as String, operation)
                    check(result.ok) { result.code ?: "INPUT_FAILED" }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.code() }
            finally { app.sessions.finishPhoneControl(lease.token) }
        }
        localJobs.add(job)
        job.invokeOnCompletion { lifecycleScope.launch { localJobs.remove(job) } }
    }
    private fun returnToAgent() {
        if (!valid() || returning) return
        returning = true
        renderer?.setResumed(false) // Cancels a touch before control may be returned.
        localJobs.toList().forEach { it.cancel() }
        app.sessions.endPhoneSession(viewer)
        app.avsr.returnHumanHandoff(taskId, handoffId)
        if (app.mainScreen.policy.state.value.phase == "running_virtual") finish()
        else {
            error = app.avsr.state.value.error
            returning = false
            if (valid()) { app.sessions.beginPhoneSession(viewer); renderer?.setResumed(resumed) }
        }
    }

    @Composable private fun HandoffScreen() {
        val l = LocalLanguage.current
        val screen by app.sessions.state.collectAsState()
        val usage by app.mainScreen.policy.state.collectAsState()
        var keyboard by remember { mutableStateOf(false) }
        var text by remember { mutableStateOf("") }
        var live by remember { mutableStateOf(false) }
        LaunchedEffect(resumed, returning, sessionId) {
            if (!resumed || returning) return@LaunchedEffect
            while (isActive && valid()) {
                withContext(Dispatchers.IO) { runCatching { app.sessions.preview() } }
                delay(if (live) 1000 else 350)
            }
        }
        BackHandler {
            if (keyboard) keyboard = false
            else localInput(BackendOperation.Back)
        }
        val p = LocalYao.current
        val her by app.avsr.state.collectAsState()
        Column(Modifier.fillMaxSize().background(p.paper).statusBarsPadding().navigationBarsPadding().imePadding()) {
            Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(l.text("现在由你操作，她正在等待", "You are in control. She is waiting.", "現在由你操作，她正在等待"),
                    style = YaoType.section, color = p.ink, textAlign = TextAlign.Center)
                if (usage.handoffReason.isNotBlank()) Text(usage.handoffReason, style = YaoType.small, color = p.inkSoft,
                    maxLines = 2, textAlign = TextAlign.Center)
                if (error.isNotBlank()) Text(error, style = YaoType.small, color = p.rose, textAlign = TextAlign.Center)
            }
            Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 28.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                val frame = screen.frame
                YaoScreenFrame(frame?.width ?: screen.config.width, frame?.height ?: screen.config.height) {
                    AndroidView(factory = { context -> LiveDisplayView(context).also { renderer = it } },
                        modifier = Modifier.fillMaxSize(),
                        onRelease = { it.dispose(); if (renderer === it) renderer = null },
                        update = { view ->
                            view.onLiveChanged = { live = it }
                            view.onManualDown = {
                                if (valid() && resumed && !returning) app.sessions.beginPhoneGesture().token else null
                            }
                            view.onManualComplete = { app.sessions.finishPhoneControl(it) }
                            view.onCompatibilityInput = { localInput(it) }
                            view.onError = { if (it != "DISPLAY_MIRROR_UNAVAILABLE") error = it }
                            view.setTarget(screen.sessionId, app.sessions.liveChannel, screen.displayId,
                                LiveGeometry(frame?.width ?: screen.config.width, frame?.height ?: screen.config.height, screen.frameRotation), frame)
                            view.setResumed(resumed && !returning && valid())
                        })
                }
            }
            // Tools that act inside her screen, kept quiet so the two decisions below stand out.
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                YaoTextButton(l.text("返回", "Back", "返回"), { localInput(BackendOperation.Back) })
                YaoTextButton(l.text("输入文字", "Type text", "輸入文字"), { keyboard = true })
                YaoTextButton(l.text("稍后处理", "Handle later", "稍後處理"), { finish() })
            }
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                YaoAvatar(herCurrentPortrait(her), 36.dp)
                YaoOutlineButton(l.text("结束这件事", "End this task", "結束這件事"),
                    { renderer?.setResumed(false); app.avsr.cancelTask(taskId); finish() }, Modifier.weight(1f), danger = true)
                YaoButton(l.text("处理好了，继续", "Done, continue", "處理好了，繼續"), ::returnToAgent, Modifier.weight(1.3f),
                    busy = returning)
            }
        }
        if (keyboard) AlertDialog(onDismissRequest = { keyboard = false }, containerColor = p.card, shape = YaoShapes.card,
            title = { Text(l.text("输入到她的屏幕", "Type into her screen", "輸入到她的螢幕"), style = YaoType.section) },
            text = { YaoField(text, { text = it }, l.text("文字", "Text", "文字"), singleLine = false) },
            confirmButton = { YaoTextButton(l.text("输入", "Type", "輸入"), { localInput(BackendOperation.Text(text)); text = ""; keyboard = false }) },
            dismissButton = { YaoTextButton(l.text("取消", "Cancel", "取消"), { text = ""; keyboard = false }) })
    }
}

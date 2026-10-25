package io.github.lejw0925.virtualscreen.ui

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import io.github.lejw0925.virtualscreen.ui.design.*
import io.github.lejw0925.virtualscreen.ui.screens.herCurrentPortrait
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import io.github.lejw0925.virtualscreen.VirtualScreenApplication
import io.github.lejw0925.virtualscreen.backend.LiveGeometry
import io.github.lejw0925.virtualscreen.core.SessionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Observes the existing display without acquiring control or changing the running task. */
class VirtualScreenPreviewActivity : ComponentActivity() {
    private val app get() = application as VirtualScreenApplication
    private var renderer: LiveDisplayView? = null
    private var resumed by mutableStateOf(false)

    private fun rejectSourceDisplay(): Boolean {
        if (!isSelfMirrorDisplay(display?.displayId, app.sessions.state.value.displayId)) return false
        renderer?.setResumed(false)
        finish()
        return true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (rejectSourceDisplay()) return
        setContent { VirtualScreenTheme(app) { YaoTheme { PreviewScreen() } } }
        lifecycleScope.launch {
            app.sessions.state.map { it.displayId }.distinctUntilChanged().collect { source ->
                if (rejectSourceDisplay()) return@collect
                if (resumed) app.sessions.previewVisibility(source != null)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (rejectSourceDisplay()) return
        resumed = true
        app.sessions.previewVisibility(app.sessions.state.value.displayId != null)
        renderer?.setResumed(true)
    }

    override fun onPause() {
        resumed = false
        renderer?.setResumed(false)
        app.sessions.previewVisibility(false)
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        // A moved activity must not attach a display to itself.
        rejectSourceDisplay()
        super.onConfigurationChanged(newConfig)
    }

    override fun onDestroy() {
        renderer?.dispose()
        renderer = null
        super.onDestroy()
    }

    @Composable
    private fun PreviewScreen() {
        val l = LocalLanguage.current
        val p = LocalYao.current
        val screen by app.sessions.state.collectAsState()
        val her by app.avsr.state.collectAsState()
        var live by remember(screen.sessionId) { mutableStateOf(false) }
        var error by remember(screen.sessionId) { mutableStateOf("") }
        LaunchedEffect(resumed, screen.sessionId) {
            if (!resumed || screen.displayId == null || rejectSourceDisplay()) return@LaunchedEffect
            while (isActive && resumed && !isFinishing) {
                try {
                    withContext(Dispatchers.IO) { app.sessions.preview() }
                    error = ""
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    error = l.text("暂时无法刷新虚拟屏。", "The virtual screen could not be refreshed.", "暫時無法重新整理虛擬螢幕。")
                }
                delay(if (live) 1000 else 350)
            }
        }
        // Leaving this page never becomes a Back key inside the observed app.
        BackHandler { finish() }
        val status = when {
            screen.displayId == null -> null
            screen.status == SessionStatus.ERROR -> l.text("异常", "Error", "異常")
            live -> l.text("实时", "Live", "即時")
            screen.frame != null -> l.text("截图", "Snapshot", "截圖")
            else -> l.text("等待", "Waiting", "等待")
        }
        Column(Modifier.fillMaxSize().background(p.paper).statusBarsPadding().navigationBarsPadding()) {
            YaoScreenBar(l.text("她的屏幕", "Her screen", "她的螢幕"), l.text("返回", "Return", "返回"), { finish() }, status, live)
            Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 28.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                if (screen.displayId == null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        RibbonW(Modifier.width(44.dp), color = p.inkFaint)
                        Text(if (screen.status == SessionStatus.STARTING) l.text("正在准备她的屏幕…", "Preparing her screen…", "正在準備她的螢幕…")
                            else l.text("她现在没在用手机", "She is not using the phone right now", "她現在沒在用手機"),
                            style = YaoType.small, color = p.inkSoft)
                    }
                } else if (!isSelfMirrorDisplay(display?.displayId, screen.displayId)) {
                    val frame = screen.frame
                    val width = frame?.width ?: screen.config.width
                    val height = frame?.height ?: screen.config.height
                    YaoScreenFrame(width, height) {
                        AndroidView(factory = { context -> LiveDisplayView(context).also { renderer = it } },
                            modifier = Modifier.fillMaxSize(),
                            onRelease = { view -> view.dispose(); if (renderer === view) renderer = null },
                            update = { view ->
                                view.onLiveChanged = { live = it }
                                // Keep all input callbacks inert, including the JPEG compatibility path.
                                view.onManualDown = { null }
                                view.onManualComplete = {}
                                view.onCompatibilityInput = {}
                                view.onError = { code ->
                                    if (code != "DISPLAY_MIRROR_UNAVAILABLE") error = localizedError(l, code)
                                }
                                view.setTarget(screen.sessionId, app.sessions.liveChannel, screen.displayId,
                                    LiveGeometry(width, height, screen.frameRotation), frame)
                                view.setResumed(resumed && !isFinishing &&
                                    !isSelfMirrorDisplay(display?.displayId, screen.displayId))
                            })
                    }
                }
            }
            // What she is doing, from the task's own step text.
            val doing = listOf(her.task.appName, her.task.step).filter { it.isNotBlank() }.joinToString(" · ")
            YaoScreenCaption(herCurrentPortrait(her),
                doing.ifBlank { l.text("只是看看，不会打断她", "Just watching; she keeps going", "只是看看，不會打斷她") },
                speaking = her.voice == "speaking")
            val problem = screen.errorCode?.let { localizedError(l, it) } ?: error.takeIf { it.isNotBlank() }
            if (problem != null) Text(problem, Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
                style = YaoType.small, color = p.rose, textAlign = TextAlign.Center)
        }
    }
}

package io.github.lejw0925.virtualscreen

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.os.PowerManager
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.github.lejw0925.virtualscreen.backend.BackendKind
import io.github.lejw0925.virtualscreen.backend.CapabilityStatus
import io.github.lejw0925.virtualscreen.backend.ShizukuBackend
import io.github.lejw0925.virtualscreen.core.VirtualScreenSessionManager
import io.github.lejw0925.virtualscreen.ui.ViewerActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DevicePreviewTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    @Test fun mcpStaysSilentAndExplicitPreviewKeepsRemoteControl() = runBlocking<Unit> {
        val authorized = ShizukuBackend(context).probe().displayCreation != CapabilityStatus.REQUIRES_AUTHORIZATION
        if (InstrumentationRegistry.getArguments().getString("require_shizuku") == "true") {
            assertTrue("Shizuku is required", authorized)
        } else assumeTrue("Grant Shizuku before validating PiP", authorized)
        val app = context.applicationContext as VirtualScreenApplication
        val manager = app.sessions
        assertNull("Run with no existing display", manager.state.value.displayId)
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        instrumentation.runOnMainSync {
            context.startForegroundService(Intent(context, VirtualScreenForegroundService::class.java))
        }
        manager.selectBackend(BackendKind.SHIZUKU)
        val originalPip = app.settings.flow.value.pipEnabled
        app.settings.update { it.copy(pipEnabled=false) }
        app.mcp.start().getOrThrow()
        val client = PreviewMcpClient("http://127.0.0.1:${app.mcp.state().port}/mcp", app.mcp.state().token)
        var lease = client.tool("acquire_control").getString("lease_token")
        try {
            client.tool("create_screen", "lease_token" to lease, "width" to 720, "height" to 1600,
                "density" to 320, "idle_timeout_minutes" to 0)
            val display = manager.state.value.displayId
            val session = manager.state.value.sessionId
            client.tool("launch_app", "lease_token" to lease,
                "component" to "io.github.lejw0925.virtualscreen.fixture/.FixtureActivity", "observe_after" to "screenshot")
            delay(500)
            assertNull("Remote control must not open a viewer", viewer())
            app.settings.update { it.copy(pipEnabled=true) }
            delay(500)
            assertNull("Enabling PiP must not open a viewer", viewer())
            val remoteOwner=client.tool("get_status").getString("controller")
            openPreview()
            awaitPip()
            assertFalse(manager.phoneViewerActive)

            repeat(2) { cycle ->
                screenshot(automation, "pip-$cycle")
                showPipMenu(automation)
                val expand = try {
                    withTimeout(5000) {
                        var button: AccessibilityNodeInfo? = null
                        while (button == null) {
                            button = automation.windows.firstNotNullOfOrNull { findExpand(it.root) }
                            if (button == null) delay(100)
                        }
                        button
                    }
                } catch (failure: Exception) {
                    screenshot(automation, "missing-expand")
                    throw failure
                }
                assertTrue("Click the actual system PiP Expand button", expand.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                await {
                    viewer()?.let { !it.isInPictureInPictureMode && it.hasWindowFocus() && it.window.decorView.alpha == 1f } == true &&
                        !manager.phoneViewerActive && manager.leaseStatus()?.owner == remoteOwner
                }
                assertEquals(display, manager.state.value.displayId)
                assertEquals(session, manager.state.value.sessionId)
                screenshot(automation, "fullscreen-$cycle")
                assertEquals(remoteOwner, client.tool("get_status").getString("controller"))
                client.tool("observe", "lease_token" to lease, "mode" to "screenshot")

                if(cycle == 1) {
                    // Real system touch, not a direct call into the lease manager.
                    showPipMenu(automation)
                    await { manager.leaseStatus()?.owner == VirtualScreenSessionManager.PHONE_CLIENT }
                    val rejected=client.tool("observe", "lease_token" to lease, "mode" to "screenshot", ok=false)
                    assertEquals("LEASE_REQUIRED",rejected.getString("code"))
                    val release=withTimeout(5000) {
                        var found: AccessibilityNodeInfo?=null
                        while(found==null) {
                            found=automation.windows.firstNotNullOfOrNull { findRelease(it.root) }
                            if(found==null) delay(100)
                        }
                        found
                    }
                    screenshot(automation,"touch-takeover")
                    var button: AccessibilityNodeInfo?=release
                    while(button != null && !button.isClickable) button=button.parent
                    assertTrue("Click the actual release button",button?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true)
                    await { manager.leaseStatus()==null }
                    lease=client.tool("acquire_control").getString("lease_token")
                }

                if (cycle == 0) {
                    val old = viewer()!!
                    instrumentation.runOnMainSync { old.recreate() }
                    await { viewer()?.let { it !== old && !it.isInPictureInPictureMode && it.hasWindowFocus() && it.window.decorView.alpha == 1f } == true }
                }
                automation.executeShellCommand("input -d 0 keyevent KEYCODE_HOME").use { descriptor ->
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
                }
                awaitPip()
                await { !manager.phoneViewerActive && manager.leaseStatus()?.owner == remoteOwner }
                val observed = client.tool("observe", "lease_token" to lease, "mode" to "screenshot")
                client.tool("tap_at", "lease_token" to lease, "observation_id" to observed.getString("observation_id"),
                    "x" to 350, "y" to 160, "observe_after" to "screenshot")
            }
            client.tool("release_control", "lease_token" to lease)
            await { viewer() == null }
            assertEquals(display, manager.state.value.displayId)
            assertEquals(session, manager.state.value.sessionId)
            lease = client.tool("acquire_control").getString("lease_token")
            delay(500)
            assertNull("Reacquiring must not open a viewer",viewer())
            openPreview()
            awaitPip()
            client.tool("observe", "lease_token" to lease, "mode" to "screenshot")
        } finally {
            app.settings.update { it.copy(pipEnabled=originalPip) }
            manager.shutdown()
            client.close()
        }
    }

    @Suppress("DEPRECATION")
    @Test fun mcpPreviewClosesAtIdleLeaseDeadlineWithoutReleaseOrStatusPolling() = runBlocking<Unit> {
        val authorized = ShizukuBackend(context).probe().displayCreation != CapabilityStatus.REQUIRES_AUTHORIZATION
        if (InstrumentationRegistry.getArguments().getString("require_shizuku") == "true") {
            assertTrue("Shizuku is required", authorized)
        } else assumeTrue("Grant Shizuku before validating PiP expiry", authorized)
        val app = context.applicationContext as VirtualScreenApplication
        val manager = app.sessions
        assertNull("Run with no existing display", manager.state.value.displayId)
        instrumentation.runOnMainSync {
            context.startForegroundService(Intent(context, VirtualScreenForegroundService::class.java))
        }
        manager.selectBackend(BackendKind.SHIZUKU)
        app.mcp.start().getOrThrow()
        val client = PreviewMcpClient("http://127.0.0.1:${app.mcp.state().port}/mcp", app.mcp.state().token)
        val wakeLock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "VirtualScreen:preview-expiry-test")
        try {
            wakeLock.acquire(330_000)
            val lease = client.tool("acquire_control").getString("lease_token")
            client.tool("create_screen", "lease_token" to lease, "width" to 720, "height" to 1600,
                "density" to 320, "idle_timeout_minutes" to 0)
            client.tool("launch_app", "lease_token" to lease,
                "component" to "io.github.lejw0925.virtualscreen.fixture/.FixtureActivity", "observe_after" to "screenshot")
            openPreview()
            awaitPip()
            val display = manager.state.value.displayId
            val deadline = manager.state.value.lease!!.expiresAtMillis
            // Read StateFlow only: the client makes no further calls until after expiry.
            while (deadline - System.nanoTime() / 1_000_000 > 1000) {
                assertNotNull(manager.state.value.lease)
                assertNotNull(viewer())
                val remaining = deadline - System.nanoTime() / 1_000_000
                println("Waiting for idle PiP expiry: ${remaining / 1000}s remaining")
                delay(minOf(30_000, remaining - 1000))
            }
            await { manager.state.value.lease == null && viewer() == null }
            assertEquals(display, manager.state.value.displayId)
            // The transport session also expires after five idle minutes.
            client.reconnect()
            assertEquals("hidden", client.tool("get_status").getString("preview_status"))
            assertEquals("LEASE_REQUIRED", client.tool("observe", "lease_token" to lease,
                "mode" to "screenshot", ok = false).getString("code"))
            val next = client.tool("acquire_control").getString("lease_token")
            openPreview()
            awaitPip()
            client.tool("observe", "lease_token" to next, "mode" to "screenshot")
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
            manager.shutdown()
            client.close()
        }
    }

    private suspend fun await(label: String="state transition",condition: () -> Boolean) {
        try {
            withTimeout(10000) { while (!condition()) delay(100) }
        } catch(failure: kotlinx.coroutines.TimeoutCancellationException) {
            val app=context.applicationContext as VirtualScreenApplication
            val activity=viewer()
            throw AssertionError("$label: viewer=${activity!=null}, pip=${activity?.isInPictureInPictureMode}, width=${activity?.window?.decorView?.width}, phone=${app.sessions.phoneViewerActive}, owner=${app.sessions.leaseStatus()?.owner}",failure)
        }
    }

    private suspend fun awaitPip() {
        val displayWidth = context.resources.displayMetrics.widthPixels
        await("PiP transition; displayWidth=$displayWidth") { viewer()?.let { it.isInPictureInPictureMode && it.window.decorView.width in 1 until displayWidth } == true }
        // The mode callback precedes the system's entry animation and touch region update.
        delay(600)
    }

    private fun openPreview() = instrumentation.runOnMainSync {
        context.startActivity(Intent(context,ViewerActivity::class.java)
            .putExtra("pip",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun viewer(): ViewerActivity? {
        var activity: ViewerActivity? = null
        instrumentation.runOnMainSync {
            activity = listOf(Stage.RESUMED, Stage.PAUSED, Stage.STARTED)
                .flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }
                .filterIsInstance<ViewerActivity>().firstOrNull { !it.isFinishing }
        }
        return activity
    }

    private suspend fun showPipMenu(automation: UiAutomation) {
        val bounds = Rect()
        val activity = viewer()!!
        instrumentation.runOnMainSync {
            val view = activity.window.decorView
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            bounds.set(location[0], location[1], location[0] + view.width, location[1] + view.height)
        }
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                bounds.exactCenterX(), bounds.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue(automation.injectInputEvent(event, true)) }
            finally { event.recycle() }
            if (action == MotionEvent.ACTION_DOWN) delay(50)
        }
    }

    private fun findExpand(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isClickable && node.viewIdResourceName?.endsWith(":id/expand_button") == true) return node
        return (0 until node.childCount).firstNotNullOfOrNull { findExpand(node.getChild(it)) }
    }

    private fun findRelease(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if(node==null) return null
        if(node.text?.toString() in setOf("Release control","交还 Agent","交還 Agent")) return node
        return (0 until node.childCount).firstNotNullOfOrNull { findRelease(node.getChild(it)) }
    }

    private fun screenshot(automation: UiAutomation, name: String) {
        val bitmap = automation.takeScreenshot() ?: error("Screenshot unavailable")
        try {
            File(context.getExternalFilesDir(null), "preview-$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { bitmap.recycle() }
    }

    /** Real HTTP transport keeps the test on the same entry path as an external MCP client. */
    private class PreviewMcpClient(private val url: String, private val token: String) : AutoCloseable {
        private val http = OkHttpClient()
        private var session: String? = null
        private var id = 0

        init { reconnect() }

        fun reconnect() {
            session = null
            request("initialize", JSONObject().put("protocolVersion", "2025-11-25")
                .put("capabilities", JSONObject()).put("clientInfo", JSONObject().put("name", "pip-device-test").put("version", "1")))
            request("notifications/initialized", notification = true)
        }

        fun tool(name: String, vararg values: Pair<String, Any>, ok: Boolean = true): JSONObject {
            val arguments = JSONObject(mapOf("purpose" to "验证 MCP 画中画与系统全屏切换") + values.toMap())
            val result = request("tools/call", JSONObject().put("name", name).put("arguments", arguments))
                .getJSONObject("result").getJSONObject("structuredContent")
            assertEquals("$name: ${result.optString("code")}", ok, result.getBoolean("ok"))
            return result
        }

        private fun request(method: String, params: JSONObject? = null, notification: Boolean = false): JSONObject {
            val body = JSONObject().put("jsonrpc", "2.0").put("method", method)
            if (!notification) body.put("id", ++id)
            params?.let { body.put("params", it) }
            val request = authenticated().post(body.toString().toRequestBody("application/json".toMediaType())).build()
            return http.newCall(request).execute().use {
                assertEquals(if (notification) 202 else 200, it.code)
                it.header("Mcp-Session-Id")?.let { value -> session = value }
                val response = it.body.string()
                if (response.isEmpty()) JSONObject() else JSONObject(response)
            }
        }

        private fun authenticated() = Request.Builder().url(url)
            .header("Authorization", "Bearer $token").header("Accept", "application/json, text/event-stream")
            .apply { session?.let { header("Mcp-Session-Id", it).header("MCP-Protocol-Version", "2025-11-25") } }

        override fun close() {
            try {
                http.newCall(authenticated().delete().build()).execute().use { assertEquals(204, it.code) }
            } finally {
                http.connectionPool.evictAll()
                http.dispatcher.executorService.shutdown()
            }
        }
    }
}

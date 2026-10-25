package io.github.lejw0925.virtualscreen

import android.app.Application
import io.github.lejw0925.virtualscreen.backend.BackendKind
import io.github.lejw0925.virtualscreen.core.LeaseDecision
import io.github.lejw0925.virtualscreen.core.VirtualScreenSessionManager
import io.github.lejw0925.virtualscreen.mcp.McpToolRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class CreateScreenObservationScenarioTest {
    @Test fun createScreenAcceptsPostObservationAndKeepsEmptyDisplayCreationSuccessful() = runBlocking {
        val backend = FakeBackend(BackendKind.ROOT).apply { inspectionFailure = "NO_RUNNING_APP" }
        val sessions = VirtualScreenSessionManager(RuntimeEnvironment.getApplication(), backend, FakeBackend(BackendKind.SHIZUKU), initialBackend = BackendKind.ROOT)
        try {
            val registry = McpToolRegistry(sessions)
            val lease = (sessions.acquireLease("agent") as LeaseDecision.Granted).lease
            val properties = registry.tools.first { it.name == "create_screen" }.schema.getJSONObject("properties")
            assertEquals("none", properties.getJSONObject("observe_after").getString("default"))
            val result = registry.call("create_screen", JSONObject().put("lease_token", lease.token)
                .put("purpose", "Create a virtual display for music").put("observe_after", "tree"), lease.owner)
            val data = result.getJSONObject("structuredContent")
            assertFalse(result.getBoolean("isError"))
            assertTrue(data.getBoolean("ok"))
            assertEquals("NO_RUNNING_APP", data.getString("observation_error"))
            assertTrue(data.getBoolean("requires_launch"))
            assertFalse(data.getBoolean("requires_display"))
            assertNotNull(sessions.state.value.displayId)
        } finally { sessions.shutdown() }
    }

    @Test fun omittedObservationPreservesCreateOnlyBehavior() = runBlocking {
        val backend = FakeBackend(BackendKind.ROOT).apply { inspectionFailure = "NO_RUNNING_APP" }
        val sessions = VirtualScreenSessionManager(RuntimeEnvironment.getApplication(), backend, FakeBackend(BackendKind.SHIZUKU), initialBackend = BackendKind.ROOT)
        try {
            val lease = (sessions.acquireLease("agent") as LeaseDecision.Granted).lease
            val data = McpToolRegistry(sessions).call("create_screen", JSONObject().put("lease_token", lease.token)
                .put("purpose", "Create the display"), lease.owner).getJSONObject("structuredContent")
            assertTrue(data.getBoolean("ok"))
            assertFalse(data.has("observation_status"))
            assertNotNull(sessions.state.value.displayId)
        } finally { sessions.shutdown() }
    }

    @Test fun unknownArgumentReturnsItsNameAndAllowedFieldsBeforeCreatingDisplay() = runBlocking {
        val sessions = VirtualScreenSessionManager(RuntimeEnvironment.getApplication(), FakeBackend(BackendKind.ROOT), FakeBackend(BackendKind.SHIZUKU))
        try {
            val lease = (sessions.acquireLease("agent") as LeaseDecision.Granted).lease
            val data = McpToolRegistry(sessions).call("create_screen", JSONObject().put("lease_token", lease.token)
                .put("purpose", "Create the display").put("unexpected_option", "unused"), lease.owner).getJSONObject("structuredContent")
            assertEquals("UNKNOWN_ARGUMENT", data.getString("code"))
            assertEquals("unexpected_option", data.getJSONArray("unknown_arguments").getString(0))
            assertTrue(data.getJSONArray("accepted_arguments").toString().contains("observe_after"))
            assertEquals("rejected", data.getString("execution_status"))
            assertNull(sessions.state.value.displayId)
        } finally { sessions.shutdown() }
    }
}

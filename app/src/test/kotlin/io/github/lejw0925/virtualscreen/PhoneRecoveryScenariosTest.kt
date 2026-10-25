package io.github.lejw0925.virtualscreen

import android.app.Application
import io.github.lejw0925.virtualscreen.backend.*
import io.github.lejw0925.virtualscreen.core.*
import io.github.lejw0925.virtualscreen.mcp.McpToolRegistry
import io.github.lejw0925.virtualscreen.ui.UiLanguage
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** P2-phone isolated scenarios: real registry/session code, fake Android backend. */
@RunWith(RobolectricTestRunner::class) @Config(sdk=[34],application=Application::class)
class PhoneRecoveryScenariosTest {
    @Test fun missingObservationExplainsRecoveryInAllLanguagesWithoutInput() = runBlocking {
        val root=FakeBackend(BackendKind.ROOT)
        val sessions=VirtualScreenSessionManager(RuntimeEnvironment.getApplication(),root,FakeBackend(BackendKind.SHIZUKU))
        val lease=(sessions.acquireLease("agent") as LeaseDecision.Granted).lease
        sessions.create(lease.owner,lease.token)
        for(language in UiLanguage.entries) {
            val registry=McpToolRegistry(sessions,language={language})
            for(value in listOf(null,"",JSONObject.NULL)) {
                val args=JSONObject().put("purpose","Go back").put("lease_token",lease.token).put("key_code",4)
                if(value!=null) args.put("observation_id",value)
                val result=registry.call("press_key",args,lease.owner).getJSONObject("structuredContent")
                assertEquals("OBSERVATION_REQUIRED",result.getString("code"))
                assertEquals("rejected",result.getString("execution_status"))
                assertEquals("observe",result.getJSONArray("recovery_tools").getString(0))
                assertTrue(result.getString("message").contains("observation_id"))
            }
        }
        assertEquals(0,root.inputs)
    }

    @Test fun closedDisplayExplainsCreateRecoveryAndKeepsLeaseGuard() = runBlocking {
        val root=FakeBackend(BackendKind.ROOT)
        val sessions=VirtualScreenSessionManager(RuntimeEnvironment.getApplication(),root,FakeBackend(BackendKind.SHIZUKU))
        val lease=(sessions.acquireLease("agent") as LeaseDecision.Granted).lease
        val registry=McpToolRegistry(sessions)
        val args=JSONObject().put("purpose","Go back").put("lease_token",lease.token).put("observation_id","stale").put("key_code",4)
        val closed=registry.call("press_key",args,lease.owner).getJSONObject("structuredContent")
        assertEquals("NO_VIRTUAL_SCREEN",closed.getString("code"))
        assertEquals("create_screen",closed.getJSONArray("recovery_tools").getString(0))
        assertEquals("rejected",closed.getString("execution_status"))
        args.put("lease_token","invalid")
        assertEquals("LEASE_REQUIRED",registry.call("press_key",args,lease.owner).getJSONObject("structuredContent").getString("code"))
        args.put("lease_token",lease.token)
        sessions.create(lease.owner,lease.token)
        assertEquals("STALE_OBSERVATION",registry.call("press_key",args,lease.owner).getJSONObject("structuredContent").getString("code"))
        assertEquals(0,root.inputs)
    }
}

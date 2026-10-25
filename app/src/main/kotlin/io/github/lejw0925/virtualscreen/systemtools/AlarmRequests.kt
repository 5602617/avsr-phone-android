package io.github.lejw0925.virtualscreen.systemtools

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.AlarmClock
import io.github.lejw0925.virtualscreen.VirtualScreenApplication
import io.github.lejw0925.virtualscreen.core.OperationEnvelope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Bounded, process-local phone consent requests; not a scheduler or execution task store. */
internal object AlarmRequests {
    data class Request(val owner: String, val id: String, val hour: Int, val minute: Int, val label: String, val days: List<Int>,
                       val phase: String = "pending", val receipt: OperationEnvelope? = null,
                       val binding: Map<String, String> = emptyMap())
    private val requests = linkedMapOf<String, Request>()
    private val mutableState = MutableStateFlow<List<Request>>(emptyList())
    val state = mutableState.asStateFlow()
    private fun publish() { mutableState.value = requests.values.toList() }
    private fun key(owner: String, id: String) = owner to id
    private val keys = mutableMapOf<Pair<String, String>, String>()

    @Synchronized fun register(owner: String, requestId: String, hour: Int, minute: Int, label: String, days: List<Int>,
                               binding: Map<String, String> = emptyMap()): OperationEnvelope {
        val requestKey = key(owner, requestId)
        keys[requestKey]?.let { token ->
            val old = requests.getValue(token)
            if (old.hour != hour || old.minute != minute || old.label != label || old.days != days || old.binding != binding)
                return rejected("ALARM_REQUEST_ID_CONFLICT")
            return envelope(old)
        }
        if (binding.isNotEmpty()) requests.values.firstOrNull { old ->
            old.owner == owner && old.binding == binding && old.phase == "pending" &&
                old.hour == hour && old.minute == minute && old.label == label && old.days == days
        }?.let { return envelope(it) }
        if (requests.size >= 32) {
            val oldest = requests.entries.firstOrNull { it.value.phase !in setOf("pending", "dispatching") }
                ?: return rejected("ALARM_CONFIRMATION_QUEUE_FULL")
            requests.remove(oldest.key); keys.remove(key(oldest.value.owner, oldest.value.id))
        }
        val token = java.util.UUID.randomUUID().toString()
        val request = Request(owner, requestId, hour, minute, label, days, binding = binding.toMap())
        requests[token] = request; keys[requestKey] = token
        publish()
        return envelope(request)
    }

    @Synchronized fun status(owner: String, id: String): OperationEnvelope = keys[key(owner, id)]?.let { requests[it] }?.let(::envelope)
        ?: unknown("ALARM_REQUEST_UNKNOWN", "request_id" to id, "retry_safe" to false, "reason" to "not_in_process_memory_or_evicted_do_not_assume_not_dispatched")

    @Synchronized fun pending(): List<Pair<String, Request>> = requests.filterValues { it.phase == "pending" }.toList()

    @Synchronized fun invalidate(owner: String?) {
        requests.replaceAll { _, request ->
            if ((owner == null || request.owner == owner) && request.phase == "pending") request.copy(phase = "invalidated", receipt = rejected("ALARM_REQUEST_INVALIDATED")) else request
        }
        publish()
    }

    /** A cancelled/replaced original execution cannot retain a usable native confirmation. */
    @Synchronized fun reconcileBinding(binding: Map<String, String>, live: Boolean) {
        requests.replaceAll { _, request ->
            if (request.phase == "pending" && request.binding.isNotEmpty() && (!live || request.binding != binding))
                request.copy(phase = "invalidated", receipt = rejected("ALARM_REQUEST_INVALIDATED")) else request
        }
        publish()
    }

    fun currentBinding(app: VirtualScreenApplication): Map<String, String> {
        val phone = app.phoneSession.flow.value
        val usage = app.mainScreen.policy.state.value
        if (phone.connectionId == null || usage.connectionId != phone.connectionId || usage.taskId.isNullOrBlank()
            || usage.executionId.isNullOrBlank()) return emptyMap()
        return mapOf("device_id" to phone.deviceId, "connection_id" to phone.connectionId,
            "task_id" to usage.taskId, "execution_id" to usage.executionId)
    }

    /** Protocol facts only; owner handles and phone approval tokens never leave this object. */
    fun fact(request: Request): Map<String, Any?> {
        val result = envelope(request)
        return request.binding + mapOf("kind" to "alarm", "alarm_request_id" to request.id, "phase" to request.phase,
            "native_receipt" to (result.values + mapOf("ok" to result.ok, "code" to result.code)))
    }

    @Synchronized fun cancel(token: String) {
        val request = requests[token] ?: return
        if (request.phase == "pending") requests[token] = request.copy(phase = "cancelled", receipt = rejected("ALARM_USER_DECLINED"))
        publish()
    }

    /** Called exclusively by the foreground Activity button; atomically consume before dispatch. */
    fun dispatchFromUserClick(activity: Activity, token: String): OperationEnvelope {
        val request = synchronized(this) {
            val current = requests[token] ?: return unknown("ALARM_REQUEST_UNKNOWN")
            if (current.phase != "pending") return envelope(current)
            // Recheck at the actual phone-side consumption boundary, not only at MCP registration.
            // isClientConnected reads the concurrent client map without acquiring the disconnect lock.
            val app = activity.application as? VirtualScreenApplication
            val refusal = if (app == null) "ALARM_AUTHORIZATION_UNAVAILABLE" else try {
                app.phoneSession.checkOperation("alarm_set")
                if (current.binding.isNotEmpty() && (current.binding != currentBinding(app) || !app.mainScreen.policy.state.value.live))
                    "ALARM_REQUEST_INVALIDATED"
                else if (app.mcp.isClientConnected(current.owner)) null else "ALARM_CLIENT_DISCONNECTED"
            } catch (e: Exception) {
                e.message?.takeIf { it in setOf("PHONE_EXECUTION_PAUSED", "PHONE_DISCONNECTED") } ?: "ALARM_AUTHORIZATION_UNAVAILABLE"
            }
            if (refusal != null) {
                val denied = current.copy(phase = "invalidated", receipt = rejected(refusal))
                requests[token] = denied
                publish()
                return envelope(denied)
            }
            requests[token] = current.copy(phase = "dispatching")
            publish()
            current
        }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, request.hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, request.minute).putExtra(AlarmClock.EXTRA_MESSAGE, request.label).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (request.days.isNotEmpty()) intent.putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, ArrayList(request.days))
        val result = try {
            activity.startActivity(intent)
            // An immediate read may precede the clock application's write. Never equate time equality with identity.
            val observed = runCatching { nextAlarm(activity) }
            OperationEnvelope.failure("ALARM_DISPATCHED_UNCONFIRMED", null, "execution_status" to "accepted", "effect_status" to "unconfirmed",
                "confirmation" to "unconfirmed", "intent_dispatched" to true, "may_open_system_ui" to true, "user_approved_on_phone" to true,
                "next_alarm" to observed.getOrNull(), "next_alarm_read_status" to if (observed.isSuccess) "observed" else "failed",
                "target_alarm_confirmed" to false, "full_alarm_list_available" to false, "retry_safe" to false)
        } catch (_: ActivityNotFoundException) { rejected("ALARM_APP_UNAVAILABLE") }
        catch (_: SecurityException) { rejected("ALARM_INTENT_DENIED") }
        catch (_: Exception) { unknown("ALARM_DISPATCH_UNCONFIRMED", "retry_safe" to false) }
        synchronized(this) { requests[token] = request.copy(phase = "finished", receipt = result); publish() }
        return envelope(request.copy(phase = "finished", receipt = result))
    }

    private fun envelope(request: Request): OperationEnvelope {
        val result = request.receipt ?: if (request.phase == "dispatching") unknown("ALARM_DISPATCH_IN_PROGRESS", "retry_safe" to false)
        else OperationEnvelope.failure("ALARM_PHONE_CONFIRMATION_REQUIRED", null, "execution_status" to "not_executed", "effect_status" to "not_applied", "confirmation" to "requires_user_action",
            "user_action" to "open_apk_system_tools_then_approve_this_alarm", "permission_activity" to SystemToolsPermissionActivity::class.java.name, "may_open_system_ui" to true,
            "opens_main_screen_automatically" to false, "request_lifetime" to "process_memory_until_disconnect_or_eviction")
        return result.copy(values = result.values + mapOf("request_id" to request.id, "alarm_request_id" to request.id,
            "phase" to request.phase, "native_confirmation_kind" to "alarm", "native_confirmation_binding" to request.binding,
            "requires_user_action" to (request.phase == "pending"), "retry_safe" to false))
    }
}

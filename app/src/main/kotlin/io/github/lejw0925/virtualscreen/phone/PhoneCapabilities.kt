package io.github.lejw0925.virtualscreen.phone

import org.json.JSONObject

/** Negotiated implementations, not device permissions or per-action authorization. */
data class HostCapabilities(
    val negotiated: Boolean = false,
    val features: Map<String, Int> = emptyMap(),
) {
    val deviceControlUnavailable: Boolean get() = negotiated && "device_control" !in features
    val avatarProfileAvailable: Boolean get() = negotiated && "avatar_profile" in features
}

/** Contract authority: astrbot_plugin_avsr_phone/docs/protocol/. */
object PhoneCapabilities {
    val clientFeatureVersions: Map<String, Int> = mapOf(
        "chat_text" to 1,
        "audio_input" to 1,
        "audio_stream" to 1,
        "voice_timing" to 1,
        "device_control" to 1,
        "authorization_confirmation" to 1,
        "screen_borrow" to 1,
        "human_handoff" to 1,
        "avatar_profile" to 1,
        "expression_catalog" to 1,
        "native_confirmation" to 1,
    )
    // These host services are observable here; the APK does not implement them.
    val knownHostFeatureVersions: Map<String, Int> = clientFeatureVersions + mapOf(
        "task_lifecycle" to 1,
        "surroundings" to 1,
    )

    fun clientFrame(): JSONObject = JSONObject()
        .put("type", "client.capabilities")
        .put("audio_stream", 1)
        .put("voice_timing", 1)
        .put("features", JSONObject(clientFeatureVersions))

    fun readHost(frame: JSONObject, unsupported: (String, Int) -> Unit): HostCapabilities {
        val announced = frame.optJSONObject("features") ?: JSONObject()
        val accepted = mutableMapOf<String, Int>()
        knownHostFeatureVersions.forEach { (name, supportedVersion) ->
            if (announced.has(name)) {
                val version = announced.opt(name) as? Int ?: -1
                if (version == supportedVersion) accepted[name] = version
                else unsupported(name, version)
            }
        }
        return HostCapabilities(negotiated = true, features = accepted.toMap())
    }
}

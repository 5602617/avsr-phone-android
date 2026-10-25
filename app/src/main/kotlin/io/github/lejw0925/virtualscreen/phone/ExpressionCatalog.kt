package io.github.lejw0925.virtualscreen.phone

import org.json.JSONArray
import org.json.JSONObject

data class ExpressionTag(val id: String, val label: String, val description: String = "")

/** Host-owned labels for one Persona; an absent catalog is not an empty replacement. */
data class ExpressionCatalog(val personaId: String, val entries: List<ExpressionTag>, val defaultEmotion: String = "") {
    fun toJson(): JSONObject = JSONObject().put("persona_id", personaId)
        .put("default_emotion", defaultEmotion).put("emotion_catalog", JSONArray().apply {
            entries.forEach { put(JSONObject().put("id", it.id).put("label", it.label).put("description", it.description)) }
        })

    companion object {
        fun read(frame: JSONObject): ExpressionCatalog? {
            val personaId = frame.optString("persona_id")
            val rows = frame.optJSONArray("emotion_catalog") ?: return null
            if (personaId.isBlank()) return null
            val entries = (0 until rows.length()).mapNotNull { index ->
                val row = rows.optJSONObject(index) ?: return@mapNotNull null
                val id = row.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
                ExpressionTag(id, row.optString("label").ifBlank { id }, row.optString("description"))
            }.distinctBy { it.id }
            return ExpressionCatalog(personaId, entries, frame.optString("default_emotion"))
        }
    }
}

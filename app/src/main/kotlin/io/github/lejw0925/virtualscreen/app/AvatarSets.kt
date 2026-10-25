package io.github.lejw0925.virtualscreen.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import io.github.lejw0925.virtualscreen.phone.ExpressionCatalog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** assets/avatar_sets/<id>/manifest.json, or files/avatar_sets/<id>/manifest.json.
 * Paths are relative to the set, so a set is portable and cannot reference private app files. */
class AvatarSets(private val context: Context) {
    private val prefs = context.getSharedPreferences("avsr_product", 0)
    @Synchronized fun saveCatalog(catalog: ExpressionCatalog) {
        val name = "expression_catalog_${key(catalog.personaId)}"
        val encoded = catalog.toJson().toString()
        if (prefs.getString(name, null) != encoded) prefs.edit().putString(name, encoded).apply()
    }
    private fun catalog(personaId: String): ExpressionCatalog? = runCatching {
        prefs.getString("expression_catalog_${key(personaId)}", null)?.let { ExpressionCatalog.read(JSONObject(it)) }
    }.getOrNull()

    @Synchronized fun read(): List<AvatarSet> {
        val bundled = context.assets.list("avatar_sets").orEmpty().mapNotNull { id ->
            runCatching { parse(id, context.assets.open("avatar_sets/$id/manifest.json").bufferedReader().use { it.readText() }, "asset") }.getOrNull()
        }
        val local = File(context.filesDir, "avatar_sets").listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { dir ->
            runCatching { parse(dir.name, File(dir, "manifest.json").readText(), "file") }.getOrNull()
        }
        return (bundled + local).associateBy { it.id }.values.toList()
    }
    private fun parse(id: String, text: String, source: String): AvatarSet {
        require(id.matches(Regex("[a-zA-Z0-9_-]+")))
        val json = JSONObject(text)
        val images = json.getJSONObject("images")
        val paths = images.keys().asSequence().mapNotNull { emotion ->
            val path = images.optString(emotion)
            if (path.isBlank()) return@mapNotNull null
            require(!path.startsWith('/') && !path.contains('\\') && !path.contains(':') && path.split('/').none { it == ".." })
            val uri = if (source == "asset") {
                context.assets.open("avatar_sets/$id/$path").use { }
                "file:///android_asset/avatar_sets/$id/$path"
            } else {
                val base = File(context.filesDir, "avatar_sets/$id").canonicalFile
                val file = File(base, path).canonicalFile
                require(file.toPath().startsWith(base.toPath()) && file.isFile)
                android.net.Uri.fromFile(file).toString()
            }
            emotion to uri
        }.toMap()
        require(paths.containsKey("neutral"))
        val personas = json.optJSONArray("persona")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter(String::isNotBlank) }.orEmpty()
        return AvatarSet(id, json.optString("name", id), paths, source, personas, strings(json.optJSONArray("persona_ids")))
    }
    fun selected(sets: List<AvatarSet> = read()): String = prefs.getString("avatar_set", "").orEmpty()
        .takeIf { id -> sets.any { it.id == id } } ?: sets.firstOrNull()?.id.orEmpty()
    fun select(id: String) { require(read().any { it.id == id }) { "AVATAR_SET_NOT_FOUND" }; prefs.edit().putString("avatar_set", id).apply() }
    fun image(set: AvatarSet, emotion: String) = set.images[emotion] ?: set.images["neutral"]

    private fun strings(array: JSONArray?) = array?.let { values ->
        (0 until values.length()).map { values.optString(it) }.filter(String::isNotBlank)
    }.orEmpty()
    private fun key(personaId: String): String {
        require(personaId.isNotBlank()) { "PERSONA_ID_REQUIRED" }
        return MessageDigest.getInstance("SHA-256").digest(personaId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
    private fun directory(personaId: String) = File(context.filesDir, "avatar_sets/persona_${key(personaId)}")
    private fun load(personaId: String): JSONObject? {
        val file = File(directory(personaId), "manifest.json")
        if (!file.isFile) return null
        return JSONObject(file.readText()).also {
            require(it.optString("kind") == "persona_override" && it.optString("persona_id") == personaId) {
                "AVATAR_PROFILE_ID_MISMATCH"
            }
        }
    }
    private fun base(personaId: String, sets: List<AvatarSet>, manifest: JSONObject?): AvatarSet? {
        val selected = manifest?.optString("base_set_id").orEmpty().ifBlank {
            prefs.getString("avatar_set_persona_${key(personaId)}", "").orEmpty()
        }
        return sets.firstOrNull { it.id == selected }
            ?: sets.firstOrNull { personaId in it.personaIds || personaId in it.personas }
    }
    private fun seededSlots(set: AvatarSet?): JSONArray {
        val groups = set?.images.orEmpty().filterKeys { it != "neutral" }.entries.groupBy { it.value }
        return JSONArray().apply { groups.values.forEach { entries ->
            val mapped = entries.map { it.key }
            val id = "base_" + MessageDigest.getInstance("SHA-256")
                .digest(mapped.sorted().joinToString("|").toByteArray(Charsets.UTF_8))
                .take(8).joinToString("") { "%02x".format(it) }
            put(JSONObject().put("id", id).put("name", mapped.joinToString(" / "))
                .put("emotions", JSONArray(mapped)).put("source_emotions", JSONArray(mapped)))
        } }
    }
    private fun editable(personaId: String): JSONObject = load(personaId) ?: run {
        val set = base(personaId, read(), null)
        JSONObject().put("kind", "persona_override").put("persona_id", personaId)
            .put("base_set_id", set?.id.orEmpty()).put("slots", seededSlots(set))
    }
    private fun save(personaId: String, manifest: JSONObject) {
        val folder = directory(personaId).apply { mkdirs() }
        val temporary = File(folder, "manifest.json.tmp")
        temporary.writeText(manifest.toString(2))
        Files.move(temporary.toPath(), File(folder, "manifest.json").toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
    private fun slotRows(manifest: JSONObject): List<JSONObject> = manifest.optJSONArray("slots")?.let { values ->
        (0 until values.length()).mapNotNull { values.optJSONObject(it) }
    }.orEmpty()
    private fun slot(manifest: JSONObject, slotId: String) = slotRows(manifest).firstOrNull { it.optString("id") == slotId }
        ?: throw IllegalArgumentException("AVATAR_SLOT_NOT_FOUND")
    private fun localImage(personaId: String, path: String): String? {
        if (path.isBlank()) return null
        val folder = directory(personaId).canonicalFile
        val file = File(folder, path).canonicalFile
        return file.takeIf { it.toPath().startsWith(folder.toPath()) && it.isFile }?.let { Uri.fromFile(it).toString() }
    }

    /** No unmatched Persona receives another Persona's selected set. Names remain a legacy set alias only. */
    @Synchronized fun profile(personaId: String, sets: List<AvatarSet> = read()): PersonaAvatarProfile {
        val manifest = runCatching { load(personaId) }.getOrNull()
        val set = base(personaId, sets, manifest)
        val catalog = catalog(personaId)
        val customDefault = localImage(personaId, manifest?.optString("default_image").orEmpty())
        val defaultImage = customDefault ?: set?.images?.get("neutral")
        val rows = manifest?.let(::slotRows) ?: slotRows(JSONObject().put("slots", seededSlots(set)))
        val slots = rows.map { row ->
            val custom = localImage(personaId, row.optString("image"))
            val original = strings(row.optJSONArray("source_emotions")).firstNotNullOfOrNull { set?.images?.get(it) }
            AvatarSlot(row.optString("id"), row.optString("name"), strings(row.optJSONArray("emotions")),
                custom ?: original ?: defaultImage, custom != null)
        }
        val images = catalog?.entries.orEmpty().mapNotNull { tag ->
            val emotion = tag.id
            (slots.firstOrNull { emotion in it.emotions }?.imageUri ?: defaultImage)?.let { emotion to it }
        }.toMap()
        return PersonaAvatarProfile(personaId, set?.id.orEmpty(), slots, defaultImage, images, manifest != null, customDefault != null, catalog)
    }

    @Synchronized fun selectForPersona(personaId: String, setId: String) {
        val chosen = read().firstOrNull { it.id == setId } ?: throw IllegalArgumentException("AVATAR_SET_NOT_FOUND")
        val manifest = load(personaId) ?: JSONObject().put("kind", "persona_override").put("persona_id", personaId)
            .put("slots", seededSlots(chosen))
        save(personaId, manifest.put("base_set_id", setId))
        prefs.edit().putString("avatar_set_persona_${key(personaId)}", setId).apply()
    }
    @Synchronized fun configuredPersonaIds(): List<String> = (File(context.filesDir, "avatar_sets").listFiles().orEmpty()
        .filter { it.isDirectory }.mapNotNull { folder -> runCatching {
            val manifest = JSONObject(File(folder, "manifest.json").readText())
            val personaId = manifest.optString("persona_id")
            personaId.takeIf { it.isNotBlank() && manifest.optString("kind") == "persona_override"
                && folder.name == "persona_${key(it)}" }
        }.getOrNull() } + prefs.all.filterKeys { it.startsWith("expression_catalog_") }.values.mapNotNull { value ->
            runCatching { ExpressionCatalog.read(JSONObject(value as String))?.personaId }.getOrNull()
        }).distinct()
    @Synchronized fun addSlot(personaId: String, name: String) {
        val manifest = editable(personaId)
        val rows = slotRows(manifest) + JSONObject().put("id", UUID.randomUUID().toString()).put("name", name)
            .put("emotions", JSONArray()).put("source_emotions", JSONArray())
        save(personaId, manifest.put("slots", JSONArray(rows)))
    }
    @Synchronized fun renameSlot(personaId: String, slotId: String, name: String) {
        val manifest = editable(personaId)
        slot(manifest, slotId).put("name", name)
        save(personaId, manifest)
    }
    @Synchronized fun removeSlot(personaId: String, slotId: String) {
        val manifest = editable(personaId)
        save(personaId, manifest.put("slots", JSONArray(slotRows(manifest).filterNot { it.optString("id") == slotId })))
    }
    @Synchronized fun mapSlot(personaId: String, slotId: String, mapped: List<String>) {
        val manifest = editable(personaId)
        val chosen = mapped.distinct().filter(String::isNotBlank)
        slot(manifest, slotId)
        slotRows(manifest).forEach { row ->
            row.put("emotions", JSONArray(if (row.optString("id") == slotId) chosen
                else strings(row.optJSONArray("emotions")).filterNot { it in chosen }))
        }
        save(personaId, manifest)
    }
    /** Freeze the selected Persona and slot before opening the external picker. */
    @Synchronized fun prepareImageSelection(personaId: String, slotId: String?) {
        val manifest = editable(personaId)
        if (slotId != null) slot(manifest, slotId)
        save(personaId, manifest)
    }
    @Synchronized fun restoreImage(personaId: String, slotId: String?) {
        val manifest = editable(personaId)
        if (slotId == null) manifest.remove("default_image") else slot(manifest, slotId).remove("image")
        save(personaId, manifest)
    }
    @Synchronized fun reset(personaId: String) {
        val file = File(directory(personaId), "manifest.json")
        if (file.exists() && !file.delete()) throw IllegalStateException("AVATAR_PROFILE_RESET_FAILED")
    }

    /** Removes only this phone's Persona override and cached metadata, never host state or shared sets. */
    @Synchronized fun deleteProfile(personaId: String) {
        val folder = directory(personaId).canonicalFile
        val parent = File(context.filesDir, "avatar_sets").canonicalFile
        check(folder.parentFile == parent && folder.name == "persona_${key(personaId)}") { "AVATAR_PROFILE_PATH_INVALID" }
        // Imported image files stay on disk; only the local editor configuration is removed.
        Files.deleteIfExists(File(folder, "manifest.json").toPath())
        prefs.edit().remove("expression_catalog_${key(personaId)}")
            .remove("avatar_set_persona_${key(personaId)}").apply()
    }

    /** Reads only the URI returned by the user's system picker; stores a fresh URI to refresh image caches. */
    fun importImage(personaId: String, slotId: String?, uri: Uri) {
        synchronized(this) { if (slotId != null) slot(editable(personaId), slotId) else key(personaId) }
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > 1024) decoder.setTargetSize((info.size.width * 1024L / longest).toInt().coerceAtLeast(1),
                (info.size.height * 1024L / longest).toInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val folder = directory(personaId).apply { mkdirs() }
        val name = "image_${UUID.randomUUID()}.png"
        val target = File(folder, name)
        try {
            target.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "AVATAR_IMAGE_IMPORT_FAILED" } }
            synchronized(this) {
                val manifest = editable(personaId)
                if (slotId == null) manifest.put("default_image", name) else slot(manifest, slotId).put("image", name)
                save(personaId, manifest)
            }
        } finally { bitmap.recycle() }
    }
}

package io.github.lejw0925.virtualscreen.app

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import io.github.lejw0925.virtualscreen.VirtualScreenApplication

/** A system picker bridge only. Its Intent retains the original Persona/slot across recreation. */
class AvatarImagePickerActivity : ComponentActivity() {
    companion object {
        const val PERSONA_ID = "avatar_persona_id"
        const val SLOT_ID = "avatar_slot_id"
    }
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val personaId = intent.getStringExtra(PERSONA_ID).orEmpty()
        if (uri != null && personaId.isNotBlank()) {
            val alreadyHeld = contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
            val retained = !alreadyHeld && runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.isSuccess
            (application as VirtualScreenApplication).avsr.importAvatarImage(personaId, intent.getStringExtra(SLOT_ID), uri, retained)
        }
        finish()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getStringExtra(PERSONA_ID).isNullOrBlank()) { finish(); return }
        if (savedInstanceState == null) picker.launch(arrayOf("image/*"))
    }
}

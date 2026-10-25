package io.github.lejw0925.virtualscreen.ui.screens

import io.github.lejw0925.virtualscreen.app.AvatarSet
import io.github.lejw0925.virtualscreen.app.AvsrAppState

/**
 * Who is on the other end. A host that names its persona gets that persona's avatar set, or the
 * default mark when no set lists it, so another persona never wears Yao's face. Hosts that do not
 * send a name keep the set the user picked.
 */
internal fun herSet(state: AvsrAppState): AvatarSet? =
    if (state.personaName.isNotBlank()) state.avatarSets.firstOrNull { state.personaName in it.personas }
    else state.avatarSets.firstOrNull { it.id == state.selectedAvatarSet } ?: state.avatarSets.firstOrNull()

/**
 * The face for one persona and tone. A persona with a profile uses it as-is: profile.images already
 * falls back to the persona's plain picture, so no second mapping lives here. Without a persona id
 * (old messages, older hosts) the legacy set rule applies; such a message is never given today's persona.
 */
internal fun portraitFor(state: AvsrAppState, personaId: String, emotion: String = "neutral"): String? {
    val profile = personaId.takeIf { it.isNotBlank() }?.let { state.avatarProfiles[it] }
    if (profile != null) return profile.images[emotion] ?: profile.images["neutral"] ?: profile.defaultImageUri
    return herSet(state)?.let { it.images[emotion] ?: it.images["neutral"] }
}

internal fun herPortrait(state: AvsrAppState, emotion: String = "neutral"): String? = portraitFor(state, state.personaId, emotion)

internal fun herName(state: AvsrAppState): String =
    state.personaName.ifBlank { herSet(state)?.name.orEmpty() }.ifBlank { "Persona" }

/** Her face follows the tone of her latest reply, worn by the persona who sent it. */
internal fun herCurrentPortrait(state: AvsrAppState): String? {
    val latest = state.conversation.lastOrNull { it.role == "assistant" }
    return portraitFor(state, latest?.personaId?.ifBlank { state.personaId } ?: state.personaId, latest?.speechEmotion ?: "neutral")
}

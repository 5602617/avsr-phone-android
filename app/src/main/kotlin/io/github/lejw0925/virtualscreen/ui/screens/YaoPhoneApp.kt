package io.github.lejw0925.virtualscreen.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import io.github.lejw0925.virtualscreen.app.AvsrActions
import io.github.lejw0925.virtualscreen.app.AvsrAppState
import io.github.lejw0925.virtualscreen.ui.design.YaoTheme

/** App screen router: setup until the controller reports it complete, then chat; settings on top of either. */
@Composable
fun YaoPhoneApp(state: AvsrAppState, actions: AvsrActions) = YaoTheme {
    var page by rememberSaveable { mutableStateOf("main") }
    // Chat is reachable once signed in, even if pairing is not done yet; the controller still gates what she can do.
    var chatFirst by rememberSaveable { mutableStateOf(false) }
    val setup = !state.onboardingComplete && (state.onboardingStep == "login" || !chatFirst)
    val target = when {
        page == "settings" && state.onboardingStep != "login" -> "settings"
        setup -> "setup"
        else -> "home"
    }
    AnimatedContent(target, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "page") { current ->
        when (current) {
            "settings" -> SettingsScreen(state, actions) { page = "main" }
            "setup" -> OnboardingScreen(state, actions, onSkipToChat = { chatFirst = true })
            else -> HomeScreen(state, actions, onSettings = { page = "settings" },
                onFinishSetup = if (state.onboardingComplete) null else ({ chatFirst = false }))
        }
    }
}

@Composable
fun YaoFloatingContent(state: AvsrAppState, actions: AvsrActions) = YaoTheme { YaoFloatingPanel(state, actions) }

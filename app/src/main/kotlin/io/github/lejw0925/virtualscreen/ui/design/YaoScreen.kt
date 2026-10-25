package io.github.lejw0925.virtualscreen.ui.design

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Her virtual display as a quiet card: true aspect ratio, soft corners, one hairline edge, no bezel. */
@Composable
fun YaoScreenFrame(width: Int, height: Int, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val p = LocalYao.current
    val shape = RoundedCornerShape(18.dp)
    Box(modifier.aspectRatio(width.toFloat() / height.coerceAtLeast(1), matchHeightConstraintsFirst = true)
        .shadow(if (p.dark) 0.dp else 14.dp, shape, ambientColor = p.ink.copy(alpha = 0.10f), spotColor = p.ink.copy(alpha = 0.14f))
        .clip(shape).background(Color.Black).border(0.5.dp, p.hairline, shape), content = content)
}

/** Three-slot top bar: back, a centred title, and an optional quiet status on the right. */
@Composable
fun YaoScreenBar(title: String, backLabel: String, onBack: () -> Unit, status: String? = null, live: Boolean = false) {
    val p = LocalYao.current
    Box(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 6.dp)) {
        IconButton(onBack, Modifier.align(Alignment.CenterStart)) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, backLabel, tint = p.inkSoft)
        }
        Text(title, Modifier.align(Alignment.Center), style = YaoType.section, color = p.ink, textAlign = TextAlign.Center)
        if (status != null) Row(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            val pulse by rememberInfiniteTransition(label = "live").animateFloat(0.3f, 1f,
                infiniteRepeatable(tween(1000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "dot")
            Box(Modifier.size(6.dp).clip(CircleShape).background(p.ink.copy(alpha = if (live) pulse else 0.3f)))
            Spacer(Modifier.width(6.dp))
            Text(status, style = YaoType.small, color = p.inkSoft)
        }
    }
}

/** One line under the screen: her face and what she is doing. */
@Composable
fun YaoScreenCaption(portrait: String?, text: String, speaking: Boolean = false, modifier: Modifier = Modifier) {
    val p = LocalYao.current
    Row(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        YaoAvatar(portrait, 28.dp, speaking = speaking)
        Spacer(Modifier.width(10.dp))
        Text(text, style = YaoType.small, color = p.inkSoft, maxLines = 2, modifier = Modifier.weight(1f, fill = false))
    }
}

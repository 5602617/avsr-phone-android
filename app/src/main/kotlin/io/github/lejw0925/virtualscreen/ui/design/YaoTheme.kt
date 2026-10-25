package io.github.lejw0925.virtualscreen.ui.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lejw0925.virtualscreen.R

/** Monochrome palette taken from her design: pearl paper, ribbon ink, grey layers. Rose is reserved for failures. */
@Immutable
data class YaoPalette(
    val paper: Color, val card: Color, val layer: Color, val layerStrong: Color,
    val ink: Color, val onInk: Color, val inkSoft: Color, val inkFaint: Color,
    val hairline: Color, val lace: Color,
    val herBubble: Color, val myBubble: Color, val onMyBubble: Color,
    val rose: Color, val roseLayer: Color, val onRoseLayer: Color,
    val dark: Boolean,
)

val YaoLight = YaoPalette(
    paper = Color(0xFFF6F5F2), card = Color(0xFFFFFFFF), layer = Color(0xFFEDEBE7), layerStrong = Color(0xFFDDDAD5),
    ink = Color(0xFF141414), onInk = Color(0xFFFAF9F7), inkSoft = Color(0xFF5E5C59), inkFaint = Color(0xFF9A9793),
    hairline = Color(0xFFD6D3CE), lace = Color(0xFFE4E1DC),
    herBubble = Color(0xFFFFFFFF), myBubble = Color(0xFF1C1C1C), onMyBubble = Color(0xFFF6F5F2),
    rose = Color(0xFFA8636E), roseLayer = Color(0xFFF4E6E8), onRoseLayer = Color(0xFF5A2E35),
    dark = false,
)

val YaoDark = YaoPalette(
    paper = Color(0xFF0F0F10), card = Color(0xFF19191B), layer = Color(0xFF222225), layerStrong = Color(0xFF2E2E32),
    ink = Color(0xFFF2F1EE), onInk = Color(0xFF111112), inkSoft = Color(0xFFB4B2AE), inkFaint = Color(0xFF7A7875),
    hairline = Color(0xFF34343A), lace = Color(0xFF26262A),
    herBubble = Color(0xFF1F1F22), myBubble = Color(0xFFE9E8E4), onMyBubble = Color(0xFF141414),
    rose = Color(0xFFD69CA5), roseLayer = Color(0xFF3A2529), onRoseLayer = Color(0xFFF3D6DA),
    dark = true,
)

val LocalYao = staticCompositionLocalOf { YaoLight }

object YaoFonts {
    /** ZCOOL XiaoWei, subset to the UI's own strings. Titles only. */
    val title = FontFamily(Font(R.font.yao_title, FontWeight.Normal))
    /** Resource Han Rounded, GB2312 subset. Rare characters fall back to the system font. */
    val body = FontFamily(Font(R.font.yao_body_regular, FontWeight.Normal), Font(R.font.yao_body_medium, FontWeight.Medium))
    /** Quicksand for numerals and Latin labels. */
    val numeric = FontFamily(Font(R.font.yao_num_medium, FontWeight.Medium), Font(R.font.yao_num_bold, FontWeight.Bold))
}

object YaoType {
    val display = TextStyle(fontFamily = YaoFonts.title, fontSize = 30.sp, lineHeight = 38.sp, letterSpacing = 1.sp)
    val title = TextStyle(fontFamily = YaoFonts.title, fontSize = 22.sp, lineHeight = 30.sp, letterSpacing = 0.5.sp)
    val section = TextStyle(fontFamily = YaoFonts.title, fontSize = 17.sp, lineHeight = 24.sp, letterSpacing = 0.5.sp)
    val body = TextStyle(fontFamily = YaoFonts.body, fontSize = 15.sp, lineHeight = 23.sp)
    val bodyStrong = body.copy(fontWeight = FontWeight.Medium)
    val small = TextStyle(fontFamily = YaoFonts.body, fontSize = 12.5.sp, lineHeight = 18.sp)
    val label = TextStyle(fontFamily = YaoFonts.body, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
    val number = TextStyle(fontFamily = YaoFonts.numeric, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    val bigNumber = TextStyle(fontFamily = YaoFonts.numeric, fontSize = 34.sp, fontWeight = FontWeight.Bold)
}

object YaoShapes {
    val card = RoundedCornerShape(22.dp)
    val field = RoundedCornerShape(18.dp)
    val pill = RoundedCornerShape(50)
    val herBubble = RoundedCornerShape(topStart = 6.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 20.dp)
    val myBubble = RoundedCornerShape(topStart = 20.dp, topEnd = 6.dp, bottomEnd = 20.dp, bottomStart = 20.dp)
}

/** Follows the system light/dark setting, as the package requires. Sits inside VirtualScreenTheme, which supplies language. */
@Composable
fun YaoTheme(content: @Composable () -> Unit) {
    val p = if (isSystemInDarkTheme()) YaoDark else YaoLight
    val scheme = (if (p.dark) darkColorScheme() else lightColorScheme()).copy(
        primary = p.ink, onPrimary = p.onInk, primaryContainer = p.layerStrong, onPrimaryContainer = p.ink,
        secondary = p.inkSoft, onSecondary = p.onInk, secondaryContainer = p.layer, onSecondaryContainer = p.ink,
        tertiary = p.inkSoft, onTertiary = p.onInk, tertiaryContainer = p.layer, onTertiaryContainer = p.ink,
        background = p.paper, onBackground = p.ink, surface = p.paper, onSurface = p.ink,
        surfaceVariant = p.layer, onSurfaceVariant = p.inkSoft, outline = p.hairline, outlineVariant = p.lace,
        surfaceContainerLowest = p.card, surfaceContainerLow = p.card, surfaceContainer = p.card,
        surfaceContainerHigh = p.layer, surfaceContainerHighest = p.layerStrong,
        error = p.rose, onError = p.onInk, errorContainer = p.roseLayer, onErrorContainer = p.onRoseLayer,
        inverseSurface = p.ink, inverseOnSurface = p.onInk, inversePrimary = p.layerStrong,
    )
    val base = Typography()
    val typography = Typography(
        displayLarge = YaoType.display, displayMedium = YaoType.display, displaySmall = YaoType.display,
        headlineLarge = YaoType.title, headlineMedium = YaoType.title, headlineSmall = YaoType.title,
        titleLarge = YaoType.title, titleMedium = YaoType.section, titleSmall = YaoType.bodyStrong,
        bodyLarge = YaoType.body, bodyMedium = YaoType.body, bodySmall = YaoType.small,
        labelLarge = YaoType.label, labelMedium = YaoType.small, labelSmall = base.labelSmall.copy(fontFamily = YaoFonts.body),
    )
    CompositionLocalProvider(LocalYao provides p) {
        MaterialTheme(colorScheme = scheme, typography = typography, shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(18.dp),
            large = YaoShapes.card, extraLarge = RoundedCornerShape(28.dp))) {
            CompositionLocalProvider(LocalContentColor provides p.ink, LocalTextStyle provides YaoType.body, content = content)
        }
    }
}

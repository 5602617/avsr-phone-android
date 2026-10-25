package io.github.lejw0925.virtualscreen.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.ui.composed
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A speech bubble whose little tail hangs from its bottom corner toward whatever sits below it
 * (her orb). `tailEnd` puts the tail on the right, for when the orb is docked on the right.
 */
fun talkBubbleShape(radius: Dp = 18.dp, tail: Dp = 9.dp, tailEnd: Boolean = false): Shape = object : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = with(density) { radius.toPx() }
        val t = with(density) { tail.toPx() }
        val bodyBottom = size.height - t
        val path = Path().apply {
            fillType = PathFillType.NonZero
            addRoundRect(RoundRect(0f, 0f, size.width, bodyBottom, CornerRadius(r, r)))
            // A soft, slightly curved tail: starts inside the body so the seam never shows.
            val baseStart = if (tailEnd) size.width - r * 1.9f else r * 0.9f
            val baseEnd = if (tailEnd) size.width - r * 0.9f else r * 1.9f
            val tipX = if (tailEnd) size.width - r * 0.35f else r * 0.35f
            moveTo(baseStart, bodyBottom - 2f)
            quadraticTo((baseStart + tipX) / 2f, bodyBottom + t * 0.35f, tipX, size.height)
            quadraticTo((baseEnd + tipX) / 2f, bodyBottom + t * 0.2f, baseEnd, bodyBottom - 2f)
            close()
        }
        return Outline.Generic(path)
    }
}

/**
 * Her words in a bubble above the orb: pearl card, a lace frill under the top edge like the one on her collar,
 * and a thin ink rule down the side nearest the orb so the bubble reads as hers. `wide` is for replies long
 * enough to scroll, which get the panel's full card width.
 */
@Composable
fun YaoTalkBubble(modifier: Modifier = Modifier, tailEnd: Boolean = false, danger: Boolean = false, wide: Boolean = false,
    content: @Composable BoxScope.() -> Unit) {
    val p = LocalYao.current
    val shape = talkBubbleShape(tailEnd = tailEnd)
    Column(modifier.widthIn(min = 64.dp, max = if (wide) 264.dp else 236.dp)
        .shadow(6.dp, shape, ambientColor = p.ink.copy(alpha = 0.18f), spotColor = p.ink.copy(alpha = 0.18f))
        .clip(shape).background(if (danger) p.roseLayer else p.card)
        .border(1.dp, if (danger) p.rose.copy(alpha = 0.35f) else p.hairline, shape)) {
        if (!danger) LaceEdge(scallop = 7.dp)
        val ruleColor = if (danger) p.rose.copy(alpha = 0.5f) else p.ink.copy(alpha = 0.75f)
        // Drawn rather than laid out, so a scrolling body never needs intrinsic measurement.
        Box(Modifier.padding(start = 12.dp, end = 12.dp, top = if (danger) 9.dp else 3.dp, bottom = 9.dp + 9.dp)
            .drawBehind {
                val x = if (tailEnd) size.width - 1.dp.toPx() else 1.dp.toPx()
                drawLine(ruleColor, Offset(x, 2.dp.toPx()), Offset(x, size.height - 2.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
            }
            .padding(start = if (tailEnd) 0.dp else 11.dp, end = if (tailEnd) 11.dp else 0.dp), content = content)
    }
}

/** A row of mutually exclusive choices in one pill; replaces stacks of radio cards for simple settings. */
@Composable
fun <T> YaoSegmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val p = LocalYao.current
    Row(modifier.fillMaxWidth().clip(YaoShapes.pill).background(p.layer).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEach { (value, label) ->
            val on = value == selected
            Box(Modifier.weight(1f).clip(YaoShapes.pill).background(if (on) p.ink else Color.Transparent)
                .clickableNoRippleDesign { onSelect(value) }.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                Text(label, style = YaoType.label, color = if (on) p.onInk else p.inkSoft, maxLines = 1)
            }
        }
    }
}

/** "Fold into her orb": a ring holding a small face, drawn to match the line icons. */
val OrbIcon: ImageVector by lazy {
    ImageVector.Builder("Orb", 24.dp, 24.dp, 24f, 24f).apply {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round) {
            moveTo(12f, 4f); arcTo(8f, 8f, 0f, true, true, 11.99f, 4f); close()
        }
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 8.5f); arcTo(3.5f, 3.5f, 0f, true, true, 11.99f, 8.5f); close()
        }
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round) {
            moveTo(18.5f, 18.5f); lineTo(21f, 21f)
        }
    }.build()
}

internal fun Modifier.clickableNoRippleDesign(onClick: () -> Unit): Modifier = composed {
    clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
}

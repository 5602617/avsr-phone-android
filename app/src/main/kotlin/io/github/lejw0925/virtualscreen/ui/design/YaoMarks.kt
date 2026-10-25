package io.github.lejw0925.virtualscreen.ui.design

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.lejw0925.virtualscreen.app.AvatarSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Same paths as res/drawable/ic_launcher_foreground.xml (108 viewport).
private const val W_MAIN = "M30.5,43.5C28.5,37 33.5,33 37,37.5L45.6,66.5C46.6,70 49.4,70 50.4,66.5L54,54.5L57.6,66.5C58.6,70 61.4,70 62.4,66.5L71,37.5C72.6,33.2 78.4,32.6 80.6,36.8"
private const val W_TAIL = "M80.6,36.8C83.2,41.6 82.2,49 76.6,55.2"
private const val W_HAIR = "M39.8,43.6L46.6,65.4"
private val wMain by lazy { PathParser().parsePathString(W_MAIN).toPath() }
private val wTail by lazy { PathParser().parsePathString(W_TAIL).toPath() }
private val wHair by lazy { PathParser().parsePathString(W_HAIR).toPath() }

/** Her mark: the W1 ribbon-stroke W, cropped to the glyph (x 27..85, y 31..72 of the icon grid). */
@Composable
fun RibbonW(modifier: Modifier = Modifier, color: Color = LocalYao.current.ink) {
    Canvas(modifier.aspectRatio(58f / 41f)) {
        val k = size.width / 58f
        translate(-27f * k, -31f * k) {
            scale(k, k, pivot = Offset.Zero) {
                drawPath(wMain, color, style = Stroke(5.6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(wTail, color, style = Stroke(3f, cap = StrokeCap.Round))
                drawPath(wHair, color, style = Stroke(1.2f, cap = StrokeCap.Round))
            }
        }
    }
}

/** A row of lace scallops hanging from the top edge of this box, like the frill on her collar. */
@Composable
fun LaceEdge(modifier: Modifier = Modifier, color: Color = LocalYao.current.lace, scallop: Dp = 9.dp) {
    Canvas(modifier.fillMaxWidth().height(scallop / 2 + 3.dp)) {
        val d = scallop.toPx(); val r = d / 2
        val count = (size.width / d).toInt() + 1
        val offset = (size.width - count * d) / 2
        for (i in 0 until count) {
            val cx = offset + i * d + r
            drawArc(color, 0f, 180f, true, topLeft = Offset(cx - r, -r), size = Size(d, d))
            drawCircle(color, radius = d * 0.09f, center = Offset(cx, r + d * 0.22f))
        }
    }
}

/** A plain hairline between sections; the earlier diamond ornament was dropped for a quieter page. */
@Composable
fun LaceDivider(modifier: Modifier = Modifier, color: Color = LocalYao.current.hairline) {
    Canvas(modifier.fillMaxWidth().height(9.dp)) {
        val y = size.height / 2
        drawLine(color, Offset(0f, y), Offset(size.width, y), 1f)
    }
}

private val avatarCache = object : LruCache<String, ImageBitmap>(24) {}

/** Loads an avatar-set image URI (bundled asset or app file) off the main thread. */
@Composable
fun rememberAvatarImage(uri: String?): ImageBitmap? {
    val context = LocalContext.current
    // produceState keeps its state across key changes, so the first picture would stick
    // and later speech_emotion changes would never show. Key the holder on the URI itself.
    val loaded = remember(uri) { mutableStateOf(uri?.let { avatarCache.get(it) }) }
    LaunchedEffect(uri) {
        if (uri == null || loaded.value != null) return@LaunchedEffect
        loaded.value = withContext(Dispatchers.IO) {
            runCatching {
                val bitmap = if (uri.startsWith("file:///android_asset/"))
                    context.assets.open(uri.removePrefix("file:///android_asset/")).use { BitmapFactory.decodeStream(it) }
                else BitmapFactory.decodeFile(android.net.Uri.parse(uri).path)
                bitmap?.asImageBitmap()?.also { avatarCache.put(uri, it) }
            }.getOrNull()
        }
    }
    return loaded.value
}

fun avatarUri(sets: List<AvatarSet>, selected: String, emotion: String): String? {
    val set = sets.firstOrNull { it.id == selected } ?: sets.firstOrNull() ?: return null
    return set.images[emotion] ?: set.images["neutral"]
}

/** A brief dip-and-settle when her face changes, so a new expression is noticed at small sizes. */
@Composable
private fun rememberFacePop(uri: String?): Float {
    val scale = remember { Animatable(1f) }
    var seen by remember { mutableStateOf(uri) }
    LaunchedEffect(uri) {
        if (uri == seen) return@LaunchedEffect
        seen = uri
        scale.snapTo(0.88f)
        scale.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 420f))
    }
    return scale.value
}

/** Round portrait following her reply's speech_emotion. Without a set, her mark stands in. */
@Composable
fun YaoAvatar(uri: String?, size: Dp, modifier: Modifier = Modifier, speaking: Boolean = false) {
    val p = LocalYao.current
    val ring by if (speaking) rememberInfiniteTransition(label = "speak").animateFloat(0.35f, 1f,
        infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "ring")
        else remember { mutableFloatStateOf(0f) }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (speaking) Box(Modifier.matchParentSize().border(1.5.dp, p.ink.copy(alpha = ring), CircleShape))
        val inner = if (speaking) size - 6.dp else size
        val pop = rememberFacePop(uri)
        Crossfade(uri, Modifier.graphicsLayer { scaleX = pop; scaleY = pop }, label = "avatar") { current ->
            val image = rememberAvatarImage(current)
            Box(Modifier.size(inner).clip(CircleShape).background(p.layer).border(1.dp, p.hairline, CircleShape),
                contentAlignment = Alignment.Center) {
                if (image != null) Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else RibbonW(Modifier.fillMaxWidth(0.62f))
            }
        }
    }
}

/** Her voice colour: the one hue on the orb, and only while she speaks. */
val ChargeGreen = Color(0xFF3DD68C)

/**
 * Her floating orb: the portrait nested in a clear glass ring. While she speaks, a green charge runs round the
 * ring; louder speech fills more of it and makes it thicker, and it drains slowly between phrases, so it reads
 * as stored energy rather than a level meter. The glass has a dark outer and a light inner edge so it stays
 * visible over any app behind the overlay. The outer box stays size × 1.4: the floating window docks by that width.
 */
@Composable
fun VoiceOrb(uri: String?, size: Dp, level: Float, modifier: Modifier = Modifier,
    speaking: Boolean = false, listening: Boolean = false, dimmed: Boolean = false) {
    val p = LocalYao.current
    val glow by animateFloatAsState(if (speaking) level else 0f, spring(dampingRatio = 0.6f, stiffness = 700f), label = "glow")
    // Rises within a beat, drains over about 1.6 s from full: fills with each phrase and ebbs in the pauses.
    val charge = remember { Animatable(0f) }
    LaunchedEffect(speaking, level) {
        val target = if (speaking) level.coerceIn(0f, 1f) else 0f
        if (target > charge.value) charge.animateTo(target, tween(120))
        else charge.animateTo(target, tween(((charge.value - target) * 1600).toInt().coerceAtLeast(1), easing = LinearEasing))
    }
    val spin = if (speaking) rememberInfiniteTransition(label = "spin").animateFloat(0f, 360f,
        infiniteRepeatable(tween(3200, easing = LinearEasing)), label = "spinAngle").value else 0f
    val listen = if (listening) rememberInfiniteTransition(label = "listen").animateFloat(0f, 1f,
        infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "listenWave").value else 0f
    val pad = size * 0.2f
    val face = size * 0.78f
    val fade = if (dimmed) 0.55f else 1f
    Box(modifier.size(size + pad * 2), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val c = center
            val fr = face.toPx() / 2
            val band = size.toPx() * 0.11f
            val ringR = fr + band / 2
            val outer = size.toPx() / 2 + pad.toPx()
            fun box(r: Float) = Offset(c.x - r, c.y - r) to Size(r * 2, r * 2)
            val cv = charge.value
            if (cv > 0.01f) drawCircle(ChargeGreen.copy(alpha = 0.16f * cv * fade), ringR + band / 2 + (outer - ringR - band / 2) * cv, c)
            if (listening) drawCircle(p.ink.copy(alpha = 0.35f * (1f - listen)), ringR + (outer - ringR) * listen, c, style = Stroke(1.5.dp.toPx()))
            // Glass: a clear band, dark outer edge, light inner edge, and a highlight on the upper left.
            drawCircle(Color.White.copy(alpha = 0.30f * fade), ringR, c, style = Stroke(band))
            drawCircle(Color.Black.copy(alpha = 0.16f * fade), ringR + band / 2, c, style = Stroke(1.dp.toPx()))
            drawCircle(Color.White.copy(alpha = 0.75f * fade), fr + 0.5.dp.toPx(), c, style = Stroke(1.dp.toPx()))
            val (hlAt, hlSize) = box(ringR + band * 0.12f)
            drawArc(Color.White.copy(alpha = 0.6f * fade), 198f, 64f, false, hlAt, hlSize, style = Stroke(band * 0.24f, cap = StrokeCap.Round))
            if (cv > 0.01f) {
                val sweep = 40f + 320f * cv
                val start = -90f + spin
                val (at, sz) = box(ringR)
                rotate(start, c) {
                    // Tail transparent, head full green: the charge has a direction.
                    val brush = Brush.sweepGradient(0f to Color.Transparent, sweep / 360f to ChargeGreen.copy(alpha = fade), center = c)
                    drawArc(brush, 0f, sweep, false, at, sz, style = Stroke(band * (0.5f + 0.3f * glow), cap = StrokeCap.Butt))
                }
                val a = Math.toRadians((start + sweep).toDouble())
                val head = Offset(c.x + ringR * kotlin.math.cos(a).toFloat(), c.y + ringR * kotlin.math.sin(a).toFloat())
                drawCircle(ChargeGreen.copy(alpha = 0.35f * fade), band * (0.7f + 0.4f * glow), head)
                drawCircle(Color.White.copy(alpha = 0.9f * fade), band * 0.2f, head)
            }
        }
        val pop = rememberFacePop(uri)
        Crossfade(uri, Modifier.size(face).graphicsLayer { scaleX = pop; scaleY = pop }.clip(CircleShape).background(p.layer),
            label = "orbFace") { current ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val image = rememberAvatarImage(current)
                if (image != null) Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                    colorFilter = if (dimmed) ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) else null,
                    alpha = if (dimmed) 0.6f else 1f)
                else RibbonW(Modifier.fillMaxWidth(0.6f))
            }
        }
    }
}

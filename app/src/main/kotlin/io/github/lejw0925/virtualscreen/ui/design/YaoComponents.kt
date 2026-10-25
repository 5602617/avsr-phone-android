package io.github.lejw0925.virtualscreen.ui.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Ribbon-black primary action. */
@Composable
fun YaoButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    icon: ImageVector? = null, busy: Boolean = false) {
    val p = LocalYao.current
    Button(onClick, modifier.heightIn(min = 48.dp), enabled = enabled && !busy, shape = YaoShapes.pill,
        colors = ButtonDefaults.buttonColors(containerColor = p.ink, contentColor = p.onInk,
            disabledContainerColor = p.layerStrong, disabledContentColor = p.inkFaint),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 10.dp)) {
        if (busy) { CircularProgressIndicator(Modifier.size(16.dp), color = p.inkFaint, strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)) }
        else if (icon != null) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, style = YaoType.label)
    }
}

/** Outlined secondary action. */
@Composable
fun YaoOutlineButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    icon: ImageVector? = null, danger: Boolean = false) {
    val p = LocalYao.current
    val tint = if (danger) p.rose else p.ink
    OutlinedButton(onClick, modifier.heightIn(min = 44.dp), enabled = enabled, shape = YaoShapes.pill,
        border = BorderStroke(1.dp, if (danger) p.rose.copy(alpha = 0.6f) else p.hairline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = tint, disabledContentColor = p.inkFaint),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp)) {
        if (icon != null) { Icon(icon, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, style = YaoType.label)
    }
}

@Composable
fun YaoTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false) {
    val p = LocalYao.current
    TextButton(onClick, modifier, enabled = enabled, shape = YaoShapes.pill,
        colors = ButtonDefaults.textButtonColors(contentColor = if (danger) p.rose else p.inkSoft)) { Text(text, style = YaoType.label) }
}

/** Round icon button; filled when it is the main action of its row. */
@Composable
fun YaoIconButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    filled: Boolean = false, enabled: Boolean = true, size: Dp = 42.dp) {
    val p = LocalYao.current
    val bg = when { !filled -> Color.Transparent; enabled -> p.ink; else -> p.layerStrong }
    val fg = when { !enabled -> p.inkFaint; filled -> p.onInk; else -> p.ink }
    Box(modifier.size(size).clip(CircleShape).background(bg).clickable(enabled = enabled, onClick = onClick,
        onClickLabel = description), contentAlignment = Alignment.Center) {
        Icon(icon, description, Modifier.size(size * 0.5f), tint = fg)
    }
}

/** A card on pearl paper; `lace` hangs a scalloped frill under its top edge. */
@Composable
fun YaoCard(modifier: Modifier = Modifier, emphasis: Boolean = false, danger: Boolean = false, lace: Boolean = false,
    padding: Dp = 18.dp, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalYao.current
    val bg = when { danger -> p.roseLayer; emphasis -> p.ink; else -> p.card }
    val border = when { danger -> p.rose.copy(alpha = 0.35f); emphasis -> p.ink; else -> p.hairline }
    Column(modifier.fillMaxWidth().clip(YaoShapes.card).background(bg).border(1.dp, border, YaoShapes.card)) {
        if (lace) LaceEdge(color = if (emphasis) p.inkSoft.copy(alpha = 0.35f) else p.lace)
        CompositionLocalProvider(LocalContentColor provides when { danger -> p.onRoseLayer; emphasis -> p.onInk; else -> p.ink }) {
            Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
    }
}

@Composable
fun YaoSectionTitle(text: String, modifier: Modifier = Modifier, hint: String? = null) {
    Column(modifier.padding(top = 8.dp, bottom = 2.dp)) {
        Text(text, style = YaoType.section)
        if (hint != null) Text(hint, style = YaoType.small, color = LocalYao.current.inkSoft)
    }
}

@Composable
fun YaoField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier,
    password: Boolean = false, number: Boolean = false, singleLine: Boolean = true, placeholder: String? = null) {
    val p = LocalYao.current
    OutlinedTextField(value, onValueChange, modifier.fillMaxWidth(), singleLine = singleLine, shape = YaoShapes.field,
        label = { Text(label) }, placeholder = placeholder?.let { { Text(it, color = p.inkFaint) } },
        textStyle = if (number) YaoType.number.copy(fontSize = YaoType.body.fontSize) else YaoType.body,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = when { password -> KeyboardType.Password; number -> KeyboardType.Number; else -> KeyboardType.Text }),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = p.ink, unfocusedBorderColor = p.hairline,
            focusedLabelColor = p.ink, unfocusedLabelColor = p.inkSoft, cursorColor = p.ink,
            focusedContainerColor = p.card, unfocusedContainerColor = p.card))
}

/** Small capsule for states such as "后台" or "已连接". */
@Composable
fun YaoPill(text: String, modifier: Modifier = Modifier, strong: Boolean = false, danger: Boolean = false, dot: Boolean = false) {
    val p = LocalYao.current
    val bg = when { danger -> p.roseLayer; strong -> p.ink; else -> p.layer }
    val fg = when { danger -> p.onRoseLayer; strong -> p.onInk; else -> p.inkSoft }
    Row(modifier.clip(YaoShapes.pill).background(bg).padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (dot) { Box(Modifier.size(6.dp).clip(CircleShape).background(if (danger) p.rose else fg)); Spacer(Modifier.width(6.dp)) }
        Text(text, style = YaoType.small, color = fg)
    }
}

/** A selectable card used for usage mode and similar exclusive choices. */
@Composable
fun YaoChoice(title: String, body: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, badge: String? = null) {
    val p = LocalYao.current
    Row(modifier.fillMaxWidth().clip(YaoShapes.card).background(if (selected) p.card else p.paper)
        .border(if (selected) 1.5.dp else 1.dp, if (selected) p.ink else p.hairline, YaoShapes.card)
        .clickable(onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = YaoType.bodyStrong)
                if (badge != null) { Spacer(Modifier.width(8.dp)); YaoPill(badge) }
            }
            Text(body, style = YaoType.small, color = p.inkSoft)
        }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.size(22.dp).clip(CircleShape).background(if (selected) p.ink else Color.Transparent)
            .border(1.5.dp, if (selected) p.ink else p.hairline, CircleShape), contentAlignment = Alignment.Center) {
            if (selected) Icon(Icons.Rounded.Check, null, Modifier.size(14.dp), tint = p.onInk)
        }
    }
}

/** One permission or service row: state on the left, the way to fix it on the right. */
@Composable
fun YaoStatusRow(title: String, detail: String, ok: Boolean, action: String?, modifier: Modifier = Modifier, onAction: () -> Unit) {
    val p = LocalYao.current
    Row(modifier.fillMaxWidth().clip(YaoShapes.field).clickable(enabled = action != null, onClick = onAction)
        .padding(horizontal = 6.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(if (ok) p.ink else p.paper)
            .border(1.dp, if (ok) p.ink else p.hairline, CircleShape), contentAlignment = Alignment.Center) {
            if (ok) Icon(Icons.Rounded.Check, null, Modifier.size(15.dp), tint = p.onInk)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = YaoType.bodyStrong)
            Text(detail, style = YaoType.small, color = p.inkSoft)
        }
        if (action != null) {
            Text(action, style = YaoType.label, color = p.ink)
            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp), tint = p.inkSoft)
        }
    }
}

@Composable
fun YaoSwitchRow(title: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val p = LocalYao.current
    Row(Modifier.fillMaxWidth().clip(YaoShapes.field).clickable(enabled = enabled) { onChange(!checked) }
        .padding(horizontal = 6.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = YaoType.bodyStrong)
            if (detail != null) Text(detail, style = YaoType.small, color = p.inkSoft)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked, onChange, enabled = enabled, colors = SwitchDefaults.colors(
            checkedThumbColor = p.onInk, checkedTrackColor = p.ink, checkedBorderColor = p.ink,
            uncheckedThumbColor = p.inkFaint, uncheckedTrackColor = p.layer, uncheckedBorderColor = p.hairline))
    }
}

/** Failure notice in muted rose; the code stays visible in small print so reports match what she says. */
@Composable
fun YaoNotice(message: String, code: String? = null, modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}) {
    val p = LocalYao.current
    Row(modifier.fillMaxWidth().clip(YaoShapes.field).background(p.roseLayer).padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(p.rose))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(message, style = YaoType.small, color = p.onRoseLayer)
            if (!code.isNullOrBlank()) Text(code, style = YaoType.number.copy(fontSize = YaoType.small.fontSize * 0.85f), color = p.rose)
        }
        if (action != null) TextButton(onAction, colors = ButtonDefaults.textButtonColors(contentColor = p.onRoseLayer)) { Text(action, style = YaoType.label) }
    }
}

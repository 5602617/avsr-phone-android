package io.github.lejw0925.virtualscreen.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.github.lejw0925.virtualscreen.app.AvatarSlot
import io.github.lejw0925.virtualscreen.app.AvsrActions
import io.github.lejw0925.virtualscreen.app.AvsrAppState
import io.github.lejw0925.virtualscreen.ui.LocalLanguage
import io.github.lejw0925.virtualscreen.ui.design.*

/**
 * Her faces, per persona (contracts/avatar-presets.md). The user names the slots and decides how many;
 * each slot covers one or more of the tones she already has. Every action names the persona being edited
 * here, captured when the editor opened, so a reply from another persona meanwhile cannot redirect it.
 * Storage, image copying and the tone → image fallback stay in the program; this only edits.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AvatarEditor(state: AvsrAppState, actions: AvsrActions) {
    val l = LocalLanguage.current
    val p = LocalYao.current
    // Personas this phone has met: the current one first, then any with their own settings or history.
    val personas = (listOf(state.personaId) + state.avatarProfiles.keys).filter { it.isNotBlank() }.distinct()
    if (personas.isEmpty()) { AvatarSetPanel(state, actions); return }
    var editing by remember { mutableStateOf(personas.first()) }
    if (editing !in personas) editing = personas.first()
    val personaId = editing
    val profile = state.avatarProfiles[personaId] ?: return
    var naming by remember { mutableStateOf<AvatarSlot?>(null) }
    var adding by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        YaoSectionTitle(l.text("她的头像", "Her faces", "她的頭像"),
            hint = l.text("每张图对应几种语气，她用哪种语气说话就换成哪张；没分到的语气用普通图。",
                "Each picture covers some tones; she wears the one matching how she speaks. Unassigned tones use the plain picture.",
                "每張圖對應幾種語氣，她用哪種語氣說話就換成哪張；沒分到的語氣用普通圖。"))
        if (personas.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            personas.forEach { id ->
                val label = if (id == state.personaId) herName(state) else id
                Box(Modifier.clickableNoRipple { editing = id }) { YaoPill(label, strong = id == personaId) }
            }
        }
        YaoCard(padding = 14.dp) {
            if (state.avatarSets.isNotEmpty()) {
                Text(l.text("底图", "Base set", "底圖"), style = YaoType.bodyStrong)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    state.avatarSets.forEach { set ->
                        val selected = set.id == profile.baseSetId
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            YaoAvatar(set.images["neutral"], 48.dp, Modifier.clip(CircleShape)
                                .then(if (selected) Modifier.border(2.dp, p.ink, CircleShape) else Modifier)
                                .clickableNoRipple { if (!selected) actions.selectPersonaAvatarSet(personaId, set.id) })
                            Text(set.name, style = YaoType.small, maxLines = 1)
                        }
                    }
                }
                Text(l.text("换底图不会丢掉你自己选的图和分组。", "Changing the base keeps your own pictures and groups.",
                    "換底圖不會丟掉你自己選的圖和分組。"), style = YaoType.small, color = p.inkFaint)
                LaceDivider()
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                YaoAvatar(profile.defaultImageUri, 52.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(l.text("普通图", "Plain picture", "普通圖"), style = YaoType.bodyStrong)
                    Text(if (profile.hasCustomDefaultImage) l.text("你选的图", "Your picture", "你選的圖") else l.text("底图自带", "From the base set", "底圖自帶"),
                        style = YaoType.small, color = p.inkSoft)
                }
                YaoIconButton(Icons.Rounded.Image, l.text("换图", "Change picture", "換圖"), { actions.pickAvatarDefaultImage(personaId) }, size = 38.dp)
                if (profile.hasCustomDefaultImage) YaoTextButton(l.text("恢复", "Restore", "恢復"), { actions.restoreAvatarDefaultImage(personaId) })
            }
        }
        profile.slots.forEach { slot ->
            SlotCard(slot, state.speechEmotions, profile.slots,
                onRename = { naming = slot },
                onToggle = { emotion ->
                    val next = if (emotion in slot.emotions) slot.emotions - emotion else slot.emotions + emotion
                    actions.setAvatarSlotEmotions(personaId, slot.id, next)
                },
                onPick = { actions.pickAvatarSlotImage(personaId, slot.id) },
                onRestore = { actions.restoreAvatarSlotImage(personaId, slot.id) },
                onRemove = { actions.removeAvatarSlot(personaId, slot.id) })
        }
        val unassigned = state.speechEmotions.filter { e -> e != "neutral" && profile.slots.none { e in it.emotions } }
        if (unassigned.isNotEmpty()) Text(l.text("用普通图的语气：", "Using the plain picture: ", "用普通圖的語氣：") +
            unassigned.joinToString("、") { YaoWords.emotion(l, it) }, style = YaoType.small, color = p.inkFaint)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            YaoOutlineButton(l.text("加一张", "Add a picture", "加一張"), { adding = true }, Modifier.weight(1f), icon = Icons.Rounded.Add)
            if (profile.hasOverride) YaoOutlineButton(l.text("全部重置", "Reset all", "全部重置"), { confirmReset = true }, Modifier.weight(1f), danger = true)
        }
    }

    naming?.let { slot -> NameDialog(l.text("改个名字", "Rename", "改個名字"), slot.name, { naming = null }) { name ->
        naming = null; actions.renameAvatarSlot(personaId, slot.id, name) } }
    if (adding) NameDialog(l.text("新的一张叫什么", "Name the new picture", "新的一張叫什麼"), "", { adding = false }) { name ->
        adding = false; actions.addAvatarSlot(personaId, name) }
    if (confirmReset) AlertDialog(onDismissRequest = { confirmReset = false },
        title = { Text(l.text("重置头像？", "Reset faces?", "重置頭像？"), style = YaoType.section) },
        text = { Text(l.text("你选的图、改的名字和分组都会清掉，底图保留。", "Your pictures, names and groups are cleared; the base set stays.",
            "你選的圖、改的名字和分組都會清掉，底圖保留。")) },
        confirmButton = { YaoTextButton(l.text("重置", "Reset", "重置"), { confirmReset = false; actions.resetAvatarProfile(personaId) }, danger = true) },
        dismissButton = { YaoTextButton(l.text("算了", "Cancel", "算了"), { confirmReset = false }) },
        containerColor = p.card, shape = YaoShapes.card)
}

/** One picture: its face, its name, and the tones it covers. A tone already used by another picture moves here on tap. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SlotCard(slot: AvatarSlot, emotions: List<String>, all: List<AvatarSlot>, onRename: () -> Unit,
    onToggle: (String) -> Unit, onPick: () -> Unit, onRestore: () -> Unit, onRemove: () -> Unit) {
    val l = LocalLanguage.current
    val p = LocalYao.current
    YaoCard(padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            YaoAvatar(slot.imageUri, 52.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).clickableNoRipple(onRename)) {
                Text(slot.name.ifBlank { l.text("未命名", "Untitled", "未命名") }, style = YaoType.bodyStrong, maxLines = 1)
                Text(if (slot.emotions.isEmpty()) l.text("还没分到语气", "No tone yet", "還沒分到語氣")
                    else slot.emotions.joinToString("、") { YaoWords.emotion(l, it) }, style = YaoType.small, color = p.inkSoft, maxLines = 2)
            }
            YaoIconButton(Icons.Rounded.Edit, l.text("改名", "Rename", "改名"), onRename, size = 34.dp)
            YaoIconButton(Icons.Rounded.Image, l.text("换图", "Change picture", "換圖"), onPick, size = 34.dp)
            YaoIconButton(Icons.Rounded.Delete, l.text("删掉这张", "Remove", "刪掉這張"), onRemove, size = 34.dp)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            emotions.filter { it != "neutral" }.forEach { emotion ->
                val mine = emotion in slot.emotions
                val elsewhere = !mine && all.any { it.id != slot.id && emotion in it.emotions }
                Box(Modifier.clip(YaoShapes.pill).clickableNoRipple { onToggle(emotion) }) {
                    Text(YaoWords.emotion(l, emotion), style = YaoType.small,
                        color = when { mine -> p.onInk; elsewhere -> p.inkFaint; else -> p.inkSoft },
                        modifier = Modifier.clip(YaoShapes.pill).background(if (mine) p.ink else p.layer)
                            .padding(horizontal = 10.dp, vertical = 4.dp))
                }
            }
        }
        if (slot.hasCustomImage) YaoTextButton(l.text("恢复底图这张", "Restore the base picture", "恢復底圖這張"), onRestore)
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val l = LocalLanguage.current
    val p = LocalYao.current
    var name by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(title, style = YaoType.section) },
        text = { YaoField(name, { name = it.take(24) }, l.text("名字", "Name", "名字")) },
        confirmButton = { YaoTextButton(l.text("好", "OK", "好"), { onDone(name.trim()) }, enabled = name.isNotBlank()) },
        dismissButton = { YaoTextButton(l.text("算了", "Cancel", "算了"), onDismiss) },
        containerColor = p.card, shape = YaoShapes.card)
}

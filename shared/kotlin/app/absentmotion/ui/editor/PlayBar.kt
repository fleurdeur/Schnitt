package app.absentmotion.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import app.absentmotion.R
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.PreviewCache
import app.absentmotion.editor.isPlainGroup
import app.absentmotion.editor.skipBack
import app.absentmotion.editor.skipForward
import app.absentmotion.library.PreviewQuality
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.Presence
import app.absentmotion.ui.tutorial.tutorialTarget

@Composable
fun PlayBar(state: EditorState, modifier: Modifier = Modifier, transportOnly: Boolean = false) {
    var menuOpen by remember { mutableStateOf(false) }
    var holdOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = LocalEditorScope.current
    Row(modifier.background(Am.TimelineBg)) {
        val cell = Modifier.weight(1f).fillMaxHeight()
        if (!transportOnly) {
            BarButton(ChromeIcons.Undo, stringResource(R.string.play_undo), cell.tutorialTarget("undo"), enabled = state.canUndo) { state.undo() }
            BarButton(ChromeIcons.Redo, stringResource(R.string.play_redo), cell.tutorialTarget("redo"), enabled = state.canRedo) { state.redo() }
        }
        BarButton(ChromeIcons.SkipBack, stringResource(R.string.play_skip_back), cell.tutorialTarget("skip_back")) { state.skipBack() }
        val settings = state.settings
        Box(cell.tutorialTarget("play")) {
            BarButton(
                if (state.playing) ChromeIcons.Pause else if (settings.loopingPlay) ChromeIcons.PlayRepeat else ChromeIcons.Play,
                stringResource(if (state.playing) R.string.play_pause else R.string.play_play), Modifier.fillMaxSize(),
                onLongClick = if (state.playing) null else ({ holdOpen = true }),
            ) { state.playing = !state.playing }
            if (holdOpen) {
                Popup(
                    popupPositionProvider = AbovePlay,
                    onDismissRequest = { holdOpen = false },
                    properties = PopupProperties(focusable = true),
                ) {
                    HoldPlayPanel(
                        state = state, context = context,
                        close = { holdOpen = false }, render = { grouped ->
                            state.playing = false
                            if (grouped && !state.inGroup) {
                                val id = state.selectedId
                                if (id != null && state.scene.layer(id)?.isPlainGroup == true) state.enterGroup(id)
                            }
                            PreviewCache.render(state, context, scope)
                            holdOpen = false
                        },
                    )
                }
            }
        }
        BarButton(ChromeIcons.SkipForward, stringResource(R.string.play_skip_forward), cell.tutorialTarget("skip_forward")) { state.skipForward() }
        BarButton(ChromeIcons.CopyPaste, stringResource(R.string.play_copy_paste), cell) { menuOpen = true }
        if (!transportOnly) Box(cell) {
            BarButton(
                if (state.previewPanelOpen) ChromeIcons.PreviewModeOpen else ChromeIcons.PreviewMode, stringResource(R.string.play_preview_mode), Modifier.fillMaxSize(),
                iconSize = DpSize(24.dp, 36.dp),
            ) { state.previewPanelOpen = !state.previewPanelOpen }
            if (state.cameraMode && !state.previewPanelOpen) Icon(ChromeIcons.PreviewCamera, null, Modifier.align(Alignment.Center).size(20.dp, 37.dp), tint = Am.Accent)
        }
        Presence(menuOpen) { CopyPasteMenu(state) { menuOpen = false } }
    }
}

@Composable
private fun HoldPlayPanel(
    state: EditorState,
    context: android.content.Context,
    close: () -> Unit,
    render: (Boolean) -> Unit,
) {
    val grouped = state.inGroup || state.selected?.isPlainGroup == true
    val rendering = PreviewCache.phase == PreviewCache.Phase.Rendering
    Column(
        Modifier.width(232.dp).clip(RoundedCornerShape(14.dp)).background(Am.Panel).padding(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.prerender_hold_title), Modifier.weight(1f), color = Am.OnDark, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.action_close), Modifier.clickable(onClick = close).padding(horizontal = 6.dp, vertical = 2.dp), color = Am.OnDarkDim, fontSize = 11.sp)
        }
        Text(
            stringResource(if (grouped) R.string.prerender_hold_grouped_hint else R.string.prerender_hold_active),
            Modifier.padding(top = 2.dp), color = Am.OnDarkDim, fontSize = 10.sp, lineHeight = 13.sp, maxLines = 2,
        )
        Spacer(Modifier.height(7.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
            PreviewQuality.entries.forEach { quality ->
                val active = state.settings.previewQuality == quality
                Box(
                    Modifier.weight(1f).height(28.dp).clip(RoundedCornerShape(14.dp)).background(if (active) Am.Accent else Am.Well)
                        .clickable { state.settings.previewQuality = quality }.padding(horizontal = 2.dp),
                    Alignment.Center,
                ) {
                    Text(
                        stringResource(quality.label), color = if (active) Am.OnAccent else Am.OnDark, fontSize = 9.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal, maxLines = 1,
                    )
                }
            }
            val loop = state.settings.loopingPlay
            Box(
                Modifier.height(28.dp).clip(RoundedCornerShape(14.dp)).background(if (loop) Am.Accent else Am.Well)
                    .clickable { state.settings.loopingPlay = !loop }.padding(horizontal = 8.dp),
                Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(ChromeIcons.PlayRepeat, null, Modifier.size(13.dp), tint = if (loop) Am.OnAccent else Am.OnDark)
                    Spacer(Modifier.width(3.dp))
                    Text(stringResource(R.string.element_retime_loop), color = if (loop) Am.OnAccent else Am.OnDark, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(7.dp))
        Box(
            Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(18.dp)).background(Am.Accent)
                .clickable { if (rendering) PreviewCache.cancel() else render(grouped) },
            Alignment.Center,
        ) {
            Text(
                if (rendering) "${(PreviewCache.progress * 100).toInt()}% · ${context.getString(R.string.preview_cache_cancel_render)}"
                else if (grouped) stringResource(R.string.prerender_hold_grouped) else stringResource(R.string.prerender),
                color = Am.OnAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1,
            )
        }
    }
}

private object AbovePlay : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(8, (windowSize.width - popupContentSize.width - 8).coerceAtLeast(8))
        val above = anchorBounds.top - popupContentSize.height - 8
        val y = if (above >= 8) above else (anchorBounds.bottom + 8).coerceAtMost((windowSize.height - popupContentSize.height - 8).coerceAtLeast(8))
        return IntOffset(x, y)
    }
}

package app.absentmotion.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.absentmotion.R
import app.absentmotion.ai.DepthAnything
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.addDepthMap
import app.absentmotion.model.Layer
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.launch

@Composable
internal fun DepthMapSheet(state: EditorState, layer: Layer, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var installed by remember { mutableStateOf(DepthAnything.installed(context)) }
    var downloading by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var message by remember { mutableStateOf<String?>(null) }
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = true)) {
        Column(Modifier.width(360.dp).clip(RoundedCornerShape(18.dp)).background(Am.Panel).padding(18.dp)) {
            Text(stringResource(R.string.depth_map), color = Am.OnDark, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                if (installed) stringResource(R.string.depth_model_ready) else stringResource(R.string.depth_model_missing),
                Modifier.padding(top = 6.dp), color = Am.OnDarkDim, fontSize = 12.sp, lineHeight = 17.sp,
            )
            if (downloading || running) {
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Am.Well)) {
                    Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(4.dp).background(Am.Accent))
                }
                Text("${(progress * 100).toInt()}%", Modifier.padding(top = 4.dp), color = Am.Accent, fontSize = 11.sp)
            }
            message?.let { Text(it, Modifier.padding(top = 8.dp), color = Am.Warning, fontSize = 12.sp, lineHeight = 16.sp) }
            Spacer(Modifier.height(14.dp))
            if (!installed) {
                Box(
                    Modifier.fillMaxWidth().height(42.dp).clip(RoundedCornerShape(21.dp)).background(Am.Accent)
                        .clickable(enabled = !downloading) {
                            downloading = true; message = null
                            scope.launch {
                                try {
                                    DepthAnything.download(context) { progress = it }
                                    installed = true
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    message = app.absentmotion.i18n.failureText(context.getString(R.string.depth_map_failed), e)
                                } finally { downloading = false }
                            }
                        },
                    Alignment.Center,
                ) { Text(stringResource(if (downloading) R.string.depth_downloading else R.string.depth_download), color = Am.OnAccent, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
            } else {
                Box(
                    Modifier.fillMaxWidth().height(42.dp).clip(RoundedCornerShape(21.dp)).background(Am.Accent)
                        .clickable(enabled = !running) {
                            running = true; progress = 0f; message = null
                            scope.launch {
                                try {
                                    state.addDepthMap(context, layer) { progress = it }
                                    message = context.getString(R.string.depth_map_done)
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    message = app.absentmotion.i18n.failureText(context.getString(R.string.depth_map_failed), e)
                                } finally { running = false }
                            }
                        },
                    Alignment.Center,
                ) { Text(stringResource(if (running) R.string.depth_map_working else R.string.depth_generate), color = Am.OnAccent, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(stringResource(R.string.action_close), Modifier.clickable(onClick = onClose).padding(8.dp), color = Am.OnDarkDim, fontSize = 12.sp)
            }
        }
    }
}

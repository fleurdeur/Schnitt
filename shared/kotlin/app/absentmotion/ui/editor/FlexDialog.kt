package app.absentmotion.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.widget.Toast
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.FlexOptions
import app.absentmotion.editor.applyFlexLayout
import app.absentmotion.ui.editor.panels.ThinSlider
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.launch

/** Dynamic row wrapping / line rigging for the current multi-selection. */
@Composable
internal fun FlexDialog(state: EditorState, ids: Set<Long>, open: Boolean, onDismiss: () -> Unit) {
    if (!open) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var spacing by remember { mutableStateOf(24f) }
    var padding by remember { mutableStateOf(32f) }
    var wrap by remember { mutableStateOf(0f) }
    var growth by remember { mutableStateOf(1f) }
    var alignment by remember { mutableStateOf(1) }
    var distribution by remember { mutableStateOf(0) }
    fun apply() {
        scope.launch {
            val moved = state.applyFlexLayout(ids, FlexOptions(spacing, alignment, padding, wrap, distribution, growth))
            Toast.makeText(context, if (moved == 0) "Select at least two layers" else "$moved layers arranged", Toast.LENGTH_SHORT).show()
        }
    }
    Dialog({ onDismiss() }, DialogProperties(usePlatformDefaultWidth = true)) {
        Column(
            Modifier.widthIn(min = 330.dp, max = 450.dp).clip(RoundedCornerShape(18.dp)).background(Am.Panel)
                .padding(start = 18.dp, end = 14.dp, top = 14.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("FLEX LAYOUT", color = Am.Accent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Text("${ids.size} layers · line and grid rig", color = Am.OnDark, fontSize = 13.sp)
                }
                Text("CLOSE", Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onDismiss).padding(8.dp), color = Am.OnDarkDim, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Number("Spacing", spacing, 0f..200f) { spacing = it }
            Number("Padding", padding, 0f..160f) { padding = it }
            Number("Wrap width", wrap, 0f..4096f) { wrap = it }
            Number("Growth", growth, 0.25f..3f) { growth = it }
            Text("ALIGNMENT", Modifier.padding(top = 5.dp), color = Am.Caption, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            ChoiceRow(listOf("Start", "Middle", "End"), alignment) { alignment = it }
            Text("DISTRIBUTION", Modifier.padding(top = 5.dp), color = Am.Caption, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            ChoiceRow(listOf("Packed", "Between", "Around", "Evenly"), distribution) { distribution = it }
            Spacer(Modifier.height(6.dp))
            Text("APPLY FLEX", Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(Am.Accent).clickable { apply() }
                .padding(vertical = 11.dp), color = Am.OnAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Number(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.widthIn(min = 104.dp), color = Am.OnDark, fontSize = 13.sp)
        ThinSlider(value, onChange, Modifier.weight(1f), valueRange = range)
        Text(value.toInt().toString(), Modifier.padding(start = 8.dp), color = Am.OnDarkDim, fontSize = 10.sp)
    }
}

@Composable
private fun ChoiceRow(labels: List<String>, chosen: Int, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        labels.forEachIndexed { i, label ->
            Text(label, Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).background(if (i == chosen) Am.Accent else Am.Button)
                .clickable { onPick(i) }.padding(vertical = 8.dp), color = if (i == chosen) Am.OnAccent else Am.OnDark,
                fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

package app.absentmotion.ui.editor.panels

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.editor.EditorState
import app.absentmotion.ui.theme.Am

/** Shared editor for executable keyframe expressions. */
@Composable
internal fun ExpressionDialog(state: EditorState) {
    if (!state.expressionOpen) return
    AlertDialog(
        onDismissRequest = { state.expressionOpen = false },
        title = { Text("Expression · ${state.expressionLabel}") },
        text = {
            Column {
                Text(
                    "time, t, frame, value, index · sin, cos, wiggle, noise, clamp, smoothstep, random",
                    color = Am.Caption, fontSize = 11.sp, lineHeight = 15.sp,
                )
                OutlinedTextField(
                    state.expressionText, { state.expressionText = it },
                    Modifier.fillMaxWidth().padding(top = 8.dp), minLines = 3,
                )
            }
        },
        confirmButton = { TextButton({ state.applyExpression() }) { Text("Bake keyframes", color = Am.Accent) } },
        dismissButton = { TextButton({ state.expressionOpen = false }) { Text("Cancel", color = Am.OnDarkDim) } },
    )
}

package app.absentmotion.ui.editor.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.Expressions
import app.absentmotion.ui.editor.ChromeIcons
import app.absentmotion.ui.theme.Am
import kotlin.math.max

/** Shared, app-styled editor for executable keyframe expressions. */
@Composable
internal fun ExpressionDialog(state: EditorState) {
    if (!state.expressionOpen) return
    val layer = state.selected
    val ref = state.expressionRef
    var value by remember { mutableStateOf(TextFieldValue(state.expressionText)) }
    val existing = remember(ref, layer?.id) {
        ref?.let { layer?.element(it)?.get("value") }?.split(',')?.mapNotNull { it.trim().toFloatOrNull() }.orEmpty()
    }
    val samples = remember(value.text, ref, layer?.id, state.scene.fps) {
        val parts = Expressions.parts(value.text)
        (0..48).map { i ->
            val t = i / 48f
            val frame = ((layer?.startTime ?: 0) + ((layer?.endTime ?: 1) - (layer?.startTime ?: 0)) * t).toInt()
            Expressions.evaluate(parts[0], t * 1000f, t, frame, state.scene.fps, state.scene.width, state.scene.height, 0, existing.firstOrNull() ?: 0f)
        }
    }
    Dialog({ state.expressionOpen = false }, DialogProperties(usePlatformDefaultWidth = true)) {
        Column(
            Modifier.padding(14.dp).widthIn(min = 300.dp, max = 470.dp).clip(RoundedCornerShape(18.dp)).background(Am.Panel)
                .verticalScroll(rememberScrollState()),
        ) {
                Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.clip(CircleShape).background(Am.Accent.copy(alpha = 0.15f)).padding(horizontal = 8.dp, vertical = 3.dp)) {
                        Text("EXPRESSION", color = Am.Accent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("  ${state.expressionLabel}", Modifier.weight(1f), color = Am.OnDark, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Box(Modifier.clip(CircleShape).clickable { state.expressionOpen = false }.padding(6.dp), Alignment.Center) {
                        androidx.compose.material3.Icon(ChromeIcons.CloseThick, null, Modifier.height(15.dp), tint = Am.OnDarkDim)
                    }
                }
                Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(Am.Field).padding(10.dp)) {
                        if (value.text.isBlank()) Text("sin(t * 6.28) * 40", color = Am.Caption, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                        BasicTextField(
                            value = value,
                            onValueChange = { value = it; state.expressionText = it.text },
                            textStyle = TextStyle(color = Am.OnDark, fontSize = 13.sp, lineHeight = 19.sp, fontFamily = FontFamily.Monospace),
                            cursorBrush = SolidColor(Am.Accent),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 84.dp),
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("LIVE CURVE", Modifier.weight(1f), color = Am.Caption, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Text("component 1", color = Am.Caption, fontSize = 9.sp)
                }
                val lo = samples.minOrNull() ?: 0f
                val hi = samples.maxOrNull() ?: 1f
                Canvas(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp).height(72.dp).clip(RoundedCornerShape(8.dp)).background(Am.Field)) {
                    val span = max(1e-5f, hi - lo)
                    val pad = 6.dp.toPx()
                    val w = size.width - pad * 2
                    val h = size.height - pad * 2
                    for (i in 1 until samples.size) {
                        val x0 = pad + w * (i - 1) / (samples.size - 1)
                        val x1 = pad + w * i / (samples.size - 1)
                        val y0 = pad + h - h * ((samples[i - 1] - lo) / span)
                        val y1 = pad + h - h * ((samples[i] - lo) / span)
                        drawLine(Am.Accent, Offset(x0, y0), Offset(x1, y1), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                    }
                    val zero = pad + h - h * ((0f - lo) / span)
                    if (zero in pad..(pad + h)) drawLine(Am.Caption.copy(alpha = 0.28f), Offset(pad, zero), Offset(pad + w, zero), strokeWidth = 1.dp.toPx())
                }
                Text(
                    "Variables  time · t · frame · value · index · pi",
                    Modifier.padding(horizontal = 14.dp, vertical = 3.dp), color = Am.OnDarkDim, fontSize = 10.sp,
                )
                FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    for (chip in listOf("t", "time", "frame", "value", "sin()", "cos()", "wiggle(2, 20)", "noise(t, 1)", "clamp(x, 0, 1)", "lerp(0, 1, t)", "smoothstep(0, 1, t)", "random(t)", "if(t > .5, 1, 0)")) {
                        Box(
                            Modifier.clip(RoundedCornerShape(6.dp)).background(Am.Button).clickable {
                                val insert = chip.removeSuffix("()")
                                val text = value.text
                                val at = value.selection.end.coerceIn(0, text.length)
                                val next = text.substring(0, at) + insert + text.substring(at)
                                value = TextFieldValue(next, androidx.compose.ui.text.TextRange(at + insert.length))
                                state.expressionText = next
                            }.padding(horizontal = 8.dp, vertical = 5.dp),
                        ) { Text(chip, color = Am.OnDark, fontSize = 10.sp, fontFamily = FontFamily.Monospace) }
                    }
                }
                Text(
                    "The expression runs as soon as you add it. Its generated values stay hidden from the timeline so they never mix with your own keyframes.",
                    Modifier.padding(horizontal = 14.dp, vertical = 8.dp), color = Am.Caption, fontSize = 10.sp, lineHeight = 14.sp,
                )
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    Text("CANCEL", Modifier.clip(RoundedCornerShape(7.dp)).clickable { state.expressionOpen = false }.padding(horizontal = 12.dp, vertical = 9.dp), color = Am.OnDarkDim, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Box(Modifier.clip(RoundedCornerShape(7.dp)).background(Am.Accent).clickable { state.applyExpression() }.padding(horizontal = 13.dp, vertical = 9.dp)) {
                        Text("ADD EXPRESSION", color = Am.OnAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
        }
    }
}

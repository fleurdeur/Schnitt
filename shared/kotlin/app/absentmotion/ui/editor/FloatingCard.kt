package app.absentmotion.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.padding as pad
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.absentmotion.ui.theme.Am
import kotlin.math.roundToInt

/**
 * A small, dependency-free floating window. Drag the top grip to move it and
 * drag the right, bottom or corner grips to resize it.
 */
@Composable
internal fun FloatingCard(storageKey: String, onMinimize: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("floating-window", 0) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val minW = with(density) { 280.dp.toPx() }
        val minH = with(density) { 260.dp.toPx() }
        val maxW = with(density) { maxWidth.toPx() }
        val maxH = with(density) { maxHeight.toPx() }
        var minimized by remember { mutableStateOf(false) }
        if (minimized) {
            Box(Modifier.align(Alignment.TopEnd).padding(16.dp).clip(RoundedCornerShape(18.dp)).background(Am.Panel).border(1.dp, Am.Line, RoundedCornerShape(18.dp))
                .clickable { minimized = false }.padding(horizontal = 14.dp, vertical = 8.dp)) { Text("Open", color = Am.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            return@BoxWithConstraints
        }
        val defaultW = minOf(maxW * 0.96f, with(density) { 560.dp.toPx() })
        val defaultH = minOf(maxH * 0.86f, with(density) { 780.dp.toPx() })
        fun stored(key: String, fallback: Float) = prefs.getFloat("$storageKey.$key", fallback)
        var width by remember { mutableFloatStateOf(stored("w", defaultW).coerceIn(minW, maxW)) }
        var height by remember { mutableFloatStateOf(stored("h", defaultH).coerceIn(minH, maxH)) }
        var x by remember { mutableFloatStateOf(stored("x", 0f)) }
        var y by remember { mutableFloatStateOf(stored("y", 0f)) }
        fun save() { prefs.edit().putFloat("$storageKey.w", width).putFloat("$storageKey.h", height).putFloat("$storageKey.x", x).putFloat("$storageKey.y", y).apply() }
        fun reset() { width = defaultW; height = defaultH; x = 0f; y = 0f; prefs.edit().remove("$storageKey.w").remove("$storageKey.h").remove("$storageKey.x").remove("$storageKey.y").apply() }
        fun drag(dx: Float, dy: Float) {
            x = (x + dx).coerceIn(-(maxW - width) / 2f, (maxW - width) / 2f)
            y = (y + dy).coerceIn(-(maxH - height) / 2f, (maxH - height) / 2f)
        }
        fun resize(dw: Float, dh: Float) {
            width = (width + dw).coerceIn(minW, maxW)
            height = (height + dh).coerceIn(minH, maxH)
            drag(0f, 0f)
        }
        val shape = RoundedCornerShape(16.dp)
        Box(
            Modifier.align(Alignment.Center)
                .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .width(with(density) { width.toDp() })
                .height(with(density) { height.toDp() })
                .clip(shape)
                .background(Am.Panel)
                .border(1.dp, Am.Line, shape),
        ) {
            content()
            Row(Modifier.align(Alignment.TopEnd).pad(top = 4.dp, end = 4.dp)) {
                Text("↺", Modifier.clickable { reset() }.pad(4.dp), color = Am.OnDarkDim, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("—", Modifier.clickable { minimized = true }.pad(4.dp), color = Am.OnDarkDim, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Box(
                Modifier.align(Alignment.TopCenter).padding(top = 5.dp).size(44.dp, 16.dp)
                    .clip(RoundedCornerShape(8.dp)).background(Am.OnDarkDim.copy(alpha = 0.45f))
                    .pointerInput(storageKey) { detectDragGestures(onDragEnd = { save() }) { change, amount -> change.consume(); drag(amount.x, amount.y) } },
            )
            Box(
                Modifier.align(Alignment.CenterEnd).width(10.dp).fillMaxHeight()
                    .pointerInput(storageKey) { detectDragGestures(onDragEnd = { save() }) { change, amount -> change.consume(); resize(amount.x, 0f) } },
            )
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(10.dp)
                    .pointerInput(storageKey) { detectDragGestures(onDragEnd = { save() }) { change, amount -> change.consume(); resize(0f, amount.y) } },
            )
            Box(
                Modifier.align(Alignment.BottomEnd).size(28.dp)
                    .drawBehind {
                        drawLine(Color(0x887ED9A0), Offset(size.width * 0.35f, size.height * 0.8f), Offset(size.width * 0.8f, size.height * 0.35f), 2.dp.toPx())
                        drawLine(Color(0x557ED9A0), Offset(size.width * 0.55f, size.height * 0.85f), Offset(size.width * 0.85f, size.height * 0.55f), 2.dp.toPx())
                    }
                    .pointerInput(storageKey) { detectDragGestures(onDragEnd = { save() }) { change, amount -> change.consume(); resize(amount.x, amount.y) } },
            )
        }
    }
}

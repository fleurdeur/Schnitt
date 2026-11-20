package app.absentmotion.ui.home

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.absentmotion.library.DebugLog
import app.absentmotion.platform.DialogStatusBarIcons
import app.absentmotion.platform.LocalContext
import app.absentmotion.platform.sheetDialogProperties
import app.absentmotion.ui.editor.PhoneScreen
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DebugLogScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cleared by remember { mutableIntStateOf(0) }
    val text by produceState("", cleared) { value = withContext(Dispatchers.IO) { DebugLog.text(context) } }
    Dialog(onClose, sheetDialogProperties()) {
        PhoneScreen {
            DialogStatusBarIcons(darkIcons = Am.Card.luminance() > 0.5f)
            Column(Modifier.fillMaxSize().background(Am.SheetBg).navigationBarsPadding()) {
                TitleBar(
                    "Debug log", HomeIcons.Close, "Close", onClose,
                    action = HomeIcons.Delete.takeIf { text.isNotEmpty() }, actionDescription = "Clear log",
                ) { cleared++; scope.launch(Dispatchers.IO) { DebugLog.clear(context) } }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier.height(38.dp).clip(RoundedCornerShape(13.dp)).background(Am.Navy).clickable {
                            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Schnitt debug log", text))
                        }.padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("Copy all", color = Am.OnNavy, fontSize = 14.sp) }
                    Box(
                        Modifier.height(38.dp).clip(RoundedCornerShape(13.dp)).background(Am.Accent).clickable {
                            cleared++
                            scope.launch(Dispatchers.IO) { DebugLog.clear(context) }
                        }.padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("Clear", color = Am.OnAccent, fontSize = 14.sp) }
                }
                Spacer(Modifier.height(4.dp))
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    SelectionContainer {
                        Text(
                            text.ifEmpty { "No debug log yet. Import a project or open one, then come back here." },
                            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                            color = Am.HomeInk, fontSize = 11.sp, lineHeight = 15.sp, fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

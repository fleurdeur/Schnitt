package app.absentmotion.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.effects.EffectCatalog
import app.absentmotion.effects.EffectDefinition
import app.absentmotion.i18n.text
import app.absentmotion.library.ImportReport
import app.absentmotion.library.ProjectStore
import app.absentmotion.platform.LocalContext
import app.absentmotion.ui.theme.Am

/** Lets the user replace effects that an imported project could not carry over. */
@Composable
internal fun EffectRemapDialog(store: ProjectStore, report: ImportReport, onDone: (Int) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val catalog = remember { EffectCatalog.read(context) }
    var choices by remember(report.id) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var picking by remember(report.id) { mutableStateOf<app.absentmotion.compat.EffectMigration.Gap?>(null) }
    val definitions = remember(catalog) { catalog.definitions.associateBy { it.id } }
    fun options(gap: app.absentmotion.compat.EffectMigration.Gap): List<EffectDefinition> {
        val words = (gap.label + " " + gap.properties.joinToString(" ")).lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }.toSet()
        return catalog.definitions.map { effect ->
            val text = (effect.name + " " + effect.description + " " + effect.params.joinToString(" ") { it.label }).lowercase()
            effect to words.count { text.contains(it) }
        }.filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }.take(30)
            .ifEmpty { catalog.definitions.take(30) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(context.getString(app.absentmotion.R.string.effect_remap_title), color = Am.Navy) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(context.getString(app.absentmotion.R.string.effect_remap_hint), color = Am.HomeDim, fontSize = 13.sp, lineHeight = 18.sp)
                for (gap in report.gaps) {
                    val chosen = choices[gap.id]?.let { definitions[it]?.name }
                    Column(Modifier.fillMaxWidth().padding(top = 12.dp).clickable { picking = gap }) {
                        Text(gap.label.replaceFirstChar { it.uppercase() }, color = Am.Navy, fontSize = 15.sp)
                        Text(chosen ?: context.getString(app.absentmotion.R.string.effect_remap_choose), color = if (chosen != null) Am.Accent else Am.HomeDim, fontSize = 13.sp)
                        if (gap.properties.isNotEmpty()) Text(gap.properties.joinToString(", "), color = Am.HomeDim, fontSize = 11.sp, maxLines = 2)
                    }
                }
            }
        },
        confirmButton = {
            TextButton({
                val changed = store.remapEffects(report.id, choices, definitions)
                onDone(changed)
            }) { Text(context.getString(app.absentmotion.R.string.done), color = Am.Navy) }
        },
        dismissButton = { TextButton(onDismiss) { Text(context.getString(app.absentmotion.R.string.cancel), color = Am.Navy) } },
        containerColor = Am.Card,
    )
    picking?.let { gap ->
        AlertDialog(
            onDismissRequest = { picking = null },
            title = { Text(gap.label.replaceFirstChar { it.uppercase() }, color = Am.Navy) },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    for (effect in options(gap)) {
                        Text(effect.name, Modifier.fillMaxWidth().clickable {
                            choices = choices + (gap.id to effect.id)
                            picking = null
                        }.padding(vertical = 11.dp), color = Am.Navy, fontSize = 15.sp)
                    }
                }
            },
            confirmButton = { TextButton({ picking = null }) { Text(context.getString(app.absentmotion.R.string.cancel), color = Am.Navy) } },
            containerColor = Am.Card,
        )
    }
}

package app.absentmotion.ui.home

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.ai.AiEnhance
import app.absentmotion.ai.AiMedia
import app.absentmotion.ai.ClipSmooth
import app.absentmotion.ai.DeadFrameRemover
import app.absentmotion.ai.EnhanceMode
import app.absentmotion.ai.EnhanceOptions
import app.absentmotion.engine.EngineThread
import app.absentmotion.export.FrameInterpolation
import app.absentmotion.export.MotionBlur
import app.absentmotion.export.SmoothLevel
import app.absentmotion.export.Smoothing
import app.absentmotion.export.formatBytes
import app.absentmotion.i18n.AppText
import app.absentmotion.media.videoDeclaration
import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.Scene
import app.absentmotion.platform.LocalContext
import app.absentmotion.tools.Compressed
import app.absentmotion.editor.videoLayer
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

internal enum class AiVideoMode(
    @StringRes val title: Int,
    @StringRes val about: Int,
    @StringRes val start: Int,
    @StringRes val running: Int,
    val icon: ImageVector,
) {
    Twixtor(R.string.tools_twixor, R.string.tools_twixor_about, R.string.tools_twixor_start, R.string.tools_making_twixor, HomeIcons.FrameRate),
    DeadFrames(R.string.tools_dfr, R.string.tools_dfr_about, R.string.tools_dfr_start, R.string.tools_removing_dead, HomeIcons.Trim),
    GmAnime(R.string.tools_gm_anime, R.string.tools_gm_anime_about, R.string.tools_upscale_start, R.string.tools_upscaling, HomeIcons.Resolution),
    GmSoul(R.string.tools_gm_soul, R.string.tools_gm_soul_about, R.string.tools_upscale_start, R.string.tools_upscaling, HomeIcons.Resolution),
}

@Composable
internal fun AiVideoScreen(mode: AiVideoMode, initial: Uri? = null, onClose: () -> Unit) {
    val context = LocalContext.current
    val work = rememberToolWork()
    val pick = rememberVideoPick(initial) { work.cancel(); work.reset() }
    val picked = pick.picked
    var factor by rememberSaveable { mutableIntStateOf(if (mode == AiVideoMode.Twixtor) 2 else 1) }
    val media by produceState<MediaDeclaration?>(null, picked?.uri) {
        value = picked?.let { withContext(Dispatchers.IO) { videoDeclaration(context, it.uri) } }
    }
    val engine = remember(mode) {
        if (mode == AiVideoMode.Twixtor) EngineThread(background = Am.PreviewBg.toArgb(), context = context.applicationContext) else null
    }
    DisposableEffect(engine) { onDispose { engine?.close() } }
    val back = { if (work.running) work.cancel() else onClose() }
    val hint = when {
        picked == null -> stringResource(R.string.tools_ai_pick_first)
        media == null -> stringResource(R.string.compress_unreadable)
        mode == AiVideoMode.Twixtor -> stringResource(R.string.tools_twixor_hint, "${media!!.fps?.roundToInt()?.coerceAtLeast(1) ?: 30}", factor)
        mode == AiVideoMode.DeadFrames -> stringResource(R.string.tools_dfr_hint)
        else -> stringResource(R.string.tools_upscale_hint, (media!!.width * 4).coerceAtLeast(2), (media!!.height * 4).coerceAtLeast(2))
    }
    ToolSheet(stringResource(mode.title), back, bottom = {
        ToolBar(
            work, hint, false,
            (work.step as? ToolStep.Done)?.let { stringResource(R.string.tools_done_facts, formatBytes(it.bytes), "video") }.orEmpty(),
            mode.running, stringResource(mode.start), mode.icon,
            picked != null && media != null,
        ) {
            val found = picked ?: return@ToolBar
            val source = media ?: return@ToolBar
            work.start { progress ->
                val output = runAiVideo(context, mode, engine, found.uri, source, factor) { value -> progress(value) }
                Compressed(output.file, output.declaration.type.ifBlank { "video/mp4" }, true)
            }
        }
    }, side = {
        if (mode == AiVideoMode.Twixtor) {
            Label(stringResource(R.string.tools_twixor_frames))
            Chips(listOf("2x", "3x", "4x"), (factor - 2).coerceIn(0, 2), !work.running) { i -> work.change { factor = i + 2 } }
        }
        Label(stringResource(R.string.tools_ai_about))
        Text(when (mode) {
            AiVideoMode.Twixtor -> stringResource(R.string.tools_twixor_note)
            AiVideoMode.DeadFrames -> stringResource(R.string.tools_dfr_note)
            AiVideoMode.GmAnime -> stringResource(R.string.tools_gm_anime_note)
            AiVideoMode.GmSoul -> stringResource(R.string.tools_gm_soul_note)
        }, color = Am.HomeDim, fontSize = 14.sp, lineHeight = 19.sp)
        Spacer(Modifier.height(24.dp))
    }) {
        Spacer(Modifier.height(20.dp))
        PickCard(picked, pick.problem != null, !work.running, R.string.tools_ai_pick, pick.problem ?: R.string.compress_unreadable) { pick.launch() }
        val done = work.step as? ToolStep.Done
        if (done != null) ToolResult(done.result, app.absentmotion.export.ExportKind.Video)
    }
}

private suspend fun runAiVideo(
    context: android.content.Context, mode: AiVideoMode, engine: EngineThread?, uri: Uri, media: MediaDeclaration,
    factor: Int, progress: (Float) -> Unit,
): AiMedia {
    val fps = (media.fps ?: 30f).roundToInt().coerceAtLeast(1)
    val scene = Scene.create("AI Video", media.width.coerceAtLeast(2), media.height.coerceAtLeast(2), fps, 0xFF000000.toInt())
    val layer = scene.videoLayer(media, 0) ?: error(AppText.get(R.string.compress_unreadable))
    val report: app.absentmotion.ai.AiProgress = { value, _ -> progress(value) }
    return when (mode) {
        AiVideoMode.Twixtor -> {
            val target = (fps * factor).coerceIn(12, 240)
            ClipSmooth.run(context, checkNotNull(engine), layer, media, target,
                Smoothing(FrameInterpolation.Flow, SmoothLevel.High, MotionBlur.Off), report)
        }
        AiVideoMode.DeadFrames -> DeadFrameRemover.run(context, layer, media, report).media
        AiVideoMode.GmAnime -> AiEnhance.run(context, layer, media, EnhanceOptions(mode = EnhanceMode.GmAnime), report)
        AiVideoMode.GmSoul -> AiEnhance.run(context, layer, media, EnhanceOptions(mode = EnhanceMode.GmSoul), report)
    }
}

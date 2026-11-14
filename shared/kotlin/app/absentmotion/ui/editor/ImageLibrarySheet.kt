package app.absentmotion.ui.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.absentmotion.R
import app.absentmotion.editor.EditorState
import app.absentmotion.media.ImageSource
import app.absentmotion.media.LibraryImage
import app.absentmotion.media.WebImageLibrary
import app.absentmotion.media.addLibraryImage
import app.absentmotion.ui.export.ExportIcons
import app.absentmotion.ui.theme.Am
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ImageLibrarySheet(state: EditorState, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf(ImageSource.Pinterest) }
    var query by remember { mutableStateOf("") }
    var images by remember { mutableStateOf<List<LibraryImage>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(1) }
    var hasMore by remember { mutableStateOf(false) }
    var moreLoading by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<LibraryImage?>(null) }
    fun search() {
        if (query.isBlank()) return
        loading = true; failed = false; page = 1
        scope.launch {
            val found = runCatching { WebImageLibrary.search(context, source, query, 1) }.getOrDefault(emptyList())
            images = found
            hasMore = found.isNotEmpty()
            failed = found.isEmpty()
            loading = false
        }
    }
    fun loadMore() {
        if (moreLoading || loading || !hasMore || query.isBlank()) return
        moreLoading = true
        scope.launch {
            val next = page + 1
            val found = runCatching { WebImageLibrary.search(context, source, query, next) }.getOrDefault(emptyList())
            val merged = (images + found).distinctBy { it.id }
            hasMore = found.isNotEmpty() && merged.size > images.size
            images = merged
            page = next
            moreLoading = false
        }
    }
    fun add(image: LibraryImage) {
        if (adding != null) return
        adding = image.id
        scope.launch {
            try { state.addLibraryImage(context, image) } finally { adding = null }
        }
    }
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Am.Panel).systemBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clickable(onClick = onClose), Alignment.Center) {
                    androidx.compose.material3.Icon(ExportIcons.Back, stringResource(R.string.action_close), Modifier.size(24.dp), tint = Am.OnDark)
                }
                Text(stringResource(R.string.image_library_title), Modifier.weight(1f), color = Am.OnDark, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 8.dp)) {
                listOf(ImageSource.Pinterest, ImageSource.Cosmos).forEach { item ->
                    val on = item == source
                    Box(Modifier.weight(1f).fillMaxSize().clickable { source = item; images = emptyList(); failed = false; hasMore = false; page = 1 }, Alignment.Center) {
                        Text(item.label, color = if (on) Am.Accent else Am.OnDarkDim, fontSize = 14.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
                        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth(0.6f).height(2.dp).background(if (on) Am.Accent else androidx.compose.ui.graphics.Color.Transparent))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    SearchField(query, { query = it }, Modifier.fillMaxWidth().height(38.dp), hint = stringResource(R.string.image_library_search))
                }
                Spacer(Modifier.width(8.dp))
                Box(Modifier.height(38.dp).clip(RoundedCornerShape(19.dp)).background(Am.Accent).clickable(enabled = !loading) { search() }.padding(horizontal = 16.dp), Alignment.Center) {
                    Text(if (loading) stringResource(R.string.image_library_loading) else stringResource(R.string.image_library_search_button), color = Am.OnAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            when {
                failed -> Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) { Text(stringResource(R.string.image_library_empty), color = Am.OnDarkDim, fontSize = 14.sp) }
                images.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) { Text(stringResource(R.string.image_library_hint), color = Am.OnDarkDim, fontSize = 14.sp) }
                else -> LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize().padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(images, key = { it.id }) { image ->
                        Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(5.dp)).background(Am.Well)
                            .combinedClickable(onClick = { preview = image }, onLongClick = { add(image) })) {
                            var loadUrl by remember(image.id) { mutableStateOf(image.thumb) }
                            var triedFull by remember(image.id) { mutableStateOf(false) }
                            AsyncImage(
                                model = ImageRequest.Builder(context).data(loadUrl).crossfade(false).build(),
                                contentDescription = image.title,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                                onError = {
                                    if (!triedFull) {
                                        triedFull = true
                                        loadUrl = image.full
                                    }
                                },
                            )
                            if (adding == image.id) Box(Modifier.fillMaxSize().background(Am.Scrim.copy(alpha = 0.6f)), Alignment.Center) {
                                Text("…", color = Am.Accent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    if (hasMore) item(key = "image-load-more", span = { GridItemSpan(maxLineSpan) }) {
                        Box(
                            Modifier.fillMaxWidth().padding(vertical = 10.dp).height(44.dp).clip(RoundedCornerShape(22.dp)).background(Am.Well)
                                .clickable(enabled = !moreLoading) { loadMore() },
                            Alignment.Center,
                        ) {
                            if (moreLoading) Text("…", color = Am.Accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            else Text("Load more", color = Am.OnDark, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
    preview?.let { image ->
        Dialog({ preview = null }, DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.fillMaxSize().background(Am.Panel).systemBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(52.dp).clickable { preview = null }, Alignment.Center) { Text("×", color = Am.OnDark, fontSize = 22.sp) }
                    Text(image.title, Modifier.weight(1f), color = Am.OnDark, fontSize = 15.sp, maxLines = 1)
                }
                var previewUrl by remember(image.id) { mutableStateOf(image.full) }
                var previewFallback by remember(image.id) { mutableStateOf(false) }
                AsyncImage(
                    model = ImageRequest.Builder(context).data(previewUrl).crossfade(true).build(),
                    contentDescription = image.title,
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(8.dp),
                    contentScale = ContentScale.Fit,
                    onError = {
                        if (!previewFallback) {
                            previewFallback = true
                            previewUrl = image.thumb
                        }
                    },
                )
                Box(Modifier.fillMaxWidth().padding(12.dp).height(44.dp).clip(RoundedCornerShape(22.dp)).background(Am.Accent).clickable { add(image); preview = null }, Alignment.Center) {
                    Text(stringResource(R.string.image_library_add), color = Am.OnAccent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

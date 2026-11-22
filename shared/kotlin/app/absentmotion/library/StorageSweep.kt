package app.absentmotion.library

import app.absentmotion.model.XmlNode
import app.absentmotion.media.VideoProxies
import app.absentmotion.platform.Context
import app.absentmotion.platform.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.net.URLDecoder

internal fun sweepStorage(files: File, shared: List<File>, now: Long = System.currentTimeMillis()): Int {
    fun old(f: File) = f.walkTopDown().all { now - it.lastModified() > StaleTempMs }
    val projects = File(files, "projects")
    val versionsRoot = File(projects, "versions")
    val versions = ProjectVersions(versionsRoot, now = { now })
    var removed = 0
    for (dir in versionsRoot.listFiles { f -> f.isDirectory && safeSegment(f.name) }.orEmpty()) {
        if (File(projects, "${dir.name}.xml").exists()) versions.prune(dir.name)
        else if (old(dir) && dir.deleteRecursively()) removed++
    }
    for (dir in File(files, "templates").listFiles { f -> f.isDirectory }.orEmpty()) {
        if (!File(dir, "scene.xml").exists() && old(dir) && dir.deleteRecursively()) removed++
    }
    val owners = File(files, "media").listFiles { f -> f.isDirectory && !File(f, ImportMarker).exists() }.orEmpty().toList() +
        File(files, "templates").listFiles { f -> f.isDirectory }.orEmpty().map { File(it, "media") }.filter { it.isDirectory } +
        listOf(File(files, "image-library")).filter { it.isDirectory }
    val candidates = (owners.flatMap { it.listFiles().orEmpty().toList() } + shared.flatMap { it.listFiles { f -> f.isFile }.orEmpty().toList() })
        .filter(::old).toMutableList()
    if (candidates.isEmpty()) return removed
    val paths = HashSet<String>()
    val names = HashSet<String>()
    val canonical = HashMap<String, String?>()
    val texts = projects.listFiles { f -> f.extension == "xml" }.orEmpty().asSequence() +
        versionsRoot.listFiles { f -> f.isDirectory }.orEmpty().asSequence().flatMap { it.listFiles { f -> f.extension == "xml" || f.extension == "damaged" }.orEmpty().asSequence() } +
        File(files, "recovery").listFiles { f -> f.extension == "xml" }.orEmpty().asSequence() +
        File(files, "templates").listFiles { f -> f.isDirectory }.orEmpty().asSequence().map { File(it, "scene.xml") }.filter { it.isFile } +
        File(files, "presets").listFiles { f -> f.extension == "xml" }.orEmpty().asSequence()
    for (source in texts) {
        val text = try { source.readText() } catch (e: Exception) { return removed } catch (e: OutOfMemoryError) { return removed }
        val uris = try { XmlNode.parse(text).keptUris() } catch (e: OutOfMemoryError) { return removed } catch (e: Exception) { null }
        if (uris == null) {
            candidates.removeAll { f -> listOf(f.name, f.toURI().rawPath.trimEnd('/').substringAfterLast('/')).any { it.isNotEmpty() && (text.contains(it) || text.contains(it.replace("&", "&amp;"))) } }
            continue
        }
        for (uri in uris) {
            val leaf = uri.substringAfterLast('/').substringAfterLast('\\')
            names += runCatching { URLDecoder.decode(leaf.replace("+", "%2B"), "UTF-8") }.getOrDefault(leaf)
            if (uri.startsWith("file:", ignoreCase = true)) canonical.getOrPut(uri) { fileFromUri(uri)?.canonicalPath }.let { it?.let(paths::add) }
        }
    }
    for (f in candidates) {
        val path = runCatching { f.canonicalPath }.getOrNull() ?: continue
        val used = f.name in names || path in paths || f.isDirectory && paths.any { it.startsWith(path + File.separator) }
        if (!used && f.deleteRecursively()) removed++
    }
    for (owner in owners) {
        if (owner.parentFile?.name == "media" && !File(projects, "${owner.name}.xml").exists() && owner.list()?.isEmpty() == true && owner.delete()) removed++
    }
    return removed
}

/**
 * Cache is regenerable, so it can be budgeted independently from projects.
 * Waveforms are kept hot for recent edits; preview copies have their own
 * 1 GB budget and partial downloads are always disposable.
 */
internal fun sweepCache(cache: File, now: Long = System.currentTimeMillis()): Int {
    val maxAge = 14L * 24 * 60 * 60 * 1000
    var removed = 0
    for (file in cache.listFiles().orEmpty()) {
        when {
            file.name == "proxies" || file.name.startsWith("gpu-") || file.name == "image-library-http" || file.name == "picture-cache" -> continue
            file.name == "waveforms" && file.isDirectory -> {
                val files = file.listFiles { f -> f.isFile }.orEmpty().sortedByDescending { it.lastModified() }
                var total = 0L
                for (entry in files) {
                    total += entry.length()
                    if ((now - entry.lastModified() > 30L * 24 * 60 * 60 * 1000 || total > 32L * 1024 * 1024) && entry.delete()) removed++
                }
            }
            file.isFile && (file.extension == "part" || now - file.lastModified() > maxAge) -> if (file.delete()) removed++
            file.isDirectory && now - file.lastModified() > maxAge && file.deleteRecursively() -> removed++
        }
    }
    return removed
}


/** Downloaded Google fonts are re-fetchable; keep the most recent 64 MB. */
internal fun sweepFontCache(files: File, now: Long = System.currentTimeMillis()): Int {
    val dir = File(files, "fonts/google")
    if (!dir.isDirectory) return 0
    var removed = 0
    var total = 0L
    for (file in dir.listFiles { f -> f.isFile }.orEmpty().sortedByDescending { it.lastModified() }) {
        total += file.length()
        if (total > 64L * 1024 * 1024 && file.delete()) removed++
    }
    return removed
}
suspend fun sweepStorageDaily(context: Context) = withContext(Dispatchers.IO) {
    val stamp = File(context.noBackupFilesDir, "storage-sweep")
    val now = System.currentTimeMillis()
    // Preview frames are a cache, not project data. Keep only today's run so
    // the app does not carry a gigabyte of old tiles between sessions.
    runCatching {
        context.cacheDir.listFiles { f -> f.isFile && f.name.startsWith("preview-cache-") && now - f.lastModified() > 6L * 60 * 60 * 1000 }
            ?.forEach { it.delete() }
    }
    if (now - stamp.lastModified() in 0 until StaleTempMs) return@withContext
    runCatching { stamp.writeText("") }
    runCatching { sweepCache(context.cacheDir, now) }
    runCatching { VideoProxies.sweep(context, now) }
    runCatching { sweepFontCache(context.filesDir, now) }
    val external = context.getExternalFilesDir(null)
    runCatching {
        File(context.filesDir, "extensions").listFiles { f -> f.name.startsWith(".tmp-") && now - f.lastModified() > StaleTempMs }
            ?.forEach { it.deleteRecursively() }
    }
    runCatching { external?.resolve("compress")?.listFiles()?.filter { now - it.lastModified() > StaleTempMs }?.forEach { it.deleteRecursively() } }
    runCatching { sweepStorage(context.filesDir, listOfNotNull(external?.resolve("ai"), external?.resolve("gif")), now) }
        .onSuccess { Log.i("AMStore", "storage clean-up removed $it unused files") }
        .onFailure { Log.w("AMStore", "storage clean-up failed", it) }
}

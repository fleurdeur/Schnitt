package app.absentmotion.ui.home

import app.absentmotion.platform.readAtMost
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import app.absentmotion.platform.LocalContext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.library.AppSettings
import app.absentmotion.platform.Context
import app.absentmotion.platform.Log
import app.absentmotion.platform.versionName
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.io.File

internal data class ApkAsset(val name: String, val url: String, val size: Long, val sha256: String?, val sums: String?, val manifest: String? = null)

internal data class Release(val version: String, val link: String, val apk: ApkAsset? = null, val assets: List<ApkAsset> = emptyList()) {
    val download: String? get() = apk?.url
    val choices: List<ApkAsset> get() = assets.ifEmpty { listOfNotNull(apk) }
}

internal sealed interface UpdateAnswer {
    data class Available(val release: Release, val external: Boolean = false) : UpdateAnswer
    data object Latest : UpdateAnswer
    data object Failed : UpdateAnswer
}

internal const val UPDATES_ENABLED = true

internal object Updates {
    var checking by mutableStateOf(false)
        private set
    var answer by mutableStateOf<UpdateAnswer?>(null)
    var installedOverride: String? = null
    var source: UpdateSource = GitHubSource
    private var lastAutoAt = 0L
    private var quiet = false
    private val scope = MainScope()

    fun installed(context: Context): String = installedOverride ?: versionName(context)

    fun atStart(context: Context, settings: AppSettings) {
        check(context, settings, manual = false)
    }

    fun hush() {
        quiet = true
        if (answer is UpdateAnswer.Available) answer = null
    }

    fun check(context: Context, settings: AppSettings, manual: Boolean) {
        if (!UPDATES_ENABLED) {
            if (manual) answer = UpdateAnswer.Latest
            return
        }
        if (HOME_PAGE.isBlank()) {
            if (manual) answer = UpdateAnswer.Latest
            return
        }
        if (manual && Updater.reveal()) return
        if (manual && checking) return
        if (!manual) {
            val now = System.currentTimeMillis()
            if (now - lastAutoAt < 20_000) return
            lastAutoAt = now
        }
        if (manual) checking = true
        val current = installed(context)
        val from = source
        val app = context.applicationContext
        scope.launch {
            val (release, inApp) = withContext(Dispatchers.IO) {
                val release = try {
                    fetchLatest(from, app)
                } catch (e: Exception) {
                    Log.i("AMUpdate", "update check failed: $e")
                    cachedRelease(app, from)
                }
                val installable = release != null && (release.apk != null || release.assets.isNotEmpty())
                release to (if (release == null) false else if (!installable) true else Updater.inApp(app, from))
            }
            release?.let { settings.rememberNewest(it.version, it.link) }
            val result = offerFor(answerFor(release, current), inApp)
            if (manual) checking = false
            val ignored = !manual && release != null && settings.ignoredUpdate == release.version
            if (!ignored && (manual || (result is UpdateAnswer.Available && !quiet))) answer = result
        }
    }

    private fun cacheFiles(context: Context) = File(context.noBackupFilesDir, "update-latest.json") to File(context.noBackupFilesDir, "update-latest.etag")

    private fun cachedRelease(context: Context, from: UpdateSource): Release? = runCatching {
        cacheFiles(context).first.takeIf { it.isFile }?.readText()?.let { parseReleases(it, source = from) }
    }.getOrNull()

    private fun fetchLatest(from: UpdateSource, context: Context): Release? {
        val (cache, etagFile) = cacheFiles(context)
        val cachedText = runCatching { cache.takeIf { it.isFile }?.readText() }.getOrNull()
        val etag = runCatching { etagFile.takeIf { it.isFile }?.readText()?.trim() }.getOrNull()
        val headers = linkedMapOf("Accept" to "application/vnd.github+json", "X-GitHub-Api-Version" to "2022-11-28")
        etag?.takeIf { it.isNotBlank() }?.let { headers["If-None-Match"] = it }
        return try {
            TrustedHttp(from, TIMEOUT_MS, TIMEOUT_MS).get(from.releases, headers) { connection ->
                when (connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> {
                        val body = connection.inputStream.use { it.readAtMost(MAX_BYTES) }.decodeToString()
                        runCatching { cache.parentFile?.mkdirs(); cache.writeText(body) }
                        connection.getHeaderField("ETag")?.takeIf { it.isNotBlank() }?.let { tag -> runCatching { etagFile.writeText(tag) } }
                        parseReleases(body, source = from)
                    }
                    HttpURLConnection.HTTP_NOT_MODIFIED -> cachedText?.let { parseReleases(it, source = from) }
                    403, 429 -> cachedText?.let { parseReleases(it, source = from) }
                    else -> cachedText?.let { parseReleases(it, source = from) }
                }
            }
        } catch (e: Exception) {
            Log.i("AMUpdate", "release request failed, using the saved copy: $e")
            cachedText?.let { parseReleases(it, source = from) }
        }
    }

    private const val TIMEOUT_MS = 12_000
    private const val MAX_BYTES = 4 shl 20
}

internal fun answerFor(release: Release?, current: String): UpdateAnswer {
    val newer = release?.let { isNewer(it.version, current) } ?: return UpdateAnswer.Failed
    return if (newer) UpdateAnswer.Available(release) else UpdateAnswer.Latest
}

internal fun warnMetered(answer: UpdateAnswer.Available, metered: Boolean?) = metered == true && answer.release.apk != null

internal fun offerFor(answer: UpdateAnswer, inApp: Boolean): UpdateAnswer =
    if (answer is UpdateAnswer.Available && (answer.release.apk != null || answer.release.assets.isNotEmpty()) && !inApp)
        UpdateAnswer.Available(answer.release.copy(apk = null), external = true)
    else answer

internal fun parseRelease(json: String, flavour: String = app.absentmotion.BuildConfig.FLAVOR, source: UpdateSource = GitHubSource, abi: String? = deviceAbi()): Release? =
    runCatching { JSONObject(json) }.getOrNull()?.let { releaseOf(it, flavour, source, abi) }

internal fun parseReleases(json: String, flavour: String = app.absentmotion.BuildConfig.FLAVOR, source: UpdateSource = GitHubSource, abi: String? = deviceAbi()): Release? {
    val list = runCatching { JSONArray(json) }.getOrNull() ?: return null
    return (0 until list.length()).mapNotNull { list.optJSONObject(it) }
        .filterNot { it.optBoolean("draft") || it.optBoolean("prerelease") }
        .mapNotNull { releaseOf(it, flavour, source, abi) }
        .maxWithOrNull(compareBy<Release> { Version.parse(it.version) })
}

private fun releaseOf(release: JSONObject, flavour: String, source: UpdateSource, abi: String?): Release? {
    val tag = release.optString("tag_name").trim()
    if (Version.parse(tag) == null) return null
    val page = release.optString("html_url").takeIf { it.startsWith("$RELEASE_HOME/", ignoreCase = true) || it.startsWith("$HOME_PAGE/", ignoreCase = true) } ?: "$HOME_PAGE/releases/latest"
    val version = tag.removePrefix("v").removePrefix("V")
    val chosen = apkOf(release, tag, version, flavour, source, abi)
    return Release(version, page, chosen, assetsOf(release, tag, version, source))
}

private val SAFE_VERSION = Regex("^[0-9]+(\\.[0-9]+)*(-[0-9A-Za-z.]+)?$")

internal fun deviceAbi(): String? = Build.SUPPORTED_ABIS.orEmpty().firstOrNull()

private val RELEASE_HOMES = listOf(RELEASE_HOME, HOME_PAGE)

internal fun releaseAsset(url: String, tag: String, name: String): Boolean =
    RELEASE_HOMES.any { home -> url.equals("$home/releases/download/${tag.trim()}/$name", ignoreCase = true) }

internal fun assetsOf(release: JSONObject, tag: String, version: String, source: UpdateSource): List<ApkAsset> {
    if (!SAFE_VERSION.matches(version) || tag.trim() !in setOf("v$version", "V$version")) return emptyList()
    val arrays = release.optJSONArray("assets") ?: return emptyList()
    return (0 until arrays.length()).mapNotNull { arrays.optJSONObject(it) }.mapNotNull { asset ->
        val name = asset.optString("name")
        if (!name.endsWith(".apk", ignoreCase = true)) return@mapNotNull null
        val url = asset.optString("browser_download_url")
        if (!releaseAsset(url, tag, name)) return@mapNotNull null
        val size = asset.optLong("size", -1L).takeIf { it in 1..MAX_APK_BYTES } ?: return@mapNotNull null
        ApkAsset(name, url, size, parseDigest(asset.optString("digest")), null)
    }.sortedWith(compareBy<ApkAsset> { apk -> when { apk.name.contains("arm64", true) -> 0; apk.name.contains("armv7", true) || apk.name.contains("armeabi", true) -> 1; apk.name.contains("universal", true) -> 2; else -> 3 } }.thenBy { it.name })
}

internal fun apkName(version: String, flavour: String, abi: String? = null) =
    "Schnitt-$version-${when (flavour) { "compat" -> "compatible"; "compat32" -> "compatible-32bit"; else -> flavour }}${if (abi == "x86_64" && flavour != "compat32") "-x86_64" else ""}.apk"

private fun apkOf(release: JSONObject, tag: String, version: String, flavour: String, source: UpdateSource, abi: String?): ApkAsset? {
    if (flavour !in setOf("foss", "compat", "compat32", "desktop") || !SAFE_VERSION.matches(version) || tag.trim() !in setOf("v$version", "V$version")) return null
    val assets = release.optJSONArray("assets") ?: return null
    val list = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
    fun linkOf(file: String) = list.firstOrNull { it.optString("name") == file }?.takeIf { releaseAsset(it.optString("browser_download_url"), tag, file) }
    val (name, asset) = (if (flavour == "desktop") installerName(list.map { it.optString("name") }, version)?.let { n -> linkOf(n)?.let { n to it } }
        else apkName(version, flavour, abi).let { n -> linkOf(n)?.let { n to it } }) ?: return null
    val size = asset.optLong("size", -1L).takeIf { it in 1..MAX_APK_BYTES } ?: return null
    val sums = linkOf(SUMS)?.optString("browser_download_url")
    val manifest = if (flavour == "desktop" && linkOf(MANIFEST) != null && linkOf("$MANIFEST.sig") != null) linkOf(MANIFEST)?.optString("browser_download_url") else null
    return ApkAsset(name, asset.optString("browser_download_url"), size, parseDigest(asset.optString("digest")), sums, manifest)
}

private const val SUMS = "SHA256SUMS.txt"
private const val MANIFEST = "update-manifest.json"

private val ARM_TAGS = listOf("arm64", "aarch64")
private val INTEL_TAGS = listOf("x64", "x86_64", "amd64", "intel")

internal fun installerExtensions(os: String, site: InstallSite? = installSite()): List<String> = when {
    os.startsWith("mac", ignoreCase = true) -> if (site?.kind == InstallKind.MacApp) listOf("-update.zip", ".dmg") else listOf(".dmg")
    os.startsWith("windows", ignoreCase = true) -> if (site?.kind == InstallKind.Setup) listOf(".ampack", ".msi") else listOf(".msi", ".exe")
    site?.kind == InstallKind.Deb -> listOf(".deb")
    site?.kind == InstallKind.AppImage -> listOf(".appimage")
    site?.kind == InstallKind.Tarball -> listOf(".tar.gz")
    else -> emptyList()
}

internal fun installerExtension(name: String): String? = listOf(".dmg", ".msi", ".exe", ".ampack", ".deb", ".rpm", ".appimage", ".tar.gz", ".zip").firstOrNull { name.endsWith(it, ignoreCase = true) }

internal fun installerName(names: List<String>, version: String, os: String = System.getProperty("os.name").orEmpty(), arch: String = System.getProperty("os.arch").orEmpty(), site: InstallSite? = installSite()): String? {
    val own = installerExtensions(os, site)
    val wanted = if (arch.contains("aarch64", ignoreCase = true) || arch.contains("arm", ignoreCase = true)) ARM_TAGS else INTEL_TAGS
    val others = (ARM_TAGS + INTEL_TAGS) - wanted.toSet()
    val candidates = names.filter { n -> n.startsWith("absentmotion", ignoreCase = true) && version in n && own.any { n.endsWith(it, ignoreCase = true) } && others.none { n.contains(it, ignoreCase = true) } }
    return own.firstNotNullOfOrNull { ext ->
        val list = candidates.filter { it.endsWith(ext, ignoreCase = true) }
        list.firstOrNull { c -> wanted.any { c.contains(it, ignoreCase = true) } } ?: list.firstOrNull()
    }
}

internal fun isNewer(latest: String, current: String): Boolean? {
    val a = Version.parse(latest) ?: return null
    val b = Version.parse(current) ?: return null
    return a > b
}

internal data class Outdated(val installed: String, val newest: String, val link: String)

internal fun outdatedHint(allowed: Boolean, installed: String, newest: String?, link: String?): Outdated? {
    if (!allowed || newest == null || link == null || !link.startsWith("$HOME_PAGE/", ignoreCase = true)) return null
    return if (isNewer(newest, installed) == true) Outdated(installed, newest, link) else null
}

@Composable
internal fun outdatedNow(settings: AppSettings): Outdated? {
    val context = LocalContext.current
    return outdatedHint(settings.autoUpdateCheck, Updates.installed(context), settings.newestVersion, settings.newestLink)
}

@Composable
internal fun OutdatedHint(outdated: Outdated?, modifier: Modifier = Modifier) {
    outdated ?: return
    val links = LocalUriHandler.current
    Column(modifier.fillMaxWidth()) {
        Text(stringResource(R.string.update_outdated, outdated.installed, outdated.newest), color = Am.Navy)
        TextButton({ runCatching { links.openUri(outdated.link) } }, Modifier.align(Alignment.End)) {
            Text(stringResource(R.string.update_download), color = Am.Navy)
        }
    }
}

internal class Version private constructor(private val numbers: List<Int>, private val pre: List<String>) : Comparable<Version> {
    override fun compareTo(other: Version): Int {
        for (i in 0 until maxOf(numbers.size, other.numbers.size)) {
            val d = numbers.getOrElse(i) { 0 }.compareTo(other.numbers.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        if (pre.isEmpty() != other.pre.isEmpty()) return if (pre.isEmpty()) 1 else -1
        for ((a, b) in pre.zip(other.pre)) {
            val x = a.toIntOrNull()
            val y = b.toIntOrNull()
            val d = when {
                x != null && y != null -> x.compareTo(y)
                x != null -> -1
                y != null -> 1
                else -> a.compareTo(b)
            }
            if (d != 0) return d
        }
        return pre.size.compareTo(other.pre.size)
    }

    companion object {
        fun parse(text: String): Version? {
            val plain = text.trim().removePrefix("v").removePrefix("V").substringBefore('+')
            val numbers = plain.substringBefore('-').split('.').map { part -> part.toIntOrNull()?.takeIf { it >= 0 } ?: return null }
            return Version(numbers, plain.substringAfter('-', "").split('.').filter { it.isNotEmpty() })
        }
    }
}

@Composable
internal fun UpdateAskDialog(settings: AppSettings) {
    AlertDialog(
        onDismissRequest = {},
        text = {
            Column {
                Text(stringResource(R.string.update_check_on_open), color = Am.Navy, fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium)
                Text(stringResource(R.string.update_check_note), Modifier.padding(top = 6.dp), color = Am.HomeMid, fontSize = 14.sp, lineHeight = 20.sp)
            }
        },
        dismissButton = { TextButton({ settings.chooseUpdates(false) }) { Text(stringResource(R.string.update_not_now), color = Am.Navy) } },
        confirmButton = { TextButton({ settings.chooseUpdates(true) }) { Text(stringResource(R.string.update_turn_on), color = Am.Navy) } },
        containerColor = Am.Card,
    )
}

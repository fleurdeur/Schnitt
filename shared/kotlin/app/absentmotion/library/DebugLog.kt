package app.absentmotion.library

import app.absentmotion.platform.versionCodeLong
import app.absentmotion.platform.versionName
import app.absentmotion.platform.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Plain-text support log for import and rendering problems.
 *
 * Every line is readable on its own, so the whole file can be pasted into an
 * issue without a decoder. Paths are shortened the same way crash reports are.
 */
object DebugLog {
    private const val Max = 512 * 1024
    private const val Keep = 256 * 1024
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    @Volatile private var target: File? = null
    private val lock = Any()

    fun install(context: Context) {
        if (target != null) return
        val app = context.applicationContext
        val file = File(app.filesDir, "debug.log")
        target = file
        if (!file.isFile) {
            val code = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionCodeLong }.getOrDefault(0L)
            write("app", "Schnitt ${versionName(app)} ($code) starting")
            write("device", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        }
    }

    fun write(tag: String, message: String) {
        val file = target ?: return
        val line = "${stamp.format(Date())} [$tag] ${message.replace('\n', ' ').take(12000)}\n"
        synchronized(lock) {
            runCatching {
                file.parentFile?.mkdirs()
                if (file.length() > Max) {
                    val old = File(file.parentFile, "debug.old.log")
                    old.delete()
                    file.renameTo(old)
                }
                file.appendText(line)
                if (file.length() > Max) {
                    val text = file.readText().takeLast(Keep)
                    file.writeText(text)
                }
            }
        }
    }

    fun text(context: Context): String {
        val file = File(context.applicationContext.filesDir, "debug.log")
        return runCatching { if (file.isFile) redactPaths(file.readText()) else "" }.getOrDefault("")
    }

    fun clear(context: Context) {
        synchronized(lock) {
            File(context.applicationContext.filesDir, "debug.log").delete()
            File(context.applicationContext.filesDir, "debug.old.log").delete()
        }
    }
}

package app.absentmotion.compat

import app.absentmotion.plugins.PluginKind
import app.absentmotion.plugins.PluginStore
import com.aurea.aurea.engine.AureaEngine
import dalvik.system.DexClassLoader
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Optional bridge to Aurea's native After Effects preset/script reader.
 *
 * Aurea does not read binary .aep or .aepx project files; its importer accepts
 * the RIFX FaFX .ffx preset form and .jsx ExtendScript. Schnitt keeps its own
 * native AEP reader for projects and uses this bridge as a secondary reader
 * when the proprietary library is present.
 *
 * The library is no longer shipped inside the app APK. It is installed as a
 * regular Schnitt plugin and loaded through the plugin's own class loader so
 * Android's native-library namespace accepts the plugin library directory.
 */
object AureaBackend {
    @Volatile private var ready = false
    @Volatile private var checked = false

    private val bundled: Boolean by lazy {
        runCatching { System.loadLibrary("aurea") }.isSuccess
    }

    val available: Boolean get() = (ready || bundled) && isArm64()

    /**
     * Loads the optional Aurea plugin if it is installed. Safe to call from any
     * thread; failures are ignored so the rest of the editor keeps working.
     */
    fun ensure(context: android.content.Context): Boolean {
        if (ready) return true
        if (checked) return ready
        synchronized(this) {
            if (checked) return ready
            checked = true
            ready = bundled || loadPlugin(context)
            return ready
        }
    }

    private fun loadPlugin(context: android.content.Context): Boolean {
        if (!isArm64()) return false
        val plugin = runCatching {
            PluginStore(context).list().firstOrNull { it.kind == PluginKind.Aurea && it.enabled }
        }.getOrNull() ?: return false
        val folder = File(plugin.folder)
        val dex = File(folder, "classes.dex")
        val lib = File(folder, "lib/arm64-v8a")
        if (!dex.isFile || !File(lib, "libaurea.so").isFile) return false
        return runCatching {
            val cache = File(context.codeCacheDir, "aurea-plugin").apply { mkdirs() }
            val loader = DexClassLoader(dex.path, cache.path, lib.path, AureaBackend::class.java.classLoader)
            loader.loadClass("aurea.plugin.Loader").getMethod("load").invoke(null)
            true
        }.getOrDefault(false)
    }

    /**
     * Returns Aurea's JSON import report, or null when the backend is not
     * available or the engine rejects the data.
     */
    fun report(data: ByteArray, name: String, timeMs: Long = 0L): String? {
        if (!available || data.isEmpty()) return null
        val handle = runCatching { AureaEngine.nativeCreate() }.getOrDefault(0L)
        if (handle == 0L) return null
        return try {
            AureaEngine().nativeImportAeData(
                handle,
                data,
                name.toByteArray(StandardCharsets.UTF_8),
                timeMs,
                false,
            )?.toString(StandardCharsets.UTF_8)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { AureaEngine.nativeDestroy(handle) }
        }
    }

    private fun isArm64(): Boolean {
        val arch = System.getProperty("os.arch").orEmpty().lowercase()
        return arch.contains("aarch64") || arch.contains("arm64")
    }
}

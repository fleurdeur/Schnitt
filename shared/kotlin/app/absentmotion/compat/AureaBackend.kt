package app.absentmotion.compat

import com.aurea.aurea.engine.AureaEngine
import java.nio.charset.StandardCharsets

/**
 * Optional bridge to Aurea's native After Effects preset/script reader.
 *
 * Aurea does not read binary .aep or .aepx project files; its importer accepts
 * the RIFX FaFX .ffx preset form and .jsx ExtendScript. Schnitt keeps its own
 * native AEP reader for projects and uses this bridge as a secondary reader
 * when the proprietary library is present. Loading is best effort so a build
 * without the ARM64 library, or a device running another ABI, stays usable.
 */
object AureaBackend {
    private val loaded: Boolean by lazy {
        runCatching { System.loadLibrary("aurea") }.isSuccess
    }

    val available: Boolean get() = loaded && isArm64()

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

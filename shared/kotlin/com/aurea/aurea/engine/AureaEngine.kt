package com.aurea.aurea.engine

/**
 * Minimal binding for the optional Aurea native engine.
 *
 * The class name and native signatures match libaurea.so, so its existing JNI
 * entry points resolve without the rest of the Aurea application. Only the
 * methods used by [app.absentmotion.compat.AureaBackend] are declared here.
 */
class AureaEngine {
    external fun nativeImportAeData(handle: Long, data: ByteArray, name: ByteArray, timeMs: Long, flag: Boolean): ByteArray?

    companion object {
        @JvmStatic external fun nativeCreate(): Long
        @JvmStatic external fun nativeDestroy(handle: Long)
    }
}

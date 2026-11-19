package app.absentmotion.compat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AureaBackendTest {
    @Test fun staysOptionalWhenTheNativeLibraryIsNotOnTheTestHost() {
        if (AureaBackend.available) return
        assertNull(AureaBackend.report(byteArrayOf(1, 2, 3), "preset.ffx"))
        assertFalse(AureaBackend.available)
    }
}

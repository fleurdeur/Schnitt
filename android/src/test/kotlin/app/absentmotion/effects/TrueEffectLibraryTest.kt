package app.absentmotion.effects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TrueEffectLibraryTest {
    private val folder = File("../shared/assets/true-effects")

    @Test fun additionalEffectsParseAndUseKnownCategories() {
        val catalog = EffectCatalog.parse { File(folder, it).inputStream() }
        assertNull(catalog.message)
        assertEquals(291, catalog.definitions.size)
        assertEquals(catalog.definitions.size, catalog.definitions.map { it.key }.toSet().size)
        assertEquals(catalog.definitions.size, catalog.definitions.map { it.id }.toSet().size)
        assertTrue(catalog.definitions.all { it.available && it.sksl?.isNotBlank() == true })
        assertTrue(catalog.definitions.all { it.category in EffectCatalog.Categories })
        assertTrue(catalog.definitions.all { File("../shared/assets/effects", it.thumbnail).isFile })
    }
}

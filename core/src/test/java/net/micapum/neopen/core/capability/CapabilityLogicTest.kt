package net.micapum.neopen.core.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Tests de la lógica pura portada del servidor (marcadores y LanguageTool). */
class CapabilityLogicTest {

    // --- MarkerDetector ---------------------------------------------------------

    @Test
    fun detectsTagsTitleAndKind() {
        val d = MarkerDetector.detect("#proyecto *Mi título* [TODO]\nresto del texto")
        assertEquals(listOf("proyecto"), d.tags)
        assertEquals("Mi título", d.title)
        assertEquals("TODO", d.kind)
    }

    @Test
    fun tagsAreLowercasedDedupedAndUnicode() {
        val d = MarkerDetector.detect("#Café #cafe #Café #señal")
        assertEquals(listOf("café", "cafe", "señal"), d.tags)
    }

    @Test
    fun onlyScansHeaderLines() {
        val text = "a\nb\nc\nd\ne\n#tarde"
        assertEquals(emptyList<String>(), MarkerDetector.detect(text, maxLines = 5).tags)
        assertEquals(listOf("tarde"), MarkerDetector.detect(text, maxLines = 6).tags)
    }

    @Test
    fun noMarkersYieldsEmpty() {
        val d = MarkerDetector.detect("solo texto sin marcadores")
        assertEquals(emptyList<String>(), d.tags)
        assertNull(d.title)
        assertNull(d.kind)
    }

    // --- LanguageTool: applyMatches (reemplazo por offset descendente) ----------

    @Test
    fun appliesReplacementsRightToLeft() {
        val text = "helo wrld"
        val matches = listOf(
            LtMatch(offset = 0, length = 4, replacement = "hello"),
            LtMatch(offset = 5, length = 4, replacement = "world"),
        )
        assertEquals("hello world", applyMatches(text, matches))
    }

    @Test
    fun skipsMatchesWithoutReplacement() {
        val text = "abcde"
        val matches = listOf(LtMatch(offset = 0, length = 2, replacement = null))
        assertEquals("abcde", applyMatches(text, matches))
    }

    @Test
    fun skipsOverlappingAndOutOfRangeMatches() {
        val text = "abcdef"
        // Tras aplicar [3,6)->"X" (nextOffset=3), un match [2,4) solapa (2+2=4 > 3) → se ignora.
        val matches = listOf(
            LtMatch(offset = 3, length = 3, replacement = "X"),
            LtMatch(offset = 2, length = 2, replacement = "Y"),
            LtMatch(offset = 10, length = 1, replacement = "Z"), // fuera de rango
        )
        assertEquals("abcX", applyMatches(text, matches))
    }

    // --- LanguageTool: normalizeLanguage ---------------------------------------

    @Test
    fun normalizesLanguageNamesAndCodes() {
        assertEquals("es", normalizeLanguage("Spanish"))
        assertEquals("ca", normalizeLanguage("català"))
        assertEquals("en", normalizeLanguage("en"))
        assertEquals("en-us", normalizeLanguage("en-US"))
        assertEquals("auto", normalizeLanguage(null))
        assertEquals("auto", normalizeLanguage(""))
        assertEquals("auto", normalizeLanguage("klingon"))
    }
}

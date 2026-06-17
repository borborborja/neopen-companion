package net.micapum.neopen.core.capability

import net.micapum.neopen.core.data.DotDto
import net.micapum.neopen.core.data.OfflineReimportDecision
import net.micapum.neopen.core.data.OfflineReimportPolicy
import net.micapum.neopen.core.data.StrokeDto
import net.micapum.neopen.core.data.freshReimportStrokes
import net.micapum.neopen.core.data.resolveOfflineReimport
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

    // --- Reimportación offline -------------------------------------------------

    @Test
    fun resolvesOfflineReimportPolicies() {
        assertEquals(OfflineReimportDecision.ASK, resolveOfflineReimport(OfflineReimportPolicy.ASK, mergeTargetAvailable = true))
        assertEquals(OfflineReimportDecision.DISCARD, resolveOfflineReimport(OfflineReimportPolicy.DISCARD, mergeTargetAvailable = true))
        assertEquals(OfflineReimportDecision.NEW_CAPTURE, resolveOfflineReimport(OfflineReimportPolicy.NEW_CAPTURE, mergeTargetAvailable = true))
        assertEquals(OfflineReimportDecision.MERGE, resolveOfflineReimport(OfflineReimportPolicy.MERGE, mergeTargetAvailable = true))
    }

    @Test
    fun mergePolicyFallsBackToAskWithoutValidTarget() {
        assertEquals(OfflineReimportDecision.ASK, resolveOfflineReimport(OfflineReimportPolicy.MERGE, mergeTargetAvailable = false))
    }

    @Test
    fun reimportDedupeUsesStartTimeAndDotCount() {
        val existing = listOf(stroke(start = 10, dotCount = 2), stroke(start = 20, dotCount = 3))
        val incoming = listOf(
            stroke(start = 10, dotCount = 2),
            stroke(start = 10, dotCount = 4),
            stroke(start = 30, dotCount = 1),
        )
        assertEquals(listOf(incoming[1], incoming[2]), freshReimportStrokes(existing, incoming))
    }

    private fun stroke(start: Long, dotCount: Int): StrokeDto =
        StrokeDto(
            startTime = start,
            endTime = start + 1,
            dots = List(dotCount) { DotDto(x = it.toFloat(), y = it.toFloat(), f = 1f, t = it.toLong()) },
        )
}

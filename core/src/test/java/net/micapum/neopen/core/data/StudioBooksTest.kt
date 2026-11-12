package net.micapum.neopen.core.data

import org.junit.Assert.*
import org.junit.Test

class StudioBooksTest {
    @Test fun futurePensMakeTwoUniversalScopesOverlapEvenWithCurrentExclusions() {
        assertTrue(StudioPenScopeDto("all", listOf("a")).overlaps(StudioPenScopeDto("all", listOf("b"))))
        assertFalse(StudioPenScopeDto("all", listOf("a")).overlaps(StudioPenScopeDto("selected", penIds = listOf("a"))))
        assertTrue(StudioPenScopeDto("all", listOf("a")).overlaps(StudioPenScopeDto("selected", penIds = listOf("b"))))
    }
    @Test fun identitiesNormalizeOnlyExactMacAndNeverNames() {
        val pens = listOf(PenInfoDto("AA:BB:CC:DD:EE:FF", "Same name", "one", listOf("sdk-id")), PenInfoDto("11:22:33:44:55:66", "Same name", "two"))
        assertEquals("one", canonicalPenId(" aa-bb-cc-dd-ee-ff ", pens))
        assertEquals("one", canonicalPenId("sdk-id", pens))
        assertNull(canonicalPenId("Same name", pens))
        assertEquals("external-ID", normalizePenIdentifier("external-ID"))
    }
    @Test fun secondaryMacIsReadFromCanonicalServerIdentifiers() {
        val pen = requireNotNull(apiMoshi().adapter(PenInfoDto::class.java).fromJson("""{"id":"one","mac":"AA:BB:CC:DD:EE:FF","identifiers":["11:22:33:44:55:66"]}"""))
        assertEquals("one", canonicalPenId("11-22-33-44-55-66", listOf(pen)))
    }
    @Test fun sameTimestampFromDifferentPensIsNeverDiscarded() {
        val old = StrokeDto(1, 2, listOf(DotDto(1f, 1f, 1f)), penId = "one")
        assertEquals(listOf(old.copy(penId = "two")), freshReimportStrokes(listOf(old), listOf(old, old.copy(penId = "two"))))
    }
    @Test fun availabilityCountsCodesOnceAndUsesSyncedProfileBounds() {
        val profile = StudioProfileDto(STUDIO_ENABLED_PROFILE_ID, section = 3, owner = 27, bookMin = 500, bookMax = 503, patternValidated = true)
        fun book(id: String, code: Int, status: String = "ready", archived: Boolean = false, scope: StudioPenScopeDto = StudioPenScopeDto("selected", penIds = listOf(id))) =
            StudioBookDto(id, 1, id, profile.id, 3, 27, code, "a5", StudioRecipeDto(), 4, status, archived, scope, createdAt = "now", updatedAt = "now")
        val books = listOf(
            book("shared-a", 501), book("shared-b", 501),
            book("archived", 502, archived = true),
            book("generating", 503, "generating", scope = StudioPenScopeDto("all", excludedPenIds = listOf("known-a", "known-b"))),
            book("failed", 503, "failed"),
            book("unassigned", 500, scope = StudioPenScopeDto("selected")),
            book("deleted", 500, "deleted"),
            book("out-of-range", 504), book("other-owner", 500).copy(owner = 28),
        )
        assertEquals(StudioCodeAvailability(total = 4, used = 3, available = 1), studioCodeAvailability(books, profile))
        // Se pueden haber creado más libros que códigos ocupados al reutilizar los códigos.
        assertEquals(8, books.count { it.status != "deleted" })
    }
    @Test fun recipesPreserveMixedSectionsAndEpochThroughJson() {
        val json = """{"id":"b","rev":4,"name":"Book","profileId":"p","section":3,"owner":27,"book":512,"format":"a5","recipe":{"version":1,"format":"a5","sections":[{"id":"pdf","kind":"pdf","fromPage":2,"toPage":5,"assetId":"asset"},{"id":"blank","kind":"blank","pageCount":3},{"id":"blank-template","kind":"template","config":{"template":"blank"}}],"borderSeed":4294967295},"pageCount":7,"status":"ready","createdAt":"now","updatedAt":"now","assignmentId":"binding","assignmentRevision":42}"""
        val adapter = apiMoshi().adapter(StudioBookDto::class.java)
        val book = requireNotNull(adapter.fromJson(json))
        assertEquals(4294967295L, book.recipe.borderSeed)
        assertEquals(42L, book.assignmentRevision)
        val restored = requireNotNull(adapter.fromJson(adapter.toJson(book)))
        assertEquals(2, restored.recipe.sections.first().fromPage)
        assertEquals("template", restored.recipe.sections.last().kind)
        assertEquals("", restored.recipe.sections.last().label)
        assertEquals("blank", restored.recipe.sections.last().config?.get("template"))
    }
}

package net.micapum.neopen.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiContractTest {
    private val moshi = apiMoshi()

    @Test
    fun healthMatchesTheCanonicalResponse() {
        val dto = moshi.adapter(HealthDto::class.java)
            .fromJson("""{"ok":true,"version":"0.23.3"}""")

        assertEquals(HealthDto(ok = true, version = "0.23.3"), dto)
    }

    @Test
    fun deltaSyncKeepsRevisionAndTombstoneFields() {
        val json = """
            {
              "cursor": 42,
              "hasMore": false,
              "reset": false,
              "captures": [{
                "id": "capture-1",
                "rev": 40,
                "sobp": {"section": 3, "owner": 27, "book": 388, "page": 5},
                "source": "realtime",
                "capturedAt": "2026-01-02T10:00:00.000Z",
                "importedAt": "2026-01-02T10:00:01.000Z",
                "updatedAt": "2026-01-02T10:00:02.000Z",
                "name": "Contract fixture",
                "tags": [],
                "strokes": [],
                "destination": "inbox",
                "position": 0
              }],
              "notebooks": [],
              "deletions": [{"id":"old-capture","kind":"capture","rev":41}]
            }
        """.trimIndent()

        val dto = moshi.adapter(ChangesResponseDto::class.java).fromJson(json)!!
        assertEquals(42, dto.cursor)
        assertEquals(40, dto.captures.single().rev)
        assertEquals("old-capture", dto.deletions.single().id)
        assertEquals(41, dto.deletions.single().rev)
    }

    @Test
    fun serverSettingsExposeSecretStateButNeverSecretValues() {
        val json = """
            {
              "webdav": {
                "url": {"value":"https://cloud.example.test/dav","locked":false},
                "password": {"configured":true,"locked":true}
              }
            }
        """.trimIndent()

        val dto = moshi.adapter(ServerSettingsDto::class.java).fromJson(json)!!
        assertTrue(dto.webdav?.password?.configured == true)
        assertTrue(dto.webdav?.password?.locked == true)
        assertFalse(json.contains("never-return-this"))
    }
}

package net.micapum.neopen.core.pen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests del gate de re-descarga del volcado offline (Problema 2). */
class OfflineGateTest {

    @Test
    fun skipsWhenCountUnchanged() {
        // Nota ya importada con 120 trazos y el boli reporta los mismos → se omite.
        assertTrue(canSkipOfflineNote(knownStrokeCount = 120, reportedTotal = 120))
    }

    @Test
    fun doesNotSkipWhenCountGrew() {
        // La nota creció (se le añadieron páginas) → hay que re-transferirla.
        assertFalse(canSkipOfflineNote(knownStrokeCount = 120, reportedTotal = 150))
    }

    @Test
    fun doesNotSkipFirstImport() {
        // Nunca importada (sin contador guardado) → siempre se importa.
        assertFalse(canSkipOfflineNote(knownStrokeCount = null, reportedTotal = 120))
    }

    @Test
    fun doesNotSkipWhenReportedZero() {
        // Total 0 (nota vacía o respuesta dudosa) → no se omite por seguridad.
        assertFalse(canSkipOfflineNote(knownStrokeCount = 0, reportedTotal = 0))
    }
}

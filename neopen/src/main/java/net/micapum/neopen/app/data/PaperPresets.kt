package net.micapum.neopen.app.data

import net.micapum.neopen.core.data.PaperDto

/** Formato de página predefinido (en celdas Ncode), espejo de PAPER_PRESETS de la web. */
data class PaperPreset(val id: String, val label: String, val width: Float, val height: Float)

val PAPER_PRESETS: List<PaperPreset> = listOf(
    PaperPreset("a4", "A4", 88.57f, 125.26f),
    PaperPreset("a5", "A5", 62.42f, 88.57f),
    PaperPreset("mole-p", "Moleskine vertical", 37.96f, 59.05f),
    PaperPreset("mole-l", "Moleskine apaisado", 54.83f, 88.57f),
    PaperPreset("mole-xl", "Moleskine XL", 80.14f, 105.44f),
)

/** PaperDto a partir de un preset (origen = id del preset, x0/y0 = 0). */
fun paperFromPreset(p: PaperPreset): PaperDto = PaperDto(0f, 0f, p.width, p.height, p.id)

/** Etiqueta legible del formato actual de un cuaderno/hoja, o null si no tiene. */
fun paperLabel(paper: PaperDto?): String? {
    if (paper == null) return null
    val preset = PAPER_PRESETS.firstOrNull { it.id == paper.source }
    return preset?.label ?: if (paper.source == "neolab") "Auto (NeoLAB)" else "Propio"
}

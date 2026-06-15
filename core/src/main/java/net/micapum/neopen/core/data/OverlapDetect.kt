package net.micapum.neopen.core.data

import kotlin.math.roundToInt

/** Umbral configurable de la detección de trazos superpuestos. */
data class OverlapConfig(
    val enabled: Boolean = true,
    /** Nº mínimo de trazos nuevos que pisan contenido anterior para disparar. */
    val minStrokes: Int = 3,
    /** % mínimo del contenido anterior pisado para disparar (0–100). */
    val minAreaPercent: Int = 25,
)

/** Clave de celda Ncode (resolución entera) empaquetada en un Long. */
private fun cellKey(x: Float, y: Float): Long {
    val xi = Math.round(x).toLong() and 0xffffffffL
    val yi = Math.round(y).toLong() and 0xffffffffL
    return (xi shl 32) or yi
}

/**
 * Detecta si los trazos `fresh` se superponen sobre `existing` por encima del
 * umbral. Trabaja en celdas Ncode (redondeo a entero). Espejo 1:1 de
 * `detectOverlap` en packages/shared (web): determinista y sin dependencias.
 * Devuelve la info del corte sugerido, o `null` si no dispara.
 */
fun detectOverlap(
    existing: List<StrokeDto>,
    fresh: List<StrokeDto>,
    cfg: OverlapConfig,
): OverlapDto? {
    if (!cfg.enabled || existing.isEmpty() || fresh.isEmpty()) return null

    val existingCells = HashSet<Long>()
    for (s in existing) for (d in s.dots) existingCells.add(cellKey(d.x, d.y))
    if (existingCells.isEmpty()) return null

    val hitCells = HashSet<Long>()
    var overlappingStrokes = 0
    for (s in fresh) {
        val seen = HashSet<Long>()
        var hits = 0
        for (d in s.dots) {
            val k = cellKey(d.x, d.y)
            if (!seen.add(k)) continue
            if (existingCells.contains(k)) {
                hits++
                hitCells.add(k)
            }
        }
        // Un trazo "se superpone" si ≥30% de sus celdas pisan contenido anterior.
        if (seen.isNotEmpty() && hits.toDouble() / seen.size >= 0.3) overlappingStrokes++
    }

    val areaPercent = hitCells.size.toDouble() / existingCells.size * 100.0
    val triggers = overlappingStrokes >= cfg.minStrokes || areaPercent >= cfg.minAreaPercent
    if (!triggers) return null

    return OverlapDto(
        strokeIndex = existing.size,
        atTime = fresh.firstOrNull()?.startTime ?: 0L,
        overlappingStrokes = overlappingStrokes,
        areaPercent = areaPercent.roundToInt(),
    )
}

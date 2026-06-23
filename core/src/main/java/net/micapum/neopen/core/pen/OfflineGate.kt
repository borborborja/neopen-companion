package net.micapum.neopen.core.pen

/**
 * Gate de re-descarga del volcado offline (no destructivo).
 *
 * El boli solo permite pedir el volcado por nota/cuaderno completo, e informa del total de
 * trazos de la nota (0xa3) antes de transferir sus paquetes. Si ese total coincide con el que
 * ya importamos de esa nota (guardado por MAC en Room), la nota no ha cambiado y se puede
 * omitir su transferencia. El boli conserva su copia.
 *
 * Granularidad por nota: si la nota cambió (se le añadieron páginas), el total difiere y se
 * re-transfiere entera (la deduplicación por trazo evita duplicar lo ya importado).
 *
 * @param knownStrokeCount total ya importado de la nota (null si nunca se importó).
 * @param reportedTotal total que reporta el boli ahora para la nota.
 */
fun canSkipOfflineNote(knownStrokeCount: Int?, reportedTotal: Int): Boolean =
    knownStrokeCount != null && reportedTotal > 0 && knownStrokeCount == reportedTotal

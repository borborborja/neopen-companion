package net.micapum.neopen.core.data

enum class OfflineReimportDecision {
    ASK,
    DISCARD,
    NEW_CAPTURE,
    MERGE,
}

fun resolveOfflineReimport(policy: OfflineReimportPolicy, mergeTargetAvailable: Boolean): OfflineReimportDecision =
    when (policy) {
        OfflineReimportPolicy.ASK -> OfflineReimportDecision.ASK
        OfflineReimportPolicy.DISCARD -> OfflineReimportDecision.DISCARD
        OfflineReimportPolicy.NEW_CAPTURE -> OfflineReimportDecision.NEW_CAPTURE
        OfflineReimportPolicy.MERGE -> if (mergeTargetAvailable) OfflineReimportDecision.MERGE else OfflineReimportDecision.ASK
    }

fun freshReimportStrokes(existing: List<StrokeDto>, incoming: List<StrokeDto>): List<StrokeDto> =
    incoming.filter { n -> existing.none { it.penId == n.penId && it.startTime == n.startTime && it.dots.size == n.dots.size } }

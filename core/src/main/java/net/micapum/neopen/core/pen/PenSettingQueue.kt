package net.micapum.neopen.core.pen

/** Cambio de ajuste ya validado y listo para codificarse en el protocolo. */
internal data class PenSettingChange(val key: PenSettingKey, val value: Int)

internal sealed interface PenSettingQueueOutcome {
    data object Unexpected : PenSettingQueueOutcome
    data class Confirmed(
        val completed: PenSettingChange,
        val next: PenSettingChange?,
    ) : PenSettingQueueOutcome
    data class Failed(
        val change: PenSettingChange,
        val reason: PenSettingFailure,
    ) : PenSettingQueueOutcome
}

/**
 * Serializa cambios de ajustes: el protocolo identifica el tipo en la respuesta, pero no
 * incluye el valor solicitado. Mantener una sola petición en vuelo permite aplicar con
 * certeza el valor correcto y coalescer cambios posteriores del mismo control.
 */
internal class PenSettingQueue {
    private var inFlight: PenSettingChange? = null
    private val queued = ArrayDeque<PenSettingChange>()
    private var awaitingReconnect = false

    val pendingKeys: Set<PenSettingKey>
        @Synchronized
        get() = buildSet {
            inFlight?.let { add(it.key) }
            queued.forEach { add(it.key) }
        }

    /** Devuelve el cambio que debe enviarse ahora, o null si queda en cola. */
    @Synchronized
    fun enqueue(change: PenSettingChange): PenSettingChange? {
        val current = inFlight
        if (current == null && !awaitingReconnect) {
            inFlight = change
            return change
        }
        if (current == change && queued.none { it.key == change.key }) return null

        // Conserva solo la intención más reciente para un ajuste todavía no enviado.
        val retained = queued.filterNot { it.key == change.key }
        queued.clear()
        queued.addAll(retained)
        queued.addLast(change)
        return null
    }

    @Synchronized
    fun onResponse(settingType: Int, successful: Boolean): PenSettingQueueOutcome {
        val current = inFlight ?: return PenSettingQueueOutcome.Unexpected
        if (current.key.protocolType != settingType) {
            return fail(PenSettingFailure.PROTOCOL)
        }
        if (!successful) {
            return fail(PenSettingFailure.REJECTED)
        }
        val next = if (queued.isEmpty()) null else queued.removeFirst()
        inFlight = next
        return PenSettingQueueOutcome.Confirmed(current, next)
    }

    @Synchronized
    fun fail(reason: PenSettingFailure): PenSettingQueueOutcome {
        val current = inFlight ?: return PenSettingQueueOutcome.Unexpected
        // Sin identificador de petición, un ACK tardío podría confirmar otro valor del
        // mismo ajuste. No enviar nada más hasta tener una NUEVA sesión GATT autorizada.
        inFlight = null
        awaitingReconnect = true
        return PenSettingQueueOutcome.Failed(current, reason)
    }

    /** Conserva intenciones aún no enviadas (incluida la restauración al salir del IME). */
    @Synchronized
    fun resumeAfterReconnect(): PenSettingChange? {
        if (!awaitingReconnect) return null
        awaitingReconnect = false
        inFlight = if (queued.isEmpty()) null else queued.removeFirst()
        return inFlight
    }

    @Synchronized
    fun clear() {
        inFlight = null
        queued.clear()
        awaitingReconnect = false
    }
}

/** Aplica al modelo únicamente un cambio que el boli ya ha confirmado. */
internal fun PenSettings.withConfirmed(change: PenSettingChange): PenSettings = when (change.key) {
    PenSettingKey.AUTO_POWER_ON -> copy(autoPowerOn = change.value != 0)
    PenSettingKey.BEEP -> copy(beep = change.value != 0)
    PenSettingKey.OFFLINE_DATA -> copy(offlineData = change.value != 0)
    PenSettingKey.AUTO_SHUTDOWN -> copy(autoShutdownTime = change.value)
    PenSettingKey.SENSITIVITY -> copy(sensitivity = change.value)
}

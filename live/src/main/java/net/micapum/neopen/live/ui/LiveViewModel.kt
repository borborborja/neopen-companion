package net.micapum.neopen.live.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.micapum.neopen.core.data.AppSettings
import net.micapum.neopen.core.pen.KnownPen
import net.micapum.neopen.core.pen.PenState
import net.micapum.neopen.live.NeopenLiveApp
import net.micapum.neopen.live.ink.InkLanguage
import net.micapum.neopen.live.ink.InkRecognizer
import net.micapum.neopen.live.sync.PenService

class LiveViewModel(app: Application) : AndroidViewModel(app) {
    private val liveApp = app as NeopenLiveApp

    val penState: StateFlow<PenState> = liveApp.pen.state

    val settings: StateFlow<AppSettings> =
        liveApp.settings.flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    /** null = comprobando/desconocido, true = listo, false = sin descargar. */
    private val _modelReady = MutableStateFlow<Boolean?>(null)
    val modelReady: StateFlow<Boolean?> = _modelReady.asStateFlow()
    private val _modelBusy = MutableStateFlow(false)
    val modelBusy: StateFlow<Boolean> = _modelBusy.asStateFlow()

    private fun lang() =
        InkLanguage.entries.firstOrNull { it.tag == settings.value.liveLanguage } ?: InkLanguage.ES

    /** Conecta el boli arrancando el servicio en primer plano (lo mantiene vivo en background). */
    fun connect() = PenService.start(getApplication())

    fun disconnect() {
        PenService.stop(getApplication())
        liveApp.pen.disconnect()
    }

    fun setLanguage(tag: String) {
        viewModelScope.launch {
            liveApp.settings.update(settings.value.copy(liveLanguage = tag))
            refreshModel()
        }
    }

    fun setNewlines(on: Boolean) {
        viewModelScope.launch { liveApp.settings.update(settings.value.copy(liveNewlines = on)) }
    }

    fun setPressureWidthEnabled(on: Boolean) {
        viewModelScope.launch { liveApp.settings.update(settings.value.copy(pressureWidthEnabled = on)) }
    }

    fun setSensitivity(level: Int) = liveApp.pen.setSensitivity(level)

    fun setLockedPen(mac: String?) {
        viewModelScope.launch { liveApp.settings.update(settings.value.copy(lockedPenMac = mac.orEmpty())) }
    }

    fun forgetPen(mac: String) {
        viewModelScope.launch {
            val cur = settings.value
            val locked = if (cur.lockedPenMac == mac) "" else cur.lockedPenMac
            liveApp.settings.update(
                cur.copy(
                    knownPens = cur.knownPens.filterNot { it.mac == mac },
                    forgottenPens = (cur.forgottenPens + mac).distinct(),
                    lockedPenMac = locked,
                ),
            )
            if (penState.value.mac == mac) liveApp.pen.disconnect()
        }
    }

    fun registerCurrentPen() {
        val mac = penState.value.mac ?: return
        val alias = penState.value.name?.takeIf { it.isNotBlank() } ?: mac
        viewModelScope.launch {
            val cur = settings.value
            if (cur.knownPens.none { it.mac == mac }) {
                liveApp.settings.update(cur.copy(knownPens = cur.knownPens + KnownPen(mac, alias, cur.defaultStyle)))
            }
        }
    }

    fun refreshModel() {
        viewModelScope.launch {
            val rec = InkRecognizer(lang())
            _modelReady.value = runCatching { rec.isDownloaded() }.getOrNull()
            rec.close()
        }
    }

    fun downloadModel() {
        if (_modelBusy.value) return
        viewModelScope.launch {
            _modelBusy.value = true
            val rec = InkRecognizer(lang())
            val ok = runCatching { rec.download() }.isSuccess
            if (ok) _modelReady.value = true
            rec.close()
            _modelBusy.value = false
        }
    }

    fun deleteModel() {
        if (_modelBusy.value) return
        viewModelScope.launch {
            _modelBusy.value = true
            val rec = InkRecognizer(lang())
            runCatching { rec.delete() }
            _modelReady.value = false
            rec.close()
            _modelBusy.value = false
        }
    }
}

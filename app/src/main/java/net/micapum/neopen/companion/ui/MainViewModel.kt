package net.micapum.neopen.companion.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.micapum.neopen.companion.NeopenApp
import net.micapum.neopen.companion.data.AppSettings
import net.micapum.neopen.companion.pen.OfflineNote
import net.micapum.neopen.companion.pen.PenState
import net.micapum.neopen.companion.sync.PenService

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val appCtx = app as NeopenApp

    val penState: StateFlow<PenState> = appCtx.pen.state
    val settings: StateFlow<AppSettings> =
        appCtx.settings.flow.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    // --- conexión ---
    fun scan() = appCtx.pen.scanAndConnect()
    fun disconnect() = appCtx.pen.disconnect()

    // --- ajustes del boli ---
    fun setAutoPowerOn(on: Boolean) = appCtx.pen.setAutoPowerOn(on)
    fun setBeep(on: Boolean) = appCtx.pen.setBeep(on)
    fun setOfflineData(on: Boolean) = appCtx.pen.setOfflineData(on)
    fun setAutoShutdown(min: Int) = appCtx.pen.setAutoShutdown(min)

    // --- memoria / offline ---
    fun listOffline() = appCtx.pen.listOffline()
    fun importNote(n: OfflineNote) = appCtx.pen.importOffline(n)
    fun importAll() = appCtx.pen.importAllOffline()
    fun clearOffline() = appCtx.pen.clearOffline(penState.value.notes ?: emptyList())

    /** Sincronización manual: pide la memoria del boli e importa todo. */
    fun syncNow() {
        appCtx.pen.listOffline()
        viewModelScope.launch {
            kotlinx.coroutines.delay(2_000)
            appCtx.pen.importAllOffline()
        }
    }

    // --- ajustes de la app ---
    fun saveSettings(s: AppSettings) {
        viewModelScope.launch {
            appCtx.settings.update(s)
            appCtx.applySyncSchedule(s.syncMode, s.syncIntervalMin)
            if (s.backgroundEnabled) PenService.start(appCtx) else PenService.stop(appCtx)
        }
    }
}

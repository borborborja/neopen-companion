package net.micapum.neopen.companion.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.micapum.neopen.companion.NeopenApp
import net.micapum.neopen.core.data.ApiFactory
import net.micapum.neopen.core.data.AppSettings
import net.micapum.neopen.core.pen.OfflineNote
import net.micapum.neopen.core.pen.PenState
import net.micapum.neopen.companion.sync.PenService

/** Resultado de la prueba de conexión con el servidor neopen. */
sealed interface ServerCheck {
    data object Idle : ServerCheck
    data object Checking : ServerCheck
    data object Ok : ServerCheck
    data object BadAuth : ServerCheck
    data class Unreachable(val detail: String) : ServerCheck
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val appCtx = app as NeopenApp

    val penState: StateFlow<PenState> = appCtx.pen.state
    val settings: StateFlow<AppSettings> =
        appCtx.settings.flow.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _serverCheck = MutableStateFlow<ServerCheck>(ServerCheck.Idle)
    val serverCheck: StateFlow<ServerCheck> = _serverCheck

    /** Registro de diagnóstico BLE (para el panel de debug en Ajustes). */
    val penLogs: StateFlow<List<String>> = appCtx.pen.logs

    // --- conexión ---
    fun scan() = appCtx.pen.scanAndConnect()
    fun disconnect() = appCtx.pen.disconnect()
    /** Detiene el escaneo/conexión en curso. */
    fun stopConnecting() = appCtx.pen.disconnect()
    fun clearLogs() = appCtx.pen.clearLogs()

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

    /**
     * Prueba la conexión con el servidor usando la URL y contraseña indicadas
     * (sin necesidad de guardarlas antes). Comprueba que responde (`/api/health`)
     * y que la contraseña es válida (`/api/capabilities`).
     */
    fun testServer(url: String, password: String) {
        if (url.isBlank()) {
            _serverCheck.value = ServerCheck.Unreachable("Falta la URL del servidor")
            return
        }
        _serverCheck.value = ServerCheck.Checking
        viewModelScope.launch {
            val api = runCatching { ApiFactory.create(url) }.getOrElse {
                _serverCheck.value = ServerCheck.Unreachable("URL no válida"); return@launch
            }
            // 1) ¿responde el servidor?
            val reachable = runCatching { api.health() }
            if (reachable.isFailure) {
                _serverCheck.value =
                    ServerCheck.Unreachable(reachable.exceptionOrNull()?.message ?: "Sin respuesta")
                return@launch
            }
            // 2) ¿es válida la contraseña?
            _serverCheck.value = runCatching { api.capabilities(password) }.fold(
                onSuccess = { resp ->
                    when (resp.code()) {
                        in 200..299 -> {
                            // Credenciales válidas → persístelas (red de seguridad por si
                            // el usuario no pulsa Guardar).
                            appCtx.appScope.launch {
                                appCtx.settings.update(settings.value.copy(serverUrl = url, password = password))
                            }
                            ServerCheck.Ok
                        }
                        401, 403 -> ServerCheck.BadAuth
                        else -> ServerCheck.Unreachable("HTTP ${resp.code()}")
                    }
                },
                onFailure = { ServerCheck.Unreachable(it.message ?: "Error de red") },
            )
        }
    }

    fun resetServerCheck() { _serverCheck.value = ServerCheck.Idle }

    // --- ajustes de la app ---
    fun saveSettings(s: AppSettings) {
        // Se guarda en el scope de la Application: aunque el usuario cierre la pantalla
        // (o la app) justo después de pulsar Guardar, la escritura en disco se completa.
        appCtx.appScope.launch {
            appCtx.settings.update(s)
            appCtx.applySyncSchedule(s.syncMode, s.syncIntervalMin)
            if (s.backgroundEnabled) PenService.start(appCtx) else PenService.stop(appCtx)
        }
    }
}

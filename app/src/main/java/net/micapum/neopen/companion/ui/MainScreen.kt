package net.micapum.neopen.companion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import net.micapum.neopen.companion.data.AppSettings
import net.micapum.neopen.companion.data.SyncMode
import net.micapum.neopen.companion.pen.OfflinePhase
import net.micapum.neopen.companion.pen.PenState
import net.micapum.neopen.companion.pen.PenStatus

private enum class Screen { PEN, SETTINGS }
private val SHUTDOWN = listOf(5, 10, 20, 30, 60)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel) {
    var screen by remember { mutableStateOf(Screen.PEN) }
    val pen by vm.penState.collectAsState()
    val settings by vm.settings.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (screen == Screen.PEN) "neopen companion" else "Ajustes") },
                navigationIcon = {
                    if (screen == Screen.SETTINGS) IconButton(onClick = { screen = Screen.PEN }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Atrás")
                    }
                },
                actions = {
                    if (screen == Screen.PEN) IconButton(onClick = { screen = Screen.SETTINGS }) {
                        Icon(Icons.Default.Settings, contentDescription = "Ajustes")
                    }
                },
            )
        },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (screen) {
                Screen.PEN -> PenScreen(pen, vm, configured = settings.configured)
                Screen.SETTINGS -> SettingsScreen(settings, vm)
            }
        }
    }
}

@Composable
private fun PenScreen(pen: PenState, vm: MainViewModel, configured: Boolean) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (!configured) {
            ElevatedCard {
                Text(
                    "Configura la URL del servidor y la contraseña en Ajustes ⚙️",
                    Modifier.padding(14.dp), color = MaterialTheme.colorScheme.error,
                )
            }
        }

        StatusHeader(pen)

        Button(
            onClick = { vm.scan() },
            enabled = pen.status != PenStatus.SCANNING && pen.status != PenStatus.CONNECTING,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (pen.connected) "Reconectar" else "Conectar boli (Bluetooth)") }

        if (pen.connected) {
            var tab by remember { mutableStateOf(0) }
            TabRow(selectedTabIndex = tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text("Configuración") })
                Tab(tab == 1, { tab = 1 }, text = { Text("Memoria") })
            }
            when (tab) {
                0 -> ConfigTab(pen, vm)
                1 -> MemoryTab(pen, vm)
            }
        } else {
            Text(
                "Necesita un Neo smartpen M1+ encendido y permisos de Bluetooth.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        pen.error?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun StatusHeader(pen: PenState) {
    val color = when (pen.status) {
        PenStatus.AUTHORIZED -> MaterialTheme.colorScheme.secondary
        PenStatus.DISCONNECTED -> MaterialTheme.colorScheme.error
        PenStatus.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> Color(0xFFFFB648)
    }
    val statusText = when (pen.status) {
        PenStatus.IDLE -> "Sin conectar"
        PenStatus.SCANNING -> "Buscando…"
        PenStatus.CONNECTING -> "Conectando…"
        PenStatus.AUTHORIZED -> "Conectado"
        PenStatus.DISCONNECTED -> "Desconectado"
    }
    ElevatedCard {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(pen.name ?: pen.mac ?: "Boli", fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).clip(CircleShape).then(Modifier).padding(0.dp)) {
                    Surface(color = color, shape = CircleShape) { Box(Modifier.size(9.dp)) }
                }
                Spacer(Modifier.width(8.dp))
                val parts = buildList {
                    add(statusText)
                    pen.settings.battery?.let { add("Batería $it%${if (pen.settings.charging) " ⚡" else ""}") }
                    pen.settings.usedMem?.let { add("Memoria ${100 - it}%") }
                }
                Text(parts.joinToString("  ·  "), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ConfigTab(pen: PenState, vm: MainViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ToggleRow("Encendido automático al escribir", pen.settings.autoPowerOn ?: false, vm::setAutoPowerOn)
        ToggleRow("Sonido del lápiz", pen.settings.beep ?: false, vm::setBeep)
        ToggleRow("Guardar escritura offline (sin app)", pen.settings.offlineData ?: false, vm::setOfflineData)
        ShutdownDropdown(pen.settings.autoShutdownTime ?: 20, vm::setAutoShutdown)
        Divider(Modifier.padding(vertical = 4.dp))
        pen.mac?.let { Meta("MAC", it) }
        pen.firmware?.let { Meta("Firmware", it) }
    }
}

@Composable
private fun MemoryTab(pen: PenState, vm: MainViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { vm.listOffline() },
                enabled = pen.offlinePhase == OfflinePhase.IDLE,
                modifier = Modifier.weight(1f),
            ) { Text("Leer memoria") }
            if (!pen.notes.isNullOrEmpty()) OutlinedButton(
                onClick = { vm.importAll() },
                enabled = pen.offlinePhase == OfflinePhase.IDLE,
                modifier = Modifier.weight(1f),
            ) { Text("Importar todo") }
        }

        if (pen.offlinePhase == OfflinePhase.TRANSFERRING) {
            Text("Importando… ${pen.offlineProgress}%")
            LinearProgressIndicator({ pen.offlineProgress / 100f }, Modifier.fillMaxWidth())
        }

        pen.notes?.let { notes ->
            if (notes.isEmpty()) Text(
                "El boli no tiene notas guardadas.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            ) else notes.forEach { n ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${n.section}.${n.owner}.${n.note}" + (n.pages?.let { "  ·  $it págs." } ?: ""))
                    TextButton(onClick = { vm.importNote(n) }, enabled = pen.offlinePhase == OfflinePhase.IDLE) {
                        Text("Importar")
                    }
                }
            }
            if (notes.isNotEmpty()) TextButton(onClick = { vm.clearOffline() }) {
                Text("Vaciar memoria del boli", color = MaterialTheme.colorScheme.error)
            }
        }

        Divider(Modifier.padding(vertical = 4.dp))
        Text("Copia de seguridad", fontWeight = FontWeight.Bold)
        Text(
            "Vuelca toda la memoria del boli al servidor. La copia/restauración completa de la app vive en el propio neopen (Ajustes → Cuadernos).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = { vm.syncNow() }, enabled = pen.offlinePhase == OfflinePhase.IDLE) {
            Text("Volcar memoria al servidor")
        }
    }
}

@Composable
private fun SettingsScreen(s: AppSettings, vm: MainViewModel) {
    var url by remember(s.serverUrl) { mutableStateOf(s.serverUrl) }
    var pw by remember(s.password) { mutableStateOf(s.password) }
    var bg by remember(s.backgroundEnabled) { mutableStateOf(s.backgroundEnabled) }
    var mode by remember(s.syncMode) { mutableStateOf(s.syncMode) }
    var interval by remember(s.syncIntervalMin) { mutableStateOf(s.syncIntervalMin.toString()) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Servidor neopen", fontWeight = FontWeight.Bold)
        OutlinedTextField(url, { url = it }, label = { Text("URL del servidor") },
            placeholder = { Text("https://neopen.midominio.net") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pw, { pw = it }, label = { Text("Contraseña") },
            visualTransformation = PasswordVisualTransformation(), singleLine = true,
            modifier = Modifier.fillMaxWidth())

        Divider()
        Text("Bluetooth y sincronización", fontWeight = FontWeight.Bold)
        ToggleRow("Mantener conexión en segundo plano", bg) { bg = it }

        Text("Volcado de la memoria offline", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(mode == SyncMode.MANUAL, { mode = SyncMode.MANUAL }, label = { Text("Manual") })
            FilterChip(mode == SyncMode.INTERVAL, { mode = SyncMode.INTERVAL }, label = { Text("Por intervalo") })
        }
        if (mode == SyncMode.INTERVAL) {
            OutlinedTextField(interval, { interval = it.filter(Char::isDigit) },
                label = { Text("Intervalo (min, mín. 15)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
        }
        OutlinedButton(onClick = { vm.syncNow() }, modifier = Modifier.fillMaxWidth()) {
            Text("Sincronizar ahora")
        }

        Button(
            onClick = {
                vm.saveSettings(
                    AppSettings(
                        serverUrl = url, password = pw, backgroundEnabled = bg,
                        syncMode = mode, syncIntervalMin = interval.toIntOrNull() ?: 30,
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Guardar") }
    }
}

// --- helpers ---

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShutdownDropdown(value: Int, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(open, { open = !open }) {
        OutlinedTextField(
            value = "$value minutos", onValueChange = {}, readOnly = true,
            label = { Text("Apagado automático") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(open, { open = false }) {
            SHUTDOWN.forEach { m ->
                DropdownMenuItem(text = { Text("$m minutos") }, onClick = { onChange(m); open = false })
            }
        }
    }
}

@Composable
private fun Meta(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
}

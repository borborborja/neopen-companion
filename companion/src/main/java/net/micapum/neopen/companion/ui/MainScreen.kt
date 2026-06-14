package net.micapum.neopen.companion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import net.micapum.neopen.core.data.AppSettings
import net.micapum.neopen.core.data.SyncMode
import net.micapum.neopen.core.pen.OfflinePhase
import net.micapum.neopen.core.pen.PenState
import net.micapum.neopen.core.pen.PenStatus

private enum class Screen { PEN, SETTINGS }
private val SHUTDOWN = listOf(5, 10, 20, 30, 60)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel) {
    var screen by remember { mutableStateOf(Screen.PEN) }
    val pen by vm.penState.collectAsState()
    val settings by vm.settings.collectAsState()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(if (screen == Screen.PEN) "neopen companion" else "Ajustes") },
                navigationIcon = {
                    if (screen == Screen.SETTINGS) IconButton(onClick = { screen = Screen.PEN }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Atrás")
                    }
                },
                actions = {
                    if (screen == Screen.PEN) IconButton(onClick = { screen = Screen.SETTINGS }) {
                        Icon(Icons.Default.Settings, contentDescription = "Ajustes")
                    }
                },
                scrollBehavior = scrollBehavior,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PenScreen(pen: PenState, vm: MainViewModel, configured: Boolean) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (!configured) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Row(
                    Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.ErrorOutline, contentDescription = null)
                    Text("Configura la URL del servidor y la contraseña en Ajustes.")
                }
            }
        }

        StatusCard(pen)

        val busy = pen.status == PenStatus.SCANNING || pen.status == PenStatus.CONNECTING
        if (busy) {
            OutlinedButton(
                onClick = { vm.stopConnecting() },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Detener conexión")
            }
        } else {
            Button(
                onClick = { vm.scan() },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Icon(Icons.Default.Bluetooth, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (pen.connected) "Reconectar" else "Conectar boli")
            }
        }

        if (pen.connected) {
            var tab by remember { mutableStateOf(0) }
            SecondaryTabRow(selectedTabIndex = tab) {
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
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        pen.error?.let { ErrorBanner(it) }
    }
}

// --- Estado del boli como tarjeta destacada con color semántico ---

private data class StatusVisual(
    val container: androidx.compose.ui.graphics.Color,
    val onContainer: androidx.compose.ui.graphics.Color,
    val icon: ImageVector,
    val label: String,
)

@Composable
private fun statusVisual(pen: PenState): StatusVisual {
    val cs = MaterialTheme.colorScheme
    return when (pen.status) {
        PenStatus.AUTHORIZED ->
            StatusVisual(cs.secondaryContainer, cs.onSecondaryContainer, Icons.Default.BluetoothConnected, "Conectado")
        PenStatus.SCANNING ->
            StatusVisual(cs.tertiaryContainer, cs.onTertiaryContainer, Icons.Default.BluetoothSearching, "Buscando…")
        PenStatus.CONNECTING ->
            StatusVisual(cs.tertiaryContainer, cs.onTertiaryContainer, Icons.Default.BluetoothSearching, "Conectando…")
        PenStatus.DISCONNECTED ->
            StatusVisual(cs.errorContainer, cs.onErrorContainer, Icons.Default.BluetoothDisabled, "Desconectado")
        PenStatus.IDLE ->
            StatusVisual(cs.surfaceVariant, cs.onSurfaceVariant, Icons.Default.BluetoothDisabled, "Sin conectar")
    }
}

@Composable
private fun StatusCard(pen: PenState) {
    val v = statusVisual(pen)
    val busy = pen.status == PenStatus.SCANNING || pen.status == PenStatus.CONNECTING
    Card(
        colors = CardDefaults.cardColors(containerColor = v.container, contentColor = v.onContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Surface(
                    shape = CircleShape,
                    color = v.onContainer.copy(alpha = 0.12f),
                    modifier = Modifier.size(48.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.5.dp,
                                color = v.onContainer,
                            )
                        } else {
                            Icon(v.icon, contentDescription = null, modifier = Modifier.size(24.dp))
                        }
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        pen.name ?: pen.mac ?: "Boli",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(v.label, style = MaterialTheme.typography.bodyMedium)
                }
            }
            val pills = buildList {
                pen.settings.battery?.let { add("Batería $it%${if (pen.settings.charging) " ⚡" else ""}") }
                pen.settings.usedMem?.let { add("Memoria ${100 - it}% libre") }
            }
            if (pills.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pills.forEach { InfoPill(it, v.onContainer) }
                }
            }
        }
    }
}

@Composable
private fun InfoPill(text: String, onContainer: androidx.compose.ui.graphics.Color) {
    Surface(shape = MaterialTheme.shapes.small, color = onContainer.copy(alpha = 0.12f)) {
        Text(
            text,
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
            color = onContainer,
        )
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp))
        Text("Error: $message", color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium)
    }
}

// --- Pestaña de configuración del boli ---

@Composable
private fun ConfigTab(pen: PenState, vm: MainViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ToggleRow("Encendido automático", "Se enciende al empezar a escribir",
            pen.settings.autoPowerOn ?: false, vm::setAutoPowerOn)
        ToggleRow("Sonido del lápiz", "Pitido al escribir",
            pen.settings.beep ?: false, vm::setBeep)
        ToggleRow("Guardar offline", "Almacena la escritura sin la app abierta",
            pen.settings.offlineData ?: false, vm::setOfflineData)
        Spacer(Modifier.height(4.dp))
        ShutdownDropdown(pen.settings.autoShutdownTime ?: 20, vm::setAutoShutdown)
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        pen.mac?.let { Meta("MAC", it) }
        pen.firmware?.let { Meta("Firmware", it) }
    }
}

// --- Pestaña de memoria offline ---

@Composable
private fun MemoryTab(pen: PenState, vm: MainViewModel) {
    val idle = pen.offlinePhase == OfflinePhase.IDLE
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.listOffline() }, enabled = idle, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Leer memoria")
            }
            if (!pen.notes.isNullOrEmpty()) FilledTonalButton(
                onClick = { vm.importAll() }, enabled = idle, modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Importar todo")
            }
        }

        if (pen.offlinePhase == OfflinePhase.TRANSFERRING) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Importando… ${pen.offlineProgress}%", style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(
                    progress = { pen.offlineProgress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        pen.notes?.let { notes ->
            if (notes.isEmpty()) {
                Text("El boli no tiene notas guardadas.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium)
            } else {
                Card {
                    Column {
                        notes.forEachIndexed { i, n ->
                            ListItem(
                                headlineContent = { Text("${n.section}.${n.owner}.${n.note}") },
                                supportingContent = n.pages?.let { { Text("$it páginas") } },
                                trailingContent = {
                                    TextButton(onClick = { vm.importNote(n) }, enabled = idle) { Text("Importar") }
                                },
                            )
                            if (i < notes.lastIndex) HorizontalDivider()
                        }
                    }
                }
                TextButton(onClick = { vm.clearOffline() }) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Vaciar memoria del boli", color = MaterialTheme.colorScheme.error)
                }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text("Copia de seguridad", style = MaterialTheme.typography.titleSmall)
        Text(
            "Vuelca toda la memoria del boli al servidor. La copia/restauración completa de la app vive en el propio neopen (Ajustes → Cuadernos).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = { vm.syncNow() }, enabled = idle, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Volcar memoria al servidor")
        }
    }
}

// --- Pantalla de ajustes de la app ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(s: AppSettings, vm: MainViewModel) {
    var url by remember(s.serverUrl) { mutableStateOf(s.serverUrl) }
    var pw by remember(s.password) { mutableStateOf(s.password) }
    var bg by remember(s.backgroundEnabled) { mutableStateOf(s.backgroundEnabled) }
    var mode by remember(s.syncMode) { mutableStateOf(s.syncMode) }
    var interval by remember(s.syncIntervalMin) { mutableStateOf(s.syncIntervalMin.toString()) }
    val check by vm.serverCheck.collectAsState()
    val logs by vm.penLogs.collectAsState()
    var showLogs by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionHeader("Servidor neopen")
        OutlinedTextField(url, { url = it; vm.resetServerCheck() }, label = { Text("URL del servidor") },
            placeholder = { Text("https://neopen.midominio.net") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pw, { pw = it; vm.resetServerCheck() }, label = { Text("Contraseña") },
            visualTransformation = PasswordVisualTransformation(), singleLine = true,
            modifier = Modifier.fillMaxWidth())

        OutlinedButton(
            onClick = { vm.testServer(url, pw) },
            enabled = check != ServerCheck.Checking,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (check == ServerCheck.Checking) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Default.CloudDone, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text("Probar conexión")
        }
        ServerCheckResult(check)

        HorizontalDivider()
        SectionHeader("Bluetooth y sincronización")
        ToggleRow("Conexión en segundo plano", "Mantiene el boli conectado con la app cerrada",
            bg) { bg = it }

        Text("Volcado de la memoria offline", style = MaterialTheme.typography.titleSmall)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = mode == SyncMode.MANUAL,
                onClick = { mode = SyncMode.MANUAL },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
            ) { Text("Manual") }
            SegmentedButton(
                selected = mode == SyncMode.INTERVAL,
                onClick = { mode = SyncMode.INTERVAL },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
            ) { Text("Por intervalo") }
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
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("Guardar") }

        HorizontalDivider()
        SectionHeader("Diagnóstico")
        Text(
            "Registro de la conexión Bluetooth con el boli. Útil para ver dónde se queda al conectar.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = { showLogs = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.BugReport, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Ver registro de conexión (${logs.size})")
        }
    }

    if (showLogs) {
        DebugLogDialog(logs, onClear = { vm.clearLogs() }, onDismiss = { showLogs = false })
    }
}

@Composable
private fun DebugLogDialog(logs: List<String>, onClear: () -> Unit, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val text = if (logs.isEmpty()) "(sin registros — pulsa «Conectar boli»)" else logs.joinToString("\n")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Registro de conexión") },
        text = {
            Column(
                Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Copiar")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onClear) { Text("Limpiar") }
                TextButton(onClick = onDismiss) { Text("Cerrar") }
            }
        },
    )
}

// --- helpers ---

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun ServerCheckResult(check: ServerCheck) {
    val cs = MaterialTheme.colorScheme
    val (icon, color, msg) = when (check) {
        ServerCheck.Idle, ServerCheck.Checking -> return
        ServerCheck.Ok ->
            Triple(Icons.Default.CheckCircle, cs.primary, "Conexión correcta. Servidor y contraseña válidos.")
        ServerCheck.BadAuth ->
            Triple(Icons.Default.ErrorOutline, cs.error, "El servidor responde, pero la contraseña es incorrecta.")
        is ServerCheck.Unreachable ->
            Triple(Icons.Default.CloudOff, cs.error, "No se pudo conectar: ${check.detail}")
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Text(msg, color = color, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Switch(checked, onChange) },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
    )
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
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
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
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

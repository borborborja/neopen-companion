package net.micapum.neopen.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import net.micapum.neopen.app.data.Capture
import net.micapum.neopen.app.data.Notebook
import net.micapum.neopen.app.render.InkCanvas
import net.micapum.neopen.core.pen.PenStatus

@Composable
fun AppRoot(vm: MainViewModel = viewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    if (!settings.configured) {
        LoginScreen(vm)
        return
    }
    LaunchedEffect(Unit) { vm.syncNow() }

    var openNotebookId by rememberSaveable { mutableStateOf<String?>(null) }
    val notebooks by vm.notebooks.collectAsStateWithLifecycle()

    val open = openNotebookId
    if (open != null) {
        val title = notebooks.firstOrNull { it.id == open }?.name ?: "Cuaderno"
        NotebookScreen(vm, open, title, onBack = { openNotebookId = null })
        return
    }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var creating by rememberSaveable { mutableStateOf(false) }
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { HomeBar(tab, syncing, pending, onSync = vm::syncNow) },
        floatingActionButton = {
            if (tab == 0) {
                FloatingActionButton(onClick = { creating = true }) {
                    Icon(Icons.Filled.Add, "Nuevo cuaderno")
                }
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.Book, null) },
                    label = { Text("Cuadernos") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Filled.Inbox, null) },
                    label = { Text("Bandeja") },
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Filled.Edit, null) },
                    label = { Text("Boli") },
                )
                NavigationBarItem(
                    selected = tab == 3,
                    onClick = { tab = 3 },
                    icon = { Icon(Icons.Filled.Settings, null) },
                    label = { Text("Ajustes") },
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                0 -> NotebooksScreen(vm, notebooks, onOpen = { openNotebookId = it })
                1 -> InboxScreen(vm, notebooks)
                2 -> PenScreen(vm)
                else -> SettingsScreen(vm)
            }
        }
    }
    if (creating) {
        TextPromptDialog(
            title = "Nuevo cuaderno",
            initial = "",
            label = "Nombre",
            onConfirm = { vm.createNotebook(it, "#5b8cff"); creating = false },
            onDismiss = { creating = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeBar(tab: Int, syncing: Boolean, pending: Int, onSync: () -> Unit) {
    val title = when (tab) {
        0 -> "Cuadernos"
        1 -> "Bandeja"
        2 -> "Boli"
        else -> "Ajustes"
    }
    TopAppBar(
        title = { Text(title) },
        actions = {
            if (pending > 0) {
                Badge { Text(pending.toString()) }
                Spacer(Modifier.size(8.dp))
            }
            if (syncing) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(12.dp))
            } else {
                IconButton(onClick = onSync) { Icon(Icons.Filled.Refresh, "Sincronizar") }
            }
        },
    )
}

@Composable
private fun LoginScreen(vm: MainViewModel) {
    var url by rememberSaveable { mutableStateOf(vm.serverUrl()) }
    var pw by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("neopen", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Conecta con tu servidor neopen", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = url,
            onValueChange = { url = it; error = null },
            label = { Text("URL del servidor") },
            placeholder = { Text("https://neopen.ejemplo.com") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = pw,
            onValueChange = { pw = it; error = null },
            label = { Text("Contraseña") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                busy = true
                vm.connect(url, pw) { _, err ->
                    busy = false
                    error = err
                }
            },
            enabled = !busy && url.isNotBlank() && pw.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text("Conectar")
        }
    }
}

@Composable
private fun NotebooksScreen(vm: MainViewModel, notebooks: List<Notebook>, onOpen: (String) -> Unit) {
    if (notebooks.isEmpty()) {
        EmptyHint("No hay cuadernos todavía.\nSincroniza para descargarlos.")
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(160.dp),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(notebooks, key = { it.id }) { nb -> NotebookCard(vm, nb, onOpen) }
    }
}

@Composable
private fun NotebookCard(vm: MainViewModel, nb: Notebook, onOpen: (String) -> Unit) {
    Card(
        onClick = { onOpen(nb.id) },
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        val cover = nb.coverUrl
        if (cover != null) {
            AsyncImage(
                model = absoluteUrl(vm.serverUrl(), cover),
                contentDescription = nb.name,
                modifier = Modifier.fillMaxWidth().aspectRatio(1.4f),
            )
        } else {
            Box(
                Modifier.fillMaxWidth().aspectRatio(1.4f)
                    .background(runCatching { Color(android.graphics.Color.parseColor(nb.color)) }
                        .getOrDefault(MaterialTheme.colorScheme.surfaceVariant)),
            )
        }
        Row(
            Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    nb.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${nb.pageCount} ${if (nb.pageCount == 1) "hoja" else "hojas"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!nb.unclassified) NotebookMenu(vm, nb)
        }
    }
}

@Composable
private fun NotebookMenu(vm: MainViewModel, nb: Notebook) {
    var open by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, "Acciones") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Renombrar") }, onClick = { open = false; renaming = true })
            DropdownMenuItem(text = { Text("Borrar") }, onClick = { open = false; deleting = true })
        }
    }
    if (renaming) {
        TextPromptDialog("Renombrar cuaderno", nb.name, "Nombre",
            onConfirm = { vm.renameNotebook(nb.id, it); renaming = false },
            onDismiss = { renaming = false })
    }
    if (deleting) {
        ConfirmDialog("¿Borrar «${nb.name}»?", "Sus hojas pasarán a «Sin clasificar».",
            onConfirm = { vm.deleteNotebook(nb.id); deleting = false },
            onDismiss = { deleting = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotebookScreen(vm: MainViewModel, notebookId: String, title: String, onBack: () -> Unit) {
    val pages by vm.capturesIn(notebookId).collectAsStateWithLifecycle(initialValue = emptyList())
    val notebooks by vm.notebooks.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") }
                },
            )
        },
    ) { padding ->
        if (pages.isEmpty()) {
            Box(Modifier.padding(padding)) { EmptyHint("Cuaderno sin hojas.") }
            return@Scaffold
        }
        LazyColumn(
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            items(pages, key = { it.id }) { page -> PageCard(vm, page, notebooks) }
        }
    }
}

@Composable
private fun PageCard(vm: MainViewModel, page: Capture, notebooks: List<Notebook>) {
    Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column {
            InkCanvas(
                strokes = page.strokes,
                paper = page.paper,
                modifier = Modifier.fillMaxWidth().height(360.dp).background(Color.White),
            )
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(page.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("p. ${page.page} · ${page.sobpLabel}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                CaptureMenu(vm, page, notebooks)
            }
        }
    }
}

@Composable
private fun InboxScreen(vm: MainViewModel, notebooks: List<Notebook>) {
    val items by vm.inbox.collectAsStateWithLifecycle()
    if (items.isEmpty()) {
        EmptyHint("Bandeja vacía.")
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(items, key = { it.id }) { c -> CaptureRow(vm, c, notebooks) }
    }
}

@Composable
private fun CaptureRow(vm: MainViewModel, c: Capture, notebooks: List<Notebook>) {
    Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            InkCanvas(
                strokes = c.strokes,
                paper = c.paper,
                modifier = Modifier.size(72.dp).background(Color.White, RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(c.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(c.sobpLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            CaptureMenu(vm, c, notebooks)
        }
    }
}

@Composable
private fun CaptureMenu(vm: MainViewModel, c: Capture, notebooks: List<Notebook>) {
    var open by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val online by vm.online.collectAsStateWithLifecycle()
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, "Acciones") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Renombrar") }, onClick = { open = false; renaming = true })
            DropdownMenuItem(text = { Text("Mover a cuaderno") }, onClick = { open = false; moving = true })
            DropdownMenuItem(
                text = { Text(if (online) "Transcribir (OCR)" else "Transcribir (sin conexión)") },
                enabled = online,
                onClick = { open = false; vm.transcribe(c.id) },
            )
            DropdownMenuItem(text = { Text("Borrar") }, onClick = { open = false; deleting = true })
        }
    }
    if (renaming) {
        TextPromptDialog("Renombrar hoja", c.name, "Nombre",
            onConfirm = { vm.renameCapture(c.id, it); renaming = false },
            onDismiss = { renaming = false })
    }
    if (moving) {
        MoveDialog(notebooks.filter { !it.unclassified || it.id != c.notebookId },
            onPick = { vm.moveCapture(c.id, it, 0); moving = false },
            onDismiss = { moving = false })
    }
    if (deleting) {
        ConfirmDialog("¿Borrar «${c.name}»?", "Se eliminará en todos tus dispositivos.",
            onConfirm = { vm.deleteCapture(c.id); deleting = false },
            onDismiss = { deleting = false })
    }
}

@Composable
private fun SettingsScreen(vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Servidor", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(settings.serverUrl, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(4.dp))
        Text("Pendiente de subir: $pending", style = MaterialTheme.typography.bodyMedium)
        if (status != null) {
            Text("Último error: $status", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = vm::syncNow, modifier = Modifier.fillMaxWidth()) { Text("Sincronizar ahora") }
        TextButton(onClick = vm::logout, modifier = Modifier.fillMaxWidth()) {
            Text("Cerrar sesión", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun PenScreen(vm: MainViewModel) {
    val state by vm.penState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val perms = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result -> if (result.values.all { it }) vm.connectPen() }
    val connect = {
        val granted = perms.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        if (granted) vm.connectPen() else launcher.launch(perms)
    }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Boli Neo M1+", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Estado: ${penStatusLabel(state.status)}", style = MaterialTheme.typography.bodyLarge)
        state.name?.let { Text("Dispositivo: $it", style = MaterialTheme.typography.bodyMedium) }
        state.settings.battery?.let { Text("Batería: $it%", style = MaterialTheme.typography.bodyMedium) }
        if (state.capturedPages > 0) {
            Text("Páginas capturadas: ${state.capturedPages}", style = MaterialTheme.typography.bodyMedium)
        }
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(8.dp))
        if (state.connected) {
            OutlinedButton(onClick = vm::importOffline, modifier = Modifier.fillMaxWidth()) {
                Text("Importar memoria offline")
            }
            OutlinedButton(onClick = vm::disconnectPen, modifier = Modifier.fillMaxWidth()) {
                Text("Desconectar")
            }
        } else {
            val busy = state.status == PenStatus.SCANNING || state.status == PenStatus.CONNECTING
            Button(onClick = connect, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) "Conectando…" else "Conectar")
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Escribe en papel Ncode: las páginas aparecen al instante en la Bandeja y se suben al servidor al reconectar.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun penStatusLabel(status: PenStatus): String = when (status) {
    PenStatus.IDLE -> "Desconectado"
    PenStatus.SCANNING -> "Buscando…"
    PenStatus.CONNECTING -> "Conectando…"
    PenStatus.AUTHORIZED -> "Conectado"
    PenStatus.DISCONNECTED -> "Desconectado"
}

@Composable
private fun TextPromptDialog(
    title: String,
    initial: String,
    label: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun ConfirmDialog(title: String, body: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Borrar", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun MoveDialog(notebooks: List<Notebook>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mover a cuaderno") },
        text = {
            LazyColumn {
                items(notebooks, key = { it.id }) { nb ->
                    Text(
                        nb.name,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(nb.id) }.padding(vertical = 12.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Une la base del servidor con una ruta relativa de portada (`/covers/...`). */
private fun absoluteUrl(serverUrl: String, path: String): String {
    if (path.startsWith("http")) return path
    val base = serverUrl.trimEnd('/')
    val rel = if (path.startsWith("/")) path else "/$path"
    return base + rel
}

package net.micapum.neopen.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ViewDay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
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
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import net.micapum.neopen.app.data.Capture
import net.micapum.neopen.app.data.Notebook
import net.micapum.neopen.app.render.ExportFormat
import net.micapum.neopen.app.render.InkCanvas
import net.micapum.neopen.core.pen.PenStatus

private val NotebookColors = listOf(
    "#5b8cff", "#64748b", "#3ad29f", "#ef4444", "#f59e0b", "#a855f7", "#ec4899", "#14b8a6", "#111827",
)

private enum class PageView { Grid, List, Large }
private enum class ExportMode { Share, Save }

private fun parseTags(s: String): List<String> =
    s.split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct()

private fun parseColor(hex: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color.Gray)

private fun penDotColor(status: PenStatus): Color = when (status) {
    PenStatus.AUTHORIZED -> Color(0xFF22C55E) // verde: conectado
    PenStatus.SCANNING, PenStatus.CONNECTING -> Color(0xFFF59E0B) // ámbar: conectando
    else -> Color(0xFF9CA3AF) // gris: desconectado
}

// --- Raíz / navegación -------------------------------------------------------

@Composable
fun AppRoot(vm: MainViewModel = viewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    if (!settings.configured) {
        LoginScreen(vm)
        return
    }
    LaunchedEffect(Unit) { vm.syncNow() }

    val notebooks by vm.notebooks.collectAsStateWithLifecycle()
    var showPen by rememberSaveable { mutableStateOf(false) }
    var openNotebookId by rememberSaveable { mutableStateOf<String?>(null) }
    var openPageId by rememberSaveable { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }

    if (showPen) {
        PenScreen(vm, onBack = { showPen = false })
        return
    }
    val pageId = openPageId
    if (pageId != null) {
        PageViewer(vm, pageId, notebooks, onBack = { openPageId = null })
        return
    }
    val nbId = openNotebookId
    if (nbId != null) {
        val title = notebooks.firstOrNull { it.id == nbId }?.name ?: "Cuaderno"
        NotebookScreen(vm, nbId, title, notebooks, onOpenPage = { openPageId = it }, onBack = { openNotebookId = null })
        return
    }

    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val penState by vm.penState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            HomeBar(tab, syncing, pending, status != null, penState.status, onSync = vm::syncNow, onPen = { showPen = true })
        },
        floatingActionButton = {
            if (tab == 0) {
                FloatingActionButton(onClick = { creating = true }) { Icon(Icons.Filled.Add, "Nuevo cuaderno") }
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == 0, { tab = 0 }, icon = { Icon(Icons.Filled.Book, null) }, label = { Text("Cuadernos") })
                NavigationBarItem(tab == 1, { tab = 1 }, icon = { Icon(Icons.Filled.Inbox, null) }, label = { Text("Bandeja") })
                NavigationBarItem(tab == 2, { tab = 2 }, icon = { Icon(Icons.Filled.Settings, null) }, label = { Text("Ajustes") })
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                0 -> NotebooksScreen(vm, notebooks, onOpen = { openNotebookId = it }, onEdit = { editingId = it })
                1 -> InboxScreen(vm, notebooks, onOpenPage = { openPageId = it })
                else -> SettingsScreen(vm)
            }
        }
    }

    if (creating) {
        NotebookEditorDialog("Nuevo cuaderno", "", NotebookColors.first(), emptyList(),
            onConfirm = { n, c, t -> vm.createNotebook(n, c, t); creating = false },
            onDismiss = { creating = false })
    }
    val editing = editingId?.let { id -> notebooks.firstOrNull { it.id == id } }
    if (editing != null) {
        NotebookEditorDialog("Editar cuaderno", editing.name, editing.color, editing.tags,
            onConfirm = { n, c, t -> vm.editNotebook(editing.id, n, c, t); editingId = null },
            onDismiss = { editingId = null })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeBar(
    tab: Int,
    syncing: Boolean,
    pending: Int,
    hasError: Boolean,
    penStatus: PenStatus,
    onSync: () -> Unit,
    onPen: () -> Unit,
) {
    val title = when (tab) {
        0 -> "Cuadernos"
        1 -> "Bandeja"
        else -> "Ajustes"
    }
    TopAppBar(
        title = { Text(title) },
        actions = {
            if (pending > 0) {
                Badge { Text(pending.toString()) }
                Spacer(Modifier.size(6.dp))
            }
            if (syncing) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
            } else {
                IconButton(onClick = onSync) {
                    Icon(
                        Icons.Filled.Refresh,
                        "Sincronizar",
                        tint = if (hasError) MaterialTheme.colorScheme.error else LocalContentColor.current,
                    )
                }
            }
            Box {
                IconButton(onClick = onPen) { Icon(Icons.Filled.Draw, "Boli") }
                StatusDot(penDotColor(penStatus), Modifier.align(Alignment.TopEnd).padding(8.dp))
            }
        },
    )
}

@Composable
private fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(10.dp)
            .background(color, CircleShape)
            .border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape),
    )
}

// --- Login -------------------------------------------------------------------

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
        Spacer(Modifier.size(24.dp))
        OutlinedTextField(
            value = url,
            onValueChange = { url = it; error = null },
            label = { Text("URL del servidor") },
            placeholder = { Text("https://neopen.ejemplo.com") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.size(12.dp))
        OutlinedTextField(
            value = pw,
            onValueChange = { pw = it; error = null },
            label = { Text("Contraseña") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        if (error != null) {
            Spacer(Modifier.size(8.dp))
            Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.size(20.dp))
        Button(
            onClick = {
                busy = true
                vm.connect(url, pw) { _, err -> busy = false; error = err }
            },
            enabled = !busy && url.isNotBlank() && pw.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Conectar")
        }
    }
}

// --- Cuadernos ---------------------------------------------------------------

@Composable
private fun NotebooksScreen(
    vm: MainViewModel,
    notebooks: List<Notebook>,
    onOpen: (String) -> Unit,
    onEdit: (String) -> Unit,
) {
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
        gridItems(notebooks, key = { it.id }) { nb -> NotebookCard(vm, nb, onOpen, onEdit) }
    }
}

@Composable
private fun NotebookCard(vm: MainViewModel, nb: Notebook, onOpen: (String) -> Unit, onEdit: (String) -> Unit) {
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
            Box(Modifier.fillMaxWidth().aspectRatio(1.4f).background(parseColor(nb.color)))
        }
        Row(
            Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(nb.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                OriginBadge(nb)
                Text(
                    "${nb.pageCount} ${if (nb.pageCount == 1) "hoja" else "hojas"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!nb.unclassified) NotebookMenu(vm, nb, onEdit)
        }
        if (nb.tags.isNotEmpty()) {
            TagChips(nb.tags, Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp))
        }
    }
}

@Composable
private fun OriginBadge(nb: Notebook) {
    val (icon, label) = when {
        nb.unclassified -> Icons.Filled.Inbox to "Sin clasificar"
        nb.bookKey != null -> Icons.Filled.Draw to "Del boli"
        nb.custom -> Icons.Filled.Collections to "Colección"
        else -> Icons.Filled.Book to "Cuaderno"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NotebookMenu(vm: MainViewModel, nb: Notebook, onEdit: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, "Acciones") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Editar") }, onClick = { open = false; onEdit(nb.id) })
            DropdownMenuItem(text = { Text("Compartir (PDF)") }, onClick = {
                open = false
                scope.launch { vm.exportNotebookPdf(nb.id)?.let { shareExport(context, it) } }
            })
            DropdownMenuItem(text = { Text("Guardar (PDF)") }, onClick = {
                open = false
                scope.launch { vm.exportNotebookPdf(nb.id)?.let { saveExport(context, it) } }
            })
            DropdownMenuItem(text = { Text("Borrar") }, onClick = { open = false; deleting = true })
        }
    }
    if (deleting) {
        ConfirmDialog("¿Borrar «${nb.name}»?", "Sus hojas pasarán a «Sin clasificar».",
            onConfirm = { vm.deleteNotebook(nb.id); deleting = false }, onDismiss = { deleting = false })
    }
}

// --- Cuaderno abierto: cuadrícula / lista / grande ---------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotebookScreen(
    vm: MainViewModel,
    notebookId: String,
    title: String,
    notebooks: List<Notebook>,
    onOpenPage: (String) -> Unit,
    onBack: () -> Unit,
) {
    val pages by vm.capturesIn(notebookId).collectAsStateWithLifecycle(initialValue = emptyList())
    var mode by rememberSaveable { mutableStateOf(PageView.Grid) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") } },
                actions = {
                    val active = MaterialTheme.colorScheme.primary
                    val muted = MaterialTheme.colorScheme.onSurfaceVariant
                    IconButton({ mode = PageView.Grid }) { Icon(Icons.Filled.GridView, "Cuadrícula", tint = if (mode == PageView.Grid) active else muted) }
                    IconButton({ mode = PageView.List }) { Icon(Icons.AutoMirrored.Filled.ViewList, "Lista", tint = if (mode == PageView.List) active else muted) }
                    IconButton({ mode = PageView.Large }) { Icon(Icons.Filled.ViewDay, "Grande", tint = if (mode == PageView.Large) active else muted) }
                },
            )
        },
    ) { padding ->
        if (pages.isEmpty()) {
            Box(Modifier.padding(padding)) { EmptyHint("Cuaderno sin hojas.") }
            return@Scaffold
        }
        when (mode) {
            PageView.Grid -> LazyVerticalGrid(
                columns = GridCells.Adaptive(120.dp),
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                gridItems(pages, key = { it.id }) { p -> PageGridItem(p, onClick = { onOpenPage(p.id) }) }
            }
            PageView.List -> LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(pages, key = { it.id }) { p -> PageListRow(vm, p, notebooks, onClick = { onOpenPage(p.id) }) }
            }
            PageView.Large -> LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(pages, key = { it.id }) { p -> PageLargeCard(vm, p, notebooks) }
            }
        }
    }
}

@Composable
private fun PageGridItem(page: Capture, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(1.dp)) {
        InkCanvas(page.strokes, page.paper, Modifier.fillMaxWidth().aspectRatio(0.8f).background(Color.White))
        Text(
            page.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun PageListRow(vm: MainViewModel, page: Capture, notebooks: List<Notebook>, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(1.dp)) {
        Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            InkCanvas(page.strokes, page.paper, Modifier.size(64.dp).background(Color.White, RoundedCornerShape(8.dp)))
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(page.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("p. ${page.page}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            CaptureActions(vm, page, notebooks, asRow = false)
        }
    }
}

@Composable
private fun PageLargeCard(vm: MainViewModel, page: Capture, notebooks: List<Notebook>) {
    Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(1.dp)) {
        Column {
            InkCanvas(page.strokes, page.paper, Modifier.fillMaxWidth().height(360.dp).background(Color.White))
            Text(
                page.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            if (page.tags.isNotEmpty()) TagChips(page.tags, Modifier.padding(horizontal = 12.dp))
            CaptureActions(vm, page, notebooks, asRow = true)
        }
    }
}

// --- Visor de una hoja -------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PageViewer(vm: MainViewModel, captureId: String, notebooks: List<Notebook>, onBack: () -> Unit) {
    val capture by vm.captureFlow(captureId).collectAsStateWithLifecycle(initialValue = null)
    val c = capture
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(c?.name ?: "Hoja", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") } },
            )
        },
    ) { padding ->
        if (c == null) {
            Box(Modifier.padding(padding)) { EmptyHint("Cargando…") }
            return@Scaffold
        }
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            InkCanvas(c.strokes, c.paper, Modifier.fillMaxWidth().height(420.dp).background(Color.White))
            CaptureActions(vm, c, notebooks, asRow = true, onAfterDelete = onBack)
            if (c.tags.isNotEmpty()) TagChips(c.tags, Modifier.padding(horizontal = 12.dp))
            val text = c.transcription?.text
            if (!text.isNullOrBlank()) {
                Text("Transcripción", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(12.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 12.dp))
                Spacer(Modifier.size(24.dp))
            }
        }
    }
}

// --- Bandeja -----------------------------------------------------------------

@Composable
private fun InboxScreen(vm: MainViewModel, notebooks: List<Notebook>, onOpenPage: (String) -> Unit) {
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
        items(items, key = { it.id }) { c -> PageListRow(vm, c, notebooks, onClick = { onOpenPage(c.id) }) }
    }
}

// --- Acciones de hoja (iconos o menú, misma lógica) --------------------------

@Composable
private fun CaptureActions(
    vm: MainViewModel,
    c: Capture,
    notebooks: List<Notebook>,
    asRow: Boolean,
    onAfterDelete: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val online by vm.online.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var tagging by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf<ExportMode?>(null) }

    fun doExport(fmt: ExportFormat, modeSel: ExportMode) {
        scope.launch {
            vm.exportPage(c.id, fmt)?.let { if (modeSel == ExportMode.Share) shareExport(context, it) else saveExport(context, it) }
        }
    }

    if (asRow) {
        Row(Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            IconButton({ exporting = ExportMode.Share }) { Icon(Icons.Filled.Share, "Compartir") }
            IconButton({ exporting = ExportMode.Save }) { Icon(Icons.Filled.FileDownload, "Descargar") }
            IconButton(onClick = { vm.transcribe(c.id) }, enabled = online) { Icon(Icons.Filled.DocumentScanner, "OCR") }
            IconButton({ tagging = true }) { Icon(Icons.Filled.Label, "Etiquetas") }
            IconButton({ renaming = true }) { Icon(Icons.Filled.Edit, "Renombrar") }
            IconButton({ moving = true }) { Icon(Icons.AutoMirrored.Filled.DriveFileMove, "Mover") }
            IconButton({ deleting = true }) { Icon(Icons.Filled.Delete, "Borrar") }
        }
    } else {
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Acciones") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Compartir") }, onClick = { menu = false; exporting = ExportMode.Share })
                DropdownMenuItem(text = { Text("Descargar") }, onClick = { menu = false; exporting = ExportMode.Save })
                DropdownMenuItem(
                    text = { Text(if (online) "Transcribir (OCR)" else "Transcribir (sin conexión)") },
                    enabled = online,
                    onClick = { menu = false; vm.transcribe(c.id) },
                )
                DropdownMenuItem(text = { Text("Etiquetas") }, onClick = { menu = false; tagging = true })
                DropdownMenuItem(text = { Text("Renombrar") }, onClick = { menu = false; renaming = true })
                DropdownMenuItem(text = { Text("Mover a cuaderno") }, onClick = { menu = false; moving = true })
                DropdownMenuItem(text = { Text("Borrar") }, onClick = { menu = false; deleting = true })
            }
        }
    }

    if (renaming) {
        TextPromptDialog("Renombrar hoja", c.name, "Nombre",
            onConfirm = { vm.renameCapture(c.id, it); renaming = false }, onDismiss = { renaming = false })
    }
    if (moving) {
        MoveDialog(notebooks.filter { !it.unclassified || it.id != c.notebookId },
            onPick = { vm.moveCapture(c.id, it, 0); moving = false }, onDismiss = { moving = false })
    }
    if (tagging) {
        TagsDialog("Etiquetas de la hoja", c.tags,
            onConfirm = { vm.setTags(c.id, it); tagging = false }, onDismiss = { tagging = false })
    }
    if (deleting) {
        ConfirmDialog("¿Borrar «${c.name}»?", "Se eliminará en todos tus dispositivos.",
            onConfirm = { vm.deleteCapture(c.id); deleting = false; onAfterDelete?.invoke() }, onDismiss = { deleting = false })
    }
    val mode = exporting
    if (mode != null) {
        FormatPickerDialog(onPick = { fmt -> exporting = null; doExport(fmt, mode) }, onDismiss = { exporting = null })
    }
}

// --- Boli --------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PenScreen(vm: MainViewModel, onBack: () -> Unit) {
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
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Boli Neo M1+") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(penDotColor(state.status))
                Spacer(Modifier.size(8.dp))
                Text("Estado: ${penStatusLabel(state.status)}", style = MaterialTheme.typography.bodyLarge)
            }
            state.name?.let { Text("Dispositivo: $it", style = MaterialTheme.typography.bodyMedium) }
            state.settings.battery?.let { Text("Batería: $it%", style = MaterialTheme.typography.bodyMedium) }
            if (state.capturedPages > 0) {
                Text("Páginas capturadas: ${state.capturedPages}", style = MaterialTheme.typography.bodyMedium)
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.size(8.dp))
            if (state.connected) {
                OutlinedButton(onClick = vm::importOffline, modifier = Modifier.fillMaxWidth()) { Text("Importar memoria offline") }
                OutlinedButton(onClick = vm::disconnectPen, modifier = Modifier.fillMaxWidth()) { Text("Desconectar") }
            } else {
                val busy = state.status == PenStatus.SCANNING || state.status == PenStatus.CONNECTING
                Button(onClick = connect, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (busy) "Conectando…" else "Conectar")
                }
            }
            Text(
                "Escribe en papel Ncode: las páginas aparecen al instante en la Bandeja y se suben al servidor al reconectar.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun penStatusLabel(status: PenStatus): String = when (status) {
    PenStatus.IDLE -> "Desconectado"
    PenStatus.SCANNING -> "Buscando…"
    PenStatus.CONNECTING -> "Conectando…"
    PenStatus.AUTHORIZED -> "Conectado"
    PenStatus.DISCONNECTED -> "Desconectado"
}

// --- Ajustes -----------------------------------------------------------------

@Composable
private fun SettingsScreen(vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Servidor", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(settings.serverUrl, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.size(4.dp))
        Text("Pendiente de subir: $pending", style = MaterialTheme.typography.bodyMedium)
        if (status != null) {
            Text("Último error: $status", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.size(8.dp))
        OutlinedButton(onClick = vm::syncNow, modifier = Modifier.fillMaxWidth()) { Text("Sincronizar ahora") }
        TextButton(onClick = vm::logout, modifier = Modifier.fillMaxWidth()) {
            Text("Cerrar sesión", color = MaterialTheme.colorScheme.error)
        }
    }
}

// --- Diálogos y piezas reutilizables -----------------------------------------

@Composable
private fun NotebookEditorDialog(
    title: String,
    initialName: String,
    initialColor: String,
    initialTags: List<String>,
    onConfirm: (String, String, List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var color by remember { mutableStateOf(initialColor) }
    var tagsText by remember { mutableStateOf(initialTags.joinToString(", ")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("Nombre") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.size(12.dp))
                Text("Color", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.size(6.dp))
                ColorSwatches(color) { color = it }
                Spacer(Modifier.size(12.dp))
                OutlinedTextField(
                    tagsText,
                    { tagsText = it },
                    label = { Text("Etiquetas (separadas por comas)") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onConfirm(name.trim(), color, parseTags(tagsText)) }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun ColorSwatches(selected: String, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (hex in NotebookColors) {
            val active = hex.equals(selected, ignoreCase = true)
            Box(
                Modifier
                    .size(30.dp)
                    .background(parseColor(hex), CircleShape)
                    .border(
                        if (active) 3.dp else 1.dp,
                        if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                        CircleShape,
                    )
                    .clickable { onSelect(hex) },
            )
        }
    }
}

@Composable
private fun TagsDialog(title: String, initial: List<String>, onConfirm: (List<String>) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial.joinToString(", ")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(text, { text = it }, label = { Text("Etiquetas (separadas por comas)") }, modifier = Modifier.fillMaxWidth())
                val preview = parseTags(text)
                if (preview.isNotEmpty()) {
                    Spacer(Modifier.size(8.dp))
                    TagChips(preview)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(parseTags(text)) }) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagChips(tags: List<String>, modifier: Modifier = Modifier) {
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (t in tags) AssistChip(onClick = {}, label = { Text(t) })
    }
}

@Composable
private fun FormatPickerDialog(onPick: (ExportFormat) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Formato") },
        text = {
            Column {
                for (f in ExportFormat.entries) {
                    TextButton(onClick = { onPick(f) }, modifier = Modifier.fillMaxWidth()) { Text(f.name) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
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
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text(label) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) }) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun ConfirmDialog(title: String, body: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Borrar", color = MaterialTheme.colorScheme.error) } },
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

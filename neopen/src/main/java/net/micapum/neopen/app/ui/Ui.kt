package net.micapum.neopen.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
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
import androidx.compose.material.icons.filled.AspectRatio
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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import net.micapum.neopen.app.data.Capture
import net.micapum.neopen.app.data.Notebook
import net.micapum.neopen.app.data.PAPER_PRESETS
import net.micapum.neopen.app.data.PaperPreset
import net.micapum.neopen.app.data.paperFromPreset
import net.micapum.neopen.app.data.paperLabel
import net.micapum.neopen.app.render.ExportFormat
import net.micapum.neopen.app.render.InkCanvas
import net.micapum.neopen.core.data.AppMode
import net.micapum.neopen.core.data.AppSettings
import net.micapum.neopen.core.data.SyncMode
import net.micapum.neopen.app.widget.TAB_NOTEBOOKS
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

/** Pestañas inferiores. El conjunto visible depende del modo de la app. */
private enum class HomeTab(val title: String, val icon: ImageVector) {
    INBOX("Bandeja", Icons.Filled.Inbox),
    NOTEBOOKS("Cuadernos", Icons.Filled.Book),
    PEN("Boli", Icons.Filled.Draw),
    SETTINGS("Ajustes", Icons.Filled.Settings),
}

@Composable
fun AppRoot(
    vm: MainViewModel = viewModel(),
    initialTab: String? = null,
    initialNotebookId: String? = null,
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    if (!settings.setupComplete) {
        SetupWizard(vm)
        return
    }
    LaunchedEffect(Unit) { vm.syncNow() }

    val notebooks by vm.notebooks.collectAsStateWithLifecycle()
    var openNotebookId by rememberSaveable { mutableStateOf(initialNotebookId) }
    var openPageId by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }

    // Modo PUENTE → solo Boli + Ajustes; modo COMPLETO → todas las pestañas.
    val tabs = if (settings.appMode == AppMode.BRIDGE) {
        listOf(HomeTab.PEN, HomeTab.SETTINGS)
    } else {
        listOf(HomeTab.INBOX, HomeTab.NOTEBOOKS, HomeTab.PEN, HomeTab.SETTINGS)
    }
    var current by rememberSaveable(settings.appMode) {
        mutableStateOf(
            if (initialTab == TAB_NOTEBOOKS && HomeTab.NOTEBOOKS in tabs) HomeTab.NOTEBOOKS else tabs.first(),
        )
    }

    // Pantallas a pantalla completa (solo en modo completo).
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
        topBar = { HomeBar(current.title, syncing, pending, status != null, onSync = vm::syncNow) },
        floatingActionButton = {
            if (current == HomeTab.NOTEBOOKS) {
                FloatingActionButton(onClick = { creating = true }) { Icon(Icons.Filled.Add, "Nuevo cuaderno") }
            }
        },
        bottomBar = {
            NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = current == t,
                        onClick = { current = t },
                        icon = {
                            if (t == HomeTab.PEN) {
                                Box {
                                    Icon(t.icon, null)
                                    StatusDot(penDotColor(penState.status), Modifier.align(Alignment.TopEnd))
                                }
                            } else {
                                Icon(t.icon, null)
                            }
                        },
                        label = { Text(t.title) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (current) {
                HomeTab.INBOX -> InboxScreen(vm, notebooks, onOpenPage = { openPageId = it })
                HomeTab.NOTEBOOKS -> NotebooksScreen(vm, notebooks, onOpen = { openNotebookId = it }, onEdit = { editingId = it })
                HomeTab.PEN -> PenTabContent(vm)
                HomeTab.SETTINGS -> SettingsScreen(vm)
            }
        }
    }

    val categorySuggestions = notebooks
        .mapNotNull { it.category?.trim()?.ifBlank { null } }
        .distinct()
        .sorted()
    if (creating) {
        NotebookEditorDialog("Nuevo cuaderno", "", NotebookColors.first(), emptyList(), "", categorySuggestions,
            onConfirm = { n, c, t, cat -> vm.createNotebook(n, c, t, cat.ifBlank { null }); creating = false },
            onDismiss = { creating = false })
    }
    val editing = editingId?.let { id -> notebooks.firstOrNull { it.id == id } }
    if (editing != null) {
        NotebookEditorDialog("Editar cuaderno", editing.name, editing.color, editing.tags, editing.category ?: "", categorySuggestions,
            onConfirm = { n, c, t, cat -> vm.editNotebook(editing.id, n, c, t, cat); editingId = null },
            onDismiss = { editingId = null })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeBar(
    title: String,
    syncing: Boolean,
    pending: Int,
    hasError: Boolean,
    onSync: () -> Unit,
) {
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
private fun SetupWizard(vm: MainViewModel) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var url by rememberSaveable { mutableStateOf(vm.serverUrl()) }
    var pw by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf(AppMode.FULL) }
    val check by vm.serverCheck.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.size(24.dp))
        Text("neopen", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Configuración inicial", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(8.dp))

        if (step == 0) {
            Text("¿Cómo quieres usar la app?", style = MaterialTheme.typography.titleMedium)
            ModeCard(
                selected = mode == AppMode.FULL,
                title = "Versión completa",
                desc = "Captura, cuadernos, bandeja, transcripción y sincronización con un servidor.",
                onClick = { mode = AppMode.FULL },
            )
            ModeCard(
                selected = mode == AppMode.LOCAL,
                title = "Local (sin servidor)",
                desc = "Todo en el móvil, sin servidor. Podrás vincular uno más tarde sin perder nada.",
                onClick = { mode = AppMode.LOCAL },
            )
            ModeCard(
                selected = mode == AppMode.BRIDGE,
                title = "Solo puente",
                desc = "Solo conecta el boli y sube lo que escribes a un servidor. Sin cuadernos en el móvil.",
                onClick = { mode = AppMode.BRIDGE },
            )
            Spacer(Modifier.size(8.dp))
            Button(
                onClick = { if (mode == AppMode.LOCAL) vm.completeSetup("", "", AppMode.LOCAL) {} else step = 1 },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (mode == AppMode.LOCAL) "Empezar" else "Siguiente") }
        } else {
            Text("Conecta con tu servidor neopen", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = url,
                onValueChange = { url = it; vm.resetServerCheck() },
                label = { Text("URL del servidor") },
                placeholder = { Text("https://neopen.ejemplo.com") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = pw,
                onValueChange = { pw = it; vm.resetServerCheck() },
                label = { Text("Contraseña") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = { vm.testServer(url, pw) },
                enabled = check != ServerCheck.Checking && url.isNotBlank() && pw.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (check == ServerCheck.Checking) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Probar conexión")
                }
            }
            ServerCheckResult(check)
            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { step = 0 }, modifier = Modifier.weight(1f)) { Text("Atrás") }
                Button(
                    onClick = { vm.completeSetup(url, pw, mode) {} },
                    enabled = check == ServerCheck.Ok,
                    modifier = Modifier.weight(1f),
                ) { Text("Empezar") }
            }
        }
    }
}

@Composable
private fun ModeCard(selected: Boolean, title: String, desc: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) cs.secondaryContainer else cs.surfaceVariant,
            contentColor = if (selected) cs.onSecondaryContainer else cs.onSurfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(desc, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Mensaje del resultado de probar el servidor (sin iconos, color semántico). */
@Composable
private fun ServerCheckResult(check: ServerCheck) {
    val cs = MaterialTheme.colorScheme
    val (color, msg) = when (check) {
        ServerCheck.Idle, ServerCheck.Checking -> return
        ServerCheck.Ok -> cs.primary to "Conexión correcta. Servidor y contraseña válidos."
        ServerCheck.BadAuth -> cs.error to "El servidor responde, pero la contraseña es incorrecta."
        is ServerCheck.Unreachable -> cs.error to "No se pudo conectar: ${check.detail}"
    }
    Text(msg, color = color, style = MaterialTheme.typography.bodyMedium)
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
    // Agrupación por categoría (opt-in): solo se muestran cabeceras si algún
    // cuaderno tiene categoría. El grupo "Sin categoría" (null) va al final.
    val hasCategories = notebooks.any { !it.category.isNullOrBlank() }
    val groups: List<Pair<String?, List<Notebook>>> =
        if (!hasCategories) {
            listOf(null to notebooks)
        } else {
            notebooks
                .groupBy { it.category?.trim()?.ifBlank { null } }
                .toList()
                .sortedWith(compareBy({ it.first == null }, { it.first?.lowercase() ?: "" }))
        }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(160.dp),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        groups.forEach { (category, items) ->
            if (hasCategories) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    CategoryHeader(category ?: "Sin categoría", items.size)
                }
            }
            gridItems(items, key = { it.id }) { nb -> NotebookCard(vm, nb, onOpen, onEdit) }
        }
    }
}

/** Cabecera de sección de categoría dentro de la rejilla de cuadernos. */
@Composable
private fun CategoryHeader(label: String, count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
                if (nb.paper == null && !nb.unclassified && !nb.custom) {
                    Text(
                        "⚠ Sin formato — proporciones aproximadas",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else paperLabel(nb.paper)?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
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
    var formatting by remember { mutableStateOf(false) }
    var background by remember { mutableStateOf(false) }
    val bookBg by remember(nb.bookKey) {
        nb.bookKey?.let { vm.observeBookBackground(it) } ?: flowOf(null)
    }.collectAsStateWithLifecycle(initialValue = null)
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, "Acciones") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Editar") }, onClick = { open = false; onEdit(nb.id) })
            DropdownMenuItem(text = { Text("Formato de página…") }, onClick = { open = false; formatting = true })
            if (nb.bookKey != null) {
                DropdownMenuItem(
                    text = { Text(if (bookBg != null) "Fondo: ${bookBg?.label}" else "Fondo…") },
                    onClick = { open = false; background = true },
                )
            }
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
    if (formatting) {
        PaperPickerDialog(
            current = nb.paper?.source,
            onPick = { p -> vm.setNotebookPaper(nb.id, paperFromPreset(p), p.id); formatting = false },
            onDismiss = { formatting = false },
        )
    }
    if (deleting) {
        ConfirmDialog("¿Borrar «${nb.name}»?", "Sus hojas pasarán a «Sin clasificar».",
            onConfirm = { vm.deleteNotebook(nb.id); deleting = false }, onDismiss = { deleting = false })
    }
    if (background && nb.bookKey != null) {
        BackgroundPickerDialog(
            vm = vm,
            scope = "book",
            bookKey = nb.bookKey,
            page = null,
            currentLabel = bookBg?.label,
            onRemove = bookBg?.let { bg -> { vm.removeBackground(bg) } },
            onDismiss = { background = false },
        )
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
                gridItems(pages, key = { it.id }) { p -> PageGridItem(vm, p, onClick = { onOpenPage(p.id) }) }
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
private fun PageGridItem(vm: MainViewModel, page: Capture, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(1.dp)) {
        PageCanvas(vm, page, Modifier.fillMaxWidth().aspectRatio(0.8f).background(Color.White))
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
            PageCanvas(vm, page, Modifier.size(64.dp).background(Color.White, RoundedCornerShape(8.dp)))
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(page.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("p. ${page.page}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (page.paper == null) {
                    Text("⚠ Sin formato", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
            CaptureActions(vm, page, notebooks, asRow = false)
        }
    }
}

@Composable
private fun PageLargeCard(vm: MainViewModel, page: Capture, notebooks: List<Notebook>) {
    Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(1.dp)) {
        Column {
            PageCanvas(vm, page, Modifier.fillMaxWidth().height(360.dp).background(Color.White))
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
    var bgOpen by remember { mutableStateOf(false) }
    val bk = c?.let { "${it.section}.${it.owner}.${it.book}" }
    val sheetBg by remember(bk, c?.page) {
        if (bk != null && c != null) vm.observeSheetBackground(bk, c.page) else flowOf(null)
    }.collectAsStateWithLifecycle(initialValue = null)
    val pageScopeBg = sheetBg?.takeIf { it.scope == "page" }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(c?.name ?: "Hoja", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") } },
                actions = {
                    if (c != null) TextButton(onClick = { bgOpen = true }) { Text("Fondo") }
                },
            )
        },
    ) { padding ->
        if (c == null) {
            Box(Modifier.padding(padding)) { EmptyHint("Cargando…") }
            return@Scaffold
        }
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            PageCanvas(vm, c, Modifier.fillMaxWidth().height(420.dp).background(Color.White))
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
    if (bgOpen && c != null && bk != null) {
        BackgroundPickerDialog(
            vm = vm,
            scope = "page",
            bookKey = bk,
            page = c.page,
            currentLabel = sheetBg?.label,
            onRemove = pageScopeBg?.let { bg -> { vm.removeBackground(bg) } },
            onDismiss = { bgOpen = false },
        )
    }
}

// --- Bandeja -----------------------------------------------------------------

@Composable
private fun InboxScreen(vm: MainViewModel, notebooks: List<Notebook>, onOpenPage: (String) -> Unit) {
    val items by vm.recent.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val terms = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val filtered = if (terms.isEmpty()) items else items.filter { c ->
        val hay = buildString {
            append(c.name.lowercase())
            c.transcription?.let {
                append(' ').append(it.title?.lowercase() ?: "").append(' ').append(it.text?.lowercase() ?: "")
            }
            c.tags.forEach { append(' ').append(it.lowercase()) }
        }
        terms.all { hay.contains(it) }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Buscar por nombre, texto o etiqueta") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        )
        if (filtered.isEmpty()) {
            EmptyHint(if (items.isEmpty()) "Aún no hay ediciones." else "Sin resultados para la búsqueda.")
        } else {
            LazyColumn(
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(filtered, key = { it.id }) { c -> PageListRow(vm, c, notebooks, onClick = { onOpenPage(c.id) }) }
            }
        }
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
    var formatting by remember { mutableStateOf(false) }
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
            IconButton({ formatting = true }) { Icon(Icons.Filled.AspectRatio, "Formato") }
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
                DropdownMenuItem(text = { Text("Formato de hoja…") }, onClick = { menu = false; formatting = true })
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
    if (formatting) {
        PaperPickerDialog(
            current = c.paper?.source,
            onPick = { p -> vm.setCapturePaper(c.id, paperFromPreset(p)); formatting = false },
            onDismiss = { formatting = false },
        )
    }
    val mode = exporting
    if (mode != null) {
        FormatPickerDialog(onPick = { fmt -> exporting = null; doExport(fmt, mode) }, onDismiss = { exporting = null })
    }
}

// --- Boli --------------------------------------------------------------------

private val SHUTDOWN_OPTIONS = listOf(5, 10, 20, 30, 60)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PenTabContent(vm: MainViewModel) {
    val state by vm.penState.collectAsStateWithLifecycle()
    val logs by vm.penLogs.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var penTab by rememberSaveable { mutableIntStateOf(0) }
    var showLogs by rememberSaveable { mutableStateOf(false) }
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
    val busy = state.status == PenStatus.SCANNING || state.status == PenStatus.CONNECTING

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Estado + métricas en tarjeta.
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(penDotColor(state.status))
                    Spacer(Modifier.size(8.dp))
                    Text(state.name ?: state.mac ?: "Boli Neo M1+", style = MaterialTheme.typography.titleMedium)
                }
                Text("Estado: ${penStatusLabel(state.status)}", style = MaterialTheme.typography.bodyMedium)
                state.settings.battery?.let {
                    Text("Batería: $it%${if (state.settings.charging) " ⚡" else ""}", style = MaterialTheme.typography.bodyMedium)
                }
                state.settings.usedMem?.let { Text("Memoria: ${100 - it}% libre", style = MaterialTheme.typography.bodyMedium) }
                if (state.capturedPages > 0) {
                    Text("Páginas capturadas: ${state.capturedPages}", style = MaterialTheme.typography.bodyMedium)
                }
                state.error?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }

        if (busy) {
            OutlinedButton(onClick = vm::stopConnecting, modifier = Modifier.fillMaxWidth()) { Text("Detener conexión") }
        } else {
            Button(onClick = connect, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.connected) "Reconectar" else "Conectar boli")
            }
        }

        if (state.connected) {
            SecondaryTabRow(selectedTabIndex = penTab) {
                Tab(penTab == 0, { penTab = 0 }, text = { Text("Configuración") })
                Tab(penTab == 1, { penTab = 1 }, text = { Text("Memoria") })
            }
            when (penTab) {
                0 -> PenConfigTab(state, vm)
                else -> PenMemoryTab(state, vm)
            }
        } else {
            Text(
                "Necesita un Neo smartpen M1+ encendido y permisos de Bluetooth. Escribe en papel Ncode: las páginas aparecen en la Bandeja y se suben al servidor.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider()
        OutlinedButton(onClick = { showLogs = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Ver registro de conexión (${logs.size})")
        }
    }

    if (showLogs) {
        PenDebugLogDialog(logs, onClear = vm::clearLogs, onDismiss = { showLogs = false })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PenConfigTab(state: net.micapum.neopen.core.pen.PenState, vm: MainViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PenToggleRow("Encendido automático", "Se enciende al empezar a escribir", state.settings.autoPowerOn ?: false, vm::setAutoPowerOn)
        PenToggleRow("Sonido del lápiz", "Pitido al escribir", state.settings.beep ?: false, vm::setBeep)
        PenToggleRow("Guardar offline", "Almacena la escritura sin la app abierta", state.settings.offlineData ?: false, vm::setOfflineData)
        Spacer(Modifier.size(8.dp))
        Text("Apagado automático", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val current = state.settings.autoShutdownTime ?: 20
            SHUTDOWN_OPTIONS.forEach { m ->
                FilterChip(selected = current == m, onClick = { vm.setAutoShutdown(m) }, label = { Text("$m min") })
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        state.mac?.let { Text("MAC: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        state.firmware?.let { Text("Firmware: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }

        // Herramienta avanzada (discreta): leer el SOBP de una marca.
        var readingSobp by remember { mutableStateOf(false) }
        TextButton(onClick = { readingSobp = true }, contentPadding = PaddingValues(top = 6.dp, bottom = 6.dp)) {
            Text("Leer SOBP…", style = MaterialTheme.typography.bodySmall)
        }
        if (readingSobp) SobpReaderDialog(vm) { readingSobp = false }
    }
}

/**
 * Diálogo de la herramienta "Leer SOBP": espera una marca del boli, muestra el
 * SOBP detectado y lo guarda con un identificador. El historial está en Ajustes.
 */
@Composable
private fun SobpReaderDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val detected by vm.detectedSobp.collectAsStateWithLifecycle()
    var label by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        vm.startReadSobp()
        onDispose { vm.cancelReadSobp() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Leer SOBP") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val s = detected
                when {
                    saved -> Text("Guardado en el historial.")
                    s == null -> {
                        Text("Conecta el boli y haz una pequeña marca en una hoja Ncode…")
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    else -> {
                        Text(
                            "SOBP detectado: ${s.section}.${s.owner}.${s.book}.${s.page}",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        OutlinedTextField(label, { label = it }, label = { Text("Identificador") }, singleLine = true)
                    }
                }
            }
        },
        confirmButton = {
            when {
                saved -> TextButton(onClick = onDismiss) { Text("Cerrar") }
                detected != null -> TextButton(
                    enabled = !saving,
                    onClick = { saving = true; vm.saveSobpScan(label) { ok -> saving = false; if (ok) saved = true } },
                ) { Text("Guardar") }
                else -> {}
            }
        },
        dismissButton = { if (!saved) TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

/** Sección "Historial de SOBP" en Ajustes (lista + borrar). */
@Composable
private fun SobpHistorySection(vm: MainViewModel) {
    val scans by vm.sobpScans.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadSobpScans() }
    Text("Historial de SOBP", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    Text(
        "SOBP escaneados con «Leer SOBP» (panel del boli).",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (scans.isEmpty()) {
        Text("Aún no has escaneado ningún SOBP.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Card {
            Column {
                scans.forEachIndexed { i, s ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(s.label, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${s.sobp.section}.${s.sobp.owner}.${s.sobp.book}.${s.sobp.page}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { vm.deleteSobpScan(s.id) }) {
                            Text("Borrar", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (i < scans.lastIndex) HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun PenMemoryTab(state: net.micapum.neopen.core.pen.PenState, vm: MainViewModel) {
    val idle = state.offlinePhase == net.micapum.neopen.core.pen.OfflinePhase.IDLE
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = vm::listOffline, enabled = idle, modifier = Modifier.weight(1f)) { Text("Leer memoria") }
            if (!state.notes.isNullOrEmpty()) {
                FilledTonalButton(onClick = vm::importOffline, enabled = idle, modifier = Modifier.weight(1f)) { Text("Importar todo") }
            }
        }
        if (state.offlinePhase == net.micapum.neopen.core.pen.OfflinePhase.TRANSFERRING) {
            Text("Importando… ${state.offlineProgress}%", style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(progress = { state.offlineProgress / 100f }, modifier = Modifier.fillMaxWidth())
        }
        state.notes?.let { notes ->
            if (notes.isEmpty()) {
                Text("El boli no tiene notas guardadas.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            } else {
                Card {
                    Column {
                        notes.forEachIndexed { i, n ->
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column {
                                    Text("${n.section}.${n.owner}.${n.note}", style = MaterialTheme.typography.bodyMedium)
                                    n.pages?.let { Text("$it páginas", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                                TextButton(onClick = { vm.importNote(n) }, enabled = idle) { Text("Importar") }
                            }
                            if (i < notes.lastIndex) HorizontalDivider()
                        }
                    }
                }
                TextButton(onClick = vm::clearOffline) {
                    Text("Vaciar memoria del boli", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun PenToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}

@Composable
private fun PenDebugLogDialog(logs: List<String>, onClear: () -> Unit, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val text = if (logs.isEmpty()) "(sin registros — pulsa «Conectar boli»)" else logs.joinToString("\n")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Registro de conexión") },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                Text(text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            }
        },
        confirmButton = {
            TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Text("Copiar") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onClear) { Text("Limpiar") }
                TextButton(onClick = onDismiss) { Text("Cerrar") }
            }
        },
    )
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
    val check by vm.serverCheck.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var url by rememberSaveable(settings.serverUrl) { mutableStateOf(settings.serverUrl) }
    var pw by rememberSaveable(settings.password) { mutableStateOf(settings.password) }
    var mode by rememberSaveable(settings.appMode) { mutableStateOf(settings.appMode) }
    var syncMode by rememberSaveable(settings.syncMode) { mutableStateOf(settings.syncMode) }
    var interval by rememberSaveable(settings.syncIntervalMin) { mutableStateOf(settings.syncIntervalMin.toString()) }
    var background by rememberSaveable(settings.backgroundEnabled) { mutableStateOf(settings.backgroundEnabled) }
    var importOnConnect by rememberSaveable(settings.importOnConnect) { mutableStateOf(settings.importOnConnect) }
    var backupMsg by rememberSaveable { mutableStateOf<String?>(null) }
    var linking by rememberSaveable { mutableStateOf(false) }
    var linkMsg by rememberSaveable { mutableStateOf<String?>(null) }

    fun persist() {
        val isLocal = settings.appMode == AppMode.LOCAL
        vm.saveSettings(
            AppSettings(
                serverUrl = if (isLocal) "" else url, password = if (isLocal) "" else pw,
                backgroundEnabled = background,
                syncMode = syncMode, syncIntervalMin = interval.toIntOrNull() ?: 30,
                appMode = if (isLocal) AppMode.LOCAL else mode, setupComplete = true,
                importOnConnect = importOnConnect,
            ),
        )
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        if (uri != null) scope.launch {
            val bytes = vm.exportBackup()
            backupMsg = if (bytes != null && runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                }.isSuccess) "Copia exportada." else "No se pudo exportar."
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) scope.launch {
            val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            backupMsg = if (bytes != null && vm.restoreBackup(bytes)) "Copia restaurada." else "No se pudo restaurar."
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (settings.appMode == AppMode.LOCAL) {
            // --- Modo local: vincular un servidor (migra los datos sin perderlos) ---
            Text("Modo local (sin servidor)", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text(
                "Tus cuadernos viven solo en este móvil. Si vinculas un servidor se subirá todo (sin perder nada) y a partir de entonces se sincronizará.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(url, { url = it }, label = { Text("URL del servidor") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(pw, { pw = it }, label = { Text("Contraseña") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(
                onClick = {
                    linking = true; linkMsg = null
                    vm.linkServer(url, pw) { ok, err ->
                        linking = false
                        linkMsg = if (ok) "Servidor vinculado y datos migrados." else (err ?: "No se pudo vincular")
                    }
                },
                enabled = !linking && url.isNotBlank() && pw.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (linking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Vincular servidor y migrar")
            }
            linkMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        } else {
            // --- Servidor ---
            Text("Servidor neopen", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            OutlinedTextField(url, { url = it; vm.resetServerCheck() }, label = { Text("URL del servidor") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(pw, { pw = it; vm.resetServerCheck() }, label = { Text("Contraseña") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { vm.testServer(url, pw) }, enabled = check != ServerCheck.Checking,
                modifier = Modifier.fillMaxWidth()) {
                if (check == ServerCheck.Checking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Probar conexión")
            }
            ServerCheckResult(check)

            HorizontalDivider()
            // --- Modo de la app ---
            Text("Modo de la app", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(mode == AppMode.FULL, { mode = AppMode.FULL }, label = { Text("Completo") })
                FilterChip(mode == AppMode.BRIDGE, { mode = AppMode.BRIDGE }, label = { Text("Solo puente") })
            }
        }

        HorizontalDivider()
        // --- Sincronización del boli ---
        Text("Bluetooth y sincronización", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        PenToggleRow("Conexión en segundo plano", "Mantiene el boli conectado con la app cerrada", background) { background = it }
        PenToggleRow("Volcar memoria al conectar", "Importa la escritura offline del boli en cuanto se conecta", importOnConnect) { importOnConnect = it }
        Text("Volcado de la memoria offline", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(syncMode == SyncMode.MANUAL, { syncMode = SyncMode.MANUAL }, label = { Text("Manual") })
            FilterChip(syncMode == SyncMode.INTERVAL, { syncMode = SyncMode.INTERVAL }, label = { Text("Por intervalo") })
        }
        if (syncMode == SyncMode.INTERVAL) {
            OutlinedTextField(interval, { interval = it.filter(Char::isDigit) },
                label = { Text("Intervalo (min, mín. 15)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }

        Button(onClick = { persist() }, modifier = Modifier.fillMaxWidth()) { Text("Guardar ajustes") }
        OutlinedButton(onClick = vm::syncNow, modifier = Modifier.fillMaxWidth()) { Text("Sincronizar ahora") }
        Text("Pendiente de subir: $pending", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (status != null) {
            Text("Último error: $status", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        HorizontalDivider()
        // --- Copia de seguridad ---
        Text("Copia de seguridad", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text("Exporta o restaura todo el contenido del servidor (cuadernos y hojas) como un fichero JSON.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { backupMsg = null; exportLauncher.launch("neopen-backup.json") }, modifier = Modifier.weight(1f)) { Text("Exportar") }
            OutlinedButton(onClick = { backupMsg = null; restoreLauncher.launch(arrayOf("application/json")) }, modifier = Modifier.weight(1f)) { Text("Restaurar") }
        }
        backupMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        HorizontalDivider()
        SobpHistorySection(vm)

        HorizontalDivider()
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
    initialCategory: String,
    categorySuggestions: List<String>,
    onConfirm: (String, String, List<String>, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var color by remember { mutableStateOf(initialColor) }
    var tagsText by remember { mutableStateOf(initialTags.joinToString(", ")) }
    var category by remember { mutableStateOf(initialCategory) }
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
                    category,
                    { category = it },
                    label = { Text("Categoría (opcional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (categorySuggestions.isNotEmpty()) {
                    Spacer(Modifier.size(6.dp))
                    CategorySuggestions(categorySuggestions, selected = category) { category = it }
                }
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
            TextButton(onClick = { if (name.isNotBlank()) onConfirm(name.trim(), color, parseTags(tagsText), category.trim()) }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

/** Chips de categorías existentes; al pulsar una, rellena el campo (toggle). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategorySuggestions(suggestions: List<String>, selected: String, onPick: (String) -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (c in suggestions) {
            val active = c.equals(selected.trim(), ignoreCase = true)
            FilterChip(selected = active, onClick = { onPick(if (active) "" else c) }, label = { Text(c) })
        }
    }
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

/** Selector del formato/proporciones de página (presets A4, A5, Moleskine…). */
@Composable
private fun PaperPickerDialog(current: String?, onPick: (PaperPreset) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Formato de página") },
        text = {
            Column {
                Text(
                    "Elige el tamaño real de la hoja para que las proporciones del dibujo sean correctas.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(8.dp))
                PAPER_PRESETS.forEach { p ->
                    TextButton(onClick = { onPick(p) }, modifier = Modifier.fillMaxWidth()) {
                        Text(p.label + if (p.id == current) "  ✓" else "", modifier = Modifier.fillMaxWidth())
                    }
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

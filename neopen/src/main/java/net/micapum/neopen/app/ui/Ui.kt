package net.micapum.neopen.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.annotation.StringRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
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
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.draw.alpha
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import net.micapum.neopen.app.R
import net.micapum.neopen.app.data.Capture
import net.micapum.neopen.app.data.Notebook
import net.micapum.neopen.app.data.OfflineImportWarning
import net.micapum.neopen.app.data.PAPER_PRESETS
import net.micapum.neopen.app.data.PaperPreset
import net.micapum.neopen.app.data.paperFromPreset
import net.micapum.neopen.app.render.ExportFormat
import net.micapum.neopen.app.render.InkCanvas
import net.micapum.neopen.app.render.OverlapPreview
import net.micapum.neopen.app.render.buildModel
import kotlin.math.roundToInt
import net.micapum.neopen.core.data.AppMode
import net.micapum.neopen.core.data.AppSettings
import net.micapum.neopen.core.data.Capability
import net.micapum.neopen.core.data.CapabilitySource
import net.micapum.neopen.core.data.DEFAULT_OCR_INSTRUCTIONS
import net.micapum.neopen.core.data.FtpLocalConfig
import net.micapum.neopen.core.data.LanguageToolLocalConfig
import net.micapum.neopen.core.data.OcrLocalConfig
import net.micapum.neopen.core.data.OfflineReimportPolicy
import net.micapum.neopen.core.data.WebdavLocalConfig
import net.micapum.neopen.core.data.phoneCapable
import net.micapum.neopen.core.data.availabilityOf
import net.micapum.neopen.core.data.sourceOf
import net.micapum.neopen.core.data.SyncMode
import net.micapum.neopen.app.widget.TAB_NOTEBOOKS
import net.micapum.neopen.core.pen.PenStatus

private val NotebookColors = listOf(
    "#5b8cff", "#64748b", "#3ad29f", "#ef4444", "#f59e0b", "#a855f7", "#ec4899", "#14b8a6", "#111827",
)

private enum class PageView { Grid, List, Large }

private enum class NotebookView { Mosaic, Compact, List }
private enum class ExportMode { Share, Save }

private fun parseTags(s: String): List<String> =
    s.split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct()

private fun parseColor(hex: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color.Gray)

private fun penDotColor(status: PenStatus): Color = when (status) {
    PenStatus.AUTHORIZED -> Color(0xFF22C55E) // verde: conectado
    PenStatus.SCANNING, PenStatus.CONNECTING -> Color(0xFFF59E0B) // ámbar: conectando
    PenStatus.LOCKED -> Color(0xFFEF4444) // rojo: bloqueado por PIN
    else -> Color(0xFF9CA3AF) // gris: desconectado
}

// --- Raíz / navegación -------------------------------------------------------

/** Pestañas inferiores. El conjunto visible depende del modo de la app. */
private enum class HomeTab(@StringRes val titleRes: Int, val icon: ImageVector) {
    INBOX(R.string.tab_inbox, Icons.Filled.Inbox),
    NOTEBOOKS(R.string.tab_notebooks, Icons.Filled.Book),
    PEN(R.string.tab_pen, Icons.Filled.Draw),
    SETTINGS(R.string.tab_settings, Icons.Filled.Settings),
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
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        vm.startForegroundPenReconnect()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> vm.startForegroundPenReconnect()
                Lifecycle.Event.ON_STOP -> vm.stopForegroundPenReconnect()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            vm.stopForegroundPenReconnect()
        }
    }
    LaunchedEffect(settings.knownPens, settings.lockedPenMac, settings.forgottenPens) {
        vm.startForegroundPenReconnect()
    }

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
        val title = notebooks.firstOrNull { it.id == nbId }?.name ?: stringResource(R.string.notebook_fallback_title)
        NotebookScreen(vm, nbId, title, notebooks, onOpenPage = { openPageId = it }, onBack = { openNotebookId = null })
        return
    }

    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val offlineWarningCount by vm.pendingOfflineImportWarnings.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val penState by vm.penState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { HomeBar(stringResource(current.titleRes), syncing, pending, status != null, onSync = vm::syncNow) },
        floatingActionButton = {
            if (current == HomeTab.NOTEBOOKS) {
                FloatingActionButton(onClick = { creating = true }) { Icon(Icons.Filled.Add, stringResource(R.string.notebook_new)) }
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
                            } else if (t == HomeTab.INBOX && offlineWarningCount > 0) {
                                Box {
                                    Icon(t.icon, null)
                                    Badge(Modifier.align(Alignment.TopEnd)) {
                                        Text(offlineWarningCount.coerceAtMost(99).toString())
                                    }
                                }
                            } else {
                                Icon(t.icon, null)
                            }
                        },
                        label = { Text(stringResource(t.titleRes)) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (current) {
                HomeTab.INBOX -> InboxScreen(vm, notebooks, onOpenPage = { openPageId = it })
                HomeTab.NOTEBOOKS -> NotebooksScreen(vm, notebooks, onOpen = { openNotebookId = it }, onOpenPage = { openPageId = it }, onEdit = { editingId = it })
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
        NotebookEditorDialog(stringResource(R.string.notebook_new), "", NotebookColors.first(), emptyList(), "", categorySuggestions,
            onConfirm = { n, c, t, cat -> vm.createNotebook(n, c, t, cat.ifBlank { null }); creating = false },
            onDismiss = { creating = false })
    }
    val editing = editingId?.let { id -> notebooks.firstOrNull { it.id == id } }
    if (editing != null) {
        NotebookEditorDialog(stringResource(R.string.notebook_edit_title), editing.name, editing.color, editing.tags, editing.category ?: "", categorySuggestions,
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
                        stringResource(R.string.action_sync),
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
        Text(stringResource(R.string.setup_subtitle), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(8.dp))

        if (step == 0) {
            Text(stringResource(R.string.setup_how_to_use), style = MaterialTheme.typography.titleMedium)
            ModeCard(
                selected = mode == AppMode.FULL,
                title = stringResource(R.string.setup_mode_full_title),
                desc = stringResource(R.string.setup_mode_full_desc),
                onClick = { mode = AppMode.FULL },
            )
            ModeCard(
                selected = mode == AppMode.LOCAL,
                title = stringResource(R.string.setup_mode_local_title),
                desc = stringResource(R.string.setup_mode_local_desc),
                onClick = { mode = AppMode.LOCAL },
            )
            ModeCard(
                selected = mode == AppMode.BRIDGE,
                title = stringResource(R.string.setup_mode_bridge_title),
                desc = stringResource(R.string.setup_mode_bridge_desc),
                onClick = { mode = AppMode.BRIDGE },
            )
            Spacer(Modifier.size(8.dp))
            Button(
                onClick = { if (mode == AppMode.LOCAL) vm.completeSetup("", "", AppMode.LOCAL) {} else step = 1 },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (mode == AppMode.LOCAL) stringResource(R.string.setup_start) else stringResource(R.string.setup_next)) }
        } else {
            Text(stringResource(R.string.setup_connect_server), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = url,
                onValueChange = { url = it; vm.resetServerCheck() },
                label = { Text(stringResource(R.string.server_url_label)) },
                placeholder = { Text(stringResource(R.string.server_url_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = pw,
                onValueChange = { pw = it; vm.resetServerCheck() },
                label = { Text(stringResource(R.string.server_password_label)) },
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
                    Text(stringResource(R.string.server_test_connection))
                }
            }
            ServerCheckResult(check)
            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { step = 0 }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_back)) }
                Button(
                    onClick = { vm.completeSetup(url, pw, mode) {} },
                    enabled = check == ServerCheck.Ok,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.setup_start)) }
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
        ServerCheck.Ok -> cs.primary to stringResource(R.string.server_check_ok)
        ServerCheck.BadAuth -> cs.error to stringResource(R.string.server_check_bad_auth)
        is ServerCheck.Unreachable -> cs.error to stringResource(R.string.server_check_unreachable, check.detail)
    }
    Text(msg, color = color, style = MaterialTheme.typography.bodyMedium)
}

// --- Cuadernos ---------------------------------------------------------------

@Composable
private fun NotebooksScreen(
    vm: MainViewModel,
    notebooks: List<Notebook>,
    onOpen: (String) -> Unit,
    onOpenPage: (String) -> Unit,
    onEdit: (String) -> Unit,
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val mode = runCatching { NotebookView.valueOf(settings.notebookView) }.getOrDefault(NotebookView.Mosaic)
    // Qué cuadernos están desplegados en la vista lista (se pierde al cerrar la app).
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.tab_notebooks), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            val active = MaterialTheme.colorScheme.primary
            val muted = MaterialTheme.colorScheme.onSurfaceVariant
            IconButton({ vm.setNotebookView(NotebookView.Mosaic.name) }) { Icon(Icons.Filled.GridView, stringResource(R.string.view_mosaic), tint = if (mode == NotebookView.Mosaic) active else muted) }
            IconButton({ vm.setNotebookView(NotebookView.Compact.name) }) { Icon(Icons.Filled.Apps, stringResource(R.string.view_compact), tint = if (mode == NotebookView.Compact) active else muted) }
            IconButton({ vm.setNotebookView(NotebookView.List.name) }) { Icon(Icons.AutoMirrored.Filled.ViewList, stringResource(R.string.view_list), tint = if (mode == NotebookView.List) active else muted) }
        }
        if (notebooks.isEmpty()) {
            EmptyHint(stringResource(R.string.notebooks_empty))
            return@Column
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
        when (mode) {
            NotebookView.Mosaic -> NotebookGrid(groups, hasCategories, 150.dp, false, vm, onOpen, onEdit)
            NotebookView.Compact -> NotebookGrid(groups, hasCategories, 104.dp, true, vm, onOpen, onEdit)
            NotebookView.List -> LazyColumn(
                contentPadding = PaddingValues(bottom = 12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                groups.forEach { (category, list) ->
                    if (hasCategories) item(key = "cat-$category") {
                        CategoryHeader(category ?: stringResource(R.string.category_none), list.size, Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                    items(list, key = { it.id }) { nb ->
                        NotebookTreeRow(
                            vm = vm,
                            nb = nb,
                            notebooks = notebooks,
                            expanded = expanded[nb.id] == true,
                            onToggle = { expanded[nb.id] = !(expanded[nb.id] == true) },
                            onOpenPage = onOpenPage,
                            onEdit = onEdit,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NotebookGrid(
    groups: List<Pair<String?, List<Notebook>>>,
    hasCategories: Boolean,
    minCell: Dp,
    compact: Boolean,
    vm: MainViewModel,
    onOpen: (String) -> Unit,
    onEdit: (String) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minCell),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        groups.forEach { (category, items) ->
            if (hasCategories) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "cat-$category") {
                    CategoryHeader(category ?: stringResource(R.string.category_none), items.size)
                }
            }
            gridItems(items, key = { it.id }) { nb -> NotebookCard(vm, nb, onOpen, onEdit, compact) }
        }
    }
}

/** Cabecera de sección de categoría dentro de la rejilla de cuadernos. */
@Composable
private fun CategoryHeader(label: String, count: Int, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(top = 4.dp),
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

/** Portadas preestablecidas del servidor (espejo de apps/web/src/covers/presets.ts). */
private val COVER_PRESETS = listOf(
    "/cover-presets/llibreta1.png",
    "/cover-presets/llibreta2.png",
    "/cover-presets/llibreta3.png",
)
private const val UNCLASSIFIED_COVER = "/cover-presets/sinclasificar.png"

/** Hash estable (FNV-1a 32-bit) para elegir un preset por id, como en la web. */
private fun coverHash(s: String): Int {
    var h = -0x7ee3623b // 0x811c9dc5
    for (c in s) { h = h xor c.code; h *= 0x01000193 }
    return h and 0x7fffffff
}

/**
 * Modelo de portada para Coil. En modo local (sin servidor) → null (solo color).
 * En modo completo: portada subida, o preset por hash (o "sin clasificar"),
 * servidos estáticamente por el servidor.
 */
private fun notebookCoverModel(serverUrl: String, nb: Notebook): Any? {
    if (serverUrl.isBlank()) return null
    val path = nb.coverUrl
        ?: if (nb.unclassified) UNCLASSIFIED_COVER
        else COVER_PRESETS[coverHash(nb.id) % COVER_PRESETS.size]
    return absoluteUrl(serverUrl, path)
}

/** Para volver a mostrar la miniatura de portada en la vista lista, pon a true. */
private const val LIST_SHOW_COVER = false

/** Tarjeta de cuaderno (mosaico/compacto): portada (o color) arriba + texto debajo. */
@Composable
private fun NotebookCard(
    vm: MainViewModel,
    nb: Notebook,
    onOpen: (String) -> Unit,
    onEdit: (String) -> Unit,
    compact: Boolean = false,
) {
    val cover = notebookCoverModel(vm.serverUrl(), nb)
    val noFormat = nb.paper == null && !nb.unclassified && !nb.custom
    // Con portada: proporción de libreta; sin portada (local): franja fina de color.
    val coverAspect = if (cover != null) 1.5f else 2.6f
    Card(
        onClick = { onOpen(nb.id) },
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(coverAspect)) {
            Box(Modifier.matchParentSize().background(parseColor(nb.color)))
            if (cover != null) {
                AsyncImage(
                    model = cover,
                    contentDescription = nb.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            }
            if (noFormat) {
                Text(
                    "⚠",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
        Row(
            Modifier.padding(start = 10.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    nb.name,
                    style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!compact) {
                    Text(
                        stringResource(if (nb.pageCount == 1) R.string.sheet_count_one else R.string.sheet_count_other, nb.pageCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!nb.unclassified) NotebookMenu(vm, nb, onEdit)
        }
    }
}

/** Fila de cuaderno (vista lista): detalle de color + nombre; al pulsar despliega sus hojas. */
@Composable
private fun NotebookTreeRow(
    vm: MainViewModel,
    nb: Notebook,
    notebooks: List<Notebook>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenPage: (String) -> Unit,
    onEdit: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (LIST_SHOW_COVER) {
                val cover = notebookCoverModel(vm.serverUrl(), nb)
                Box(Modifier.size(36.dp, 46.dp).clip(RoundedCornerShape(5.dp)).background(parseColor(nb.color))) {
                    if (cover != null) AsyncImage(cover, nb.name, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                }
            } else {
                Box(Modifier.size(14.dp).clip(CircleShape).background(parseColor(nb.color)))
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(nb.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(if (nb.pageCount == 1) R.string.sheet_count_one else R.string.sheet_count_other, nb.pageCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                if (expanded) stringResource(R.string.action_collapse) else stringResource(R.string.action_expand),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!nb.unclassified) NotebookMenu(vm, nb, onEdit)
        }
        if (expanded) {
            val pages by vm.capturesIn(nb.id).collectAsStateWithLifecycle(initialValue = emptyList())
            if (pages.isEmpty()) {
                Text(
                    stringResource(R.string.notebook_no_sheets),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 40.dp, bottom = 10.dp),
                )
            } else {
                Column(
                    Modifier.padding(start = 24.dp, end = 8.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    pages.forEach { p -> PageListRow(vm, p, notebooks, onClick = { onOpenPage(p.id) }) }
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun OriginBadge(nb: Notebook) {
    val (icon, label) = when {
        nb.unclassified -> Icons.Filled.Inbox to stringResource(R.string.origin_unclassified)
        nb.bookKey != null -> Icons.Filled.Draw to stringResource(R.string.origin_from_pen)
        nb.custom -> Icons.Filled.Collections to stringResource(R.string.origin_collection)
        else -> Icons.Filled.Book to stringResource(R.string.notebook_fallback_title)
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
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.action_more)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.action_edit)) }, onClick = { open = false; onEdit(nb.id) })
            DropdownMenuItem(text = { Text(stringResource(R.string.notebook_page_format)) }, onClick = { open = false; formatting = true })
            if (nb.bookKey != null) {
                DropdownMenuItem(
                    text = { Text(if (bookBg != null) stringResource(R.string.notebook_background_set, bookBg?.label ?: "") else stringResource(R.string.notebook_background)) },
                    onClick = { open = false; background = true },
                )
            }
            DropdownMenuItem(text = { Text(stringResource(R.string.notebook_share_pdf)) }, onClick = {
                open = false
                scope.launch { vm.exportNotebookPdf(nb.id)?.let { shareExport(context, it) } }
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.notebook_save_pdf)) }, onClick = {
                open = false
                scope.launch { vm.exportNotebookPdf(nb.id)?.let { saveExport(context, it) } }
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = { open = false; deleting = true })
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
        ConfirmDialog(stringResource(R.string.notebook_delete_title, nb.name), stringResource(R.string.notebook_delete_body),
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
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
                actions = {
                    val active = MaterialTheme.colorScheme.primary
                    val muted = MaterialTheme.colorScheme.onSurfaceVariant
                    IconButton({ mode = PageView.Grid }) { Icon(Icons.Filled.GridView, stringResource(R.string.view_grid), tint = if (mode == PageView.Grid) active else muted) }
                    IconButton({ mode = PageView.List }) { Icon(Icons.AutoMirrored.Filled.ViewList, stringResource(R.string.view_list), tint = if (mode == PageView.List) active else muted) }
                    IconButton({ mode = PageView.Large }) { Icon(Icons.Filled.ViewDay, stringResource(R.string.view_large), tint = if (mode == PageView.Large) active else muted) }
                },
            )
        },
    ) { padding ->
        if (pages.isEmpty()) {
            Box(Modifier.padding(padding)) { EmptyHint(stringResource(R.string.notebook_empty_sheets)) }
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
                Text(stringResource(R.string.page_number, page.page), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (page.paper == null) {
                    Text(stringResource(R.string.page_no_format), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
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
                title = { Text(c?.name ?: stringResource(R.string.sheet_fallback_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
                actions = {
                    if (c != null) TextButton(onClick = { bgOpen = true }) { Text(stringResource(R.string.action_background)) }
                },
            )
        },
    ) { padding ->
        if (c == null) {
            Box(Modifier.padding(padding)) { EmptyHint(stringResource(R.string.loading)) }
            return@Scaffold
        }
        Column(Modifier.padding(padding).fillMaxSize()) {
            // Proporción real de la hoja (papel o, sin papel, bbox de los trazos).
            val aspect = remember(c.id, c.updatedAt) {
                val m = buildModel(c.strokes, c.paper)
                if (m.height > 0f) m.width / m.height else 0.75f
            }
            BoxWithConstraints(
                Modifier.weight(1f).fillMaxWidth().padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                // "Contain": la hoja sale tan grande como cabe conservando su proporción.
                val areaRatio = maxWidth.value / maxHeight.value
                val sheetMod = if (areaRatio > aspect) {
                    Modifier.fillMaxHeight().aspectRatio(aspect, matchHeightConstraintsFirst = true)
                } else {
                    Modifier.fillMaxWidth().aspectRatio(aspect)
                }
                Surface(
                    modifier = sheetMod,
                    shape = RoundedCornerShape(8.dp),
                    color = Color.White,
                    shadowElevation = 4.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    PageCanvas(vm, c, Modifier.fillMaxSize())
                }
            }
            CaptureActions(vm, c, notebooks, asRow = true, onAfterDelete = onBack)
            if (c.tags.isNotEmpty()) TagChips(c.tags, Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            val text = c.transcription?.text
            if (!text.isNullOrBlank()) {
                Column(
                    Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text(stringResource(R.string.transcription_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 4.dp))
                    Text(text, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.size(16.dp))
                }
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
    val warnings by vm.offlineImportWarnings.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val terms = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val searching = terms.isNotEmpty()
    val matched = if (!searching) items else items.filter { c ->
        val hay = buildString {
            append(c.name.lowercase())
            c.transcription?.let {
                append(' ').append(it.title?.lowercase() ?: "").append(' ').append(it.text?.lowercase() ?: "")
            }
            c.tags.forEach { append(' ').append(it.lowercase()) }
        }
        terms.all { hay.contains(it) }
    }
    // Sin búsqueda: oculta lo "limpiado" (por última edición) y limita a N.
    val cleared = settings.recentClearedAt
    val shown = if (searching) matched
    else (if (cleared.isNotEmpty()) items.filter { it.updatedAt > cleared } else items).take(settings.recentShowCount)

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.inbox_recent_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (!searching) {
                listOf(5, 10, 20).forEach { n ->
                    FilterChip(
                        selected = settings.recentShowCount == n,
                        onClick = { vm.setRecentShowCount(n) },
                        label = { Text("$n") },
                        modifier = Modifier.padding(horizontal = 2.dp),
                    )
                }
                TextButton(onClick = { vm.clearRecent() }) { Text(stringResource(R.string.action_clear)) }
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.inbox_search_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        )
        if (shown.isEmpty() && warnings.isEmpty()) {
            EmptyHint(if (items.isEmpty()) stringResource(R.string.inbox_empty) else stringResource(R.string.inbox_no_results))
        } else {
            LazyColumn(
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(warnings, key = { "offline-warning-${it.id}" }) { warning ->
                    OfflineImportWarningCard(vm, warning)
                }
                items(shown, key = { it.id }) { c -> PageListRow(vm, c, notebooks, onClick = { onOpenPage(c.id) }) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OfflineImportWarningCard(vm: MainViewModel, warning: OfflineImportWarning) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    Card(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.16f)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                modifier = Modifier.size(86.dp),
                color = Color.White,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.55f)),
            ) {
                InkCanvas(
                    warning.strokes,
                    warning.paper,
                    Modifier.fillMaxSize(),
                    pressureWidthEnabled = settings.pressureWidthEnabled,
                    pressureWidthContrast = settings.pressureWidthContrast,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.offline_reimport_warning_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                }
                Text(
                    if (warning.reasonAction == "moved") {
                        stringResource(
                            R.string.offline_reimport_warning_moved_desc,
                            warning.sobpLabel,
                            warning.actionNotebookName ?: warning.actionCaptureName ?: warning.name,
                        )
                    } else {
                        stringResource(
                            R.string.offline_reimport_warning_deleted_desc,
                            warning.sobpLabel,
                            warning.actionCaptureName ?: warning.name,
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.discardOfflineImportWarning(warning.id) }) {
                        Text(stringResource(R.string.offline_reimport_action_discard))
                    }
                    OutlinedButton(onClick = { vm.importOfflineWarningAsNewCapture(warning.id) }) {
                        Text(stringResource(R.string.offline_reimport_action_new_capture))
                    }
                    if (warning.canMerge) {
                        Button(onClick = { vm.mergeOfflineImportWarning(warning.id) }) {
                            Text(stringResource(R.string.offline_reimport_action_merge))
                        }
                    }
                }
            }
        }
    }
}

// --- Acciones de hoja (iconos o menú, misma lógica) --------------------------

@OptIn(ExperimentalLayoutApi::class)
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
    var resolvingOverlap by remember { mutableStateOf(false) }
    var finalizing by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf<ExportMode?>(null) }
    // Bloqueada: sellada y ya hay otra hoja con el mismo SOBP → no se puede reabrir.
    val superseded by vm.supersededSobps.collectAsStateWithLifecycle()
    val reopenLocked = c.sealed && superseded.contains(c.sobpLabel)

    fun doExport(fmt: ExportFormat, modeSel: ExportMode) {
        scope.launch {
            vm.exportPage(c.id, fmt)?.let { if (modeSel == ExportMode.Share) shareExport(context, it) else saveExport(context, it) }
        }
    }

    if (asRow) {
        // Chips con icono + etiqueta (claras), que envuelven a varias filas.
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val iconMod = Modifier.size(18.dp)
            AssistChip(
                onClick = { vm.transcribe(c.id) },
                enabled = online,
                label = { Text(stringResource(R.string.action_transcribe)) },
                leadingIcon = { Icon(Icons.Filled.DocumentScanner, null, iconMod) },
            )
            AssistChip(
                onClick = { exporting = ExportMode.Share },
                label = { Text(stringResource(R.string.action_share)) },
                leadingIcon = { Icon(Icons.Filled.Share, null, iconMod) },
            )
            AssistChip(
                onClick = { exporting = ExportMode.Save },
                label = { Text(stringResource(R.string.action_download)) },
                leadingIcon = { Icon(Icons.Filled.FileDownload, null, iconMod) },
            )
            AssistChip(
                onClick = { tagging = true },
                label = { Text(stringResource(R.string.action_tags)) },
                leadingIcon = { Icon(Icons.Filled.Label, null, iconMod) },
            )
            AssistChip(
                onClick = { renaming = true },
                label = { Text(stringResource(R.string.action_rename)) },
                leadingIcon = { Icon(Icons.Filled.Edit, null, iconMod) },
            )
            AssistChip(
                onClick = { formatting = true },
                label = { Text(stringResource(R.string.action_format)) },
                leadingIcon = { Icon(Icons.Filled.AspectRatio, null, iconMod) },
            )
            AssistChip(
                onClick = { moving = true },
                label = { Text(stringResource(R.string.action_move)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.DriveFileMove, null, iconMod) },
            )
            if (c.overlap != null) {
                AssistChip(
                    onClick = { resolvingOverlap = true },
                    label = { Text(stringResource(R.string.action_overlap)) },
                    leadingIcon = { Icon(Icons.Filled.Warning, null, iconMod, tint = Color(0xFFEA580C)) },
                )
            }
            AssistChip(
                onClick = { if (c.sealed) vm.finalizeCapture(c) else finalizing = true },
                enabled = !reopenLocked,
                label = {
                    Text(
                        if (reopenLocked) stringResource(R.string.action_finished_locked)
                        else if (c.sealed) stringResource(R.string.action_reopen)
                        else stringResource(R.string.action_finish),
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Filled.Flag,
                        null,
                        iconMod,
                        tint = if (c.sealed) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                    )
                },
            )
            AssistChip(
                onClick = { deleting = true },
                label = { Text(stringResource(R.string.action_delete)) },
                leadingIcon = { Icon(Icons.Filled.Delete, null, iconMod) },
            )
        }
    } else {
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.action_more)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.action_share)) }, onClick = { menu = false; exporting = ExportMode.Share })
                DropdownMenuItem(text = { Text(stringResource(R.string.action_download)) }, onClick = { menu = false; exporting = ExportMode.Save })
                DropdownMenuItem(
                    text = { Text(if (online) stringResource(R.string.action_transcribe_ocr) else stringResource(R.string.action_transcribe_offline)) },
                    enabled = online,
                    onClick = { menu = false; vm.transcribe(c.id) },
                )
                DropdownMenuItem(text = { Text(stringResource(R.string.action_tags)) }, onClick = { menu = false; tagging = true })
                DropdownMenuItem(text = { Text(stringResource(R.string.action_rename)) }, onClick = { menu = false; renaming = true })
                DropdownMenuItem(text = { Text(stringResource(R.string.action_sheet_format)) }, onClick = { menu = false; formatting = true })
                DropdownMenuItem(text = { Text(stringResource(R.string.action_move_to_notebook)) }, onClick = { menu = false; moving = true })
                DropdownMenuItem(
                    text = {
                        Text(
                            if (reopenLocked) stringResource(R.string.action_finished_locked)
                            else if (c.sealed) stringResource(R.string.action_reopen_sheet)
                            else stringResource(R.string.action_finish_page),
                        )
                    },
                    enabled = !reopenLocked,
                    onClick = { menu = false; if (c.sealed) vm.finalizeCapture(c) else finalizing = true },
                )
                if (c.overlap != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_overlap_strokes)) },
                        onClick = { menu = false; resolvingOverlap = true },
                        leadingIcon = { Icon(Icons.Filled.Warning, null, tint = Color(0xFFEA580C)) },
                    )
                }
                DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = { menu = false; deleting = true })
            }
        }
    }

    if (renaming) {
        TextPromptDialog(stringResource(R.string.rename_sheet_title), c.name, stringResource(R.string.field_name),
            onConfirm = { vm.renameCapture(c.id, it); renaming = false }, onDismiss = { renaming = false })
    }
    if (moving) {
        MoveDialog(notebooks.filter { !it.unclassified || it.id != c.notebookId },
            onPick = { vm.moveCapture(c.id, it, 0); moving = false }, onDismiss = { moving = false })
    }
    if (tagging) {
        TagsDialog(stringResource(R.string.sheet_tags_title), c.tags,
            onConfirm = { vm.setTags(c.id, it); tagging = false }, onDismiss = { tagging = false })
    }
    if (deleting) {
        ConfirmDialog(stringResource(R.string.sheet_delete_title, c.name), stringResource(R.string.sheet_delete_body),
            onConfirm = { vm.deleteCapture(c.id); deleting = false; onAfterDelete?.invoke() }, onDismiss = { deleting = false })
    }
    if (formatting) {
        PaperPickerDialog(
            current = c.paper?.source,
            onPick = { p -> vm.setCapturePaper(c.id, paperFromPreset(p)); formatting = false },
            onDismiss = { formatting = false },
        )
    }
    if (resolvingOverlap) {
        OverlapResolverDialog(c, vm, onDismiss = { resolvingOverlap = false })
    }
    if (finalizing) {
        ConfirmDialog(
            stringResource(R.string.finish_warn_title),
            stringResource(R.string.finish_warn_body),
            confirmText = stringResource(R.string.action_finish),
            destructive = false,
            onConfirm = { vm.finalizeCapture(c); finalizing = false },
            onDismiss = { finalizing = false },
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
    var showPin by rememberSaveable { mutableStateOf(false) }
    // Cuando el boli queda bloqueado, abre automáticamente el diálogo de PIN.
    LaunchedEffect(state.status) { if (state.status == PenStatus.LOCKED) showPin = true }
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
                    Text(state.name ?: state.mac ?: stringResource(R.string.pen_default_name), style = MaterialTheme.typography.titleMedium)
                }
                Text(stringResource(R.string.pen_status, penStatusLabel(state.status, state.autoReconnecting)), style = MaterialTheme.typography.bodyMedium)
                state.settings.battery?.let {
                    Text(
                        if (state.settings.charging) stringResource(R.string.pen_battery_charging, it) else stringResource(R.string.pen_battery, it),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                state.settings.usedMem?.let { Text(stringResource(R.string.pen_memory_free, 100 - it), style = MaterialTheme.typography.bodyMedium) }
                if (state.capturedPages > 0) {
                    Text(stringResource(R.string.pen_captured_pages, state.capturedPages), style = MaterialTheme.typography.bodyMedium)
                }
                state.error?.let { Text(stringResource(R.string.pen_error, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }

        if (busy) {
            OutlinedButton(onClick = vm::stopConnecting, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.pen_stop_connecting)) }
        } else if (state.locked) {
            Button(onClick = { showPin = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.pen_locked_enter_pin))
            }
        } else {
            Button(onClick = connect, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.connected) stringResource(R.string.pen_reconnect) else stringResource(R.string.pen_connect))
            }
        }

        if (state.connected) {
            SecondaryTabRow(selectedTabIndex = penTab) {
                Tab(penTab == 0, { penTab = 0 }, text = { Text(stringResource(R.string.pen_tab_config)) })
                Tab(penTab == 1, { penTab = 1 }, text = { Text(stringResource(R.string.pen_tab_memory)) })
            }
            when (penTab) {
                0 -> PenConfigTab(state, vm)
                else -> PenMemoryTab(state, vm)
            }
        } else {
            Text(
                stringResource(R.string.pen_disconnected_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider()
        // Bolis emparejados + estilo de trazo: SIEMPRE visible (editable aunque estén apagados).
        PairedPensSection(vm)

        HorizontalDivider()
        OutlinedButton(onClick = { showLogs = true }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.pen_view_log, logs.size))
        }
    }

    if (showLogs) {
        PenDebugLogDialog(logs, onClear = vm::clearLogs, onDismiss = { showLogs = false })
    }
    if (showPin && state.status == PenStatus.LOCKED) {
        PinUnlockDialog(state, onUnlock = { pin, remember -> vm.unlockPen(pin, remember) }, onDismiss = { showPin = false })
    }
}

/** Diálogo para introducir el PIN y desbloquear el boli. */
@Composable
private fun PinUnlockDialog(
    state: net.micapum.neopen.core.pen.PenState,
    onUnlock: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var remember by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pen_pw_unlock_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(8) },
                    label = { Text(stringResource(R.string.pen_pw_pin_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = remember, onCheckedChange = { remember = it })
                    Text(stringResource(R.string.pen_pw_remember), style = MaterialTheme.typography.bodyMedium)
                }
                if (state.error == "password-wrong") {
                    Text(stringResource(R.string.pen_pw_wrong, state.passwordRetry, state.passwordMaxRetry),
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (state.error == "password-reset") {
                    Text(stringResource(R.string.pen_pw_reset_done),
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                Text(stringResource(R.string.pen_pw_reset_warning, state.passwordMaxRetry),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = { if (pin.isNotBlank()) onUnlock(pin, remember) }) { Text(stringResource(R.string.pen_pw_unlock_action)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PenConfigTab(state: net.micapum.neopen.core.pen.PenState, vm: MainViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PenToggleRow(stringResource(R.string.pen_auto_power_on_title), stringResource(R.string.pen_auto_power_on_desc), state.settings.autoPowerOn ?: false, vm::setAutoPowerOn)
        PenToggleRow(stringResource(R.string.pen_beep_title), stringResource(R.string.pen_beep_desc), state.settings.beep ?: false, vm::setBeep)
        PenToggleRow(stringResource(R.string.pen_offline_title), stringResource(R.string.pen_offline_desc), state.settings.offlineData ?: false, vm::setOfflineData)
        if (state.settings.pressureSupported) {
            PenSensitivitySection(state.settings.sensitivity ?: 2, vm::setSensitivity)
        }
        Spacer(Modifier.size(8.dp))
        Text(stringResource(R.string.pen_auto_shutdown), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val current = state.settings.autoShutdownTime ?: 20
            SHUTDOWN_OPTIONS.forEach { m ->
                FilterChip(selected = current == m, onClick = { vm.setAutoShutdown(m) }, label = { Text(stringResource(R.string.pen_minutes, m)) })
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        // Contraseña / PIN del boli.
        PenPasswordSection(state, vm)

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        state.mac?.let { Text(stringResource(R.string.pen_mac, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        state.firmware?.let { Text(stringResource(R.string.pen_firmware, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }

        // Herramienta avanzada (discreta): leer el SOBP de una marca.
        var readingSobp by remember { mutableStateOf(false) }
        TextButton(onClick = { readingSobp = true }, contentPadding = PaddingValues(top = 6.dp, bottom = 6.dp)) {
            Text(stringResource(R.string.pen_read_sobp), style = MaterialTheme.typography.bodySmall)
        }
        if (readingSobp) SobpReaderDialog(vm) { readingSobp = false }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PenSensitivitySection(current: Int, onChange: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.pen_sensitivity_title), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.pen_sensitivity_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (0..4).forEach { level ->
                FilterChip(
                    selected = current == level,
                    onClick = { onChange(level) },
                    label = { Text(stringResource(R.string.pen_sensitivity_level, level + 1)) },
                )
            }
        }
    }
}

/** Gestión de la contraseña del boli: poner / cambiar / eliminar. */
@Composable
private fun PenPasswordSection(state: net.micapum.neopen.core.pen.PenState, vm: MainViewModel) {
    var dialog by remember { mutableStateOf("") } // "", "set", "change", "remove"
    Text(stringResource(R.string.pen_pw_section), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (state.hasPassword) {
        TextButton(onClick = { dialog = "change" }, contentPadding = PaddingValues(vertical = 6.dp)) {
            Text(stringResource(R.string.pen_pw_change), style = MaterialTheme.typography.bodyMedium)
        }
        TextButton(onClick = { dialog = "remove" }, contentPadding = PaddingValues(vertical = 6.dp)) {
            Text(stringResource(R.string.pen_pw_remove), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
    } else {
        TextButton(onClick = { dialog = "set" }, contentPadding = PaddingValues(vertical = 6.dp)) {
            Text(stringResource(R.string.pen_pw_set), style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (vm.hasSavedPin(state.mac)) {
        TextButton(onClick = { vm.forgetPin(state.mac) }, contentPadding = PaddingValues(vertical = 6.dp)) {
            Text(stringResource(R.string.pen_pw_forget_saved), style = MaterialTheme.typography.bodyMedium)
        }
    }
    Text(stringResource(R.string.pen_pw_reset_warning, state.passwordMaxRetry),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    when (dialog) {
        "set" -> PinFormDialog(
            title = stringResource(R.string.pen_pw_set),
            askCurrent = false,
            onConfirm = { _, new -> vm.setOrChangePenPassword("", new); dialog = "" },
            onDismiss = { dialog = "" },
        )
        "change" -> PinFormDialog(
            title = stringResource(R.string.pen_pw_change),
            askCurrent = true,
            onConfirm = { cur, new -> vm.setOrChangePenPassword(cur, new); dialog = "" },
            onDismiss = { dialog = "" },
        )
        "remove" -> PinFormDialog(
            title = stringResource(R.string.pen_pw_remove),
            askCurrent = true,
            askNew = false,
            destructive = true,
            body = stringResource(R.string.pen_pw_remove_body),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = { cur, _ -> vm.removePenPassword(cur); dialog = "" },
            onDismiss = { dialog = "" },
        )
    }
}

/** Diálogo genérico de PIN: PIN actual (opcional) + PIN nuevo (opcional). */
@Composable
private fun PinFormDialog(
    title: String,
    askCurrent: Boolean,
    askNew: Boolean = true,
    destructive: Boolean = false,
    body: String? = null,
    confirmLabel: String = stringResource(R.string.action_save),
    onConfirm: (current: String, new: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val mismatch = askNew && confirm.isNotEmpty() && new != confirm
    val pinField: @Composable (String, String, (String) -> Unit) -> Unit = { value, label, set ->
        OutlinedTextField(
            value = value,
            onValueChange = { set(it.filter(Char::isDigit).take(8)) },
            label = { Text(label) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                body?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                if (askCurrent) pinField(current, stringResource(R.string.pen_pw_current_label)) { current = it }
                if (askNew) {
                    pinField(new, stringResource(R.string.pen_pw_new_label)) { new = it }
                    pinField(confirm, stringResource(R.string.pen_pw_confirm_label)) { confirm = it }
                    if (mismatch) {
                        Text(stringResource(R.string.pen_pw_mismatch),
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(stringResource(R.string.pen_pw_reset_warning_short),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            val okCur = !askCurrent || current.isNotBlank()
            val okNew = !askNew || (new.isNotBlank() && new == confirm)
            TextButton(onClick = { if (okCur && okNew) onConfirm(current, new) }, enabled = okCur && okNew) {
                Text(confirmLabel, color = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * Bolis emparejados + estilo de trazo. SIEMPRE visible (no requiere conexión): permite
 * personalizar el color/grueso de cada boli aunque esté apagado, y el estilo por defecto.
 */
@Composable
private fun PairedPensSection(vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    Text(stringResource(R.string.pen_paired_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    if (settings.knownPens.isEmpty()) {
        Text(stringResource(R.string.pen_paired_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        settings.knownPens.forEach { kp ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(kp.alias, style = MaterialTheme.typography.titleSmall)
                        TextButton(onClick = { vm.forgetPen(kp.mac) }) {
                            Text(stringResource(R.string.pen_forget), color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.pen_lock_to_this_title), style = MaterialTheme.typography.bodyMedium)
                            Text(stringResource(R.string.pen_lock_to_this_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = settings.lockedPenMac == kp.mac,
                            onCheckedChange = { checked -> vm.setLockedPen(if (checked) kp.mac else null) },
                        )
                    }
                    PenStyleSection(stringResource(R.string.pen_style_title), kp.mac, vm)
                }
            }
        }
    }
    PenStyleSection(stringResource(R.string.pen_style_default_title), null, vm)
}

/** Paleta + grueso del trazo. `mac` = boli concreto; null = estilo por defecto. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PenStyleSection(title: String, mac: String?, vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val style = settings.styleFor(mac)
    fun apply(s: net.micapum.neopen.core.pen.StrokeStyle) {
        if (mac != null) vm.setPenStyle(mac, s) else vm.setDefaultStyle(s)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            net.micapum.neopen.core.pen.StrokePresets.COLORS.forEach { (c, _) ->
                val selected = style.color == c
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color(c))
                        .border(
                            width = if (selected) 3.dp else 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            shape = CircleShape,
                        )
                        .clickable { apply(style.copy(color = c)) },
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            net.micapum.neopen.core.pen.StrokePresets.WIDTHS.forEach { (w, label) ->
                FilterChip(style.width == w, { apply(style.copy(width = w)) }, label = { Text(label) })
            }
        }
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
        title = { Text(stringResource(R.string.sobp_reader_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val s = detected
                when {
                    saved -> Text(stringResource(R.string.sobp_saved_in_history))
                    s == null -> {
                        Text(stringResource(R.string.sobp_make_a_mark))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    else -> {
                        Text(
                            stringResource(R.string.sobp_detected, s.section, s.owner, s.book, s.page),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        OutlinedTextField(label, { label = it }, label = { Text(stringResource(R.string.field_identifier)) }, singleLine = true)
                    }
                }
            }
        },
        confirmButton = {
            when {
                saved -> TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
                detected != null -> TextButton(
                    enabled = !saving,
                    onClick = { saving = true; vm.saveSobpScan(label) { ok -> saving = false; if (ok) saved = true } },
                ) { Text(stringResource(R.string.action_save)) }
                else -> {}
            }
        },
        dismissButton = { if (!saved) TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Sección "Historial de SOBP" en Ajustes (lista + borrar). */
@Composable
private fun SobpHistorySection(vm: MainViewModel) {
    val scans by vm.sobpScans.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadSobpScans() }
    Text(stringResource(R.string.sobp_history_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    Text(
        stringResource(R.string.sobp_history_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (scans.isEmpty()) {
        Text(stringResource(R.string.sobp_history_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
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
            OutlinedButton(onClick = vm::listOffline, enabled = idle, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.pen_read_memory)) }
            if (!state.notes.isNullOrEmpty()) {
                FilledTonalButton(onClick = vm::importOffline, enabled = idle, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.pen_import_all)) }
            }
        }
        if (state.offlinePhase == net.micapum.neopen.core.pen.OfflinePhase.TRANSFERRING) {
            Text(stringResource(R.string.pen_importing, state.offlineProgress), style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(progress = { state.offlineProgress / 100f }, modifier = Modifier.fillMaxWidth())
        }
        state.notes?.let { notes ->
            if (notes.isEmpty()) {
                Text(stringResource(R.string.pen_no_notes), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
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
                                    n.pages?.let { Text(stringResource(R.string.pen_note_pages, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                                TextButton(onClick = { vm.importNote(n) }, enabled = idle) { Text(stringResource(R.string.action_import)) }
                            }
                            if (i < notes.lastIndex) HorizontalDivider()
                        }
                    }
                }
                TextButton(onClick = vm::clearOffline) {
                    Text(stringResource(R.string.pen_clear_memory), color = MaterialTheme.colorScheme.error)
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
    val text = if (logs.isEmpty()) stringResource(R.string.pen_log_empty) else logs.joinToString("\n")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pen_log_title)) },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                Text(text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            }
        },
        confirmButton = {
            TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Text(stringResource(R.string.action_copy)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onClear) { Text(stringResource(R.string.action_clear)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
            }
        },
    )
}

@Composable
private fun penStatusLabel(status: PenStatus, autoReconnecting: Boolean = false): String = when {
    autoReconnecting && (status == PenStatus.SCANNING || status == PenStatus.CONNECTING || status == PenStatus.IDLE || status == PenStatus.DISCONNECTED) ->
        stringResource(R.string.pen_status_auto_reconnecting)
    else -> when (status) {
    PenStatus.IDLE -> stringResource(R.string.pen_status_disconnected)
    PenStatus.SCANNING -> stringResource(R.string.pen_status_scanning)
    PenStatus.CONNECTING -> stringResource(R.string.pen_status_connecting)
    PenStatus.LOCKED -> stringResource(R.string.pen_status_locked)
    PenStatus.AUTHORIZED -> stringResource(R.string.pen_status_connected)
    PenStatus.DISCONNECTED -> stringResource(R.string.pen_status_disconnected)
    }
}

// --- Ajustes -----------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SettingsScreen(vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val check by vm.serverCheck.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()
    val sync by vm.syncStatus.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var settingsTab by rememberSaveable { mutableIntStateOf(0) }
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }

    var url by rememberSaveable(settings.serverUrl) { mutableStateOf(settings.serverUrl) }
    var pw by rememberSaveable(settings.password) { mutableStateOf(settings.password) }
    var syncMode by rememberSaveable(settings.syncMode) { mutableStateOf(settings.syncMode) }
    var interval by rememberSaveable(settings.syncIntervalMin) { mutableStateOf(settings.syncIntervalMin.toString()) }
    var background by rememberSaveable(settings.backgroundEnabled) { mutableStateOf(settings.backgroundEnabled) }
    var importOnConnect by rememberSaveable(settings.importOnConnect) { mutableStateOf(settings.importOnConnect) }
    var controlNotif by rememberSaveable(settings.controlNotification) { mutableStateOf(settings.controlNotification) }
    var backupMsg by rememberSaveable { mutableStateOf<String?>(null) }
    var linking by rememberSaveable { mutableStateOf(false) }
    var linkMsg by rememberSaveable { mutableStateOf<String?>(null) }

    fun persist() {
        val isLocal = settings.appMode == AppMode.LOCAL
        // copy() sobre los ajustes actuales: preserva todo lo no editado aquí (knownPens, estilos,
        // idioma de escritura en vivo, etc.) y solo cambia los campos de este formulario.
        vm.saveSettings(
            settings.copy(
                serverUrl = if (isLocal) "" else url,
                password = if (isLocal) "" else pw,
                backgroundEnabled = background,
                controlNotification = controlNotif,
                syncMode = syncMode,
                syncIntervalMin = interval.toIntOrNull() ?: 30,
                setupComplete = true,
                importOnConnect = importOnConnect,
            ),
        )
    }

    LaunchedEffect(Unit) { vm.refreshFinishMode(); vm.refreshOverlapConfig() }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        if (uri != null) scope.launch {
            val bytes = vm.exportBackup()
            backupMsg = if (bytes != null && runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                }.isSuccess) context.getString(R.string.backup_exported) else context.getString(R.string.backup_export_failed)
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) scope.launch {
            val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            backupMsg = if (bytes != null && vm.restoreBackup(bytes)) context.getString(R.string.backup_restored) else context.getString(R.string.backup_restore_failed)
        }
    }

    Column(Modifier.fillMaxSize()) {
        SecondaryTabRow(selectedTabIndex = settingsTab) {
            listOf(
                stringResource(R.string.settings_tab_general) to Icons.Filled.Tune,
                stringResource(R.string.settings_tab_connection) to Icons.Filled.Smartphone,
                stringResource(R.string.settings_tab_capabilities) to Icons.Filled.Apps,
                stringResource(R.string.settings_tab_other) to Icons.Filled.MoreVert,
                stringResource(R.string.settings_tab_server) to Icons.Filled.Cloud,
            ).forEachIndexed { i, (label, icon) ->
                Tab(
                    selected = settingsTab == i,
                    onClick = { settingsTab = i },
                    icon = { Icon(icon, label) },
                    text = if (settingsTab == i) {
                        { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    } else {
                        null
                    },
                )
            }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (settingsTab) {
                // ============================ GENERAL ============================
                0 -> {
                    // --- Tema ---
                    Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val opts = listOf(
                            "system" to stringResource(R.string.theme_system),
                            "light" to stringResource(R.string.theme_light),
                            "dark" to stringResource(R.string.theme_dark),
                        )
                        opts.forEach { (value, label) ->
                            FilterChip(settings.themeMode == value, { vm.setThemeMode(value) }, label = { Text(label) })
                        }
                    }

                    HorizontalDivider()
                    // --- Modo de la app ---
                    Text(stringResource(R.string.settings_app_mode_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    if (settings.appMode == AppMode.LOCAL) {
                        Text(stringResource(R.string.settings_app_mode_local_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(settings.appMode == AppMode.FULL, { vm.setAppMode(AppMode.FULL) }, label = { Text(stringResource(R.string.mode_full)) })
                            FilterChip(settings.appMode == AppMode.BRIDGE, { vm.setAppMode(AppMode.BRIDGE) }, label = { Text(stringResource(R.string.mode_bridge)) })
                        }
                        Text(
                            if (settings.appMode == AppMode.BRIDGE) stringResource(R.string.settings_app_mode_bridge_desc)
                            else stringResource(R.string.settings_app_mode_full_desc),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    HorizontalDivider()
                    // --- Al finalizar una hoja ---
                    Text(stringResource(R.string.settings_finish_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(settings.finishMode == "newSheet", { vm.setFinishMode("newSheet") }, label = { Text(stringResource(R.string.finish_new_sheet)) })
                        FilterChip(settings.finishMode == "newNotebook", { vm.setFinishMode("newNotebook") }, label = { Text(stringResource(R.string.notebook_new)) })
                    }
                    Text(
                        if (settings.finishMode == "newNotebook") stringResource(R.string.settings_finish_desc_notebook)
                        else stringResource(R.string.settings_finish_desc_sheet),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    HorizontalDivider()
                    // --- Trazos superpuestos ---
                    Text(stringResource(R.string.settings_overlap_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    PenToggleRow(
                        stringResource(R.string.settings_overlap_toggle_title),
                        stringResource(R.string.settings_overlap_toggle_desc),
                        settings.overlapEnabled,
                    ) { vm.setOverlapConfig(it, settings.overlapMinStrokes, settings.overlapMinAreaPercent) }
                    if (settings.overlapEnabled) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                settings.overlapMinStrokes.toString(),
                                { v -> v.filter(Char::isDigit).toIntOrNull()?.coerceIn(1, 100)?.let { n -> vm.setOverlapConfig(true, n, settings.overlapMinAreaPercent) } },
                                label = { Text(stringResource(R.string.settings_overlap_min_strokes)) }, singleLine = true, modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                settings.overlapMinAreaPercent.toString(),
                                { v -> v.filter(Char::isDigit).toIntOrNull()?.coerceIn(0, 100)?.let { n -> vm.setOverlapConfig(true, settings.overlapMinStrokes, n) } },
                                label = { Text(stringResource(R.string.settings_overlap_min_area)) }, singleLine = true, modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    HorizontalDivider()
                    // --- Presión como grosor ---
                    Text(stringResource(R.string.settings_pressure_width_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    PenToggleRow(
                        stringResource(R.string.settings_pressure_width_toggle_title),
                        stringResource(R.string.settings_pressure_width_toggle_desc),
                        settings.pressureWidthEnabled,
                    ) { vm.setPressureWidthEnabled(it) }
                    if (settings.pressureWidthEnabled) {
                        val contrast = settings.pressureWidthContrast.coerceIn(0.5f, 3f)
                        Text(
                            stringResource(R.string.settings_pressure_width_contrast_label, ((contrast * 10f).roundToInt() / 10f).toString()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Slider(
                            value = contrast,
                            onValueChange = vm::setPressureWidthContrast,
                            valueRange = 0.5f..3f,
                            steps = 4,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text(
                        stringResource(R.string.settings_pressure_width_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // ========================== CONEXIÓN ===========================
                1 -> {
                    Text(stringResource(R.string.settings_bluetooth_sync_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    PenToggleRow(stringResource(R.string.settings_background_title), stringResource(R.string.settings_background_desc), background) {
                        background = it
                        vm.saveSettings(settings.copy(backgroundEnabled = it))
                    }
                    PenToggleRow(stringResource(R.string.settings_import_on_connect_title), stringResource(R.string.settings_import_on_connect_desc), importOnConnect) { importOnConnect = it }
                    PenToggleRow(stringResource(R.string.settings_control_notif_title), stringResource(R.string.settings_control_notif_desc), controlNotif) { controlNotif = it }

                    HorizontalDivider()
                    Text(stringResource(R.string.settings_offline_dump), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.settings_offline_dump_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.settings_reimport_policy_title), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.settings_reimport_policy_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val options = listOf(
                            OfflineReimportPolicy.ASK to stringResource(R.string.reimport_policy_ask),
                            OfflineReimportPolicy.DISCARD to stringResource(R.string.reimport_policy_discard),
                            OfflineReimportPolicy.NEW_CAPTURE to stringResource(R.string.reimport_policy_new_capture),
                            OfflineReimportPolicy.MERGE to stringResource(R.string.reimport_policy_merge),
                        )
                        options.forEach { (policy, label) ->
                            FilterChip(
                                selected = settings.offlineReimportPolicy == policy,
                                onClick = { vm.setOfflineReimportPolicy(policy) },
                                label = { Text(label) },
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(syncMode == SyncMode.MANUAL, { syncMode = SyncMode.MANUAL }, label = { Text(stringResource(R.string.sync_manual)) })
                        FilterChip(syncMode == SyncMode.INTERVAL, { syncMode = SyncMode.INTERVAL }, label = { Text(stringResource(R.string.sync_interval)) })
                    }
                    if (syncMode == SyncMode.INTERVAL) {
                        OutlinedTextField(interval, { interval = it.filter(Char::isDigit) },
                            label = { Text(stringResource(R.string.settings_interval_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    Button(onClick = { persist() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_save)) }
                }

                // ========================= CAPACIDADES =========================
                2 -> {
                    Text(stringResource(R.string.settings_cap_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.settings_cap_local_intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    // Importar del servidor: rellena lo no-secreto de la config local.
                    val canImport = settings.appMode != AppMode.LOCAL && settings.configured && online
                    var importMsg by rememberSaveable { mutableStateOf<String?>(null) }
                    val importOkMsg = stringResource(R.string.cap_import_ok)
                    val importFailMsg = stringResource(R.string.cap_import_failed)
                    OutlinedButton(
                        onClick = { importMsg = null; vm.importServerConfig { ok -> importMsg = if (ok) importOkMsg else importFailMsg } },
                        enabled = canImport,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.cap_import_server)) }
                    Text(stringResource(R.string.cap_import_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    importMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }

                    // Una tarjeta colapsable por servicio (colapsadas por defecto).
                    OcrCapabilityCard(settings, online) { vm.saveSettings(it) }
                    LanguageToolCapabilityCard(settings, online) { vm.saveSettings(it) }
                    MarkerCapabilityCard(settings, online) { vm.saveSettings(it) }
                    ConnectorCapabilityCard(settings, online) { vm.saveSettings(it) }
                }

                // ============================ OTROS ============================
                3 -> {
                    // --- Copia de seguridad ---
                    Text(stringResource(R.string.settings_backup_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.settings_backup_desc),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { backupMsg = null; exportLauncher.launch("neopen-backup.json") }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_export)) }
                        OutlinedButton(onClick = { backupMsg = null; restoreLauncher.launch(arrayOf("application/json")) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_restore)) }
                    }
                    backupMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

                    HorizontalDivider()
                    SobpHistorySection(vm)

                    HorizontalDivider()
                    // --- Borrar contenido ---
                    Text(stringResource(R.string.settings_delete_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                    Text(stringResource(R.string.settings_delete_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = { showDeleteDialog = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.settings_delete_button), color = MaterialTheme.colorScheme.error)
                    }
                }

                // =========================== SERVIDOR ==========================
                else -> {
                    if (settings.appMode == AppMode.LOCAL) {
                        // --- Modo local: vincular un servidor (migra los datos sin perderlos) ---
                        Text(stringResource(R.string.settings_local_mode_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.settings_local_mode_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.server_url_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(pw, { pw = it }, label = { Text(stringResource(R.string.server_password_label)) },
                            visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                        val linkOkMsg = stringResource(R.string.settings_link_ok)
                        val linkFailMsg = stringResource(R.string.settings_link_failed)
                        Button(
                            onClick = {
                                linking = true; linkMsg = null
                                vm.linkServer(url, pw) { ok, err -> linking = false; linkMsg = if (ok) linkOkMsg else (err ?: linkFailMsg) }
                            },
                            enabled = !linking && url.isNotBlank() && pw.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (linking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Text(stringResource(R.string.settings_link_button))
                        }
                        linkMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    } else {
                        // --- Servidor: credenciales + probar ---
                        Text(stringResource(R.string.settings_server_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        OutlinedTextField(url, { url = it; vm.resetServerCheck() }, label = { Text(stringResource(R.string.server_url_label)) },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(pw, { pw = it; vm.resetServerCheck() }, label = { Text(stringResource(R.string.server_password_label)) },
                            visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { vm.testServer(url, pw) }, enabled = check != ServerCheck.Checking, modifier = Modifier.weight(1f)) {
                                if (check == ServerCheck.Checking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                else Text(stringResource(R.string.server_test_connection))
                            }
                            Button(onClick = { persist() }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.settings_save)) }
                        }
                        ServerCheckResult(check)

                        HorizontalDivider()
                        // --- Sincronización con el servidor ---
                        val connected = settings.configured && online
                        Text(stringResource(R.string.settings_server_sync_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        val lastSync = if (sync.lastServerSync <= 0L) stringResource(R.string.settings_last_sync_never)
                            else android.text.format.DateUtils.getRelativeTimeSpanString(sync.lastServerSync).toString()
                        Text(
                            stringResource(R.string.settings_last_server_sync, lastSync),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (connected) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline,
                        )
                        Text(
                            stringResource(R.string.settings_pending_upload, pending),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (connected) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline,
                        )
                        OutlinedButton(onClick = vm::syncNow, enabled = connected, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.settings_sync_now))
                        }
                        if (status != null) {
                            Text(stringResource(R.string.settings_last_error, status ?: ""), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }

                        HorizontalDivider()
                        Text(stringResource(R.string.settings_server_actions_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.settings_delete_title)) },
            text = { Text(stringResource(R.string.settings_delete_dialog_body)) },
            confirmButton = {
                TextButton(onClick = { showDeleteDialog = false; vm.logout() }) {
                    Text(stringResource(R.string.settings_delete_button), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** Etiqueta + color de estado para la cabecera colapsada de una tarjeta de capacidad. */
@Composable
private fun capStatus(settings: AppSettings, cap: Capability): Pair<String, Color> = when {
    settings.sourceOf(cap) == CapabilitySource.OFF ->
        stringResource(R.string.cap_status_off) to MaterialTheme.colorScheme.outline
    settings.sourceOf(cap) == CapabilitySource.SERVER ->
        stringResource(R.string.cap_status_server) to MaterialTheme.colorScheme.primary
    settings.phoneCapable(cap) ->
        stringResource(R.string.cap_status_phone) to MaterialTheme.colorScheme.primary
    else ->
        stringResource(R.string.cap_status_no_creds) to MaterialTheme.colorScheme.error
}

/**
 * Tarjeta colapsable de un servicio: cabecera clicable (icono + título + estado +
 * chevron) que alterna [expanded]; el cuerpo solo se muestra al expandir. Sigue el
 * patrón de colapso del árbol de cuadernos. Colapsada por defecto.
 */
@Composable
private fun CapabilityCard(
    title: String,
    icon: ImageVector,
    statusLabel: String,
    statusColor: Color,
    expanded: Boolean,
    onToggle: () -> Unit,
    body: @Composable ColumnScope.() -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(statusLabel, style = MaterialTheme.typography.bodySmall, color = statusColor)
                }
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                Column(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    content = body,
                )
            }
        }
    }
}

/**
 * Conmutador de 3 estados (Desactivado / Teléfono / Servidor) de una capacidad.
 * Atenúa y deshabilita los segmentos no disponibles, y explica el estado efectivo.
 */
@Composable
private fun CapabilitySourceControl(
    help: String,
    cap: Capability,
    settings: AppSettings,
    online: Boolean,
    onSelect: (CapabilitySource) -> Unit,
) {
    val avail = settings.availabilityOf(cap, online)
    val current = settings.sourceOf(cap)
    val usable = avail.phoneAvailable || avail.serverAvailable || current == CapabilitySource.OFF
    Column(
        Modifier.fillMaxWidth().alpha(if (usable) 1f else 0.5f),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = current == CapabilitySource.OFF,
                onClick = { onSelect(CapabilitySource.OFF) },
                shape = SegmentedButtonDefaults.itemShape(0, 3),
                icon = { Icon(Icons.Filled.Block, null, Modifier.size(18.dp)) },
            ) { Text(stringResource(R.string.cap_source_off)) }
            SegmentedButton(
                selected = current == CapabilitySource.PHONE,
                onClick = { onSelect(CapabilitySource.PHONE) },
                enabled = avail.phoneAvailable,
                shape = SegmentedButtonDefaults.itemShape(1, 3),
                icon = { Icon(Icons.Filled.Smartphone, null, Modifier.size(18.dp)) },
            ) { Text(stringResource(R.string.cap_source_phone)) }
            SegmentedButton(
                selected = current == CapabilitySource.SERVER,
                onClick = { onSelect(CapabilitySource.SERVER) },
                enabled = avail.serverAvailable,
                shape = SegmentedButtonDefaults.itemShape(2, 3),
                icon = { Icon(Icons.Filled.Cloud, null, Modifier.size(18.dp)) },
            ) { Text(stringResource(R.string.cap_source_server)) }
        }
        val note = when {
            current == CapabilitySource.SERVER && avail.serverAvailable && avail.phoneAvailable ->
                stringResource(R.string.cap_note_server_failover)
            current == CapabilitySource.SERVER && !avail.serverAvailable ->
                stringResource(R.string.cap_note_no_server)
            current == CapabilitySource.PHONE && !avail.phoneAvailable ->
                stringResource(R.string.cap_note_no_phone)
            else -> help
        }
        Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Tarjeta OCR: 3 estados + prompt local prominente + credenciales locales. */
@Composable
private fun OcrCapabilityCard(settings: AppSettings, online: Boolean, onSave: (AppSettings) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var ocrUrl by rememberSaveable(settings.ocrLocal) { mutableStateOf(settings.ocrLocal.baseUrl) }
    var ocrModel by rememberSaveable(settings.ocrLocal) { mutableStateOf(settings.ocrLocal.model) }
    var ocrKey by rememberSaveable(settings.ocrLocal) { mutableStateOf(settings.ocrLocal.apiKey) }
    var ocrInstr by rememberSaveable(settings.ocrLocal) { mutableStateOf(settings.ocrLocal.instructions) }
    val (status, statusColor) = capStatus(settings, Capability.OCR)
    val fieldMod = Modifier.fillMaxWidth()
    CapabilityCard(stringResource(R.string.cap_ocr), Icons.Filled.DocumentScanner, status, statusColor, expanded, { expanded = !expanded }) {
        CapabilitySourceControl(stringResource(R.string.cap_ocr_help), Capability.OCR, settings, online) {
            onSave(settings.copy(ocrSource = it))
        }
        Text(stringResource(R.string.cap_ocr_prompt_label), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            ocrInstr, { ocrInstr = it },
            label = { Text(stringResource(R.string.cap_ocr_prompt_field)) },
            placeholder = { Text(DEFAULT_OCR_INSTRUCTIONS, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            minLines = 3, modifier = fieldMod,
        )
        TextButton(onClick = { ocrInstr = "" }, enabled = ocrInstr.isNotBlank()) {
            Text(stringResource(R.string.cap_ocr_prompt_reset))
        }
        Text(stringResource(R.string.cap_ocr_local_creds), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(ocrUrl, { ocrUrl = it }, label = { Text(stringResource(R.string.cap_field_ocr_url)) }, singleLine = true, modifier = fieldMod)
        OutlinedTextField(ocrModel, { ocrModel = it }, label = { Text(stringResource(R.string.cap_field_ocr_model)) }, singleLine = true, modifier = fieldMod)
        OutlinedTextField(ocrKey, { ocrKey = it }, label = { Text(stringResource(R.string.cap_field_ocr_key)) }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = fieldMod)
        Button(onClick = { onSave(settings.copy(ocrLocal = OcrLocalConfig(ocrUrl.trim(), ocrKey, ocrModel.trim(), ocrInstr))) }, modifier = fieldMod) {
            Text(stringResource(R.string.settings_save))
        }
    }
}

/** Tarjeta LanguageTool: 3 estados + credenciales locales. */
@Composable
private fun LanguageToolCapabilityCard(settings: AppSettings, online: Boolean, onSave: (AppSettings) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var ltUrl by rememberSaveable(settings.ltLocal) { mutableStateOf(settings.ltLocal.baseUrl) }
    var ltUser by rememberSaveable(settings.ltLocal) { mutableStateOf(settings.ltLocal.username) }
    var ltKey by rememberSaveable(settings.ltLocal) { mutableStateOf(settings.ltLocal.apiKey) }
    val (status, statusColor) = capStatus(settings, Capability.LANGUAGE_TOOL)
    val fieldMod = Modifier.fillMaxWidth()
    CapabilityCard(stringResource(R.string.cap_lt), Icons.Filled.Edit, status, statusColor, expanded, { expanded = !expanded }) {
        CapabilitySourceControl(stringResource(R.string.cap_lt_help), Capability.LANGUAGE_TOOL, settings, online) {
            onSave(settings.copy(languageToolSource = it))
        }
        OutlinedTextField(ltUrl, { ltUrl = it }, label = { Text(stringResource(R.string.cap_field_lt_url)) }, singleLine = true, modifier = fieldMod)
        OutlinedTextField(ltUser, { ltUser = it }, label = { Text(stringResource(R.string.cap_field_lt_user)) }, singleLine = true, modifier = fieldMod)
        OutlinedTextField(ltKey, { ltKey = it }, label = { Text(stringResource(R.string.cap_field_lt_key)) }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = fieldMod)
        Button(onClick = { onSave(settings.copy(ltLocal = LanguageToolLocalConfig(ltUrl.trim(), ltUser, ltKey))) }, modifier = fieldMod) {
            Text(stringResource(R.string.settings_save))
        }
    }
}

/** Tarjeta de detección de marcadores: solo el conmutador (sin credenciales). */
@Composable
private fun MarkerCapabilityCard(settings: AppSettings, online: Boolean, onSave: (AppSettings) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val (status, statusColor) = capStatus(settings, Capability.MARKER_DETECTION)
    CapabilityCard(stringResource(R.string.cap_marker), Icons.Filled.Flag, status, statusColor, expanded, { expanded = !expanded }) {
        CapabilitySourceControl(stringResource(R.string.cap_marker_help), Capability.MARKER_DETECTION, settings, online) {
            onSave(settings.copy(markerDetectionSource = it))
        }
    }
}

/** Tarjeta de conectores: 3 estados + WebDAV + FTP/SFTP (un solo guardado). */
@Composable
private fun ConnectorCapabilityCard(settings: AppSettings, online: Boolean, onSave: (AppSettings) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var wdUrl by rememberSaveable(settings.webdavLocal) { mutableStateOf(settings.webdavLocal.url) }
    var wdUser by rememberSaveable(settings.webdavLocal) { mutableStateOf(settings.webdavLocal.username) }
    var wdPass by rememberSaveable(settings.webdavLocal) { mutableStateOf(settings.webdavLocal.password) }
    var wdBase by rememberSaveable(settings.webdavLocal) { mutableStateOf(settings.webdavLocal.basePath) }
    var ftpUrl by rememberSaveable(settings.ftpLocal) { mutableStateOf(settings.ftpLocal.url) }
    var ftpUser by rememberSaveable(settings.ftpLocal) { mutableStateOf(settings.ftpLocal.username) }
    var ftpPass by rememberSaveable(settings.ftpLocal) { mutableStateOf(settings.ftpLocal.password) }
    var ftpBase by rememberSaveable(settings.ftpLocal) { mutableStateOf(settings.ftpLocal.basePath) }
    val (status, statusColor) = capStatus(settings, Capability.CONNECTOR_SYNC)
    val fieldMod = Modifier.fillMaxWidth()
    CapabilityCard(stringResource(R.string.cap_connector), Icons.Filled.Cloud, status, statusColor, expanded, { expanded = !expanded }) {
        CapabilitySourceControl(stringResource(R.string.cap_connector_help), Capability.CONNECTOR_SYNC, settings, online) {
            onSave(settings.copy(connectorSyncSource = it))
        }
        Text(stringResource(R.string.cap_section_webdav), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(wdUrl, { wdUrl = it }, label = { Text(stringResource(R.string.cap_field_webdav_url)) }, singleLine = true, modifier = fieldMod)
        OutlinedTextField(wdUser, { wdUser = it }, label = { Text(stringResource(R.string.cap_field_user)) }, singleLine = true, modifier = fieldMod)
        OutlinedTextField(wdPass, { wdPass = it }, label = { Text(stringResource(R.string.cap_field_password)) }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = fieldMod)
        OutlinedTextField(wdBase, { wdBase = it }, label = { Text(stringResource(R.string.cap_field_basepath)) }, singleLine = true, modifier = fieldMod)
        Text(stringResource(R.string.cap_section_ftp), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(ftpUrl, { ftpUrl = it }, label = { Text(stringResource(R.string.cap_field_ftp_url)) }, singleLine = true, modifier = fieldMod)
        OutlinedTextField(ftpUser, { ftpUser = it }, label = { Text(stringResource(R.string.cap_field_user)) }, singleLine = true, modifier = fieldMod)
        OutlinedTextField(ftpPass, { ftpPass = it }, label = { Text(stringResource(R.string.cap_field_password)) }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = fieldMod)
        OutlinedTextField(ftpBase, { ftpBase = it }, label = { Text(stringResource(R.string.cap_field_basepath)) }, singleLine = true, modifier = fieldMod)
        Button(onClick = {
            onSave(settings.copy(
                webdavLocal = WebdavLocalConfig(wdUrl.trim(), wdUser, wdPass, wdBase.trim()),
                ftpLocal = FtpLocalConfig(ftpUrl.trim(), ftpUser, ftpPass, ftpBase.trim()),
            ))
        }, modifier = fieldMod) { Text(stringResource(R.string.settings_save)) }
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
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.field_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.size(12.dp))
                Text(stringResource(R.string.field_color), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.size(6.dp))
                ColorSwatches(color) { color = it }
                Spacer(Modifier.size(12.dp))
                OutlinedTextField(
                    category,
                    { category = it },
                    label = { Text(stringResource(R.string.field_category)) },
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
                    label = { Text(stringResource(R.string.field_tags)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onConfirm(name.trim(), color, parseTags(tagsText), category.trim()) }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
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
                OutlinedTextField(text, { text = it }, label = { Text(stringResource(R.string.field_tags)) }, modifier = Modifier.fillMaxWidth())
                val preview = parseTags(text)
                if (preview.isNotEmpty()) {
                    Spacer(Modifier.size(8.dp))
                    TagChips(preview)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(parseTags(text)) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
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
        title = { Text(stringResource(R.string.action_format)) },
        text = {
            Column {
                for (f in ExportFormat.entries) {
                    TextButton(onClick = { onPick(f) }, modifier = Modifier.fillMaxWidth()) { Text(f.name) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Selector del formato/proporciones de página (presets A4, A5, Moleskine…). */
@Composable
private fun PaperPickerDialog(current: String?, onPick: (PaperPreset) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.paper_picker_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.paper_picker_desc),
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
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
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
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirmText: String = stringResource(R.string.action_delete),
    destructive: Boolean = true,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmText, color = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun MoveDialog(notebooks: List<Notebook>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_move_to_notebook)) },
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
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * "Reproductor" para resolver una hoja con trazos superpuestos: dibuja la página
 * y, con un slider tipo línea de tiempo (trazos ordenados por startTime), elige el
 * punto de corte — lo posterior (naranja) se moverá. Tres salidas: misma página
 * (descartar), nueva hoja o continuar en otro cuaderno.
 */
@Composable
private fun OverlapResolverDialog(c: Capture, vm: MainViewModel, onDismiss: () -> Unit) {
    val sorted = remember(c.strokes) { c.strokes.sortedBy { it.startTime } }
    val o = c.overlap
    val suggested = remember(c) {
        if (o == null) sorted.size
        else sorted.count { it.startTime < o.atTime }.let { if (it == 0) o.strokeIndex else it }.coerceIn(0, sorted.size)
    }
    var cut by remember { mutableIntStateOf(suggested) }
    val moved = sorted.size - cut
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.overlap_dialog_title, c.sobpLabel)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.overlap_dialog_body, o?.overlappingStrokes ?: 0, o?.areaPercent ?: 0),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(
                    Modifier.fillMaxWidth().height(220.dp).background(Color.White, RoundedCornerShape(6.dp)),
                ) {
                    OverlapPreview(sorted, c.paper, cut, Modifier.fillMaxSize().padding(6.dp))
                }
                Slider(
                    value = cut.toFloat(),
                    onValueChange = { cut = it.roundToInt().coerceIn(0, sorted.size) },
                    valueRange = 0f..sorted.size.toFloat().coerceAtLeast(1f),
                )
                Text(
                    stringResource(R.string.overlap_dialog_moved, moved),
                    style = MaterialTheme.typography.bodySmall, color = Color(0xFFEA580C),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { vm.resolveOverlap(c, cut, "newSheet"); onDismiss() },
                        enabled = moved > 0, modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.finish_new_sheet)) }
                    Button(
                        onClick = { vm.resolveOverlap(c, cut, "newNotebook"); onDismiss() },
                        enabled = moved > 0, modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.overlap_dialog_other_notebook)) }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = { vm.resolveOverlap(c, cut, "same"); onDismiss() }) { Text(stringResource(R.string.overlap_dialog_same_page)) }
        },
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

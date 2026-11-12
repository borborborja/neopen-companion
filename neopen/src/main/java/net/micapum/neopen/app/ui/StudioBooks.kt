package net.micapum.neopen.app.ui

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import net.micapum.neopen.app.R
import net.micapum.neopen.app.data.CachedStudioBook
import net.micapum.neopen.core.data.*

@Composable
fun StudioBooksScreen(vm: MainViewModel) {
    val enabled by vm.studioEnabled.collectAsStateWithLifecycle()
    if (!enabled) return
    val rows by vm.studioBooks.collectAsStateWithLifecycle()
    val availability by vm.studioAvailability.collectAsStateWithLifecycle()
    val pens by vm.studioPens.collectAsStateWithLifecycle()
    val conflicts by vm.studioConflicts.collectAsStateWithLifecycle()
    val pendingConflicts by vm.studioPendingConflicts.collectAsStateWithLifecycle()
    var resolving by remember { mutableStateOf<StudioCaptureConflictDto?>(null) }
    var archived by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<String?>(null) }
    var linking by remember { mutableStateOf(false) }
    val visible = rows.filter { it.book.status != "deleted" && (archived || !it.book.archived) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.studio_books_intro), style = MaterialTheme.typography.bodyMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.studio_capacity_title), style = MaterialTheme.typography.titleSmall)
                availability?.let { capacity ->
                    Text(stringResource(R.string.studio_capacity_counts, capacity.available, capacity.used, capacity.total))
                } ?: Text(stringResource(R.string.studio_capacity_unknown))
                Text(stringResource(R.string.studio_created_books, rows.count { it.book.status != "deleted" }))
                Text(stringResource(R.string.studio_capacity_help), style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = archived, onClick = { archived = !archived }, label = { Text(stringResource(R.string.studio_show_archived)) })
            TextButton(onClick = { linking = true }) { Text(stringResource(R.string.studio_link_pen)) }
        }
        if (visible.isEmpty()) Text(stringResource(R.string.studio_empty))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(conflicts, key = { "conflict-${it.id}" }) { conflict ->
            if (conflict.id in pendingConflicts) {
                Text(stringResource(R.string.studio_pending))
                TextButton(onClick = { vm.cancelStudioChange(conflict.id) }) { Text(stringResource(R.string.studio_cancel_pending)) }
            } else OutlinedButton(onClick = { resolving = conflict }) { Text(stringResource(R.string.studio_capture_conflict, conflict.input.name)) }
        }
            items(visible, key = { it.book.id }) { row ->
                Card(Modifier.fillMaxWidth().clickable { selected = row.book.id }) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(row.book.name, style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.studio_summary, row.book.code, row.book.format, row.book.pageCount))
                        Text(if (row.book.penScope.mode == "all") stringResource(R.string.studio_all_future) else stringResource(R.string.studio_selected_count, row.book.penScope.penIds.size))
                        Text(studioStatus(row))
                    }
                }
            }
        }
    }
    rows.firstOrNull { it.book.id == selected }?.let { row ->
        StudioBookDialog(vm, row, rows, pens, onDismiss = { selected = null })
    }
    if (linking) LinkPenDialog(vm, pens) { linking = false }
    resolving?.let { conflict ->
        var pen by remember(conflict.id) { mutableStateOf<String?>(null) }
        var historical by remember(conflict.id) { mutableStateOf<StudioInstanceDto?>(null) }
        val closed = rows.flatMap { row -> row.book.instances.filter { instance ->
            val s = conflict.input.sobp
            instance.closedAt != null && instance.section == s.section && instance.owner == s.owner && instance.book == s.book && s.page < row.book.pageCount
        } }.distinctBy { it.id }
        val destinations = rows.map { it.book }.filter { book ->
            val sobp = conflict.input.sobp
            book.status == "ready" && book.section == sobp.section && book.owner == sobp.owner && book.book == sobp.book && pen?.let(book.penScope::contains) == true
        }
        val destination = destinations.singleOrNull()
        AlertDialog(onDismissRequest = { resolving = null }, title = { Text(stringResource(R.string.studio_conflict)) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.studio_capture_conflict_help))
                Text(conflict.input.name)
                val s = conflict.input.sobp
                Text("${s.section}.${s.owner}.${s.book}.${s.page}")
                pens.forEach { p -> p.id?.let { id ->
                    Row(Modifier.clickable { pen = id; historical = null }) { RadioButton(pen == id, { pen = id; historical = null }); Column {
                        Text(p.alias ?: p.mac, Modifier.padding(top = 12.dp))
                        val targets = rows.map { it.book }.filter { b -> b.status == "ready" && b.section == s.section && b.owner == s.owner && b.book == s.book && b.penScope.contains(id) }
                        Text(if (targets.size == 1) stringResource(R.string.studio_capture_destination, targets.single().name) else stringResource(R.string.studio_capture_no_destination), style = MaterialTheme.typography.bodySmall)
                    } }
                } }
                if (closed.isNotEmpty()) {
                    Text(stringResource(R.string.studio_historical_instances), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.studio_historical_resolution_help))
                    closed.forEach { instance ->
                        Row(Modifier.clickable { historical = instance; pen = null }) {
                            RadioButton(historical?.id == instance.id, { historical = instance; pen = null })
                            Text(stringResource(R.string.studio_historical_instance, instance.name, instance.closedAt.orEmpty()), Modifier.padding(top = 12.dp))
                        }
                    }
                }
                (historical?.name ?: destination?.name)?.let { Text(stringResource(R.string.studio_capture_destination, it)) }
            }
        }, confirmButton = { TextButton(enabled = historical != null || (pen != null && destination != null), onClick = { vm.resolveStudioConflict(conflict.id, pen, historical?.id); resolving = null }) { Text(stringResource(R.string.studio_assign_capture)) } }, dismissButton = { Column { TextButton(onClick = { vm.resolveStudioConflict(conflict.id, null); resolving = null }) { Text(stringResource(R.string.studio_discard_capture)) }; TextButton(onClick = { resolving = null }) { Text(stringResource(R.string.action_cancel)) } } })
    }
}

@Composable
private fun studioStatus(row: CachedStudioBook): String = when {
    row.error != null -> stringResource(R.string.studio_conflict)
    row.pending -> stringResource(R.string.studio_pending)
    row.book.status == "generating" -> stringResource(R.string.studio_generating)
    row.book.status == "failed" -> stringResource(R.string.studio_failed)
    row.book.status == "deleted" -> stringResource(R.string.studio_deleted)
    row.book.archived -> stringResource(R.string.studio_archived)
    row.book.instanceId == null -> stringResource(R.string.studio_closed)
    else -> stringResource(R.string.studio_active)
}

@Composable
private fun StudioBookDialog(vm: MainViewModel, row: CachedStudioBook, rows: List<CachedStudioBook>, pens: List<PenInfoDto>, onDismiss: () -> Unit) {
    val book = row.book
    var renaming by remember { mutableStateOf(false) }
    var assigning by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var previewPage by remember(book.id) { mutableIntStateOf(0) }
    val bitmap by produceState<android.graphics.Bitmap?>(null, book.designAssetId, previewPage) { value = vm.studioPreview(book, previewPage) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var downloading by remember { mutableStateOf(false) }
    val saveLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) scope.launch {
            downloading = true
            vm.downloadStudioBook(book)?.let { file -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } } } }
            downloading = false
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(book.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.studio_summary, book.code, book.format, book.pageCount))
                Text(studioStatus(row))
                if (row.pending) Text(stringResource(R.string.studio_pending_explanation))
                row.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (row.error != null) TextButton(onClick = { vm.cancelStudioChange(book.id) }) { Text(stringResource(R.string.studio_cancel_pending)) }
                bitmap?.let { Image(it.asImageBitmap(), stringResource(R.string.studio_preview), Modifier.fillMaxWidth().heightIn(max = 330.dp)) }
                if (book.designAssetId != null && book.pageCount > 1) Row {
                    TextButton(enabled = previewPage > 0, onClick = { previewPage-- }) { Text(stringResource(R.string.studio_previous)) }
                    Text(stringResource(R.string.studio_page, previewPage + 1, book.pageCount), Modifier.padding(top = 12.dp))
                    TextButton(enabled = previewPage + 1 < book.pageCount, onClick = { previewPage++ }) { Text(stringResource(R.string.studio_next)) }
                }
                Text(stringResource(R.string.studio_sections), style = MaterialTheme.typography.titleSmall)
                book.recipe.cover?.get("title")?.let { Text(stringResource(R.string.studio_cover, it.toString())) }
                book.recipe.sections.forEach { section ->
                    val fallbackLabel = if (section.kind == "template" && section.config?.get("template") == "blank") {
                        stringResource(R.string.studio_blank_sheet)
                    } else section.kind
                    Text(stringResource(when (section.kind) { "pdf" -> R.string.studio_section_pdf; "blank" -> R.string.studio_section_blank; else -> R.string.studio_section_template }, section.label.ifBlank { fallbackLabel }))
                    if (section.kind == "pdf") Text(stringResource(R.string.studio_import_range, section.fromPage ?: 1, section.toPage ?: 1))
                    if (section.kind == "blank") Text(stringResource(R.string.studio_blank_count, section.pageCount ?: 0))
                }
                Text(stringResource(R.string.studio_web_edit))
                Text(stringResource(R.string.studio_assignments), style = MaterialTheme.typography.titleSmall)
                Text(if (book.penScope.mode == "all") stringResource(R.string.studio_all_future) else stringResource(R.string.studio_selected_count, book.penScope.penIds.size))
                pens.forEach { pen ->
                    val id = pen.id ?: return@forEach
                    if (book.penScope.contains(id)) Text(pen.alias ?: pen.mac)
                }
                Text(stringResource(R.string.studio_physical_warning), style = MaterialTheme.typography.bodySmall)
                val closed = book.instances.count { it.closedAt != null }
                if (closed > 0) Text(stringResource(R.string.studio_closed_count, closed))
                if (downloading) LinearProgressIndicator(Modifier.fillMaxWidth())
                Row {
                    TextButton(enabled = book.status == "ready" && !downloading, onClick = { saveLauncher.launch("book-${book.code}.pdf") }) { Text(stringResource(R.string.action_download)) }
                    TextButton(enabled = book.status == "ready" && !downloading, onClick = { scope.launch {
                        downloading = true
                        vm.downloadStudioBook(book)?.let { file ->
                            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
                            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, ctx.getString(R.string.action_share)))
                        }
                        downloading = false
                    } }) { Text(stringResource(R.string.action_share)) }
                    TextButton(enabled = !downloading, onClick = { scope.launch {
                        downloading = true
                        vm.downloadStudioBook(book, true)?.let { file ->
                            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
                            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "application/json"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, ctx.getString(R.string.studio_recipe)))
                        }
                        downloading = false
                    } }) { Text(stringResource(R.string.studio_recipe)) }
                }
                Row {
                    TextButton(enabled = !row.pending && book.status != "deleted", onClick = { renaming = true }) { Text(stringResource(R.string.action_rename)) }
                    TextButton(enabled = !row.pending && book.status != "deleted", onClick = { assigning = true }) { Text(stringResource(R.string.studio_assignments)) }
                }
                TextButton(enabled = !row.pending && book.status != "deleted", onClick = { vm.patchStudioBook(book, archived = !book.archived) }) { Text(stringResource(if (book.archived) R.string.studio_unarchive else R.string.studio_archive)) }
                TextButton(enabled = !row.pending && book.status != "deleted", onClick = { deleting = true }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
    if (renaming) {
        var name by remember { mutableStateOf(book.name) }
        AlertDialog(onDismissRequest = { renaming = false }, title = { Text(stringResource(R.string.action_rename)) }, text = { OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.studio_name)) }) }, confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { vm.patchStudioBook(book, name.trim()); renaming = false }) { Text(stringResource(R.string.action_save)) } }, dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.action_cancel)) } })
    }
    if (assigning) StudioAssignmentsDialog(vm, book, rows, pens) { assigning = false }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text(stringResource(R.string.action_delete)) }, text = { Text(stringResource(R.string.studio_delete_warning)) }, confirmButton = { TextButton(onClick = { vm.deleteStudioBook(book); deleting = false }) { Text(stringResource(R.string.action_delete)) } }, dismissButton = { Column { TextButton(onClick = { vm.patchStudioBook(book, archived = true); deleting = false }) { Text(stringResource(R.string.studio_archive)) }; TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.action_cancel)) } } })
}

@Composable
private fun StudioAssignmentsDialog(vm: MainViewModel, book: StudioBookDto, rows: List<CachedStudioBook>, pens: List<PenInfoDto>, dismiss: () -> Unit) {
    var all by remember { mutableStateOf(book.penScope.mode == "all") }
    var ids by remember { mutableStateOf(book.penScope.penIds.toSet()) }
    var exclusions by remember { mutableStateOf(book.penScope.excludedPenIds.toSet()) }
    var transferConfirm by remember { mutableStateOf(false) }
    val proposed = if (all) StudioPenScopeDto("all", excludedPenIds = exclusions.toList()) else StudioPenScopeDto("selected", penIds = ids.toList())
    val others = rows.map { it.book }.filter { it.id != book.id && it.code == book.code && it.status != "deleted" }
    val conflicts = others.filter { proposed.overlaps(it.penScope) }
    val transferable = conflicts.singleOrNull()?.takeIf { !all && ids.isNotEmpty() && book.penScope.mode == "selected" && book.penScope.penIds.all(ids::contains) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.studio_assignments)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row { Checkbox(all, { all = it }); Text(stringResource(R.string.studio_all_future), Modifier.padding(top = 12.dp)) }
            Text(stringResource(R.string.studio_scope_help))
            pens.forEach { pen -> pen.id?.let { id ->
                val checked = if (all) id !in exclusions else id in ids
                Row { Checkbox(checked, { checkedNow -> if (all) exclusions = if (checkedNow) exclusions - id else exclusions + id else ids = if (checkedNow) ids + id else ids - id }); Text(pen.alias ?: pen.mac, Modifier.padding(top = 12.dp)) }
            } }
            if (pens.isEmpty()) Text(stringResource(R.string.studio_no_pens))
            others.forEach { other ->
                Text(stringResource(R.string.studio_code_used, other.name, pens.filter { p -> p.id?.let(other.penScope::contains) == true }.joinToString { it.alias ?: it.mac }))
            }
            if (conflicts.isNotEmpty()) Text(stringResource(R.string.studio_overlap, conflicts.joinToString { it.name }), color = MaterialTheme.colorScheme.error)
            Text(stringResource(R.string.studio_physical_warning))
        }
    }, confirmButton = {
        if (transferable != null) TextButton(onClick = { transferConfirm = true }) { Text(stringResource(R.string.studio_transfer)) }
        else TextButton(enabled = conflicts.isEmpty(), onClick = { vm.patchStudioBook(book, penScope = proposed); dismiss() }) { Text(stringResource(R.string.action_save)) }
    }, dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.action_cancel)) } })
    if (transferConfirm && transferable != null) AlertDialog(onDismissRequest = { transferConfirm = false }, title = { Text(stringResource(R.string.studio_transfer)) }, text = { Text(stringResource(R.string.studio_transfer_warning, transferable.name, book.name)) }, confirmButton = { TextButton(onClick = { vm.transferStudioPens(book, transferable, ids.toList()); dismiss() }) { Text(stringResource(R.string.studio_transfer)) } }, dismissButton = { TextButton(onClick = { transferConfirm = false }) { Text(stringResource(R.string.action_cancel)) } })
}

@Composable
private fun LinkPenDialog(vm: MainViewModel, pens: List<PenInfoDto>, dismiss: () -> Unit) {
    var selected by remember { mutableStateOf<String?>(null) }
    var identifier by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.studio_link_pen)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.studio_link_help))
            pens.forEach { pen -> pen.id?.let { id -> Row(Modifier.clickable { selected = id }) { RadioButton(selected == id, { selected = id }); Text(pen.alias ?: pen.mac, Modifier.padding(top = 12.dp)) } } }
            OutlinedTextField(identifier, { identifier = it }, label = { Text(stringResource(R.string.studio_identifier)) })
        }
    }, confirmButton = { TextButton(enabled = selected != null && identifier.isNotBlank(), onClick = { vm.linkStudioPen(requireNotNull(selected), identifier); dismiss() }) { Text(stringResource(R.string.action_save)) } }, dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.action_cancel)) } })
}

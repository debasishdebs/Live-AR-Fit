package com.debasish.livefit.phone.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SortByAlpha
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.debasish.livefit.phone.ui.components.ScreenHeader
import com.debasish.livefit.phone.services
import com.debasish.livefit.phone.ui.components.SoftCard
import com.debasish.livefit.phone.ui.components.TextChip
import com.debasish.livefit.phone.ui.theme.LiveFitColors
import kotlinx.coroutines.launch
import org.json.JSONObject

private enum class SortOrder { AZ, ZA }

/**
 * Reusable list screen ("function screen"): the [sourceId] picks the data provider and
 * [filterJson] optionally pre-filters it. Search, status filter and A–Z sort are generic.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(sourceId: String, filterJson: String?, onBack: () -> Unit, onMessage: (String) -> Unit, onOpen: (String) -> Unit = {}) {
    val context = LocalContext.current
    val source = remember(sourceId) { ListSources.create(sourceId, context, context.services, onOpen) }
    val filter = remember(filterJson) { filterJson?.let { runCatching { JSONObject(it) }.getOrNull() } }
    val scope = rememberCoroutineScope()

    var items by remember { mutableStateOf<List<ListItem>?>(null) }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(SortOrder.AZ) }
    var statusFilter by remember { mutableStateOf(filter?.optString("status")?.let { s -> ItemStatus.entries.firstOrNull { it.name == s } }) }
    var showFilters by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf<Pair<ListItem, ItemAction>?>(null) }
    var progress by remember { mutableStateOf<Progress?>(null) }
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(reloadKey) { items = source.load(filter) }
    // Reload when returning from a system settings screen (permissions etc.).
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) reloadKey++ }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    val grouped = source.statusLabels.isNotEmpty()
    val runAction: (ListItem, ItemAction) -> Unit = { item, action ->
        if (action.blocking) progress = Progress(item.title, null)
        scope.launch {
            val result = action.run { p -> if (action.blocking) progress = Progress(item.title, p) }
            progress = null
            when (result) {
                ActionResult.Done -> onMessage("${item.title}: done")
                ActionResult.Silent -> Unit
                is ActionResult.Message -> onMessage(result.text)
                is ActionResult.Scheduled -> onMessage(result.message)
                is ActionResult.Failed -> onMessage(result.message)
            }
            reloadKey++
        }
    }
    val onRow: (ListItem) -> Unit = { row ->
        source.actionFor(row)?.let { action -> if (action.confirmTitle == null) runAction(row, action) else confirming = row to action }
    }

    val visible = items.orEmpty()
        .filter { query.isBlank() || it.title.contains(query, true) || (it.subtitle?.contains(query, true) == true) }
        .filter { statusFilter == null || it.status == statusFilter }
        .let { list -> if (!source.sortable) list else if (sort == SortOrder.AZ) list.sortedBy { it.title.lowercase() } else list.sortedByDescending { it.title.lowercase() } }
    val installed = if (grouped) visible.filter { it.status == ItemStatus.Done || it.status == ItemStatus.InProgress } else emptyList()
    val others = visible - installed.toSet()

    Box(Modifier.fillMaxSize().background(LiveFitColors.SurfaceSoft)) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(source.titleFor(filter), onBack) {
                if (grouped) BadgedBox(badge = { if (statusFilter != null) Badge(containerColor = LiveFitColors.Mint) }) {
                    IconButton(onClick = { showFilters = true }) { Icon(Icons.Rounded.FilterList, contentDescription = "Filter") }
                }
                if (source.sortable) IconButton(onClick = { sort = if (sort == SortOrder.AZ) SortOrder.ZA else SortOrder.AZ }) {
                    Box(contentAlignment = Alignment.BottomEnd) {
                        Icon(Icons.Rounded.SortByAlpha, contentDescription = "Sort")
                        Text(if (sort == SortOrder.AZ) "↓" else "↑", style = MaterialTheme.typography.labelMedium, color = LiveFitColors.MintDeep)
                    }
                }
            }
            SearchBar(query, source.searchHint) { query = it }

            when {
                items == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = LiveFitColors.Mint) }
                visible.isEmpty() -> EmptyState(if (items!!.isEmpty()) "Nothing available on this device" else "No matches")
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                    if (installed.isNotEmpty()) {
                        item { GroupLabel(source.doneSection, installed.size) }
                        item { RowsCard(installed, source, onRow) }
                    }
                    if (others.isNotEmpty()) {
                        item { if (grouped) GroupLabel(source.actionSection, others.size) else Spacer(Modifier.height(12.dp)) }
                        item { RowsCard(others, source, onRow) }
                    }
                }
            }
        }

        confirming?.let { (item, action) ->
            AlertDialog(
                onDismissRequest = { confirming = null },
                icon = { Leading(item, LiveFitColors.ChipSky, 48.dp) },
                title = { Text(action.confirmTitle.orEmpty()) },
                text = { Text(action.confirmMessage) },
                confirmButton = {
                    TextButton(onClick = {
                        confirming = null
                        runAction(item, action)
                    }) { Text(action.confirmLabel, fontWeight = FontWeight.SemiBold, color = LiveFitColors.MintDeep) }
                },
                dismissButton = { TextButton(onClick = { confirming = null }) { Text("Cancel", color = LiveFitColors.InkSoft) } },
                containerColor = Color.White,
            )
        }

        if (showFilters) {
            ModalBottomSheet(onDismissRequest = { showFilters = false }, containerColor = Color.White) {
                Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
                    Text("Filter", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusChip("All", statusFilter == null) { statusFilter = null }
                        source.statusLabels.forEach { (status, label) -> StatusChip(label, statusFilter == status) { statusFilter = status } }
                    }
                }
            }
        }

        progress?.let { ProgressOverlay(it) }
    }
}

private data class Progress(val title: String, val fraction: Float?)

@Composable
private fun SearchBar(value: String, hint: String, onChange: (String) -> Unit) {
    TextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(hint) },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = { if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Rounded.Close, contentDescription = "Clear") } },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.White,
            unfocusedContainerColor = Color.White,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).border(1.dp, LiveFitColors.Line, RoundedCornerShape(28.dp)),
    )
}

@Composable
private fun GroupLabel(text: String, count: Int) {
    Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = LiveFitColors.InkSoft, modifier = Modifier.weight(1f))
        Text("$count", style = MaterialTheme.typography.labelMedium, color = LiveFitColors.InkSoft)
    }
}

@Composable
private fun RowsCard(rows: List<ListItem>, source: ListSource, onClick: (ListItem) -> Unit) {
    SoftCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
        Column {
            rows.forEachIndexed { i, item ->
                ItemRow(item, source) { onClick(item) }
                if (i < rows.lastIndex) Box(Modifier.padding(start = 70.dp).fillMaxWidth().height(1.dp).background(LiveFitColors.Line))
            }
        }
    }
}

@Composable
private fun ItemRow(item: ListItem, source: ListSource, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Leading(item, if (item.status == ItemStatus.Done) LiveFitColors.ChipMint else LiveFitColors.ChipSlate, 40.dp)
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium)
            item.subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = LiveFitColors.InkSoft) }
        }
        item.trailingText?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = LiveFitColors.Ink) }
        item.toggle?.let { androidx.compose.material3.Switch(it, onCheckedChange = null, enabled = source.actionFor(item) != null) }
        StatusBadge(item.status, source)
    }
}

@Composable
private fun Leading(item: ListItem, colors: Pair<Color, Color>, size: androidx.compose.ui.unit.Dp) {
    if (item.icon != null) com.debasish.livefit.phone.ui.components.IconChip(item.icon, colors, size = size)
    else TextChip(item.glyph, colors, size = size)
}

@Composable
private fun StatusBadge(status: ItemStatus, source: ListSource) {
    val label = source.statusLabels[status]
    when (status) {
        ItemStatus.Done -> Icon(Icons.Rounded.CheckCircle, contentDescription = label, tint = LiveFitColors.Mint)
        ItemStatus.InProgress -> Icon(Icons.Rounded.Downloading, contentDescription = label, tint = LiveFitColors.Sky)
        ItemStatus.ActionNeeded -> Box(
            Modifier.size(36.dp).clip(CircleShape).background(LiveFitColors.ChipSky.first),
            contentAlignment = Alignment.Center,
        ) { Icon(source.actionIcon, contentDescription = label, tint = LiveFitColors.ChipSky.second, modifier = Modifier.size(20.dp)) }
        ItemStatus.None -> Unit
    }
}

@Composable
private fun StatusChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = LiveFitColors.ChipMint.first, selectedLabelColor = LiveFitColors.MintDeep),
    )
}

@Composable
private fun EmptyState(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(text, color = LiveFitColors.InkSoft) }
}

/** Full-screen blocking overlay: swallows all touches until the action finishes. */
@Composable
private fun ProgressOverlay(p: Progress) {
    Box(
        Modifier.fillMaxSize().background(Color(0x99000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.clip(RoundedCornerShape(28.dp)).background(Color.White).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (p.fraction == null) {
                    CircularProgressIndicator(Modifier.size(96.dp), color = LiveFitColors.Mint, strokeWidth = 8.dp, trackColor = LiveFitColors.ChipMint.first)
                } else {
                    CircularProgressIndicator(progress = { p.fraction }, modifier = Modifier.size(96.dp), color = LiveFitColors.Mint, strokeWidth = 8.dp, trackColor = LiveFitColors.ChipMint.first)
                    Text("${(p.fraction * 100).toInt()}%", style = MaterialTheme.typography.titleLarge)
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Downloading ${p.title}", style = MaterialTheme.typography.titleMedium)
            Text("Keep the app open", style = MaterialTheme.typography.bodyMedium, color = LiveFitColors.InkSoft)
        }
    }
}

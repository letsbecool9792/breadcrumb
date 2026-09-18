package com.lbc.breadcrumb.ui.debug

import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lbc.breadcrumb.BuildConfig
import com.lbc.breadcrumb.data.FtsQuery
import com.lbc.breadcrumb.data.Memory
import com.lbc.breadcrumb.data.MemoryType
import com.lbc.breadcrumb.data.SyncState
import com.lbc.breadcrumb.net.ServerStatus
import com.lbc.breadcrumb.ui.common.OriginalPreview
import com.lbc.breadcrumb.ui.common.loadOriginalPreview
import com.lbc.breadcrumb.ui.theme.BreadcrumbTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The old debug list, from before the search screens (4.2). Kept for what they
 * do not show -- the server's status, the upload queue, a Sync button, sample
 * rows -- and reached only in debug builds, by long-pressing the wordmark.
 *
 * Uses text glyphs rather than Material icons on purpose -- a throwaway screen
 * is not worth an extra dependency.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryListScreen(viewModel: MemoryListViewModel = viewModel()) {
    val memories by viewModel.memories.collectAsStateWithLifecycle()
    val unsynced by viewModel.unsynced.collectAsStateWithLifecycle()
    val searching = FtsQuery.matchExpression(viewModel.query) != null

    Scaffold(
        topBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
                TopAppBar(
                    title = {
                        Column {
                            Text("Breadcrumb", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = buildString {
                                    append(
                                        when {
                                            searching -> "debug · ${memories.size} matching"
                                            memories.isEmpty() -> "debug · no memories"
                                            else -> "debug · ${memories.size} memories"
                                        }
                                    )
                                    if (unsynced > 0) append(" · $unsynced unsent")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    actions = {
                        if (unsynced > 0) {
                            TextButton(onClick = { viewModel.syncNow() }) {
                                Text("Sync")
                            }
                        }
                        // hidden while searching, where it would read as "clear these results"
                        if (memories.isNotEmpty() && !searching) {
                            TextButton(onClick = { viewModel.clearAll() }) {
                                Text("Clear")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
                ServerStatusLine(
                    status = viewModel.serverStatus,
                    onRecheck = viewModel::checkServer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                )
                SearchField(
                    query = viewModel.query,
                    onQueryChange = viewModel::onQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { viewModel.addSample() }) {
                Text("+", style = MaterialTheme.typography.headlineSmall)
            }
        },
    ) { padding ->
        if (memories.isEmpty()) {
            if (searching) NoMatches(Modifier.padding(padding)) else EmptyState(Modifier.padding(padding))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 88.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(memories, key = { memory -> memory.id }) { memory ->
                    MemoryRow(memory, onDelete = { viewModel.delete(memory) })
                }
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No memories yet", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.size(4.dp))
            Text(
                "Tap + to drop a sample crumb",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Whether the backend answers (step 3.1). Tap to check again. */
@Composable
private fun ServerStatusLine(status: ServerStatus, onRecheck: () -> Unit, modifier: Modifier = Modifier) {
    val server = BuildConfig.SERVER_URL.substringAfter("://")
    val (text, color) = when (status) {
        ServerStatus.Checking -> "server · $server · checking…" to MaterialTheme.colorScheme.onSurfaceVariant
        ServerStatus.Reachable -> "server · $server · reachable" to MaterialTheme.colorScheme.primary
        is ServerStatus.Unreachable ->
            "server · $server · unreachable: ${status.reason}" to MaterialTheme.colorScheme.error
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onRecheck),
    )
}

/**
 * Local keyword search (step 2.2). Matches whole words and word beginnings in
 * titles, shared text and text read from images; ranked search is step 4.2's.
 */
@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text("Search saved text") },
        singleLine = true,
        trailingIcon = {
            if (query.isNotEmpty()) {
                TextButton(onClick = { onQueryChange("") }) { Text("✕") }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        // results are already live; the key only needs to get the keyboard out of the way
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        modifier = modifier,
    )
}

@Composable
private fun NoMatches(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Nothing matches", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.size(4.dp))
            Text(
                "Searches titles, shared text and text read from images",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MemoryRow(memory: Memory, onDelete: () -> Unit) {
    // tap a row to see all of its OCR text, not just the first lines
    var expanded by remember { mutableStateOf(false) }

    Card(
        onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    memory.chips.forEach { TypeChip(it) }
                }
                Spacer(Modifier.size(8.dp))
                Text(
                    text = DateUtils.getRelativeTimeSpanString(
                        memory.capturedAt,
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS,
                    ).toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                SyncDot(memory.syncState)
                Spacer(Modifier.size(8.dp))
                TextButton(onClick = onDelete) {
                    Text("✕", style = MaterialTheme.typography.labelMedium)
                }
            }

            Spacer(Modifier.size(6.dp))

            val context = LocalContext.current
            val preview by produceState<OriginalPreview?>(null, memory.id, memory.localUri) {
                value = if (memory.localUri == null) {
                    null
                } else {
                    withContext(Dispatchers.IO) { loadOriginalPreview(context, memory) }
                }
            }

            preview?.thumbnail?.let { thumb ->
                Image(
                    bitmap = thumb,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .clip(RoundedCornerShape(6.dp)),
                )
                Spacer(Modifier.size(8.dp))
            }

            // What came with the save, kept apart from what OCR read, so the
            // debug list shows which of the two a piece of text came from.
            val sharedText = listOfNotNull(memory.title, memory.summary, memory.rawText)
                .filter { it.isNotBlank() }
                .joinToString("\n")
            val isImage = memory.type == MemoryType.IMAGE

            if (sharedText.isNotEmpty() || !isImage) {
                Text(
                    text = sharedText.ifEmpty { "(no text)" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isImage) {
                if (sharedText.isNotEmpty()) Spacer(Modifier.size(6.dp))
                OcrText(memory.extractedText, expanded)
            }

            // Debug-only facts that prove capture worked: provenance, the stored
            // file and its size, and the content's own creation date.
            val facts = listOfNotNull(
                // label first; the package only for rows captured before v3
                (memory.sourceAppLabel ?: memory.sourceApp)?.let { "from $it" },
                preview?.label,
                memory.contentCreatedAt?.let {
                    "taken " + DateUtils.formatDateTime(
                        context,
                        it,
                        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_YEAR,
                    )
                },
            )
            if (facts.isNotEmpty()) {
                Spacer(Modifier.size(6.dp))
                Text(
                    text = facts.joinToString("  ·  "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** An image's OCR text, or where reading it has got to. */
@Composable
private fun OcrText(extractedText: String?, expanded: Boolean) {
    if (extractedText.isNullOrEmpty()) {
        Text(
            text = if (extractedText == null) "reading text…" else "no text found",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Column {
        Text(
            text = "ocr · ${extractedText.lines().size} lines",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(2.dp))
        Text(
            text = extractedText,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            maxLines = if (expanded) Int.MAX_VALUE else 6,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TypeChip(type: MemoryType) {
    Text(
        text = type.name,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun SyncDot(state: SyncState) {
    val color = when (state) {
        SyncState.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
        SyncState.UPLOADING -> MaterialTheme.colorScheme.primary
        SyncState.SYNCED -> MaterialTheme.colorScheme.primary
        SyncState.FAILED -> MaterialTheme.colorScheme.error
    }
    Box(
        Modifier
            .size(6.dp)
            .clip(CircleShape)
            .background(color),
    )
}

@Preview
@Composable
private fun MemoryRowPreview() {
    BreadcrumbTheme(darkTheme = true) {
        Column(
            Modifier
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp)
        ) {
            MemoryRow(randomSampleMemory(), onDelete = {})
        }
    }
}

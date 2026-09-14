package com.lbc.breadcrumb.ui.debug

import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lbc.breadcrumb.data.Memory
import com.lbc.breadcrumb.data.MemoryType
import com.lbc.breadcrumb.data.SyncState
import com.lbc.breadcrumb.ui.common.OriginalPreview
import com.lbc.breadcrumb.ui.common.loadOriginalPreview
import com.lbc.breadcrumb.ui.theme.BreadcrumbTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Temporary. Exists so saves are visible while the capture surfaces are built
 * in phase 1; the real search-first UI replaces it at step 4.2.
 *
 * Uses text glyphs rather than Material icons on purpose -- a throwaway screen
 * is not worth an extra dependency.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryListScreen(viewModel: MemoryListViewModel = viewModel()) {
    val memories by viewModel.memories.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Breadcrumb", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = if (memories.isEmpty()) {
                                "debug · no memories"
                            } else {
                                "debug · ${memories.size} memories"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    if (memories.isNotEmpty()) {
                        TextButton(onClick = { viewModel.clearAll() }) {
                            Text("Clear")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { viewModel.addSample() }) {
                Text("+", style = MaterialTheme.typography.headlineSmall)
            }
        },
    ) { padding ->
        if (memories.isEmpty()) {
            EmptyState(Modifier.padding(padding))
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

@Composable
private fun MemoryRow(memory: Memory, onDelete: () -> Unit) {
    Card(
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

            Text(
                text = memory.searchableText.ifBlank { "(no text)" },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )

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

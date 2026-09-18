package com.lbc.breadcrumb.ui.capture

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lbc.breadcrumb.R
import com.lbc.breadcrumb.capture.CapturePreview
import com.lbc.breadcrumb.capture.CaptureSummary
import com.lbc.breadcrumb.capture.CaptureUiState
import com.lbc.breadcrumb.data.Memory
import com.lbc.breadcrumb.data.MemoryType
import com.lbc.breadcrumb.ui.common.DocumentGlyph
import com.lbc.breadcrumb.ui.common.OriginalPreview
import com.lbc.breadcrumb.ui.theme.Mono
import com.lbc.breadcrumb.ui.theme.Serif
import com.lbc.breadcrumb.ui.common.loadOriginalPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


private val CardShape = RoundedCornerShape(16.dp)

/**
 * What the sheet says and shows. The thing that was kept comes first and at a
 * useful size; everything about it (kind, source, size) is small mono chrome.
 */
@Composable
internal fun CaptureSheetContent(
    state: CaptureUiState,
    note: String,
    onNoteChange: (String) -> Unit,
    onUndo: () -> Unit,
    onDone: () -> Unit,
) {
    val saved = (state as? CaptureUiState.Saved)?.memories.orEmpty()
    // opened on a tap, never on its own: saving stays one tap, the note optional
    var writing by remember { mutableStateOf(false) }

    Column(
        // Grows smoothly from "Saving…" to the full preview instead of jumping.
        Modifier.fillMaxWidth().animateContentSize(tween(320)),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Header(state, saved)

        when (state) {
            is CaptureUiState.Saved -> {
                // smaller while the keyboard is up, so the sheet still fits above it
                CaptureSummary.previewFor(saved)?.let { Preview(it, compact = writing) }
                NoteField(note, writing, onOpen = { writing = true }, onNoteChange = onNoteChange)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SheetButton(stringResource(R.string.capture_undo), primary = false, onClick = onUndo, modifier = Modifier.weight(1f))
                    SheetButton(stringResource(R.string.capture_done), primary = true, onClick = onDone, modifier = Modifier.weight(1f))
                }
            }
            is CaptureUiState.Failed -> {
                SheetButton(stringResource(R.string.capture_close), primary = false, onClick = onDone, modifier = Modifier.fillMaxWidth())
            }
            CaptureUiState.Working, CaptureUiState.Undone -> Unit
        }
    }
}

@Composable
private fun Header(state: CaptureUiState, saved: List<Memory>) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        CrumbMark(play = state is CaptureUiState.Saved, size = 42.dp)

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = title(state),
                fontFamily = Serif,
                fontSize = 23.sp,
                lineHeight = 27.sp,
                color = colors.onSurface,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            CaptureSummary.subtitle(saved)?.let {
                Text(
                    text = it,
                    fontFamily = Mono,
                    fontSize = 12.sp,
                    color = colors.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun title(state: CaptureUiState): String = when (state) {
    CaptureUiState.Working -> stringResource(R.string.capture_saving)
    is CaptureUiState.Failed -> stringResource(state.message)
    CaptureUiState.Undone -> stringResource(R.string.capture_undone)
    is CaptureUiState.Saved -> when {
        state.attempted <= 1 -> stringResource(R.string.capture_saved)
        state.memories.size == state.attempted -> stringResource(R.string.capture_saved_count, state.memories.size)
        else -> stringResource(R.string.capture_saved_partial, state.memories.size, state.attempted)
    }
}

@Composable
private fun Preview(preview: CapturePreview, compact: Boolean) {
    when (preview) {
        is CapturePreview.Photo -> PhotoPreview(preview, compact)
        is CapturePreview.Files -> FilesPreview(preview)
        is CapturePreview.Document -> DocumentPreview(preview)
        is CapturePreview.Link -> LinkPreview(preview)
        is CapturePreview.Note -> NotePreview(preview, compact)
    }
}

// --- the note ------------------------------------------------------------------

/**
 * The person's own line about what they kept -- who sent it, what it is for.
 * A quiet "add a note" until tapped; then a field, with the keyboard up.
 */
@Composable
private fun NoteField(note: String, writing: Boolean, onOpen: () -> Unit, onNoteChange: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    if (!writing) {
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onOpen)
                .padding(vertical = 4.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("+", fontFamily = Mono, fontSize = 15.sp, color = colors.primary)
            Text(
                text = stringResource(R.string.capture_add_note),
                fontFamily = Mono,
                fontSize = 12.sp,
                color = colors.outline,
            )
        }
        return
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(colors.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        val style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 21.sp)
        if (note.isEmpty()) {
            Text(stringResource(R.string.capture_note_hint), style = style.copy(color = colors.outline))
        }
        BasicTextField(
            value = note,
            onValueChange = onNoteChange,
            textStyle = style.copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            maxLines = 5,
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
    }
}

// --- photo -------------------------------------------------------------------

private val PhotoMinHeight = 160.dp
private val PhotoMaxHeight = 300.dp
private val PhotoCompactHeight = 110.dp

@Composable
private fun PhotoPreview(preview: CapturePreview.Photo, compact: Boolean) {
    val colors = MaterialTheme.colorScheme
    val original = rememberOriginal(preview.memory, targetPx = 900)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val bitmap = original?.thumbnail
            // Follow the photo's own shape, within limits: a landscape shot stays
            // wide, a tall screenshot is capped rather than filling the screen.
            val height = when {
                compact -> PhotoCompactHeight
                bitmap == null -> 220.dp
                else -> (maxWidth * (bitmap.height.toFloat() / bitmap.width)).coerceIn(PhotoMinHeight, PhotoMaxHeight)
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(height)
                    .clip(CardShape)
                    .background(colors.surfaceVariant),
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        // Screenshots are recognised by their top -- the app bar,
                        // the headline -- so crop from there, not from the middle.
                        alignment = Alignment.TopCenter,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        preview.caption?.takeIf { !compact }?.let {
            Text(
                text = it,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// --- several files -------------------------------------------------------------

@Composable
private fun FilesPreview(preview: CapturePreview.Files) {
    val colors = MaterialTheme.colorScheme
    val visible = preview.visible

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        visible.forEachIndexed { index, memory ->
            val isLast = index == visible.lastIndex
            Box(
                Modifier
                    .weight(1f)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (memory.type == MemoryType.PDF) {
                    DocumentGlyph(Modifier.size(width = 34.dp, height = 44.dp))
                } else {
                    val original = rememberOriginal(memory, targetPx = 320)
                    original?.thumbnail?.let {
                        Image(
                            bitmap = it,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            alignment = Alignment.TopCenter,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                if (isLast && preview.overflow > 0) {
                    Box(
                        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.capture_more, preview.overflow),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                        )
                    }
                }
            }
        }
    }
}

// --- document ----------------------------------------------------------------

@Composable
private fun DocumentPreview(preview: CapturePreview.Document) {
    val colors = MaterialTheme.colorScheme
    val original = rememberOriginal(preview.memory, targetPx = 64)

    Column(
        Modifier.fillMaxWidth().clip(CardShape).background(colors.surfaceVariant).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            DocumentGlyph(Modifier.size(width = 40.dp, height = 52.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                Text(
                    text = preview.title ?: stringResource(R.string.capture_document),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 21.sp,
                    color = colors.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                original?.label?.let {
                    Text(text = it, fontFamily = Mono, fontSize = 11.sp, color = colors.outline)
                }
            }
        }
        preview.caption?.let {
            Text(
                text = it,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// --- link ----------------------------------------------------------------------

@Composable
private fun LinkPreview(preview: CapturePreview.Link) {
    val colors = MaterialTheme.colorScheme

    Column(
        Modifier.fillMaxWidth().clip(CardShape).background(colors.surfaceVariant).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        preview.host?.let { host ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(colors.primary))
                Text(
                    text = host,
                    fontFamily = Mono,
                    fontSize = 12.sp,
                    color = colors.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text = preview.headline,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 22.sp,
            color = colors.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        preview.detail?.let {
            Text(
                text = it,
                fontFamily = Mono,
                fontSize = 11.sp,
                color = colors.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// --- note --------------------------------------------------------------------

@Composable
private fun NotePreview(preview: CapturePreview.Note, compact: Boolean) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().clip(CardShape).background(colors.surfaceVariant).padding(16.dp)) {
        Text(
            text = preview.text,
            fontSize = 16.sp,
            lineHeight = 23.sp,
            color = colors.onSurface,
            maxLines = if (compact) 2 else 6,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// --- shared --------------------------------------------------------------------

@Composable
private fun SheetButton(text: String, primary: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier
            .height(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(if (primary) colors.primary else colors.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (primary) colors.onPrimary else colors.onSurface,
        )
    }
}

@Composable
private fun rememberOriginal(memory: Memory, targetPx: Int): OriginalPreview? {
    val context = LocalContext.current
    val original by produceState<OriginalPreview?>(initialValue = null, memory.id, targetPx) {
        if (memory.localUri != null) {
            value = withContext(Dispatchers.IO) { loadOriginalPreview(context, memory, targetPx) }
        }
    }
    return original
}

package com.lbc.breadcrumb.ui.home

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lbc.breadcrumb.R
import com.lbc.breadcrumb.data.Memory
import com.lbc.breadcrumb.data.MemoryType
import com.lbc.breadcrumb.open.OriginalAction
import com.lbc.breadcrumb.open.Originals
import com.lbc.breadcrumb.ui.common.DocumentGlyph
import com.lbc.breadcrumb.ui.common.Thumbnails
import com.lbc.breadcrumb.ui.common.rememberThumbnail
import com.lbc.breadcrumb.ui.theme.AmberBright
import com.lbc.breadcrumb.ui.theme.AmberOnContainer
import com.lbc.breadcrumb.ui.theme.Bone
import com.lbc.breadcrumb.ui.theme.BoneDim
import com.lbc.breadcrumb.ui.theme.Ink
import com.lbc.breadcrumb.ui.theme.InkElevated
import com.lbc.breadcrumb.ui.theme.InkGrabber
import com.lbc.breadcrumb.ui.theme.InkLabel
import com.lbc.breadcrumb.ui.theme.InkMedia
import com.lbc.breadcrumb.ui.theme.InkOutline
import java.time.ZoneId

/**
 * One memory, opened (board 3, step 4.5). The artifact first, at full width --
 * the thing that was saved, not metadata about it. Provenance sits below in
 * mono, so it recedes. One action, and it leaves the app: Breadcrumb's job
 * ends at handing back the original.
 *
 * The content of a [SheetLayer], so a swipe down falls back into the list it
 * came from, and a picture travels in from its tile.
 */
@Composable
internal fun ColumnScope.MemoryDetail(
    result: Result,
    /** The row as it is now: its summary may have been copied back since it was opened. */
    memory: Memory,
    now: Long,
    /** The sheet's own coming and going, which the picture travels within. */
    visibility: AnimatedVisibilityScope,
    onDelete: (Memory) -> Unit,
    /** Writes the person's note; null or blank removes it. */
    onNote: (String?) -> Unit,
) {
    val context = LocalContext.current
    val action = remember(memory) { Originals.actionFor(context, memory) }
    // a search's answer is the freshest; the phone's copy covers a memory opened from the mosaic
    val summary = result.hit?.summary ?: memory.summary
    val readText = result.hit?.readText ?: memory.readText
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }

    Grabber(Modifier.align(Alignment.CenterHorizontally))
    Column(
        Modifier
            .weight(1f, fill = false)
            .verticalScroll(rememberScrollState()),
    ) {
        // A tall screenshot is cropped at 42% of the window, so what the sheet
        // says about it is in view without scrolling.
        Artifact(memory, maxPicture = windowHeight * 0.42f, visibility, onOpen = { Originals.perform(context, action) })
        Column(
            with(visibility) {
                // the words follow the picture in, a beat behind
                Modifier.animateEnterExit(
                    enter = fadeIn(tween(320, delayMillis = 140)) + slideInVertically(tween(420, delayMillis = 100)) { it / 6 },
                    exit = fadeOut(tween(120)),
                )
            }.padding(start = 22.dp, end = 22.dp, top = 22.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Heading(memory, summary)
            NoteSection(memory, onNote)
            Provenance(memory, now)
            FoundText(memory, readText)
        }
    }
    val haptics = LocalHapticFeedback.current
    Actions(
        action = action,
        onOpen = { Originals.perform(context, action) },
        onDelete = {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            onDelete(memory)
        },
        modifier = Modifier.navigationBarsPadding(),
    )
}

@Composable
private fun Grabber(modifier: Modifier) {
    Box(modifier.padding(top = 12.dp, bottom = 10.dp).size(width = 34.dp, height = 4.dp).clip(CircleShape).background(InkGrabber))
}

// --- the artifact ------------------------------------------------------------

@Composable
private fun Artifact(memory: Memory, maxPicture: Dp, visibility: AnimatedVisibilityScope, onOpen: () -> Unit) {
    when (memory.type) {
        MemoryType.IMAGE -> Picture(memory, maxPicture, visibility, onOpen)
        MemoryType.PDF -> Page(memory, visibility, onOpen)
        MemoryType.LINK -> LinkCard(memory, onOpen)
        MemoryType.TEXT, MemoryType.AUDIO -> Note(memory)
    }
}

/**
 * The best picture of a memory to hand at once: the full-size one once it is
 * decoded, and meanwhile the tile's or the row's, already in the cache -- so
 * the picture that flies up into the sheet is never an empty box.
 */
@Composable
private fun sharpest(memory: Memory, targetPx: Int): ImageBitmap? =
    rememberThumbnail(memory, targetPx) ?: Thumbnails.cached(memory, 360) ?: Thumbnails.cached(memory, 140)

/**
 * The picture itself, following its shape within limits; tapping it opens the
 * original too. It travels up from the tile or row it was opened from.
 */
@Composable
private fun Picture(memory: Memory, maxHeight: Dp, visibility: AnimatedVisibilityScope, onOpen: () -> Unit) {
    val picture = sharpest(memory, targetPx = 1080)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val tallest = maxHeight.coerceAtLeast(200.dp)
        val height = picture?.let { (maxWidth * (it.height.toFloat() / it.width)).coerceIn(160.dp, tallest) } ?: 260.dp
        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .background(InkMedia)
                .clickable(onClick = onOpen),
        ) {
            picture?.let {
                Image(
                    it,
                    null,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier.fillMaxSize().travelling(memory.id, visibility),
                )
            }
        }
    }
}

/** A PDF's first page on the media ground, as a sheet of paper. It travels up from its tile too. */
@Composable
private fun Page(memory: Memory, visibility: AnimatedVisibilityScope, onOpen: () -> Unit) {
    val page = sharpest(memory, targetPx = 900)
    Box(
        Modifier
            .fillMaxWidth()
            .height(340.dp)
            .background(InkMedia)
            .clickable(onClick = onOpen)
            .padding(start = 28.dp, end = 28.dp, top = 24.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        if (page != null) {
            Image(
                page,
                null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier
                    .fillMaxSize()
                    .travelling(memory.id, visibility)
                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)),
            )
        } else {
            DocumentGlyph(Modifier.align(Alignment.Center).size(width = 72.dp, height = 94.dp))
        }
    }
}

@Composable
private fun LinkCard(memory: Memory, onOpen: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(InkMedia)
            .clickable(onClick = onOpen)
            .padding(horizontal = 22.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ResultText.host(memory)?.let { host ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(AmberBright))
                Text(host, style = monoStyle(12.sp, AmberBright), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(
            text = ResultText.title(memory),
            style = serif(23.sp, lineHeight = 28.sp),
        )
        memory.rawText?.takeIf { memory.title != null }?.let {
            SelectionContainer {
                Text(it, style = monoStyle(11.sp, InkOutline).copy(lineHeight = 17.sp), maxLines = 6, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A note's original is its own text, so the note is the artifact, whole and selectable. */
@Composable
private fun Note(memory: Memory) {
    Box(Modifier.fillMaxWidth().background(InkMedia).padding(horizontal = 22.dp, vertical = 24.dp)) {
        SelectionContainer {
            Text(
                text = memory.rawText?.trim().orEmpty().ifEmpty { ResultText.title(memory) },
                // opened, a note is read in the serif, as every memory's title is
                style = serif(20.sp, lineHeight = 28.sp),
            )
        }
    }
}

// --- about it ------------------------------------------------------------------

/**
 * What it is, in the reading face. A picture or PDF is named here; a link or
 * note already reads as itself above, so only the model's summary follows it.
 */
@Composable
private fun Heading(memory: Memory, summary: String?) {
    val named = memory.type == MemoryType.IMAGE || memory.type == MemoryType.PDF
    val title = if (named) ResultText.title(memory, summary) else null
    // a caption says more than a title made from its first line
    val caption = memory.rawText?.trim()?.takeIf { named && it.isNotEmpty() && it != title }
    val description = summary?.takeIf { it != title }

    if (title == null && caption == null && description == null) return
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        title?.let {
            Text(it, style = serif(27.sp, lineHeight = 32.sp))
        }
        caption?.let {
            SelectionContainer { Text(it, style = sans(15.sp, lineHeight = 22.sp)) }
        }
        description?.let {
            Text(it, style = sans(15.sp, BoneDim, lineHeight = 22.sp))
        }
    }
}

/**
 * The person's own words about it, beside an amber rule: theirs, not the
 * model's. Tapped, it becomes a field; with none yet, a quiet "add a note".
 * Only the note is editable here, never the memory itself -- this is not a
 * notes app. A draft still open when the sheet closes is kept.
 */
@Composable
private fun NoteSection(memory: Memory, onNote: (String?) -> Unit) {
    var editing by remember(memory.id) { mutableStateOf(false) }
    var draft by remember(memory.id) { mutableStateOf(memory.note.orEmpty()) }
    val keyboard = LocalSoftwareKeyboardController.current

    fun done() {
        if (draft.trim() != memory.note.orEmpty()) onNote(draft)
        editing = false
        keyboard?.hide()
    }

    // closed mid-edit -- Back, a swipe, a tap above -- the draft is still what they meant
    val closing by rememberUpdatedState(if (editing && draft.trim() != memory.note.orEmpty()) draft else null)
    val write by rememberUpdatedState(onNote)
    DisposableEffect(memory.id) {
        onDispose { closing?.let(write) }
    }

    val label = monoStyle(10.sp, InkLabel).copy(letterSpacing = 1.2.sp)
    when {
        editing -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.detail_note).uppercase(), style = label)
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { focus.requestFocus() }
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(InkElevated)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                if (draft.isEmpty()) {
                    Text(stringResource(R.string.capture_note_hint), style = sans(16.sp, InkOutline, lineHeight = 22.sp))
                }
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    textStyle = sans(16.sp, lineHeight = 22.sp),
                    cursorBrush = SolidColor(AmberBright),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            Text(
                text = stringResource(R.string.detail_note_done),
                style = sans(14.sp, AmberBright, FontWeight.SemiBold),
                modifier = Modifier
                    .align(Alignment.End)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(onClick = ::done)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }

        memory.note != null -> Column(
            Modifier.clip(RoundedCornerShape(8.dp)).clickable { editing = true },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.detail_note).uppercase(), style = label)
                Text(stringResource(R.string.detail_note_edit), style = monoStyle(10.sp, AmberBright))
            }
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.width(2.dp).fillMaxHeight().clip(CircleShape).background(AmberBright))
                Text(memory.note, style = sans(16.sp, lineHeight = 23.sp))
            }
        }

        else -> Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { editing = true }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("+", style = monoStyle(14.sp, AmberBright))
            Text(stringResource(R.string.capture_add_note), style = monoStyle(12.sp, BoneDim))
        }
    }
}

/** Where and when it came from, in mono: this is the chrome, so it recedes. */
@Composable
private fun Provenance(memory: Memory, now: Long) {
    val zone = remember { ZoneId.systemDefault() }
    val rows = listOfNotNull(
        stringResource(R.string.detail_saved) to ResultText.saved(memory.capturedAt, now, zone),
        memory.sourceAppLabel?.let { stringResource(R.string.detail_from) to it.lowercase() },
        memory.contentCreatedAt?.let { stringResource(R.string.detail_taken) to ResultText.taken(it, now, zone) },
    )
    Column {
        HorizontalDivider(color = InkElevated)
        rows.forEachIndexed { index, (label, value) ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 11.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(label, style = monoStyle(11.sp, InkOutline))
                Text(value, style = monoStyle(11.sp, BoneDim))
            }
            if (index < rows.lastIndex) HorizontalDivider(color = InkElevated.copy(alpha = 0.7f))
        }
    }
}

/** What made it findable: the words OCR read, and what the model saw in a picture. */
@Composable
private fun FoundText(memory: Memory, readText: String?) {
    val sections = listOfNotNull(
        memory.extractedText?.trim()?.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.detail_text_found) to it },
        readText?.trim()?.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.detail_seen) to it },
    )
    sections.forEach { (label, text) ->
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label.uppercase(), style = monoStyle(10.sp, InkLabel).copy(letterSpacing = 1.2.sp))
            SelectionContainer {
                Text(text, style = monoStyle(11.sp, InkOutline).copy(lineHeight = 18.sp))
            }
        }
    }
}

// --- the one action ----------------------------------------------------------

/**
 * The one action that leaves the app, and beside it, quieter, the one that
 * removes the memory -- a few seconds of undo stand in for a confirmation.
 */
@Composable
private fun Actions(action: OriginalAction, onOpen: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    val enabled = action != OriginalAction.Missing
    val label = stringResource(
        when (action) {
            is OriginalAction.ViewFile -> R.string.detail_open_original
            is OriginalAction.ViewUrl -> R.string.detail_open_link
            is OriginalAction.CopyText -> R.string.detail_copy_text
            OriginalAction.Missing -> R.string.detail_missing
        }
    )
    val ink = if (enabled) AmberOnContainer else InkOutline

    Row(
        modifier.padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .height(52.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(if (enabled) AmberBright else InkElevated)
                .clickable(enabled = enabled, onClick = onOpen),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = sans(15.sp, ink, FontWeight.SemiBold))
            if (action is OriginalAction.ViewFile || action is OriginalAction.ViewUrl) {
                Box(Modifier.width(8.dp))
                LeavesTheApp(ink)
            }
        }

        val delete = stringResource(R.string.detail_delete)
        Box(
            Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(InkElevated)
                .clickable(onClick = onDelete)
                .semantics { contentDescription = delete },
            contentAlignment = Alignment.Center,
        ) {
            Bin(BoneDim)
        }
    }
}

/** A bin: lid, handle, and a body narrowing to its foot. */
@Composable
private fun Bin(color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val u = size.width / 16f
        val stroke = Stroke(width = 1.5f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawLine(color, Offset(2.5f * u, 4f * u), Offset(13.5f * u, 4f * u), strokeWidth = 1.5f * u, cap = StrokeCap.Round)
        drawPath(
            Path().apply {
                moveTo(6f * u, 4f * u)
                lineTo(6.5f * u, 2f * u)
                lineTo(9.5f * u, 2f * u)
                lineTo(10f * u, 4f * u)
            },
            color,
            style = stroke,
        )
        drawPath(
            Path().apply {
                moveTo(3.8f * u, 4f * u)
                lineTo(4.8f * u, 14f * u)
                lineTo(11.2f * u, 14f * u)
                lineTo(12.2f * u, 4f * u)
            },
            color,
            style = stroke,
        )
    }
}

/** The design's "opens elsewhere" mark: a box with an arrow leaving it. */
@Composable
private fun LeavesTheApp(color: Color) {
    Canvas(Modifier.size(15.dp)) {
        val u = size.width / 16f
        val stroke = Stroke(width = 1.6f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val box = Path().apply {
            moveTo(6f * u, 3f * u)
            lineTo(3f * u, 3f * u)
            lineTo(3f * u, 13f * u)
            lineTo(13f * u, 13f * u)
            lineTo(13f * u, 10f * u)
        }
        drawPath(box, color, style = stroke)
        val arrow = Path().apply {
            moveTo(9.5f * u, 2.5f * u)
            lineTo(13.5f * u, 2.5f * u)
            lineTo(13.5f * u, 6.5f * u)
        }
        drawPath(arrow, color, style = stroke)
        drawLine(color, Offset(13f * u, 3f * u), Offset(7.5f * u, 8.5f * u), strokeWidth = 1.6f * u, cap = StrokeCap.Round)
    }
}

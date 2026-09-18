package com.lbc.breadcrumb.ui.home

import android.net.Uri
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeometrySize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lbc.breadcrumb.R
import com.lbc.breadcrumb.ui.common.CrumbTrail
import com.lbc.breadcrumb.ui.common.DocumentGlyph
import com.lbc.breadcrumb.ui.theme.AmberBright
import com.lbc.breadcrumb.ui.theme.AmberOnContainer
import com.lbc.breadcrumb.ui.theme.Bone
import com.lbc.breadcrumb.ui.theme.BoneDim
import com.lbc.breadcrumb.ui.theme.InkBorder
import com.lbc.breadcrumb.ui.theme.InkElevated
import com.lbc.breadcrumb.ui.theme.InkFieldBorder
import com.lbc.breadcrumb.ui.theme.InkMedia
import com.lbc.breadcrumb.ui.theme.InkOutline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** How many photos one pick may bring. */
private const val MAX_PICKED = 10

/**
 * The app's own sheet for keeping something (the "+"): one box to write in,
 * and a photo or PDF to go with it. Deliberately no title, no formatting,
 * nothing to organise -- a place to drop a thought, not a notes app.
 *
 * What is typed is the memory itself when it stands alone; with files picked,
 * it becomes their note, and the box says so.
 */
@Composable
internal fun ColumnScope.Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    attachments: List<Attachment>,
    onAttach: (List<Uri>) -> Unit,
    onDetach: (Attachment) -> Unit,
    keeping: Boolean,
    failed: Boolean,
    onKeep: () -> Unit,
) {
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICKED)) { uris ->
        if (uris.isNotEmpty()) onAttach(uris)
    }
    val pdfs = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) onAttach(uris)
    }
    val focus = remember { FocusRequester() }
    // once the sheet has risen, so the keyboard does not race it up
    LaunchedEffect(Unit) {
        delay(320)
        focus.requestFocus()
    }

    Grabber(Modifier.align(Alignment.CenterHorizontally))
    Column(
        Modifier
            .weight(1f, fill = false)
            .verticalScroll(rememberScrollState())
            .padding(start = 22.dp, end = 22.dp, top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.compose_title), style = serif(26.sp, lineHeight = 30.sp))

        Box(Modifier.fillMaxWidth().heightIn(min = 96.dp)) {
            val style = serif(20.sp, lineHeight = 28.sp)
            if (draft.isEmpty()) {
                Text(
                    stringResource(if (attachments.isEmpty()) R.string.compose_hint else R.string.compose_hint_note),
                    style = style.copy(color = InkOutline),
                )
            }
            BasicTextField(
                value = draft,
                onValueChange = onDraftChange,
                textStyle = style,
                cursorBrush = SolidColor(AmberBright),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }

        if (attachments.isNotEmpty()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                attachments.forEach { AttachmentTile(it, onRemove = { onDetach(it) }) }
            }
        }

        if (failed) {
            Text(stringResource(R.string.compose_failed), style = monoStyle(11.sp, AmberBright))
        }
    }

    Row(
        Modifier
            .navigationBarsPadding()
            .padding(start = 22.dp, end = 22.dp, top = 16.dp, bottom = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AttachChip(stringResource(R.string.compose_photo), onClick = {
            photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }) { PictureGlyph(BoneDim) }
        AttachChip(stringResource(R.string.compose_pdf), onClick = { pdfs.launch(arrayOf("application/pdf")) }) {
            DocumentGlyph(Modifier.size(width = 12.dp, height = 15.dp))
        }
        Spacer(Modifier.weight(1f))
        KeepButton(enabled = (draft.isNotBlank() || attachments.isNotEmpty()) && !keeping, keeping = keeping, onClick = onKeep)
    }
}

@Composable
private fun AttachChip(label: String, onClick: () -> Unit, glyph: @Composable () -> Unit) {
    Row(
        Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(InkElevated)
            .border(1.dp, InkFieldBorder, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) { glyph() }
        Text(label, style = monoStyle(12.sp, BoneDim))
    }
}

@Composable
private fun KeepButton(enabled: Boolean, keeping: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(if (enabled || keeping) AmberBright else InkElevated)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // the trail walks while the files are copied in, as it does while a search is out
        AnimatedVisibility(keeping, enter = fadeIn() + expandHorizontally(), exit = fadeOut() + shrinkHorizontally()) {
            CrumbTrail(Modifier.padding(end = 9.dp).size(15.dp), walking = true)
        }
        Text(
            stringResource(R.string.compose_keep),
            style = sans(15.sp, if (enabled || keeping) AmberOnContainer else InkOutline, FontWeight.SemiBold),
        )
    }
}

/** A picked file before it is kept: its picture, or a page and its name, with a way to take it back out. */
@Composable
private fun AttachmentTile(attachment: Attachment, onRemove: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(Modifier.size(76.dp)) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(shape)
                .background(InkMedia)
                .border(1.dp, InkBorder, shape),
            contentAlignment = Alignment.Center,
        ) {
            val picture = if (attachment.isPdf) null else rememberPicked(attachment, px = 220)
            when {
                picture != null -> Image(picture, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                attachment.isPdf -> Column(
                    Modifier.padding(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    DocumentGlyph(Modifier.size(width = 22.dp, height = 28.dp))
                    attachment.name?.let {
                        Text(it, style = monoStyle(8.sp, BoneDim), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        val remove = stringResource(R.string.compose_remove)
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(22.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.6f))
                .clickable(onClick = onRemove)
                .semantics { contentDescription = remove },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(8.dp)) {
                val stroke = 1.5.dp.toPx()
                drawLine(Bone, Offset(0f, 0f), Offset(size.width, size.height), stroke, StrokeCap.Round)
                drawLine(Bone, Offset(size.width, 0f), Offset(0f, size.height), stroke, StrokeCap.Round)
            }
        }
    }
}

/** A picked picture's thumbnail, straight from its provider. */
@Composable
private fun rememberPicked(attachment: Attachment, px: Int): ImageBitmap? {
    val context = LocalContext.current
    val picture by produceState<ImageBitmap?>(null, attachment.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.loadThumbnail(attachment.uri, Size(px, px), null).asImageBitmap() }
                .getOrNull()
        }
    }
    return picture
}

/** A small framed picture: a frame, a hill and a sun. */
@Composable
private fun PictureGlyph(color: Color) {
    Canvas(Modifier.size(15.dp)) {
        val u = size.width / 16f
        val stroke = Stroke(width = 1.4f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawRoundRect(color, Offset(1.5f * u, 2.5f * u), GeometrySize(13f * u, 11f * u), CornerRadius(2f * u), style = stroke)
        drawPath(
            Path().apply {
                moveTo(2f * u, 12f * u)
                lineTo(6.5f * u, 7.5f * u)
                lineTo(10f * u, 11f * u)
                lineTo(11.5f * u, 9.5f * u)
                lineTo(14f * u, 12f * u)
            },
            color,
            style = stroke,
        )
        drawCircle(color, radius = 1.2f * u, center = Offset(11f * u, 6f * u))
    }
}

/**
 * The "+" beside the search field: the way to keep something without leaving
 * the app. Amber, the one warm thing on the ink besides the search's light.
 */
@Composable
internal fun KeepSomethingButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.compose_open)
    Box(
        modifier
            .size(54.dp)
            .clip(CircleShape)
            .background(AmberBright)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(18.dp)) {
            val stroke = 2.2.dp.toPx()
            drawLine(AmberOnContainer, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), stroke, StrokeCap.Round)
            drawLine(AmberOnContainer, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), stroke, StrokeCap.Round)
        }
    }
}

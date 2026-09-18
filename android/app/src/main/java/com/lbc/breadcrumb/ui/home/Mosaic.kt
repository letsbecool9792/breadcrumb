package com.lbc.breadcrumb.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lbc.breadcrumb.R
import com.lbc.breadcrumb.data.Memory
import com.lbc.breadcrumb.data.MemoryType
import com.lbc.breadcrumb.ui.common.DocumentGlyph
import com.lbc.breadcrumb.ui.common.rememberThumbnail
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.lbc.breadcrumb.ui.theme.AmberBright
import com.lbc.breadcrumb.ui.theme.AmberContainerDark
import com.lbc.breadcrumb.ui.theme.Bone
import com.lbc.breadcrumb.ui.theme.BoneDim
import com.lbc.breadcrumb.ui.theme.Ink
import com.lbc.breadcrumb.ui.theme.InkBorder
import com.lbc.breadcrumb.ui.theme.InkElevated
import com.lbc.breadcrumb.ui.theme.InkMedia
import com.lbc.breadcrumb.ui.theme.InkOutline
import java.time.ZoneId

private val TileShape = RoundedCornerShape(10.dp)

/**
 * Everything kept, newest first, in two columns of mixed-shape tiles (board 1).
 *
 * The app never opens on an empty search box: the mosaic is the reassurance
 * that the saves are in there, and it serves what search cannot -- scanning
 * for something you would recognise but could not describe. A tile's shape
 * says what a thing is before it is read.
 */
@Composable
internal fun Mosaic(
    memories: List<Memory>?,
    now: Long,
    kept: String,
    onOpenDebug: (() -> Unit)?,
    onOpen: (Memory) -> Unit,
) {
    // still loading: draw nothing rather than flash "nothing kept"
    if (memories == null) return
    if (memories.isEmpty()) {
        Column(Modifier.padding(horizontal = 14.dp)) {
            Masthead(kept, onOpenDebug)
            Quiet(stringResource(R.string.archive_empty), stringResource(R.string.archive_empty_hint))
        }
        return
    }

    val grid = rememberLazyStaggeredGridState()
    var mastheadHeight by remember { mutableIntStateOf(0) }
    val collapse by remember {
        derivedStateOf { mastheadCollapse(grid.firstVisibleItemIndex, grid.firstVisibleItemScrollOffset, mastheadHeight) }
    }

    Box(Modifier.fillMaxSize()) {
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(2),
            state = grid,
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 56.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalItemSpacing = 10.dp,
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = MASTHEAD_KEY, span = StaggeredGridItemSpan.FullLine) {
                Masthead(kept, onOpenDebug, Modifier.onSizeChanged { mastheadHeight = it.height })
            }
            items(memories, key = { it.id }) { memory ->
                Tile(memory, now, onClick = { onOpen(memory) })
            }
        }
        if (collapse > 0f) CollapsedMasthead(kept, shown = collapse, Modifier.align(Alignment.TopCenter))
        DateScrubber(grid, memories, Modifier.align(Alignment.CenterEnd))
    }
}

private const val MASTHEAD_KEY = "masthead"

@Composable
private fun Tile(memory: Memory, now: Long, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(TileShape)
            .background(InkElevated)
            .border(1.dp, InkBorder, TileShape)
            .clickable(onClick = onClick),
    ) {
        when (memory.type) {
            MemoryType.IMAGE -> PictureTile(memory, now)
            MemoryType.PDF -> DocumentTile(memory, now)
            MemoryType.LINK -> LinkTile(memory, now)
            MemoryType.TEXT, MemoryType.AUDIO -> NoteTile(memory, now)
        }
    }
}

/**
 * A screenshot or photo, following its own shape within limits and cropped
 * from the top -- a screenshot is recognised by its app bar and headline. Its
 * words sit on the picture itself, over a shade that deepens toward the foot,
 * so the tile is all picture.
 */
@Composable
private fun PictureTile(memory: Memory, now: Long) {
    val picture = rememberThumbnail(memory, targetPx = 360)
    // a caption or title when it came with one; otherwise the model's line about it, once copied back
    val caption = ResultText.firstLine(memory.title ?: memory.rawText) ?: memory.summary

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val height = picture?.let { (maxWidth * (it.height.toFloat() / it.width)).coerceIn(150.dp, 270.dp) } ?: 190.dp
        Box(Modifier.fillMaxWidth().height(height).background(InkMedia)) {
            picture?.let {
                Image(it, null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
            }
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Ink.copy(alpha = 0.6f), Ink.copy(alpha = 0.94f))))
                    .padding(start = 11.dp, end = 11.dp, top = 30.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                caption?.let {
                    Text(
                        text = it,
                        style = sans(13.sp, Bone, FontWeight.Medium, lineHeight = 17.sp),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(ResultText.tileMeta(memory, now), style = metaStyle.copy(color = BoneDim))
            }
        }
    }
}

/** A PDF's first page, standing on its lower edge like a sheet in a tray. */
@Composable
private fun DocumentTile(memory: Memory, now: Long) {
    val page = rememberThumbnail(memory, targetPx = 360)
    Box(
        Modifier
            .fillMaxWidth()
            .height(108.dp)
            .background(InkMedia)
            .padding(start = 13.dp, end = 13.dp, top = 13.dp),
    ) {
        if (page != null) {
            Image(
                page,
                null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)),
            )
        } else {
            DocumentGlyph(Modifier.align(Alignment.Center).size(width = 40.dp, height = 52.dp))
        }
    }
    Caption(memory.title ?: stringResource(R.string.capture_document), ResultText.tileMeta(memory, now))
}

/** A link: the site it leads to, its title in the serif, and when and where. */
@Composable
private fun LinkTile(memory: Memory, now: Long) {
    Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        ResultText.host(memory)?.let { host ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                // the site's initial on a small amber square, standing in for its icon
                Box(
                    Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)).background(AmberContainerDark),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(host.take(1).uppercase(), style = monoStyle(9.sp, AmberBright).copy(fontWeight = FontWeight.Medium))
                }
                Text(host, style = monoStyle(10.sp, BoneDim), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(
            text = ResultText.title(memory),
            style = serif(18.sp, lineHeight = 20.sp),
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
        Text(ResultText.tileMeta(memory, now), style = metaStyle)
    }
}

/**
 * The note is the whole tile, set as a pull-quote: the serif in italic,
 * under an amber opening mark. What someone jotted down reads as something
 * said, not as a form field.
 */
@Composable
private fun NoteTile(memory: Memory, now: Long) {
    Column(
        Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 13.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("“", style = serif(40.sp, AmberBright, lineHeight = 30.sp))
        Text(
            text = memory.rawText?.trim().orEmpty().ifEmpty { ResultText.title(memory) },
            style = serif(19.sp, lineHeight = 22.sp, italic = true),
            maxLines = 7,
            overflow = TextOverflow.Ellipsis,
        )
        Text(ResultText.tileMeta(memory, now), style = metaStyle, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun Caption(title: String?, meta: String) {
    Column(
        Modifier.padding(start = 11.dp, end = 11.dp, top = 9.dp, bottom = 11.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        title?.let {
            Text(
                text = it,
                style = serif(17.sp, lineHeight = 19.sp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(meta, style = metaStyle)
    }
}

/**
 * The month of what is on screen, riding the right edge while the grid moves
 * and fading once it stops -- instead of chopping the grid into "Today / Last
 * week" headers.
 */
@Composable
private fun DateScrubber(grid: LazyStaggeredGridState, memories: List<Memory>, modifier: Modifier) {
    val zone = remember { ZoneId.systemDefault() }
    val month by remember(memories) {
        // item 0 is the masthead, so the tiles start at 1
        derivedStateOf {
            memories.getOrNull((grid.firstVisibleItemIndex - 1).coerceAtLeast(0))?.let { ResultText.monthOf(it.capturedAt, zone) }
        }
    }
    val alpha by animateFloatAsState(
        targetValue = if (grid.isScrollInProgress) 1f else 0f,
        animationSpec = tween(if (grid.isScrollInProgress) 120 else 700),
        label = "scrubber",
    )
    val label = month ?: return

    Row(
        modifier.graphicsLayer { this.alpha = alpha },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.width(16.dp).height(1.dp).background(AmberContainerDark))
        Text(
            text = label.uppercase(),
            style = monoStyle(10.sp, InkOutline).copy(letterSpacing = 1.sp),
            modifier = Modifier.background(Ink).padding(start = 0.dp, end = 12.dp, top = 3.dp, bottom = 3.dp),
        )
    }
}

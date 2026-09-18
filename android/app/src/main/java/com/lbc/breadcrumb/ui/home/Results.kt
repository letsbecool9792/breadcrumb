package com.lbc.breadcrumb.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lbc.breadcrumb.R
import com.lbc.breadcrumb.data.Memory
import com.lbc.breadcrumb.data.MemoryType
import com.lbc.breadcrumb.ui.common.CrumbTrail
import com.lbc.breadcrumb.ui.common.DocumentGlyph
import com.lbc.breadcrumb.ui.common.NoteGlyph
import com.lbc.breadcrumb.ui.common.rememberThumbnail
import com.lbc.breadcrumb.ui.theme.AmberBright
import com.lbc.breadcrumb.ui.theme.Bone
import com.lbc.breadcrumb.ui.theme.BoneDim
import com.lbc.breadcrumb.ui.theme.HitBackground
import com.lbc.breadcrumb.ui.theme.InkBorder
import com.lbc.breadcrumb.ui.theme.InkElevated
import com.lbc.breadcrumb.ui.theme.InkMedia

/**
 * Results as a list, not the mosaic (board 2): browsing wants shape, searching
 * wants reading room for the match fragment.
 */
@Composable
internal fun Results(state: SearchState.Searching, now: Long, onOpen: (Result) -> Unit) {
    // light up what the server searched for -- the phrase less its filter words -- or what was typed
    val words = remember(state.phrase, state.interpretation) {
        ResultText.words(state.interpretation?.query?.takeIf { it.isNotBlank() } ?: state.phrase)
    }

    if (state.results.isEmpty()) {
        when (state.status) {
            // nothing yet, and no answer yet either: the trail walks, large, where results will be
            SearchStatus.RANKING -> CrumbTrail(Modifier.padding(start = 28.dp, top = 36.dp).size(44.dp), walking = true)
            SearchStatus.RANKED -> Quiet(stringResource(R.string.results_nothing), stringResource(R.string.results_nothing_hint))
            SearchStatus.OFFLINE, SearchStatus.UNAVAILABLE ->
                Quiet(stringResource(R.string.results_words_only), stringResource(R.string.results_words_only_hint))
        }
        return
    }

    // Rows that have already arrived in this search do not arrive again --
    // when the ranked answer replaces the word matches, or when one scrolls
    // back into view. A new search starts afresh.
    val arrived = remember(state.phrase) { mutableSetOf<String>() }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 56.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(state.results, key = { _, result -> result.memory.id }) { index, result ->
            // read before this row is recorded as arrived, so its light does not replay either
            val settled = remember { result.memory.id in arrived }
            ResultRow(
                result = result,
                words = words,
                now = now,
                settled = settled,
                // the top hit sits on slightly lifted ground, once the ranking is real
                lifted = index == 0 && state.status == SearchStatus.RANKED,
                onClick = { onOpen(result) },
                modifier = Modifier
                    // a row the ranking moves glides to its new place, so the ranking is seen happening
                    .animateItem(
                        fadeInSpec = null,
                        placementSpec = spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset.VisibilityThreshold),
                        fadeOutSpec = tween(140),
                    )
                    .arriving(result.memory.id, index, arrived),
            )
        }
    }
}

/**
 * A row's first appearance in a search: it rises into place and fades in,
 * a beat after the one above it, so a list reads top to bottom as it lands.
 */
@Composable
private fun Modifier.arriving(id: String, index: Int, arrived: MutableSet<String>): Modifier {
    val progress = remember { Animatable(if (id in arrived) 1f else 0f) }
    LaunchedEffect(id) {
        if (id in arrived) return@LaunchedEffect
        arrived += id
        delay(ARRIVAL_STAGGER_MS * index.coerceAtMost(8))
        progress.animateTo(1f, tween(340, easing = FastOutSlowInEasing))
    }
    return graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 16.dp.toPx()
    }
}

private const val ARRIVAL_STAGGER_MS = 40L

@Composable
private fun ResultRow(
    result: Result,
    words: List<String>,
    now: Long,
    /** Already shown in this search: drawn as it ended, with nothing to play again. */
    settled: Boolean,
    lifted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val memory = result.memory
    // the search's answer when there is one; the phone's copy while only local matches show
    val summary = result.hit?.summary ?: memory.summary
    val readText = result.hit?.readText ?: memory.readText
    val title = ResultText.title(memory, summary)
    val fragment = remember(memory, result.hit, words) {
        ResultText.fragment(listOf(memory.rawText, memory.extractedText, readText, memory.title, summary), words)
    }
    // the matched word warms to amber a moment after its row lands
    val glow = remember { Animatable(if (settled) 1f else 0f) }
    LaunchedEffect(fragment) {
        if (fragment == null || glow.value == 1f) return@LaunchedEffect
        delay(180)
        glow.animateTo(1f, tween(520, easing = FastOutSlowInEasing))
    }

    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (lifted) InkElevated else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RowThumbnail(memory)

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                text = title,
                style = serif(19.sp, lineHeight = 22.sp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            // Why this matched: the words lit where they were found; for a
            // match by meaning alone, the model's one line about the thing.
            val because: AnnotatedString? = when {
                fragment != null -> lit(fragment, glow.value)
                summary != null && summary != title -> AnnotatedString(summary)
                else -> null
            }
            because?.let {
                Text(
                    text = it,
                    style = monoStyle(11.sp, BoneDim).copy(lineHeight = 16.sp),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Text(ResultText.rowMeta(memory, now), style = metaStyle)
        }
    }
}

/**
 * The fragment with its hit lit amber, as the design draws it. [glow] runs
 * 0 to 1 as the light comes up: from the fragment's own colour, unlit, to amber.
 */
private fun lit(fragment: Fragment, glow: Float): AnnotatedString = buildAnnotatedString {
    append(fragment.before)
    withStyle(SpanStyle(color = lerp(BoneDim, AmberBright, glow), background = HitBackground.copy(alpha = glow))) {
        append(fragment.hit)
    }
    append(fragment.after)
}

/** Stands in for the artifact: its picture when it has one, otherwise a glyph of its kind. */
@Composable
private fun RowThumbnail(memory: Memory) {
    val shape = RoundedCornerShape(7.dp)
    Box(
        Modifier
            .size(46.dp)
            .clip(shape)
            .background(InkMedia)
            .border(1.dp, InkBorder, shape),
        contentAlignment = Alignment.Center,
    ) {
        when (memory.type) {
            MemoryType.IMAGE, MemoryType.PDF -> {
                val picture = rememberThumbnail(memory, targetPx = 140)
                when {
                    picture != null -> TravellingPicture(memory.id) { travel ->
                        Image(
                            picture,
                            null,
                            contentScale = ContentScale.Crop,
                            alignment = Alignment.TopCenter,
                            modifier = Modifier.fillMaxSize().then(travel),
                        )
                    }
                    memory.type == MemoryType.PDF -> DocumentGlyph(Modifier.size(width = 24.dp, height = 30.dp))
                }
            }
            MemoryType.LINK -> Text(
                text = ResultText.host(memory)?.take(1)?.uppercase() ?: "↗",
                style = monoStyle(16.sp, AmberBright),
            )
            MemoryType.TEXT, MemoryType.AUDIO -> NoteGlyph(Modifier.padding(9.dp).fillMaxSize())
        }
    }
}

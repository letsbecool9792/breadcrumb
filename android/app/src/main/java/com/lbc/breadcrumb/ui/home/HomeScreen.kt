package com.lbc.breadcrumb.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lbc.breadcrumb.R
import com.lbc.breadcrumb.ui.common.CrumbTrail
import com.lbc.breadcrumb.ui.theme.AmberContainerDark
import com.lbc.breadcrumb.ui.theme.AmberBright
import com.lbc.breadcrumb.ui.theme.Bone
import com.lbc.breadcrumb.ui.theme.BoneDim
import com.lbc.breadcrumb.ui.theme.Ink
import com.lbc.breadcrumb.ui.theme.InkElevated
import com.lbc.breadcrumb.ui.theme.InkFieldBorder
import com.lbc.breadcrumb.ui.theme.InkOutline
import java.time.LocalDate

/**
 * The app, as the design canvas draws it: the mosaic at rest (board 1), a
 * ranked list while searching (board 2), and a memory opened over either
 * (board 3). No navigation anywhere -- there is nowhere to organise into.
 *
 * @param onOpenDebug long-pressing the wordmark opens the old debug list,
 *   which keeps the server status and the sync button. Null outside debug builds.
 */
@Composable
fun HomeScreen(onOpenDebug: (() -> Unit)?, viewModel: HomeViewModel = viewModel()) {
    val memories by viewModel.memories.collectAsStateWithLifecycle()
    val unsent by viewModel.unsent.collectAsStateWithLifecycle()
    val search = viewModel.search
    // one clock per screenful, so ages do not tick apart row by row
    val now = remember(memories, search) { System.currentTimeMillis() }

    // back out of a search before backing out of the app
    BackHandler(enabled = viewModel.query.isNotEmpty()) { viewModel.clear() }

    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .statusBarsPadding(),
    ) {
        TopLine(
            search = search,
            total = memories?.size ?: 0,
            unsent = unsent,
            onOpenDebug = onOpenDebug,
        )

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (search) {
                SearchState.Resting -> Mosaic(memories, now, onOpen = { viewModel.open(Result(it, hit = null)) })
                is SearchState.Searching -> Results(search, now, onOpen = viewModel::open)
            }
            // content fades under the search field rather than stopping abruptly
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Ink))),
            )
        }

        SearchField(
            query = viewModel.query,
            onQueryChange = viewModel::onQueryChange,
            onClear = viewModel::clear,
            modifier = Modifier
                .navigationBarsPadding()
                .imePadding()
                .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp),
        )
    }

    viewModel.opened?.let { MemoryDetail(it, now, onDismiss = viewModel::close) }
}

/** Near-zero chrome: the wordmark and a count at rest, the result counter while searching. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TopLine(search: SearchState, total: Int, unsent: Int, onOpenDebug: (() -> Unit)?) {
    val mono = TextStyle(fontFamily = MonoFamily, fontSize = 11.sp, color = InkOutline)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (search) {
            SearchState.Resting -> {
                Text(
                    text = stringResource(R.string.home_wordmark).uppercase(),
                    style = mono.copy(letterSpacing = 1.5.sp),
                    modifier = if (onOpenDebug == null) Modifier else Modifier.combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                        onLongClick = onOpenDebug,
                    ),
                )
                Text(ResultText.kept(total, unsent), style = mono)
            }
            is SearchState.Searching -> Text(
                text = ResultText.counter(
                    shown = search.results.size,
                    total = total,
                    typed = search.phrase,
                    interpretation = search.interpretation,
                    today = LocalDate.now(),
                    status = when (search.status) {
                        SearchStatus.RANKING -> "searching…"
                        SearchStatus.RANKED -> null
                        SearchStatus.OFFLINE -> "offline · words only"
                        SearchStatus.UNAVAILABLE -> "search busy · words only"
                    },
                ),
                style = mono.copy(letterSpacing = 0.3.sp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Rests at the bottom, in the thumb; rides up on the keyboard while typing.
 * Its edge warms to amber while it has focus.
 */
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(27.dp)

    Row(
        modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(shape)
            .background(InkElevated)
            .border(1.dp, if (focused) AmberContainerDark else InkFieldBorder, shape)
            .padding(start = 20.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        CrumbTrail(Modifier.size(17.dp))

        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(
                    text = stringResource(R.string.search_hint),
                    style = TextStyle(fontFamily = MonoFamily, fontSize = 13.sp, color = InkOutline),
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(fontFamily = MonoFamily, fontSize = 13.sp, color = Bone),
                cursorBrush = SolidColor(AmberBright),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                // results are already live; the key only needs to get the keyboard out of the way
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                interactionSource = interaction,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (query.isNotEmpty()) {
            val clear = stringResource(R.string.search_clear)
            Box(
                Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(InkFieldBorder)
                    .clickable(onClick = onClear)
                    .semantics { contentDescription = clear },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(9.dp)) {
                    val stroke = 1.5.dp.toPx()
                    drawLine(BoneDim, Offset(0f, 0f), Offset(size.width, size.height), stroke, StrokeCap.Round)
                    drawLine(BoneDim, Offset(size.width, 0f), Offset(0f, size.height), stroke, StrokeCap.Round)
                }
            }
        }
    }
}

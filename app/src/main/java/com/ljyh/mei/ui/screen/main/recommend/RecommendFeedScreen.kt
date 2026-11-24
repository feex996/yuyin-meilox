package com.ljyh.mei.ui.screen.main.recommend

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.ljyh.mei.R
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.mediaId
import com.ljyh.mei.data.model.toMediaItem
import com.ljyh.mei.data.model.toMediaMetadata
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.extensions.mediaItems
import com.ljyh.mei.extensions.togglePlayPause
import com.ljyh.mei.playback.PlayerConnection
import com.ljyh.mei.playback.queue.ListQueue
import com.ljyh.mei.source.api.Track
import com.ljyh.mei.ui.component.player.PlayerViewModel
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalOpenFullPlayer
import com.ljyh.mei.ui.local.LocalPageAppearance
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.local.LocalPlayerConnection
import com.ljyh.mei.ui.local.LocalYuyinNavigation
import com.ljyh.mei.ui.navigation.MeiloXImmersivePageHeader
import com.ljyh.mei.ui.screen.Index
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.ui.screen.source.SourceContentViewModel
import com.ljyh.mei.ui.screen.source.SourceTrackActions
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.core.defaultGlassColors
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.icons.SfSymbol
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

private val RecommendBaseColor = Color(0xFF111114)

/**
 * 余音「推荐」：仿汽水音乐的全屏单曲推荐流。每页一首歌，上滑下一首、下滑上一首，停稳即播放；
 * 点封面打开现有全屏播放界面。播放走现有 PlayerConnection/ListQueue，不另写播放器。
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun RecommendFeedScreen(viewModel: RecommendFeedViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val player = LocalPlayerConnection.current
    val nav = LocalNavController.current
    val isCurrent = nav.currentRoute == Screen.Recommend.route
    val setPageAppearance = LocalPageAppearance.current
    val appearanceOwner = remember { Any() }
    DisposableEffect(setPageAppearance, appearanceOwner) {
        setPageAppearance(Screen.Recommend.route, appearanceOwner, true)
        onDispose { setPageAppearance(Screen.Recommend.route, appearanceOwner, null) }
    }
    CompositionLocalProvider(
        LocalGlassColors provides defaultGlassColors(true),
        LocalContentColor provides Color.White,
    ) {
        MeiloXImmersivePageHeader()
        val drawerState = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        val navigation = LocalYuyinNavigation.current
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        val drawerOpening = drawerState.isOpen || drawerState.targetValue == DrawerValue.Open
        // 系统返回键：抽屉打开时先关抽屉。
        BackHandler(enabled = isCurrent && drawerOpening) {
            scope.launch { drawerState.close() }
        }
        // 离开推荐页（切到其他页面/搜索）时抽屉直接收起，回来时不会还开着。
        LaunchedEffect(isCurrent) {
            if (!isCurrent && drawerState.currentValue != DrawerValue.Closed) drawerState.snapTo(DrawerValue.Closed)
        }
        val openDrawer: () -> Unit = { scope.launch { drawerState.open() } }
        val closeThen: (() -> Unit) -> Unit = { action ->
            scope.launch {
                drawerState.close()
                action()
            }
        }
        ModalNavigationDrawer(
            drawerState = drawerState,
            // 关闭状态下不开整屏横滑（避免和竖向翻页、横向手势冲突），只由左边缘右滑或菜单按钮打开；打开后可横滑关闭。
            gesturesEnabled = drawerState.isOpen,
            scrimColor = Color.Black.copy(alpha = 0.45f),
            drawerContent = {
                RecommendDrawerSheet(
                    onRecommend = { scope.launch { drawerState.close() } },
                    onTab = { index -> closeThen { navigation?.openTab?.invoke(index) } },
                    onSearch = { closeThen { navigation?.openSearch?.invoke() } },
                    onShuffle = { closeThen { viewModel.shuffleNew() } },
                )
            },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(RecommendBaseColor)
                    .leftEdgeSwipeToOpen(enabled = isCurrent && !drawerOpening, rtl = rtl, onOpen = openDrawer),
            ) {
                when {
                    state.tracks.isNotEmpty() && player != null -> key(state.providerId, state.batch) {
                        RecommendPager(state, player, viewModel, isCurrent)
                    }
                    state.loading || state.tracks.isNotEmpty() -> RecommendLoading()
                    else -> RecommendFailed(onRetry = viewModel::retry)
                }
                if (isCurrent && !drawerOpening) {
                    // 左边缘让出系统返回手势的一段（Android 每侧最多 200dp），保证右滑能打开抽屉。
                    Box(
                        Modifier
                            .align(Alignment.CenterStart)
                            .width(EdgeSwipeWidth)
                            .height(200.dp)
                            .systemGestureExclusion(),
                    )
                }
                RecommendTopBar(
                    onMenu = openDrawer,
                    onShuffle = viewModel::shuffleNew,
                    onSearch = { navigation?.openSearch?.invoke() },
                )
            }
        }
    }
}

private val EdgeSwipeWidth = 28.dp

/**
 * 只认从左边缘开始、明显向右的拖动：在 Initial 阶段观察，判定为右滑才消费并打开抽屉；
 * 竖向拖动不消费，交给竖向翻页处理。
 */
private fun Modifier.leftEdgeSwipeToOpen(enabled: Boolean, rtl: Boolean, onOpen: () -> Unit): Modifier =
    if (!enabled) this else pointerInput(rtl) {
        val edge = EdgeSwipeWidth.toPx()
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val fromStart = if (rtl) size.width - down.position.x else down.position.x
            if (fromStart > edge) return@awaitEachGesture
            var dx = 0f
            var dy = 0f
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                if (!change.pressed) return@awaitEachGesture
                val delta = change.positionChange()
                dx += if (rtl) -delta.x else delta.x
                dy += delta.y
                if (abs(dy) > slop && abs(dy) >= abs(dx)) return@awaitEachGesture
                if (dx > slop && dx > abs(dy) * 1.5f) {
                    change.consume()
                    onOpen()
                    // 吞掉这次手势剩余的移动，避免页面跟着动。
                    while (true) {
                        val rest = awaitPointerEvent(PointerEventPass.Initial)
                        val c = rest.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                        c.consume()
                        if (!c.pressed) return@awaitEachGesture
                    }
                }
            }
        }
    }

@Composable
private fun RecommendTopBar(onMenu: () -> Unit, onShuffle: () -> Unit, onSearch: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundButton(
            size = 40.dp,
            background = Color.Black.copy(alpha = 0.28f),
            description = stringResource(R.string.recommend_open_menu),
            onClick = onMenu,
        ) {
            SfIcon("line.3.horizontal", contentDescription = null, size = 18.dp, tint = Color.White.copy(alpha = 0.92f))
        }
        Spacer(Modifier.weight(1f))
        // 余音：「换一批」——换一组随机关键词重新生成推荐流。
        RoundButton(
            size = 40.dp,
            background = Color.Black.copy(alpha = 0.28f),
            description = stringResource(R.string.recommend_shuffle),
            onClick = onShuffle,
        ) {
            SfIcon("shuffle", contentDescription = null, size = 18.dp, tint = Color.White.copy(alpha = 0.92f))
        }
        Spacer(Modifier.width(12.dp))
        RoundButton(
            size = 40.dp,
            background = Color.Black.copy(alpha = 0.28f),
            description = stringResource(R.string.recommend_search),
            onClick = onSearch,
        ) {
            SfIcon(SfSymbol.Search, contentDescription = null, size = 18.dp, tint = Color.White.copy(alpha = 0.92f))
        }
    }
}

/** 侧边抽屉：深色半透明，不做实时模糊，保证流畅。 */
@Composable
private fun RecommendDrawerSheet(
    onRecommend: () -> Unit,
    onTab: (Index) -> Unit,
    onSearch: () -> Unit,
    onShuffle: () -> Unit,
) {
    val itemColors = NavigationDrawerItemDefaults.colors(
        selectedContainerColor = Color.White.copy(alpha = 0.14f),
        unselectedContainerColor = Color.Transparent,
        selectedTextColor = Color.White,
        unselectedTextColor = Color.White.copy(alpha = 0.78f),
        selectedIconColor = Color.White,
        unselectedIconColor = Color.White.copy(alpha = 0.78f),
    )
    ModalDrawerSheet(
        modifier = Modifier.width(280.dp),
        drawerShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
        drawerContainerColor = Color(0xFF17171C).copy(alpha = 0.94f),
        drawerContentColor = Color.White,
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.app_name),
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(16.dp))
            DrawerEntries.forEach { index ->
                val selected = index == Index.Recommend
                NavigationDrawerItem(
                    label = { Text(stringResource(index.labelRes), fontSize = 16.sp) },
                    icon = {
                        SfIcon(
                            index.symbol,
                            contentDescription = null,
                            size = 20.dp,
                            tint = if (selected) Color.White else Color.White.copy(alpha = 0.78f),
                        )
                    },
                    selected = selected,
                    onClick = { if (selected) onRecommend() else onTab(index) },
                    colors = itemColors,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
            NavigationDrawerItem(
                label = { Text(stringResource(R.string.app_tab_search), fontSize = 16.sp) },
                icon = {
                    SfIcon(SfSymbol.Search, contentDescription = null, size = 20.dp, tint = Color.White.copy(alpha = 0.78f))
                },
                selected = false,
                onClick = onSearch,
                colors = itemColors,
                modifier = Modifier.padding(vertical = 2.dp),
            )
            NavigationDrawerItem(
                label = { Text(stringResource(R.string.recommend_shuffle), fontSize = 16.sp) },
                icon = {
                    SfIcon("shuffle", contentDescription = null, size = 20.dp, tint = Color.White.copy(alpha = 0.78f))
                },
                selected = false,
                onClick = onShuffle,
                colors = itemColors,
                modifier = Modifier.padding(vertical = 2.dp),
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

private val DrawerEntries = listOf(Index.Recommend, Index.FindMusic, Index.Library, Index.Settings)

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun RecommendPager(
    state: RecommendFeedState,
    player: PlayerConnection,
    viewModel: RecommendFeedViewModel,
    isCurrent: Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val openFullPlayer = LocalOpenFullPlayer.current
    val queueTitle = stringResource(R.string.recommend_queue_title)
    val addedMessage = stringResource(R.string.recommend_added_to_queue)
    val bottomPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding()
    val tracks = state.tracks
    val metadata = remember(tracks) { tracks.map { it.toMediaMetadata() } }
    val currentMetadata = rememberUpdatedState(metadata)
    val pagerState = rememberPagerState(pageCount = { currentMetadata.value.size })
    val queueId = "recommend-feed:${state.providerId}"

    // 当前推荐队列里已有这首就直接跳过去，否则用已加载的整串歌曲重建队列（顺序播放，便于自动跟随翻页）。
    val playAt: (Int) -> Unit = remember(player, queueTitle, queueId) {
        { page ->
            val list = currentMetadata.value
            val target = list.getOrNull(page)
            if (target != null) {
                val index = if (player.queueTitle.value == queueTitle) {
                    player.player.mediaItems.indexOfFirst { it.mediaId == target.mediaId }
                } else -1
                if (index >= 0) {
                    player.player.seekTo(index, 0L)
                    player.player.playWhenReady = true
                } else {
                    player.playQueue(
                        ListQueue(queueId, queueTitle, list.map { it.mediaId to it.toMediaItem() }, startIndex = page),
                        shuffle = false,
                    )
                }
            }
        }
    }

    // 接近末尾时加载更多。
    LaunchedEffect(pagerState, viewModel) {
        snapshotFlow { pagerState.currentPage to pagerState.pageCount }.collect { (page, count) ->
            if (count > 0 && page >= count - 3) viewModel.loadMore()
        }
    }

    // 进入页面 / 翻页停稳后自动播放当前页。进入时若正在播放别的歌曲则不打断，点播放键再切。
    LaunchedEffect(isCurrent, player, playAt) {
        if (!isCurrent) return@LaunchedEffect
        player.service.awaitPlaybackRestore()
        var entering = true
        snapshotFlow { pagerState.settledPage }.collect { page ->
            val list = currentMetadata.value
            val target = list.getOrNull(page) ?: return@collect
            val playingId = player.player.currentMediaItem?.mediaId
            val wasEntering = entering
            entering = false
            if (playingId == target.mediaId) return@collect
            if (wasEntering) {
                val ownIndex = if (player.queueTitle.value == queueTitle) list.indexOfFirst { it.mediaId == playingId } else -1
                if (ownIndex >= 0) {
                    pagerState.scrollToPage(ownIndex)
                    return@collect
                }
                // 「换一批」后，正在播的是旧一批推荐时直接切到新一批第一首；正在播别的歌单则仍不打断。
                if (state.forcePlay && player.queueTitle.value == queueTitle) {
                    playAt(page)
                    return@collect
                }
                if (player.player.playWhenReady && player.player.mediaItemCount > 0) return@collect
            }
            playAt(page)
        }
    }

    // 一首播完自动进入下一首时，页面跟着翻到下一页。
    LaunchedEffect(player, pagerState) {
        var previousId: String? = null
        player.mediaMetadata.collect { current ->
            val id = current?.mediaId
            val list = currentMetadata.value
            val settled = pagerState.settledPage
            if (id != null && previousId != null && previousId == list.getOrNull(settled)?.mediaId &&
                player.queueTitle.value == queueTitle && list.getOrNull(settled + 1)?.mediaId == id &&
                !pagerState.isScrollInProgress
            ) {
                pagerState.animateScrollToPage(settled + 1)
            }
            previousId = id
        }
    }

    val isPlaying by player.isPlaying.collectAsStateWithLifecycle()
    val playingMetadata by player.mediaMetadata.collectAsStateWithLifecycle()
    val playerViewModel: PlayerViewModel = hiltViewModel(key = "recommend-feed-like")
    val like by playerViewModel.like.collectAsStateWithLifecycle()
    val canLike by playerViewModel.canLike.collectAsStateWithLifecycle()
    val settledPage = pagerState.settledPage
    val settledItem = metadata.getOrNull(settledPage)
    LaunchedEffect(settledItem) {
        if (settledItem != null) {
            playerViewModel.mediaMetadata = settledItem
            playerViewModel.getLike(settledItem)
        }
    }
    val actions: SourceContentViewModel = hiltViewModel(key = "recommend-track-actions")
    var selection by remember { mutableStateOf<Pair<Track, Rect>?>(null) }

    Box(Modifier.fillMaxSize()) {
        RecommendBackdrop(settledItem?.coverUrl.orEmpty())
        VerticalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
            key = { index -> currentMetadata.value.getOrNull(index)?.mediaId ?: "page-$index" },
        ) { page ->
            val item = metadata.getOrNull(page)
            if (item != null) {
                val active = page == settledPage
                val ownsPlayback = playingMetadata?.mediaId == item.mediaId
                RecommendPage(
                    item = item,
                    playing = ownsPlayback && isPlaying,
                    liked = active && (like as? Resource.Success)?.data == true,
                    canLike = active && canLike,
                    bottomPadding = bottomPadding,
                    onCover = {
                        if (player.player.currentMediaItem?.mediaId == item.mediaId) openFullPlayer()
                        else scope.launch {
                            playAt(page)
                            delay(350)
                            openFullPlayer()
                        }
                    },
                    onPlayPause = {
                        if (player.player.currentMediaItem?.mediaId == item.mediaId) player.player.togglePlayPause()
                        else playAt(page)
                    },
                    onLike = { if (active) playerViewModel.like(item) },
                    onAddToQueue = {
                        player.addToQueue(item.toMediaItem())
                        Toast.makeText(context, addedMessage, Toast.LENGTH_SHORT).show()
                    },
                    onMore = { bounds -> tracks.getOrNull(page)?.let { selection = it to bounds } },
                    progressPlayer = if (active && ownsPlayback) player else null,
                )
            }
        }
        SourceTrackActions(selection, { selection = null }, actions)
    }
}

private class BoundsHolder { var rect: Rect = Rect.Zero }

@Composable
private fun RecommendPage(
    item: MediaMetadata,
    playing: Boolean,
    liked: Boolean,
    canLike: Boolean,
    bottomPadding: Dp,
    onCover: () -> Unit,
    onPlayPause: () -> Unit,
    onLike: () -> Unit,
    onAddToQueue: () -> Unit,
    onMore: (Rect) -> Unit,
    progressPlayer: PlayerConnection?,
) {
    val context = LocalContext.current
    val cover = remember(item.coverUrl) {
        ImageRequest.Builder(context).data(item.coverUrl.ifBlank { null }).size(720).build()
    }
    val moreBounds = remember { BoundsHolder() }
    val artists = remember(item) { item.artists.joinToString(" / ") { it.name } }
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(start = 28.dp, end = 28.dp, top = 64.dp, bottom = bottomPadding + 12.dp),
    ) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val side = if (maxWidth < maxHeight) maxWidth else maxHeight
            AsyncImage(
                model = cover,
                contentDescription = stringResource(R.string.recommend_open_player),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(side)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White.copy(alpha = 0.08f))
                    .clickable(onClick = onCover),
            )
        }
        Spacer(Modifier.height(22.dp))
        Text(
            text = item.title,
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (artists.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = artists,
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(16.dp))
        if (progressPlayer != null) RecommendProgress(progressPlayer) else Spacer(Modifier.fillMaxWidth().height(3.dp))
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundButton(
                size = 48.dp,
                background = Color.White.copy(alpha = 0.12f),
                description = stringResource(R.string.recommend_like),
                enabled = canLike,
                onClick = onLike,
            ) {
                SfIcon(
                    if (liked) "heart.fill" else "heart",
                    contentDescription = null,
                    size = 20.dp,
                    tint = if (liked) Color(0xFFFF4D6D) else Color.White.copy(alpha = if (canLike) 0.9f else 0.4f),
                )
            }
            RoundButton(
                size = 48.dp,
                background = Color.White.copy(alpha = 0.12f),
                description = stringResource(R.string.recommend_add_to_queue),
                onClick = onAddToQueue,
            ) {
                SfIcon("text.badge.plus", contentDescription = null, size = 20.dp, tint = Color.White.copy(alpha = 0.9f))
            }
            RoundButton(
                size = 64.dp,
                background = Color.White,
                description = stringResource(if (playing) R.string.recommend_pause else R.string.recommend_play),
                onClick = onPlayPause,
            ) {
                SfIcon(
                    if (playing) "pause.fill" else "play.fill",
                    contentDescription = null,
                    size = 24.dp,
                    tint = RecommendBaseColor,
                )
            }
            RoundButton(
                size = 48.dp,
                background = Color.White.copy(alpha = 0.12f),
                description = stringResource(R.string.recommend_open_player),
                onClick = onCover,
            ) {
                SfIcon("music.note.list", contentDescription = null, size = 20.dp, tint = Color.White.copy(alpha = 0.9f))
            }
            RoundButton(
                size = 48.dp,
                background = Color.White.copy(alpha = 0.12f),
                description = stringResource(R.string.recommend_more),
                onClick = { onMore(moreBounds.rect) },
                modifier = Modifier.onGloballyPositioned { moreBounds.rect = it.boundsInWindow() },
            ) {
                SfIcon("ellipsis", contentDescription = null, size = 20.dp, tint = Color.White.copy(alpha = 0.9f))
            }
        }
    }
}

@Composable
private fun RoundButton(
    size: Dp,
    background: Color,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** 进度只在绘制阶段读取，每 0.5 秒更新一次，不触发重组。 */
@Composable
private fun RecommendProgress(player: PlayerConnection) {
    val progress = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(player) {
        while (isActive) {
            val duration = player.player.duration
            progress.floatValue = if (duration > 0) (player.player.currentPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f
            delay(500)
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Color.White.copy(alpha = 0.2f))
            .drawBehind {
                drawRect(Color.White.copy(alpha = 0.9f), size = Size(size.width * progress.floatValue, size.height))
            },
    )
}

/** 背景只在翻页停稳后换一次：小尺寸封面放大模糊 + 渐变压暗，只有这一层模糊。 */
@Composable
private fun RecommendBackdrop(url: String) {
    val context = LocalContext.current
    Crossfade(targetState = url, animationSpec = tween(450), label = "recommend-backdrop") { target ->
        if (target.isNotBlank()) {
            val request = remember(target) { ImageRequest.Builder(context).data(target).size(96).build() }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(48.dp),
            )
        } else {
            Box(Modifier.fillMaxSize())
        }
    }
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                listOf(Color.Black.copy(alpha = 0.35f), Color.Black.copy(alpha = 0.5f), Color.Black.copy(alpha = 0.82f)),
            ),
        ),
    )
}

@Composable
private fun RecommendLoading() {
    Column(
        Modifier.fillMaxSize().statusBarsPadding(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(14.dp))
        Text(stringResource(R.string.recommend_loading), color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
    }
}

@Composable
private fun RecommendFailed(onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 36.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.recommend_failed),
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(18.dp))
        Box(
            Modifier
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.15f))
                .clickable(role = Role.Button, onClick = onRetry)
                .padding(horizontal = 26.dp, vertical = 10.dp),
        ) {
            Text(stringResource(R.string.recommend_retry), color = Color.White, fontSize = 15.sp)
        }
    }
}

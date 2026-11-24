package com.ljyh.mei.ui.screen.main.recommend

import android.widget.Toast
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import com.ljyh.mei.ui.navigation.MeiloXImmersivePageHeader
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.ui.screen.source.SourceContentViewModel
import com.ljyh.mei.ui.screen.source.SourceTrackActions
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.core.defaultGlassColors
import io.github.neoruaa.glasmio.icons.SfIcon
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
        Box(Modifier.fillMaxSize().background(RecommendBaseColor)) {
            when {
                state.tracks.isNotEmpty() && player != null -> key(state.providerId) {
                    RecommendPager(state, player, viewModel, isCurrent)
                }
                state.loading || state.tracks.isNotEmpty() -> RecommendLoading()
                else -> RecommendFailed(onRetry = viewModel::retry)
            }
        }
    }
}

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
            .padding(start = 28.dp, end = 28.dp, top = 20.dp, bottom = bottomPadding + 12.dp),
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

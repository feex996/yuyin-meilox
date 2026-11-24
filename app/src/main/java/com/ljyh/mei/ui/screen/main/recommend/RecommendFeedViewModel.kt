package com.ljyh.mei.ui.screen.main.recommend

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ljyh.mei.source.api.AccountRevisionCapability
import com.ljyh.mei.source.api.ContentType
import com.ljyh.mei.source.api.FeedKind
import com.ljyh.mei.source.api.MusicProvider
import com.ljyh.mei.source.api.PageRequest
import com.ljyh.mei.source.api.SourceCapability
import com.ljyh.mei.source.api.SourceErrorCode
import com.ljyh.mei.source.api.SourceException
import com.ljyh.mei.source.api.SourceFeatureCapability
import com.ljyh.mei.source.api.SourceFeedPage
import com.ljyh.mei.source.api.SourceItem
import com.ljyh.mei.source.api.Track
import com.ljyh.mei.source.api.TrackRef
import com.ljyh.mei.source.core.ProviderRegistry
import com.ljyh.mei.source.core.SourceHomeCacheOwner
import com.ljyh.mei.source.core.SourceHomeFeedCache
import com.ljyh.mei.source.core.SourcePreferences
import com.ljyh.mei.source.core.SourceRouter
import com.ljyh.mei.source.shared.SharedOnlineRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RecommendFeedState(
    val providerId: String = "",
    val tracks: List<Track> = emptyList(),
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val exhausted: Boolean = false,
    val failed: Boolean = false,
)

/**
 * 余音「推荐」单曲流的歌曲来源，始终跟随当前选择的浏览音源：
 * 1. 每日推荐（音源声明了 discovery.dailySongs，例如登录后的网易云）；
 * 2. 该音源首页 feed.home 里的歌曲分区（优先读首页缓存，避免重复请求，GD 接口限频）；
 * 3. 热门关键词搜索兜底，同时用于「加载更多」。
 * 任一来源失败只跳过该来源，全部失败时界面显示提示和重试。
 */
@HiltViewModel
class RecommendFeedViewModel @Inject constructor(
    private val router: SourceRouter,
    private val preferences: SourcePreferences,
    private val registry: ProviderRegistry,
    private val homeCache: SourceHomeFeedCache,
    private val onlineRepository: SharedOnlineRepository,
) : ViewModel() {
    private sealed interface Stage {
        data object Daily : Stage
        data object Feed : Stage
        data class Search(val keyword: String, val offset: Int = 0) : Stage
    }

    private val mutableState = MutableStateFlow(RecommendFeedState())
    val state: StateFlow<RecommendFeedState> = mutableState.asStateFlow()
    private var stages = ArrayDeque<Stage>()
    private val seen = HashSet<TrackRef>()
    private var loadJob: Job? = null
    private var generation = 0L

    init {
        viewModelScope.launch {
            preferences.settings.map { it.preferredBrowseProvider }.distinctUntilChanged().collect { id -> reset(id) }
        }
    }

    fun retry() {
        val id = state.value.providerId
        viewModelScope.launch {
            reset(id.ifBlank { preferences.settings.first().preferredBrowseProvider })
        }
    }

    /** 翻到接近末尾时调用；同一时间只有一个加载任务。 */
    fun loadMore() {
        val current = state.value
        if (current.exhausted || current.tracks.isEmpty() || loadJob?.isActive == true) return
        mutableState.update { it.copy(loadingMore = true) }
        load(generation, current.providerId)
    }

    private fun reset(providerId: String) {
        generation++
        loadJob?.cancel()
        seen.clear()
        stages = ArrayDeque<Stage>().apply {
            add(Stage.Daily)
            add(Stage.Feed)
            KEYWORDS.shuffled().forEach { add(Stage.Search(it)) }
        }
        mutableState.value = RecommendFeedState(providerId = providerId, loading = true)
        load(generation, providerId)
    }

    private fun load(token: Long, providerId: String) {
        loadJob = viewModelScope.launch {
            registry.awaitInitialized()
            val added = mutableListOf<Track>()
            var transportFailures = 0
            while (added.isEmpty() && stages.isNotEmpty() && token == generation) {
                val stage = stages.removeAt(0)
                try {
                    val (tracks, next) = loadStage(providerId, stage)
                    if (next != null) stages.add(0, next)
                    tracks.filterTo(added) { seen.add(it.ref) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    // 网络/限频类失败连续两次就先停下，避免把 GD 等接口的请求额度耗尽；保留剩余来源，重试或下次翻页再继续。
                    val code = (error as? SourceException)?.code
                    if (code == null || code in TRANSPORT_FAILURES) {
                        if (++transportFailures >= 2) break
                    }
                }
            }
            if (token != generation) return@launch
            mutableState.update {
                it.copy(
                    tracks = it.tracks + added,
                    loading = false,
                    loadingMore = false,
                    exhausted = stages.isEmpty(),
                    failed = it.tracks.isEmpty() && added.isEmpty(),
                )
            }
        }
    }

    private suspend fun loadStage(providerId: String, stage: Stage): Pair<List<Track>, Stage?> {
        val provider = registry.provider(providerId) ?: throw SourceException(SourceErrorCode.MISSING_PROVIDER)
        return when (stage) {
            Stage.Daily -> {
                val discovery = (provider as? SourceFeatureCapability)?.features?.get("discovery").orEmpty()
                if (SourceCapability.FEATURES !in provider.capabilities || "dailySongs" !in discovery) emptyList<Track>() to null
                else onlineRepository.screen("recommend-feed", providerId).dailyRecommendations().tracks to null
            }
            Stage.Feed -> feedTracks(providerId, provider) to null
            is Stage.Search -> {
                if (ContentType.TRACK !in router.searchTypes(providerId)) return emptyList<Track>() to null
                val page = router.search(providerId, stage.keyword, ContentType.TRACK, PageRequest(offset = stage.offset, limit = SEARCH_PAGE))
                val tracks = page.items.mapNotNull { (it as? SourceItem.TrackItem)?.track }
                val nextOffset = stage.offset + SEARCH_PAGE
                tracks to stage.copy(offset = nextOffset).takeIf { page.hasMore && tracks.isNotEmpty() && nextOffset < MAX_SEARCH_OFFSET }
            }
        }
    }

    private suspend fun feedTracks(providerId: String, provider: MusicProvider): List<Track> {
        if (SourceCapability.FEED !in provider.capabilities) return emptyList()
        val account = (provider as? AccountRevisionCapability)?.accountRevision?.first()
        val owner = SourceHomeCacheOwner(providerId, provider.instanceId, provider.version, account)
        val page: SourceFeedPage = homeCache.read(owner, provider) ?: router.getFeed(providerId, FeedKind.HOME).also { fresh ->
            // 只缓存完整的首页（多页首页由首页标签自己拼完整后写缓存）。
            if (!fresh.hasMore) {
                try { homeCache.write(owner, fresh) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { }
            }
        }
        return page.sections.flatMap { section -> section.items.mapNotNull { (it as? SourceItem.TrackItem)?.track } }.shuffled()
    }

    private companion object {
        const val SEARCH_PAGE = 20
        const val MAX_SEARCH_OFFSET = 60
        val TRANSPORT_FAILURES = setOf(SourceErrorCode.NETWORK, SourceErrorCode.TIMEOUT, SourceErrorCode.UNAVAILABLE)
        val KEYWORDS = listOf("热歌", "周杰伦", "林俊杰", "陈奕迅", "邓紫棋", "薛之谦", "周深", "Taylor Swift")
    }
}

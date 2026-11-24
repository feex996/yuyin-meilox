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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    /** 每次重新生成（切源 / 换一批 / 重试）加一，界面据此把翻页位置重置到第一首。 */
    val batch: Int = 0,
    /** 由「换一批」生成：进入时若正在播旧一批推荐，直接切到新一批。 */
    val forcePlay: Boolean = false,
)

/**
 * 余音「推荐」单曲流，始终跟随当前选择的浏览音源。每次进入（ViewModel 新建，冷启动必然如此）或点「换一批」
 * 都重新生成一串随机歌曲：
 * - 首批候选池 = 每日推荐（音源声明了 discovery.dailySongs，例如登录后的网易云）
 *   + 该音源首页 feed.home 的歌曲分区（可读首页缓存）
 *   + [FIRST_SEARCHES] 个随机热门关键词的搜索结果（随机翻页位置），合并去重后整体随机打乱；
 * - 加载更多：再取 [MORE_SEARCHES] 个随机关键词（或已有关键词的下一页），去重打乱后追加。
 * 关键词库每次随机洗牌，所以每次启动的组合都不同。GD 接口限频 5 分钟 50 次：首批最多约 3（首页未缓存时）+ 2 次 GD 请求，
 * 加载更多每次 2 次，并且网络/限频类失败连续出现就停下。任一来源失败只跳过该来源，全部失败时界面显示提示和重试。
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
    /** 待用的随机关键词搜索（含已搜关键词的下一页）。 */
    private var searches = ArrayDeque<Stage.Search>()
    private val seen = HashSet<TrackRef>()
    private var loadJob: Job? = null
    private var generation = 0L
    private var firstBatch = true

    init {
        viewModelScope.launch {
            preferences.settings.map { it.preferredBrowseProvider }.distinctUntilChanged().collect { id -> reset(id) }
        }
    }

    fun retry() = regenerate(forcePlay = false)

    /** 「换一批」：换一组随机关键词重新生成。 */
    fun shuffleNew() = regenerate(forcePlay = true)

    private fun regenerate(forcePlay: Boolean) {
        val id = state.value.providerId
        viewModelScope.launch {
            reset(id.ifBlank { preferences.settings.first().preferredBrowseProvider }, forcePlay)
        }
    }

    /** 翻到接近末尾时调用；同一时间只有一个加载任务。 */
    fun loadMore() {
        val current = state.value
        if (current.exhausted || current.tracks.isEmpty() || loadJob?.isActive == true) return
        mutableState.update { it.copy(loadingMore = true) }
        load(generation, current.providerId)
    }

    private fun reset(providerId: String, forcePlay: Boolean = false) {
        generation++
        loadJob?.cancel()
        seen.clear()
        firstBatch = true
        // 每个关键词随机从第 1 或第 2 页开始，同一关键词每次启动拿到的歌也不完全一样。
        searches = ArrayDeque(KEYWORDS.shuffled().map { Stage.Search(it, if (kotlin.random.Random.nextBoolean()) 0 else SEARCH_PAGE) })
        val batch = state.value.batch + 1
        mutableState.value = RecommendFeedState(providerId = providerId, loading = true, batch = batch, forcePlay = forcePlay)
        load(generation, providerId)
    }

    private fun load(token: Long, providerId: String) {
        loadJob = viewModelScope.launch {
            registry.awaitInitialized()
            val added = mutableListOf<Track>()
            var failedRounds = 0
            while (added.isEmpty() && (firstBatch || searches.isNotEmpty()) && token == generation) {
                val round = buildList<Stage> {
                    if (firstBatch) { add(Stage.Daily); add(Stage.Feed) }
                    repeat(if (firstBatch) FIRST_SEARCHES else MORE_SEARCHES) { searches.removeFirstOrNull()?.let { add(it) } }
                }
                firstBatch = false
                val results = coroutineScope {
                    round.map { stage -> async { runCatching { loadStage(providerId, stage) } } }.awaitAll()
                }
                if (token != generation) return@launch
                var transport = 0
                results.forEach { result ->
                    result.onSuccess { (tracks, next) ->
                        tracks.filterTo(added) { seen.add(it.ref) }
                        // 已搜过的关键词下一页放到队尾，先用新关键词，保证每批都有变化。
                        if (next != null) searches.addLast(next)
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        val code = (error as? SourceException)?.code
                        if (code == null || code in TRANSPORT_FAILURES) transport++
                    }
                }
                // 一轮里全部是网络/限频类失败，连续两轮就先停下，避免把 GD 等接口的请求额度耗尽；重试或下次翻页再继续。
                if (added.isEmpty() && transport > 0 && transport == results.count { it.isFailure }) {
                    if (++failedRounds >= 2) break
                }
            }
            if (token != generation) return@launch
            added.shuffle()
            mutableState.update {
                it.copy(
                    tracks = it.tracks + added,
                    loading = false,
                    loadingMore = false,
                    exhausted = searches.isEmpty() && !firstBatch,
                    failed = it.tracks.isEmpty() && added.isEmpty(),
                )
            }
        }
    }

    private suspend fun loadStage(providerId: String, stage: Stage): Pair<List<Track>, Stage.Search?> {
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
        return page.sections.flatMap { section -> section.items.mapNotNull { (it as? SourceItem.TrackItem)?.track } }
    }

    private companion object {
        const val SEARCH_PAGE = 20
        const val MAX_SEARCH_OFFSET = 80
        const val FIRST_SEARCHES = 2
        const val MORE_SEARCHES = 2
        val TRANSPORT_FAILURES = setOf(SourceErrorCode.NETWORK, SourceErrorCode.TIMEOUT, SourceErrorCode.UNAVAILABLE)
        val KEYWORDS = listOf(
            // 风格 / 场景
            "华语", "流行", "热歌", "抖音热歌", "抖音神曲", "新歌", "经典", "经典老歌", "民谣", "摇滚", "说唱", "粤语", "粤语金曲",
            "欧美", "欧美流行", "日语", "韩语", "伤感", "情歌", "轻音乐", "纯音乐", "钢琴", "国风", "古风", "电音", "R&B", "爵士",
            "治愈", "励志", "怀旧", "KTV", "80年代金曲", "90年代金曲", "年代金曲", "影视金曲", "动漫歌曲", "翻唱", "独立音乐",
            // 华语歌手
            "周杰伦", "林俊杰", "陈奕迅", "邓紫棋", "薛之谦", "周深", "五月天", "李荣浩", "张杰", "毛不易", "华晨宇", "汪苏泷",
            "许嵩", "赵雷", "李健", "王菲", "孙燕姿", "张学友", "蔡依林", "陶喆", "王力宏", "梁静茹", "莫文蔚", "刘若英", "田馥甄",
            "张韶涵", "告五人", "单依纯", "刀郎", "伍佰", "朴树", "许巍", "Beyond", "张国荣", "邓丽君", "刘德华", "梅艳芳", "林宥嘉",
            "杨宗纬", "萧敬腾", "韦礼安", "方大同", "王源", "易烊千玺",
            // 外语歌手
            "Taylor Swift", "Ed Sheeran", "Billie Eilish", "Coldplay", "Bruno Mars", "The Weeknd", "Adele", "Justin Bieber",
            "Maroon 5", "Dua Lipa", "Imagine Dragons", "YOASOBI", "米津玄師", "IU", "BLACKPINK", "Aimer",
        )
    }
}

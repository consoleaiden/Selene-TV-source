package org.moontechlab.selenetv.screen.tab

import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.moontechlab.selenetv.model.AdultCategory
import org.moontechlab.selenetv.model.AdultSource
import org.moontechlab.selenetv.model.AdultVideo
import org.moontechlab.selenetv.model.VideoInfo
import org.moontechlab.selenetv.service.ApiService
import org.moontechlab.selenetv.ui.FilterDialog
import org.moontechlab.selenetv.ui.FilterOption
import org.moontechlab.selenetv.ui.FilterSelector
import org.moontechlab.selenetv.ui.SelectorOption
import org.moontechlab.selenetv.ui.VideoCardSource
import org.moontechlab.selenetv.ui.grid.GRID_CARD_WIDTH
import org.moontechlab.selenetv.ui.grid.GRID_COLUMNS
import org.moontechlab.selenetv.ui.grid.GRID_PAGE_LIMIT
import org.moontechlab.selenetv.ui.grid.GridFilterRow
import org.moontechlab.selenetv.ui.grid.MediaGridScaffold

private const val TAG = "AdultTab"

// ==================== 布局常量 ====================

private const val COLUMNS = GRID_COLUMNS            // 每行6列
private const val PAGE_LIMIT = GRID_PAGE_LIMIT      // 每页数据量
private const val HORIZONTAL_PADDING = 58           // 固定左右边距 58dp

data class AdultGridLayout(
    val horizontalPadding: Dp,
    val cardGap: Dp
)

fun calculateAdultGridLayout(screenWidthDp: Float): AdultGridLayout {
    val totalCardWidth = GRID_CARD_WIDTH * COLUMNS
    val totalPadding = HORIZONTAL_PADDING * 2
    val availableForGaps = screenWidthDp - totalCardWidth - totalPadding
    val cardGap = availableForGaps / (COLUMNS - 1)
    return AdultGridLayout(
        horizontalPadding = HORIZONTAL_PADDING.dp,
        cardGap = cardGap.dp
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AdultTab(
    focusRequester: FocusRequester = remember { FocusRequester() },
    scrollState: ScrollState = rememberScrollState(),
    onVideoSelected: (VideoInfo) -> Unit = {}
) {
    val coroutineScope = rememberCoroutineScope()

    // 源站 / 分类 / 视频状态
    var sources by remember { mutableStateOf<List<AdultSource>>(emptyList()) }
    var sourcesLoading by remember { mutableStateOf(false) }
    var selectedSource by remember { mutableStateOf<AdultSource?>(null) }
    var categories by remember { mutableStateOf<List<AdultCategory>>(emptyList()) }
    var selectedCategoryId by remember { mutableStateOf<Int?>(null) }
    var videos by remember { mutableStateOf<List<AdultVideo>>(emptyList()) }
    var videosLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableIntStateOf(1) }
    var total by remember { mutableIntStateOf(0) }
    var hasMore by remember { mutableStateOf(true) }

    // 源站选择弹层
    var showSourceDialog by remember { mutableStateOf(false) }

    // 焦点管理器
    val categoriesFocusRequester = remember { FocusRequester() }
    val contentFocusRequester = remember { FocusRequester() }

    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp.toFloat()
    val gridLayout = remember(screenWidthDp) { calculateAdultGridLayout(screenWidthDp) }

    // 焦点请求安全兜底（目标未组合时 requestFocus 会抛异常）
    fun tryRequest(requester: FocusRequester) {
        try { requester.requestFocus() } catch (_: Exception) {}
    }

    // 加载源站列表并默认选中第一个
    // 注意：ApiService 是阻塞式 HTTP（必须 IO 线程），rememberCoroutineScope 在主线程
    fun loadSourcesAndInit() {
        if (sourcesLoading) return
        coroutineScope.launch {
            sourcesLoading = true
            errorMessage = null
            try {
                val list = withContext(Dispatchers.IO) { ApiService.getAdultSources() }
                sources = list
                sourcesLoading = false
                if (list.isEmpty()) {
                    errorMessage = "暂无可用的 18+ 源站"
                } else {
                    selectedSource = list.first()
                }
            } catch (e: Exception) {
                sourcesLoading = false
                errorMessage = e.message ?: "加载源站列表失败"
                Log.e(TAG, "加载源站列表失败", e)
            }
        }
    }

    // 切换源站：仅更新选中源站，分类/视频的加载由下方 Effect 联动完成
    fun switchSource(key: String) {
        val src = sources.firstOrNull { it.key == key } ?: return
        selectedSource = src
    }

    // 源站变化（含进入 tab 首次选中）：重置并加载该源站的分类列表，选中第一个分类
    // 视频列表由再下方的 Effect（selectedCategoryId 变化）联动加载
    LaunchedEffect(selectedSource?.key) {
        val src = selectedSource ?: return@LaunchedEffect
        categories = emptyList()
        selectedCategoryId = null
        videos = emptyList()
        page = 1
        total = 0
        hasMore = false
        try {
            val cats = withContext(Dispatchers.IO) { ApiService.getAdultCategories(src.key) }
            categories = cats
            if (cats.isEmpty()) {
                errorMessage = "该源站暂无分类数据"
            } else {
                errorMessage = null
                selectedCategoryId = cats.first().typeId
            }
        } catch (e: Exception) {
            errorMessage = e.message ?: "加载分类失败"
            Log.e(TAG, "加载分类失败: ${src.key}", e)
        }
    }

    // 源站或分类变化时重置并加载视频第一页
    LaunchedEffect(selectedSource?.key, selectedCategoryId) {
        val src = selectedSource ?: return@LaunchedEffect
        if (selectedCategoryId == null) return@LaunchedEffect
        videos = emptyList()
        page = 1
        hasMore = true
        videosLoading = true
        errorMessage = null
        try {
            val resp = withContext(Dispatchers.IO) {
                ApiService.getAdultVideos(src.key, selectedCategoryId, 1, PAGE_LIMIT)
            }
            videos = resp.list
            total = resp.total
            page = 2
            hasMore = resp.list.isNotEmpty() && videos.size < resp.total
        } catch (e: Exception) {
            errorMessage = e.message ?: "加载失败"
            Log.e(TAG, "加载视频列表失败", e)
        } finally {
            videosLoading = false
        }
    }

    // 加载更多（聚焦最后一行触发；loading/hasMore 由调用点 guard）
    fun loadMore() {
        val src = selectedSource ?: return
        if (videosLoading || !hasMore) return
        coroutineScope.launch {
            videosLoading = true
            try {
                val resp = withContext(Dispatchers.IO) {
                    ApiService.getAdultVideos(src.key, selectedCategoryId, page, PAGE_LIMIT)
                }
                videos = videos + resp.list
                total = resp.total
                page += 1
                hasMore = resp.list.isNotEmpty() && videos.size < resp.total
            } catch (e: Exception) {
                Log.e(TAG, "加载更多失败", e)
            } finally {
                videosLoading = false
            }
        }
    }

    // 进入 tab 时拉源站列表
    LaunchedEffect(Unit) {
        loadSourcesAndInit()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        MediaGridScaffold(
            items = videos,
            isLoading = videosLoading,
            errorMessage = errorMessage,
            scrollState = scrollState,
            horizontalPadding = gridLayout.horizontalPadding,
            cardGap = gridLayout.cardGap,
            contentFocusRequester = contentFocusRequester,
            resetKey = listOf(selectedSource?.key, selectedCategoryId),
            itemToVideoInfo = { it.toVideoInfo(selectedSource?.key ?: "", selectedSource?.name ?: "") },
            itemToSource = { VideoCardSource.SOURCE },
            onItemClick = { video ->
                selectedSource?.let { src ->
                    onVideoSelected(video.toVideoInfo(src.key, src.name))
                }
            },
            onRetry = { loadSourcesAndInit() },
            onLoadMore = { loadMore() },
            onContentNavigateUp = { tryRequest(categoriesFocusRequester) },
            filterSection = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 11.dp, bottom = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    GridFilterRow(label = "源站") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // 「源站列表」按钮：进入 tab 的首个焦点目标
                            // DOWN：有分类时移到分类行；无分类时不消费事件，
                            // 让焦点自然落到下方网格（如错误态的「重试」按钮）
                            SourceListButton(
                                onClick = {
                                    if (sources.isNotEmpty()) showSourceDialog = true
                                },
                                modifier = Modifier.focusRequester(focusRequester),
                                onNavigateDown = {
                                    if (categories.isNotEmpty()) {
                                        tryRequest(categoriesFocusRequester)
                                        true
                                    } else false
                                }
                            )

                            // 按钮后面：当前源站的分类列表
                            if (categories.isNotEmpty()) {
                                FilterSelector(
                                    options = categories.map { FilterOption(it.typeId.toString(), it.typeName) },
                                    selectedKey = selectedCategoryId?.toString() ?: "",
                                    onSelect = { key -> selectedCategoryId = key.toIntOrNull() },
                                    focusRequester = categoriesFocusRequester,
                                    modifier = Modifier.onPreviewKeyEvent { keyEvent ->
                                        if (keyEvent.type == KeyEventType.KeyDown) {
                                            when (keyEvent.key) {
                                                Key.DirectionUp -> {
                                                    tryRequest(focusRequester)
                                                    true
                                                }
                                                Key.DirectionDown -> {
                                                    tryRequest(contentFocusRequester)
                                                    true
                                                }
                                                else -> false
                                            }
                                        } else false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        )

        // 源站选择弹层（复用既有 FilterDialog：D-pad 导航 + 选中项自动聚焦）
        FilterDialog(
            visible = showSourceDialog,
            title = "选择源站",
            options = sources.map { SelectorOption(it.key, it.name) },
            selectedValue = selectedSource?.key ?: "",
            onSelect = { key -> switchSource(key) },
            onClose = { showSourceDialog = false }
        )
    }
}

/**
 * 「源站列表」按钮：与 FilterSelector 选项片同款视觉（30dp 高、圆角胶囊）。
 */
@Composable
private fun SourceListButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigateDown: () -> Boolean = { false }
) {
    var isFocused by remember { mutableStateOf(false) }

    Surface(
        onClick = onClick,
        modifier = modifier
            .onFocusChanged { isFocused = it.isFocused }
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    when (keyEvent.key) {
                        Key.DirectionDown, Key.NavigateNext -> onNavigateDown()
                        Key.DirectionUp, Key.NavigatePrevious -> true // 边界陷阱
                        else -> false
                    }
                } else false
            },
        shape = ClickableSurfaceDefaults.shape(shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.2f),
            focusedContainerColor = Color.White
        )
    ) {
        Box(
            modifier = Modifier
                .height(30.dp)
                .padding(horizontal = 13.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "源站列表",
                color = if (isFocused) Color(0xFF0A0A0A) else Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.W600
            )
        }
    }
}

@Preview(device = Devices.TV_1080p)
@Composable
fun AdultTabPreview() {
    AdultTab()
}

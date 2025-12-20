package com.workforboss.music

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.PaddingValues
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import javax.swing.JFileChooser
import com.workforboss.music.sources.SourceChain
import com.workforboss.music.sources.Track
import io.kamel.image.KamelImage
import io.kamel.image.asyncPainterResource
import androidx.compose.ui.layout.ContentScale

@Composable
fun MusicPlayerTab() {
    // 继承 Main.kt 的主题色，不在此处重新定义 darkColors，除非需要局部覆盖
    MusicPlayerContent()
}

@Composable
private fun MusicPlayerContent() {
    val scope = rememberCoroutineScope()
    val dark = MaterialTheme.colors.isLight.not()
    val chain = remember { SourceChain() }
    var searchText by remember { mutableStateOf("") }
    
    val focusRequester = remember { FocusRequester() }

    // 分平台搜索状态
    val sources = listOf("all", "netease", "qq", "kugou", "kuwo", "migu", "itunes")
    var selectedTab by remember { mutableStateOf(0) }
    val sourceResults = remember { mutableStateMapOf<String, List<Track>>() }
    val sourceLoading = remember { mutableStateMapOf<String, Boolean>() }
    val sourceError = remember { mutableStateMapOf<String, String?>() }

    var searchLoading by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var lyrics by remember { mutableStateOf<String?>(null) }
    var coverUrl by remember { mutableStateOf<String?>(null) }

    var library by remember { 
        val loaded = Storage.loadLibrary()
        mutableStateOf(if (loaded.playlists.isEmpty()) {
            PlaylistManager.createPlaylist("默认列表", loaded)
        } else loaded)
    }
    val player = remember { AudioPlayer() }
    val isPlaying by player.isPlaying.collectAsState()
    val posSec by player.positionSec.collectAsState()
    val durSec by player.durationSec.collectAsState()
    val playerError by player.error.collectAsState()
    var selectedPlaylistId by remember {
        mutableStateOf(library.playlists.firstOrNull()?.id)
    }

    DisposableEffect(Unit) {
        scope.launch {
            focusRequester.requestFocus()
        }
        onDispose {
            player.dispose()
            Storage.saveLibrary(library)
        }
    }

    // 进度自动轮询已移至 AudioPlayer 内部，这里不再需要 LaunchedEffect

    // 提取搜索逻辑为函数
    val performSearch = {
        if (searchText.isNotBlank() && !searchLoading) {
            scope.launch(Dispatchers.IO) {
                searchLoading = true
                msg = null
                sources.forEach { 
                    sourceResults[it] = emptyList()
                    sourceLoading[it] = it != "all"
                    sourceError[it] = null
                }
                try {
                    val jobs = sources.filter { it != "all" }.map { name ->
                        async {
                            runCatching {
                                val res = chain.searchBySource(name, searchText.trim())
                                sourceResults[name] = res
                                sourceLoading[name] = false
                            }.onFailure {
                                sourceError[name] = it.message
                                sourceLoading[name] = false
                            }
                        }
                    }
                    jobs.awaitAll()
                    val allResults = sources.filter { it != "all" }.flatMap { sourceResults[it] ?: emptyList() }
                    sourceResults["all"] = allResults
                    if (allResults.isEmpty()) msg = "未找到相关结果"
                } catch (e: Exception) {
                    msg = "搜索失败: ${e.message}"
                }
                searchLoading = false
            }
        }
    }

    Scaffold(
        topBar = {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = searchText, onValueChange = { searchText = it },
                    label = { Text("搜索歌曲/歌手/专辑") },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester)
                        .onPreviewKeyEvent { event ->
                            if (event.key == Key.Enter && event.type == KeyEventType.KeyDown) {
                                performSearch()
                                true
                            } else {
                                false
                            }
                        }
                )
                Spacer(Modifier.width(8.dp))
                Button(enabled = !searchLoading, onClick = { performSearch() }) { 
                    Text(if (searchLoading) "搜索中..." else "全平台搜索") 
                }
                // 移除了手动切换主题按钮，因为 Main.kt 已经统一了主题
            }
        }
    ) { inner ->
        Row(Modifier.fillMaxSize().padding(inner)) {
            // 左侧：搜索结果 (Card 风格)
            Column(Modifier.weight(1.8f).fillMaxHeight().padding(12.dp)) {
                Card(
                    modifier = Modifier.fillMaxSize(),
                    elevation = 4.dp,
                    shape = RoundedCornerShape(16.dp),
                    backgroundColor = MaterialTheme.colors.surface
                ) {
                    Column(Modifier.fillMaxSize().padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "搜索结果",
                                style = MaterialTheme.typography.h6,
                                color = MaterialTheme.colors.primary
                            )
                            Spacer(Modifier.weight(1f))
                            if (searchLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            }
                        }
                        
                        Spacer(Modifier.height(8.dp))
                        
                        // 平台切换 Tab (优化样式)
                        ScrollableTabRow(
                            selectedTabIndex = selectedTab,
                            backgroundColor = Color.Transparent,
                            contentColor = MaterialTheme.colors.primary,
                            edgePadding = 0.dp,
                            indicator = { tabPositions ->
                                TabRowDefaults.Indicator(
                                    Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                                    height = 2.dp,
                                    color = MaterialTheme.colors.primary
                                )
                            },
                            divider = {}
                        ) {
                            sources.forEachIndexed { index, name ->
                                val isSelected = selectedTab == index
                                Tab(
                                    selected = isSelected,
                                    onClick = { selectedTab = index },
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                name.uppercase(),
                                                style = MaterialTheme.typography.caption.copy(
                                                    fontWeight = if (isSelected) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal
                                                )
                                            )
                                            val count = sourceResults[name]?.size ?: 0
                                            if (count > 0 || sourceLoading[name] == true) {
                                                Spacer(Modifier.width(6.dp))
                                                Surface(
                                                    color = if (isSelected) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface.copy(alpha = 0.12f),
                                                    shape = RoundedCornerShape(10.dp)
                                                ) {
                                                    if (sourceLoading[name] == true) {
                                                        CircularProgressIndicator(
                                                            modifier = Modifier.padding(horizontal = 4.dp).size(10.dp),
                                                            strokeWidth = 1.5.dp,
                                                            color = if (isSelected) MaterialTheme.colors.onPrimary else MaterialTheme.colors.primary
                                                        )
                                                    } else {
                                                        Text(
                                                            count.toString(),
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                                            style = MaterialTheme.typography.overline.copy(
                                                                color = if (isSelected) MaterialTheme.colors.onPrimary else MaterialTheme.colors.onSurface,
                                                                fontSize = 9.sp
                                                            )
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        
                        val currentMsg = msg
                        if (currentMsg != null) {
                            val isError = currentMsg.contains("失败") || currentMsg.contains("错误") || currentMsg.contains("未找到")
                            Text(
                                currentMsg, 
                                color = if (isError) Color.Red else MaterialTheme.colors.primary, 
                                style = MaterialTheme.typography.caption,
                                modifier = Modifier.padding(bottom = 8.dp).fillMaxWidth().background(
                                    if (isError) Color.Red.copy(alpha = 0.1f) else MaterialTheme.colors.primary.copy(alpha = 0.1f),
                                    RoundedCornerShape(4.dp)
                                ).padding(8.dp)
                            )
                        }
                        val currentPlayerError = playerError
                        if (currentPlayerError != null) {
                            Text(currentPlayerError, color = Color.Red, modifier = Modifier.padding(bottom = 8.dp))
                        }
                        
                        val currentResults = sourceResults[sources[selectedTab]] ?: emptyList()
                        val currentError = sourceError[sources[selectedTab]]
                        
                        if (currentError != null) {
                            Text("该平台搜索出错: $currentError", color = Color.Red, style = MaterialTheme.typography.caption)
                        }

                        LazyColumn(Modifier.fillMaxSize()) {
                            items(currentResults) { t ->
                                SearchResultItem(
                                    t = t,
                                    chain = chain,
                                    player = player,
                                    scope = scope,
                                    library = library,
                                    onTrackSelect = { lrc, cover ->
                                        lyrics = lrc
                                        coverUrl = cover
                                    },
                                    onLibraryUpdate = {
                                        library = it
                                        Storage.saveLibrary(it)
                                    },
                                    onMsg = { msg = it }
                                )
                            }
                        }
                    }
                }
            }
            
            // 右侧：播放器控制与列表 (垂直堆叠)
            Column(Modifier.weight(1.2f).fillMaxHeight().padding(12.dp)) {
                // 当前播放卡片
                Card(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    elevation = 4.dp,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("正在播放", style = MaterialTheme.typography.subtitle1)
                        Spacer(Modifier.height(12.dp))
                        
                        // 频谱/封面区域
                        Box(Modifier.fillMaxWidth().height(140.dp).background(Color.Black.copy(alpha = 0.05f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                            SpectrumVisualization(isPlaying = isPlaying)
                            val currentCover = coverUrl
                            if (currentCover != null) {
                                KamelImage(
                                    resource = asyncPainterResource(currentCover),
                                    contentDescription = "Current Cover",
                                    modifier = Modifier.fillMaxHeight().aspectRatio(1f).padding(8.dp).background(Color.White, RoundedCornerShape(8.dp)).padding(2.dp),
                                    contentScale = ContentScale.Crop,
                                    onLoading = { CircularProgressIndicator(modifier = Modifier.size(24.dp)) }
                                )
                            }
                        }
                        
                        Spacer(Modifier.height(12.dp))
                        PlayerControls(
                            isPlaying = isPlaying,
                            posSec = posSec,
                            durSec = durSec,
                            onPlayPause = { if (isPlaying) player.pause() else player.play() },
                            onStop = { player.fullStop() },
                            onVolumePercent = { player.setVolume(it) },
                            onSeek = { player.seekTo(it) }
                        )
                        
                        Spacer(Modifier.height(12.dp))
                        Text("歌词", style = MaterialTheme.typography.subtitle2, color = MaterialTheme.colors.primary)
                        Spacer(Modifier.height(4.dp))
                        Box(
                            Modifier.fillMaxWidth()
                                .weight(1.5f) // 增加歌词权重，使其占更多空间
                                .background(Color.Black.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                                .padding(4.dp)
                        ) {
                            val currentLyrics = lyrics
                            if (!currentLyrics.isNullOrBlank()) {
                                val scrollState = rememberLazyListState()
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    state = scrollState,
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    contentPadding = PaddingValues(vertical = 16.dp)
                                ) {
                                    items(currentLyrics.lines()) { line ->
                                        if (line.isNotBlank()) {
                                            Text(
                                                line.replace(Regex("\\[.*?\\]"), ""), // 去除时间标签
                                                style = MaterialTheme.typography.body1.copy(
                                                    lineHeight = 24.sp,
                                                    textAlign = TextAlign.Center
                                                ),
                                                modifier = Modifier.padding(vertical = 4.dp, horizontal = 12.dp),
                                                color = MaterialTheme.colors.onSurface.copy(alpha = 0.8f)
                                            )
                                        }
                                    }
                                }
                            } else {
                                Column(
                                    Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(Icons.Default.PlayArrow, null, tint = Color.Gray.copy(alpha = 0.3f), modifier = Modifier.size(48.dp))
                                    Text("暂无歌词", color = Color.Gray)
                                }
                            }
                        }
                    }
                }
                
                Spacer(Modifier.height(16.dp))
                
                // 播放列表卡片
                Card(
                    modifier = Modifier.fillMaxWidth().weight(0.8f), // 减小列表权重，为歌词让路
                    elevation = 4.dp,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(Modifier.fillMaxSize().padding(16.dp)) {
                        Text("我的列表", style = MaterialTheme.typography.subtitle1)
                        Spacer(Modifier.height(8.dp))
                        PlaylistView(
                            library = library,
                            selectedId = selectedPlaylistId,
                            onSelect = { selectedPlaylistId = it },
                            onDelete = {
                                library = PlaylistManager.deletePlaylist(it, library)
                                selectedPlaylistId = library.playlists.firstOrNull()?.id
                                Storage.saveLibrary(library)
                            },
                            onMoveUp = { pid, idx ->
                                library = PlaylistManager.reorderPlaylist(pid, idx, maxOf(0, idx - 1), library)
                                Storage.saveLibrary(library)
                            },
                            onMoveDown = { pid, idx ->
                                val size = library.playlists.find { it.id == pid }?.items?.size ?: 0
                                library = PlaylistManager.reorderPlaylist(pid, idx, minOf(size - 1, idx + 1), library)
                                Storage.saveLibrary(library)
                            },
                            onRemoveItem = { pid, idx ->
                                library = PlaylistManager.removeFromPlaylist(pid, idx, library)
                                Storage.saveLibrary(library)
                            },
                            onPlayItem = { item ->
                                scope.launch {
                                    msg = "正在获取播放链接: ${item.title}..."
                                    runCatching {
                                        val url = chain.streamUrlFor(item.source, item.id)
                                        if (url.isNullOrBlank()) throw Exception("未能获取到有效的播放地址")
                                        msg = "成功获取链接，开始播放"
                                        player.loadOnline(OnlineTrack(
                                            id = item.id, 
                                            title = item.title, 
                                            artist = item.artist, 
                                            album = item.album,
                                            durationMillis = item.durationMs,
                                            previewUrl = url,
                                            coverUrl = item.coverUrl
                                        ))
                                        player.play()
                                        coverUrl = item.coverUrl
                                    }.onFailure {
                                        msg = "播放失败: ${it.message}"
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultItem(
    t: Track,
    chain: SourceChain,
    player: AudioPlayer,
    scope: kotlinx.coroutines.CoroutineScope,
    library: LibraryState,
    onTrackSelect: (String?, String?) -> Unit,
    onLibraryUpdate: (LibraryState) -> Unit,
    onMsg: (String) -> Unit
) {
    var loadingUrl by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        elevation = 2.dp,
        shape = RoundedCornerShape(12.dp),
        backgroundColor = MaterialTheme.colors.surface
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 封面图显示
            Surface(
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(8.dp),
                color = Color.Black.copy(alpha = 0.05f)
            ) {
                if (loadingUrl) {
                    CircularProgressIndicator(modifier = Modifier.padding(12.dp), strokeWidth = 2.dp)
                } else if (!t.coverUrl.isNullOrBlank()) {
                    KamelImage(
                        resource = asyncPainterResource(t.coverUrl),
                        contentDescription = "Cover",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        onLoading = { CircularProgressIndicator(modifier = Modifier.padding(12.dp), strokeWidth = 2.dp) },
                        onFailure = { Icon(Icons.Default.PlayArrow, null, modifier = Modifier.padding(12.dp), tint = Color.Gray.copy(alpha = 0.5f)) }
                    )
                } else {
                    Icon(Icons.Default.PlayArrow, null, modifier = Modifier.padding(12.dp), tint = Color.Gray.copy(alpha = 0.5f))
                }
            }
            
            Spacer(Modifier.width(12.dp))
            
            Column(Modifier.weight(1f)) {
                Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.body1.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = when(t.source) {
                            "netease" -> Color(0xFFE53935)
                            "qq" -> Color(0xFF43A047)
                            "kugou" -> Color(0xFF1E88E5)
                            "kuwo" -> Color(0xFFFDD835) // 酷我黄色
                            "migu" -> Color(0xFFF06292) // 咪咕粉色
                            "itunes" -> Color(0xFF9C27B0)
                            else -> Color.Gray
                        },
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            t.source.uppercase(),
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.caption.copy(fontSize = 10.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                            color = Color.White
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("${t.artist} · ${t.album ?: "未知专辑"}", style = MaterialTheme.typography.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            
            IconButton(
                enabled = !loadingUrl,
                onClick = {
                scope.launch {
                    loadingUrl = true
                    onMsg("正在获取播放链接: ${t.title}...")
                    runCatching {
                        val url = chain.streamUrlFor(t.source, t.id)
                        if (url.isNullOrBlank()) {
                            throw Exception("未能获取到有效的播放地址")
                        }
                        onMsg("成功获取链接，开始播放: ${t.title}")
                        val lrc = chain.lyricsFor(t.source, t.id)
                        var actualCover = t.coverUrl
                        if (actualCover.isNullOrBlank()) {
                            actualCover = chain.coverFor(t.source, t.id)
                        }
                        val ot = OnlineTrack(
                            id = t.id,
                            title = t.title,
                            artist = t.artist,
                            album = t.album,
                            durationMillis = t.durationMs, // 确保传递了 durationMs
                            previewUrl = url,
                            coverUrl = actualCover
                        )
                        onTrackSelect(lrc, actualCover)
                        player.loadOnline(ot)
                        player.play()
                    }.onFailure {
                        onMsg("播放失败: ${it.message ?: "未知错误"}")
                    }
                    loadingUrl = false
                }
            }) {
                Icon(Icons.Default.PlayArrow, "Play", tint = if (loadingUrl) Color.Gray else MaterialTheme.colors.primary)
            }
            
            var expanded by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { 
                    if (library.playlists.isEmpty()) {
                        onMsg("请先创建一个播放列表")
                    } else {
                        expanded = true 
                    }
                }) {
                    Icon(Icons.Default.Add, "Add")
                }
                DropdownMenu(expanded, { expanded = false }) {
                    library.playlists.forEach { pl ->
                        DropdownMenuItem({
                            val item = MusicItemId(
                                id = t.id,
                                source = t.source,
                                title = t.title,
                                artist = t.artist,
                                album = t.album,
                                durationMs = t.durationMs,
                                coverUrl = t.coverUrl
                            )
                            val newState = PlaylistManager.addToPlaylist(pl.id, item, library)
                            onLibraryUpdate(newState)
                            onMsg("已添加到列表: ${pl.name}")
                            expanded = false
                        }) { Text(pl.name) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerControls(
    isPlaying: Boolean,
    posSec: Double,
    durSec: Double?,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onVolumePercent: (Int) -> Unit,
    onSeek: (Double) -> Unit
) {
    val totalSec = durSec ?: 0.0
    // 使用 remember 缓存进度，防止拖动时抖动
    var sliderValue by remember { mutableStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    LaunchedEffect(posSec, totalSec) {
        if (!isDragging && totalSec > 0) {
            sliderValue = (posSec / totalSec).toFloat().coerceIn(0f, 1f)
        }
    }
    
    // 格式化时间函数
    fun formatTime(seconds: Double): String {
        if (seconds <= 0 || seconds.isNaN()) return "00:00"
        val s = seconds.toInt()
        val m = s / 60
        val sec = s % 60
        return "%02d:%02d".format(m, sec)
    }

    Column(Modifier.fillMaxWidth()) {
        // 进度条与时间
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatTime(if (isDragging) sliderValue.toDouble() * totalSec else posSec), style = MaterialTheme.typography.caption)
            
            Slider(
                value = sliderValue,
                onValueChange = {
                    isDragging = true
                    sliderValue = it
                },
                onValueChangeFinished = {
                    onSeek(sliderValue.toDouble() * totalSec)
                    isDragging = false
                },
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colors.primary,
                    activeTrackColor = MaterialTheme.colors.primary,
                    inactiveTrackColor = MaterialTheme.colors.onSurface.copy(alpha = 0.1f)
                )
            )
            
            Text(formatTime(totalSec), style = MaterialTheme.typography.caption)
        }

        Spacer(Modifier.height(8.dp))

        // 控制按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onStop) {
                Icon(Icons.Default.Delete, "Stop", tint = MaterialTheme.colors.onSurface.copy(alpha = 0.6f))
            }
            
            FloatingActionButton(
                onClick = onPlayPause,
                backgroundColor = MaterialTheme.colors.primary,
                contentColor = Color.Black,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    if (isPlaying) Icons.Default.Close else Icons.Default.PlayArrow, // 使用 Close 暂时替代 Pause
                    contentDescription = "Play/Pause"
                )
            }
            
            // 音量控制
            var vol by remember { mutableStateOf(0.8f) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Notifications, null, modifier = Modifier.size(16.dp), tint = Color.Gray) // 使用 Notifications 替代 Volume
                Slider(
                    value = vol,
                    onValueChange = {
                        vol = it
                        onVolumePercent((it * 100).toInt())
                    },
                    modifier = Modifier.width(80.dp)
                )
            }
        }
    }
}

@Composable
private fun SpectrumVisualization(isPlaying: Boolean) {
    val bars = 24
    val baseColor = if (isPlaying) Color(0xFF4FC3F7) else Color(0xFFBDBDBD)
    val t by rememberInfiniteClock(isPlaying)
    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        drawSpectrum(bars, baseColor, t)
    }
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            // Poll playback status to update UI states
            // Player instance is held outside; we cannot access it here, so polling is handled in parent
            delay(500)
        }
    }
}

@Composable
private fun EditMetaDialog(
    track: LocalTrack,
    onDismiss: () -> Unit,
    onSave: (String?, String?, String?) -> Unit
) {
    var title by remember { mutableStateOf(track.title ?: File(track.path).nameWithoutExtension) }
    var artist by remember { mutableStateOf(track.artist ?: "") }
    var album by remember { mutableStateOf(track.album ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑信息") },
        text = {
            Column {
                OutlinedTextField(title, { title = it }, label = { Text("标题") })
                OutlinedTextField(artist, { artist = it }, label = { Text("歌手") })
                OutlinedTextField(album, { album = it }, label = { Text("专辑") })
            }
        },
        confirmButton = {
            Button(onClick = { onSave(title.takeIf { it.isNotBlank() }, artist.takeIf { it.isNotBlank() }, album.takeIf { it.isNotBlank() }) }) {
                Text("保存")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun rememberInfiniteClock(running: Boolean): State<Float> {
    val s = remember { mutableStateOf(0f) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        var v = 0f
        while (true) {
            v += 0.05f
            s.value = v
            delay(16)
        }
    }
    return s
}

private fun DrawScope.drawSpectrum(bars: Int, color: Color, t: Float) {
    val w = size.width
    val h = size.height
    val margin = 4f
    val bw = (w - margin * (bars + 1)) / bars
    for (i in 0 until bars) {
        val phase = i * 0.35f
        val amp = 0.2f + 0.8f * kotlin.math.abs(kotlin.math.sin(phase + t))
        val bh = h * amp
        val x = margin + i * (bw + margin)
        drawRect(color, topLeft = Offset(x, h - bh), size = Size(bw, bh))
    }
}

@Composable
private fun SourceDropdown(selected: String, options: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Button(onClick = { expanded = true }) { Text("源: $selected") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(onClick = {
                    expanded = false
                    onSelect(opt)
                }) { Text(opt) }
            }
        }
    }
}

@Composable
private fun DropdownMenuButton(label: String, playlists: List<Playlist>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Button(onClick = { expanded = true }) { Text(label) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            playlists.forEach {
                DropdownMenuItem(onClick = {
                    expanded = false
                    onSelect(it.id)
                }) { Text(it.name) }
            }
        }
    }
}

@Composable
private fun PlaylistView(
    library: LibraryState,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onMoveUp: (String, Int) -> Unit,
    onMoveDown: (String, Int) -> Unit,
    onRemoveItem: (String, Int) -> Unit,
    onPlayItem: (MusicItemId) -> Unit
) {
    Column {
        val scroll = rememberScrollState()
        Row(Modifier.horizontalScroll(scroll)) {
            library.playlists.forEach { pl ->
                OutlinedButton(
                    onClick = { onSelect(pl.id) },
                    modifier = Modifier.padding(end = 4.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = if (selectedId == pl.id) ButtonDefaults.outlinedButtonColors(backgroundColor = MaterialTheme.colors.primary.copy(alpha = 0.1f)) else ButtonDefaults.outlinedButtonColors()
                ) {
                    Text(if (selectedId == pl.id) "● ${pl.name}" else pl.name)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        
        val currentPlaylist = library.playlists.find { it.id == selectedId }
        if (currentPlaylist != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("共 ${currentPlaylist.items.size} 首歌曲", style = MaterialTheme.typography.caption)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { onDelete(currentPlaylist.id) }) {
                    Text("删除列表", color = Color.Red.copy(alpha = 0.7f), style = MaterialTheme.typography.caption)
                }
            }
            
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(currentPlaylist.items) { idx, item ->
                    PlaylistItemView(idx, item, currentPlaylist.id, onMoveUp, onMoveDown, onRemoveItem, onPlayItem)
                }
            }
        }
    }
}

@Composable
private fun PlaylistItemView(
    idx: Int,
    item: MusicItemId,
    playlistId: String,
    onMoveUp: (String, Int) -> Unit,
    onMoveDown: (String, Int) -> Unit,
    onRemoveItem: (String, Int) -> Unit,
    onPlayItem: (MusicItemId) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        elevation = 0.dp,
        backgroundColor = Color.Black.copy(alpha = 0.05f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${idx + 1}", 
                style = MaterialTheme.typography.caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), 
                modifier = Modifier.width(28.dp),
                color = MaterialTheme.colors.primary.copy(alpha = 0.7f)
            )
            
            Column(Modifier.weight(1f)) {
                Text(
                    item.title, 
                    style = MaterialTheme.typography.body2.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), 
                    maxLines = 1, 
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = when(item.source.lowercase()) {
                            "netease" -> Color(0xFFE53935)
                            "qq" -> Color(0xFF43A047)
                            "kugou" -> Color(0xFF1E88E5)
                            "kuwo" -> Color(0xFFFDD835)
                            "migu" -> Color(0xFFE91E63)
                            "itunes" -> Color(0xFF9C27B0)
                            else -> Color.Gray
                        },
                        shape = RoundedCornerShape(2.dp)
                    ) {
                        Text(
                            item.source.uppercase(),
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.caption.copy(fontSize = 8.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                            color = if (item.source.lowercase() == "kuwo") Color.Black else Color.White
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        item.artist,
                        style = MaterialTheme.typography.caption,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = Color.Gray
                    )
                }
            }

            Row {
                IconButton(onClick = { onPlayItem(item) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colors.primary)
                }
                
                var expanded by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { expanded = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.MoreVert, null, modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded, { expanded = false }) {
                        DropdownMenuItem({ 
                            onMoveUp(playlistId, idx)
                            expanded = false 
                        }) {
                            Icon(Icons.Default.KeyboardArrowUp, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("上移")
                        }
                        DropdownMenuItem({ 
                            onMoveDown(playlistId, idx)
                            expanded = false 
                        }) {
                            Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("下移")
                        }
                        Divider()
                        DropdownMenuItem({ 
                            onRemoveItem(playlistId, idx)
                            expanded = false 
                        }) {
                            Icon(Icons.Default.Delete, null, Modifier.size(18.dp), tint = Color.Red)
                            Spacer(Modifier.width(8.dp))
                            Text("移除", color = Color.Red)
                        }
                    }
                }
            }
        }
    }
}

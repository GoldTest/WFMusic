package com.workforboss.music

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.scrollable
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
import androidx.compose.foundation.shape.CircleShape
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
fun MusicPlayerTab(onNavigateToSettings: () -> Unit) {
    // 继承 Main.kt 的主题色，不在此处重新定义 darkColors，除非需要局部覆盖
    MusicPlayerContent(onNavigateToSettings)
}

@Composable
private fun MusicPlayerContent(onNavigateToSettings: () -> Unit) {
    val scope = rememberCoroutineScope()
    val chain = remember { SourceChain() }
    var searchText by remember { mutableStateOf("") }
    
    val focusRequester = remember { FocusRequester() }

    // 分平台搜索状态
    val sources = listOf("all", "local", "bilibili", "netease", "qq", "kugou", "kuwo", "migu", "itunes")
    var selectedTab by remember { mutableStateOf(0) }
    val sourceResults = remember { mutableStateMapOf<String, List<Track>>() }
    val sourceLoading = remember { mutableStateMapOf<String, Boolean>() }
    val sourceError = remember { mutableStateMapOf<String, String?>() }
    val sourcePages = remember { mutableStateMapOf<String, Int>() }
    val sourceLoadingMore = remember { mutableStateMapOf<String, Boolean>() }

    val listState = rememberLazyListState()

    var searchLoading by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var coverUrl by remember { mutableStateOf<String?>(null) }

    var library by remember { 
        val loaded = Storage.loadLibrary()
        mutableStateOf(if (loaded.playlists.isEmpty()) {
            PlaylistManager.createPlaylist("默认列表", loaded)
        } else loaded)
    }
    val player = remember { AudioPlayer() }
    val videoPlayer = remember { BilibiliVideoPlayer(player) }
    val isPlaying by player.isPlaying.collectAsState()
    val posSec by player.positionSec.collectAsState()
    val durSec by player.durationSec.collectAsState()
    val playerError by player.error.collectAsState()
    var selectedPlaylistId by remember {
        mutableStateOf(library.playlists.firstOrNull()?.id)
    }
    var playingPlaylistId by remember { mutableStateOf<String?>(null) }
    var playingIndex by remember { mutableStateOf(-1) }
    val currentItem = remember(playingPlaylistId, playingIndex, library) {
        playingPlaylistId?.let { pid ->
            library.playlists.find { it.id == pid }?.items?.getOrNull(playingIndex)
        }
    }
    var showRenameDialog by remember { mutableStateOf<String?>(null) } // playlistId

    // 定义播放下一首的逻辑
    val playNext = {
        val pid = playingPlaylistId
        val idx = playingIndex
        if (pid != null && idx != -1) {
            val playlist = library.playlists.find { it.id == pid }
            if (playlist != null && playlist.items.isNotEmpty()) {
                val nextIdx = (idx + 1) % playlist.items.size
                val nextItem = playlist.items[nextIdx]
                playingIndex = nextIdx
                
                scope.launch {
                    msg = "正在自动播放下一首: ${nextItem.title}..."
                    runCatching {
                        // 策略：如果是播放列表中的音乐，优先检查本地文件
                        val localFile = Storage.getMusicFile(nextItem.source, nextItem.id)
                        if (localFile.exists()) {
                            msg = "正在播放本地缓存: ${nextItem.title}"
                            player.loadLocal(LocalTrack(
                                id = nextItem.id,
                                path = localFile.absolutePath,
                                title = nextItem.title,
                                artist = nextItem.artist,
                                album = nextItem.album,
                                durationMillis = nextItem.durationMs,
                                source = nextItem.source
                            ))
                        } else {
                            val stream = chain.streamUrlFor(nextItem.source, nextItem.id)
                            if (stream.url.isBlank()) throw Exception("未能获取到有效的播放地址")
                            
                            msg = "正在缓冲网络音频 (${stream.quality ?: "标准"})..."
                            player.loadOnline(OnlineTrack(
                                id = nextItem.id, 
                                title = nextItem.title, 
                                artist = nextItem.artist, 
                                album = nextItem.album,
                                durationMillis = nextItem.durationMs,
                                previewUrl = stream.url,
                                coverUrl = nextItem.coverUrl,
                                quality = stream.quality ?: nextItem.quality,
                                source = nextItem.source,
                                videoUrl = stream.videoUrl,
                                videoWidth = stream.videoWidth,
                                videoHeight = stream.videoHeight,
                                videoQuality = stream.videoQuality,
                                headers = stream.headers
                            ))
                        }
                        player.play()
                        coverUrl = nextItem.coverUrl
                    }.onFailure {
                        msg = "自动播放失败: ${it.message}"
                    }
                }
            }
        }
    }

    // 设置播放器的完成回调
    LaunchedEffect(player) {
        player.onFinished = {
            playNext()
        }
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
        if (searchText.trim() == "设置") {
            onNavigateToSettings()
            searchText = ""
        } else if (searchText.isNotBlank() && !searchLoading) {
            scope.launch(Dispatchers.IO) {
                searchLoading = true
                msg = null
                sources.forEach { 
                    sourceResults[it] = emptyList()
                    sourceLoading[it] = it != "all"
                    sourceError[it] = null
                    sourcePages[it] = 1
                    sourceLoadingMore[it] = false
                }
                try {
                    val jobs = sources.filter { it != "all" }.map { name ->
                        async {
                            runCatching {
                                val res = chain.searchBySource(name, searchText.trim(), 1)
                                // 初始搜索也进行去重保护
                                sourceResults[name] = res.distinctBy { it.id }
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
                    
                    // 搜索完成后回到顶部 (必须在主线程执行 UI 操作)
                    scope.launch(Dispatchers.Main) {
                        listState.scrollToItem(0)
                    }
                } catch (e: Exception) {
                    msg = "搜索失败: ${e.message}"
                }
                searchLoading = false
            }
        }
    }

    val loadMore = { source: String ->
        if (source != "all" && source != "local" && sourceLoadingMore[source] != true && !searchText.isBlank()) {
            scope.launch(Dispatchers.IO) {
                sourceLoadingMore[source] = true
                val nextPage = (sourcePages[source] ?: 1) + 1
                runCatching {
                    val res = chain.searchBySource(source, searchText.trim(), nextPage)
                    if (res.isNotEmpty()) {
                        val current = sourceResults[source] ?: emptyList()
                        // 去重处理，防止 LazyColumn Key 重复导致崩溃
                        val existingIds = current.map { it.id }.toSet()
                        val newItems = res.filter { it.id !in existingIds }
                        
                        if (newItems.isNotEmpty()) {
                            sourceResults[source] = current + newItems
                            sourcePages[source] = nextPage
                        } else {
                            // 如果返回的结果全部是重复的，可能已经到底了
                            msg = "没有更多新结果了"
                        }
                    } else {
                        msg = "没有更多结果了"
                    }
                }.onFailure {
                    msg = "加载更多失败: ${it.message}"
                }
                sourceLoadingMore[source] = false
            }
        }
    }

    // 监听滑动到底部
    LaunchedEffect(listState, selectedTab) {
        // 切换标签时回到顶部
        listState.scrollToItem(0)
        
        snapshotFlow { listState.layoutInfo.visibleItemsInfo }
            .collect { visibleItems ->
                val lastVisibleItem = visibleItems.lastOrNull() ?: return@collect
                val totalItemsCount = listState.layoutInfo.totalItemsCount
                
                // 距离底部还有 2 个元素时开始加载
                if (lastVisibleItem.index >= totalItemsCount - 2 && totalItemsCount > 0) {
                    val currentSource = sources[selectedTab]
                    loadMore(currentSource)
                }
            }
    }

    Scaffold(
        topBar = {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = searchText, onValueChange = { searchText = it },
                    label = { Text("搜索歌曲/歌手/专辑 (输入 '设置' 快速跳转)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
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
                                if (sources[selectedTab] == "local") "本地音乐管理" else "搜索结果",
                                style = MaterialTheme.typography.h6,
                                color = MaterialTheme.colors.primary
                            )
                            Spacer(Modifier.weight(1f))
                            if (sources[selectedTab] == "local") {
                                Button(
                                    onClick = {
                                        val chooser = JFileChooser().apply {
                                            isMultiSelectionEnabled = true
                                            fileFilter = javax.swing.filechooser.FileNameExtensionFilter("音乐文件", "mp3", "wav", "flac", "m4a")
                                        }
                                        val res = chooser.showOpenDialog(null)
                                        if (res == JFileChooser.APPROVE_OPTION) {
                                            val files = chooser.selectedFiles
                                            scope.launch(Dispatchers.IO) {
                                                 var count = 0
                                                 files.forEach { file ->
                                                     runCatching {
                                                         val id = "local_${System.currentTimeMillis()}_${file.name.hashCode()}.${file.extension}"
                                                         val targetFile = Storage.getMusicFile("local", id)
                                                         // 复制文件而不是移动，保留原始文件
                                                         java.nio.file.Files.copy(file.toPath(), targetFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                                                         
                                                         val track = LocalTrack(
                                                             id = id,
                                                             path = targetFile.absolutePath,
                                                             title = file.nameWithoutExtension,
                                                             artist = "本地歌手",
                                                             album = "本地专辑",
                                                             durationMillis = 0L,
                                                             source = "local"
                                                         )
                                                         library = PlaylistManager.addLocalTrack(track, library)
                                                         count++
                                                     }.onFailure {
                                                         msg = "添加失败: ${file.name} - ${it.message}"
                                                     }
                                                 }
                                                 Storage.saveLibrary(library)
                                                 msg = "成功添加 $count 首本地音乐"
                                             }
                                        }
                                    },
                                    shape = RoundedCornerShape(20.dp),
                                    modifier = Modifier.height(32.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                ) {
                                    Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("添加本地音乐", style = MaterialTheme.typography.caption)
                                }
                            }
                            if (searchLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            }
                        }
                        
                        Spacer(Modifier.height(8.dp))
                        
                        // 平台切换 Tab (优化样式)
                        val tabScroll = rememberScrollState()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(tabScroll)
                                .draggable(
                                    orientation = Orientation.Horizontal,
                                    state = rememberDraggableState { delta ->
                                        scope.launch {
                                            tabScroll.scrollTo(tabScroll.value - delta.toInt())
                                        }
                                    }
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            sources.forEachIndexed { index, name ->
                                val isSelected = selectedTab == index
                                OutlinedButton(
                                    onClick = { selectedTab = index },
                                    modifier = Modifier.padding(end = 4.dp),
                                    shape = RoundedCornerShape(20.dp),
                                    colors = if (isSelected) ButtonDefaults.outlinedButtonColors(backgroundColor = MaterialTheme.colors.primary.copy(alpha = 0.1f)) else ButtonDefaults.outlinedButtonColors()
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            if (isSelected) "● ${name.uppercase()}" else name.uppercase(),
                                            style = MaterialTheme.typography.caption.copy(
                                                fontWeight = if (isSelected) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal
                                            )
                                        )
                                        val count = if (name == "local") library.localTracks.size else (sourceResults[name]?.size ?: 0)
                                        if (count > 0 || (name != "local" && sourceLoading[name] == true)) {
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
                        
                        val currentSource = sources[selectedTab]
                        val currentResults = sourceResults[currentSource] ?: emptyList()
                        val currentError = sourceError[currentSource]
                        
                        if (currentError != null) {
                            Text("该平台搜索出错: $currentError", color = Color.Red, style = MaterialTheme.typography.caption)
                        }

                        LazyColumn(Modifier.fillMaxSize(), state = listState) {
                            if (currentSource == "local") {
                                items(library.localTracks, key = { it.id }) { lt ->
                                    LocalTrackItem(
                                        lt = lt,
                                        player = player,
                                        scope = scope,
                                        library = library,
                                        onTrackSelect = { coverUrl = it },
                                        onLibraryUpdate = {
                                            library = it
                                            Storage.saveLibrary(it)
                                        },
                                        onMsg = { msg = it },
                                        onClearPlayingContext = {
                                            playingPlaylistId = null
                                            playingIndex = -1
                                        }
                                    )
                                }
                            } else {
                                items(currentResults, key = { it.id + it.source }) { t ->
                                    SearchResultItem(
                                        t = t,
                                        chain = chain,
                                        player = player,
                                        scope = scope,
                                        library = library,
                                        onTrackSelect = { cover ->
                                            coverUrl = cover
                                        },
                                        onLibraryUpdate = {
                                            library = it
                                            Storage.saveLibrary(it)
                                        },
                                        onMsg = { msg = it },
                                        onClearPlayingContext = {
                                            playingPlaylistId = null
                                            playingIndex = -1
                                        },
                                        onQualityUpdate = { newQuality ->
                                            val list = sourceResults[t.source]
                                            if (list != null) {
                                                sourceResults[t.source] = list.map { 
                                                    if (it.id == t.id) it.copy(quality = newQuality) else it
                                                }
                                            }
                                            val allList = sourceResults["all"]
                                            if (allList != null) {
                                                sourceResults["all"] = allList.map { 
                                                    if (it.id == t.id && it.source == t.source) it.copy(quality = newQuality) else it
                                                }
                                            }
                                        }
                                    )
                                }
                                
                                // 加载更多指示器
                                if (sourceLoadingMore[currentSource] == true) {
                                    item {
                                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            // 右侧：播放器控制与列表 (垂直堆叠)
            Column(Modifier.weight(1.2f).fillMaxHeight().padding(12.dp)) {
                // 当前播放卡片 (减小高度，只保留封面和控制)
                Card(
                    modifier = Modifier.fillMaxWidth().wrapContentHeight(),
                    elevation = 4.dp,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("正在播放", style = MaterialTheme.typography.subtitle1)
                            currentItem?.quality?.let { q ->
                                Spacer(Modifier.width(8.dp))
                                Surface(
                                    color = MaterialTheme.colors.secondary.copy(alpha = 0.1f),
                                    shape = RoundedCornerShape(4.dp),
                                    border = BorderStroke(0.5.dp, MaterialTheme.colors.secondary.copy(alpha = 0.5f))
                                ) {
                                    Text(
                                        q,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                        style = MaterialTheme.typography.caption.copy(fontSize = 10.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
                                        color = MaterialTheme.colors.secondary
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        
                        // 封面区域
                        val currentOnline = player.currentOnlineTrack.collectAsState().value
                        Box(
                            Modifier.fillMaxWidth().height(160.dp)
                                .background(Color.Black.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                                .clickable(
                                    enabled = currentOnline?.source == "bilibili" && currentOnline.videoUrl != null,
                                    onClick = { currentOnline?.let { videoPlayer.toggleVideo(it) } }
                                ), 
                            contentAlignment = Alignment.Center
                        ) {
                            SpectrumVisualization(isPlaying = isPlaying)
                            val currentCover = coverUrl
                            if (currentCover != null) {
                                KamelImage(
                                    resource = asyncPainterResource(currentCover, key = currentCover),
                                    contentDescription = "Current Cover",
                                    modifier = Modifier.fillMaxHeight().aspectRatio(1f).padding(8.dp).background(Color.White, RoundedCornerShape(8.dp)).padding(2.dp),
                                    contentScale = ContentScale.Crop,
                                    onLoading = { CircularProgressIndicator(modifier = Modifier.size(24.dp)) }
                                )
                                
                                // 如果是 B 站视频，显示一个小的播放图标提示可以点击
                                if (currentOnline?.source == "bilibili" && currentOnline.videoUrl != null) {
                                    Box(
                                        Modifier.align(Alignment.BottomEnd).padding(12.dp)
                                            .size(24.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(16.dp))
                                    }
                                }
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
                    }
                }
                
                Spacer(Modifier.height(16.dp))
                
                // 播放列表卡片 (增加权重，使其显示更多)
                Card(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    elevation = 4.dp,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(Modifier.fillMaxSize().padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("我的列表", style = MaterialTheme.typography.subtitle1)
                            Spacer(Modifier.weight(1f))
                            // 添加创建播放列表按钮
                            IconButton(onClick = {
                                val name = "新列表 ${library.playlists.size + 1}"
                                library = PlaylistManager.createPlaylist(name, library)
                                Storage.saveLibrary(library)
                                selectedPlaylistId = library.playlists.lastOrNull()?.id
                            }) {
                                Icon(Icons.Default.Add, "Create Playlist", tint = MaterialTheme.colors.primary)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        PlaylistView(
                            library = library,
                            selectedId = selectedPlaylistId,
                            playingPlaylistId = playingPlaylistId,
                            playingIndex = playingIndex,
                            onSelect = { selectedPlaylistId = it },
                            onDelete = { pid ->
                                library = PlaylistManager.deletePlaylist(pid, library)
                                if (selectedPlaylistId == pid) {
                                    selectedPlaylistId = library.playlists.firstOrNull()?.id
                                }
                                Storage.saveLibrary(library)
                            },
                            onRename = { showRenameDialog = it },
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
                            onMoveToPlaylist = { fromPid, idx, toPid ->
                                library = PlaylistManager.moveItemBetweenPlaylists(fromPid, idx, toPid, library)
                                Storage.saveLibrary(library)
                            },
                            onPlayItem = { item, idx ->
                                playingPlaylistId = selectedPlaylistId
                                playingIndex = idx
                                scope.launch {
                                    msg = "正在获取播放链接: ${item.title}..."
                                    runCatching {
                                        // 策略：如果是播放列表中的音乐，优先检查本地文件
                                        val localFile = Storage.getMusicFile(item.source, item.id)
                                        
                                        // 对于 B 站，即使音频有本地缓存，我们也可能需要解析出 videoUrl 以便播放视频
                                        var currentVideoUrl = item.videoUrl
                                        var currentHeaders = item.headers
                                        var currentWidth = item.videoWidth
                                        var currentHeight = item.videoHeight
                                        var currentVideoQuality = item.videoQuality

                                        if (item.source == "bilibili" && currentVideoUrl == null) {
                                            try {
                                                val stream = chain.streamUrlFor(item.source, item.id)
                                                currentVideoUrl = stream.videoUrl
                                                currentHeaders = stream.headers
                                                currentWidth = stream.videoWidth
                                                currentHeight = stream.videoHeight
                                                currentVideoQuality = stream.videoQuality
                                                
                                                // 保存回播放列表，避免下次再解析
                                                val updatedItem = item.copy(
                                                    videoUrl = currentVideoUrl,
                                                    headers = currentHeaders,
                                                    videoWidth = currentWidth,
                                                    videoHeight = currentHeight,
                                                    videoQuality = currentVideoQuality
                                                )
                                                library = PlaylistManager.updatePlaylistItem(selectedPlaylistId!!, idx, updatedItem, library)
                                                Storage.saveLibrary(library)
                                            } catch (e: Exception) {
                                                println("Playlist play: Failed to fetch video info: ${e.message}")
                                            }
                                        }

                                        if (localFile.exists()) {
                                            msg = "正在播放本地缓存: ${item.title}"
                                            player.loadLocal(LocalTrack(
                                                id = item.id,
                                                path = localFile.absolutePath,
                                                title = item.title,
                                                artist = item.artist,
                                                album = item.album,
                                                durationMillis = item.durationMs,
                                                source = item.source
                                            ), onlineInfo = OnlineTrack(
                                                id = item.id,
                                                title = item.title,
                                                artist = item.artist,
                                                album = item.album,
                                                durationMillis = item.durationMs,
                                                previewUrl = "", 
                                                coverUrl = item.coverUrl,
                                                quality = item.quality,
                                                source = item.source,
                                                videoUrl = currentVideoUrl,
                                                videoWidth = currentWidth,
                                                videoHeight = currentHeight,
                                                videoQuality = currentVideoQuality,
                                                headers = currentHeaders
                                            ))
                                        } else {
                                            val stream = chain.streamUrlFor(item.source, item.id)
                                            if (stream.url.isBlank()) throw Exception("未能获取到有效的播放地址")
                                            
                                            msg = "正在缓冲网络音频 (${stream.quality ?: "标准"})..."
                                            player.loadOnline(OnlineTrack(
                                                id = item.id,
                                                title = item.title,
                                                artist = item.artist,
                                                album = item.album,
                                                durationMillis = item.durationMs,
                                                previewUrl = stream.url,
                                                coverUrl = item.coverUrl,
                                                quality = stream.quality ?: item.quality,
                                                source = item.source,
                                                videoUrl = stream.videoUrl,
                                                videoWidth = stream.videoWidth,
                                                videoHeight = stream.videoHeight,
                                                videoQuality = stream.videoQuality,
                                                headers = stream.headers
                                            ))
                                        }
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

    // 视频窗口渲染
    videoPlayer.VideoWindow()

    // 重命名对话框
    val renamingId = showRenameDialog
    if (renamingId != null) {
        val pl = library.playlists.find { it.id == renamingId }
        if (pl != null) {
            var newName by remember { mutableStateOf(pl.name) }
            AlertDialog(
                onDismissRequest = { showRenameDialog = null },
                title = { Text("重命名播放列表") },
                text = {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("列表名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        library = PlaylistManager.renamePlaylist(renamingId, newName, library)
                        Storage.saveLibrary(library)
                        showRenameDialog = null
                    }) { Text("确定") }
                },
                dismissButton = {
                    TextButton(onClick = { showRenameDialog = null }) { Text("取消") }
                }
            )
        }
    }
}

@Composable
private fun LocalTrackItem(
    lt: LocalTrack,
    player: AudioPlayer,
    scope: kotlinx.coroutines.CoroutineScope,
    library: LibraryState,
    onTrackSelect: (String?) -> Unit,
    onLibraryUpdate: (LibraryState) -> Unit,
    onMsg: (String) -> Unit,
    onClearPlayingContext: () -> Unit
) {
    var showAddMenu by remember { mutableStateOf(false) }
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
            Surface(
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colors.primary.copy(alpha = 0.1f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.List, null, tint = MaterialTheme.colors.primary)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(lt.title ?: "未知标题", style = MaterialTheme.typography.subtitle1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${lt.artist ?: "未知歌手"} • ${lt.album ?: "未知专辑"}", style = MaterialTheme.typography.caption, color = Color.Gray)
            }
            
            IconButton(onClick = {
                onClearPlayingContext()
                scope.launch {
                    player.loadLocal(lt)
                    player.play()
                }
                onTrackSelect(null)
            }) {
                Icon(Icons.Default.PlayArrow, "Play", tint = MaterialTheme.colors.primary)
            }
            
            Box {
                IconButton(onClick = { showAddMenu = true }) {
                    Icon(Icons.Default.Add, "Add to Playlist")
                }
                DropdownMenu(expanded = showAddMenu, onDismissRequest = { showAddMenu = false }) {
                    if (library.playlists.isEmpty()) {
                        DropdownMenuItem(onClick = { showAddMenu = false }) {
                            Text("暂无播放列表", style = MaterialTheme.typography.caption)
                        }
                    }
                    library.playlists.forEach { p ->
                        DropdownMenuItem(onClick = {
                            val item = MusicItemId(
                                id = lt.id,
                                source = "local",
                                title = lt.title ?: "未知",
                                artist = lt.artist ?: "未知",
                                album = lt.album,
                                durationMs = lt.durationMillis
                            )
                            onLibraryUpdate(PlaylistManager.addToPlaylist(p.id, item, library))
                            onMsg("已添加到: ${p.name}")
                            showAddMenu = false
                        }) {
                            Text(p.name)
                        }
                    }
                }
            }

            IconButton(onClick = {
                onLibraryUpdate(PlaylistManager.removeLocalTrack(lt.id, library))
                onMsg("已从本地列表移除")
            }) {
                Icon(Icons.Default.Delete, "Delete", tint = Color.Red.copy(alpha = 0.6f))
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
    onTrackSelect: (String?) -> Unit,
    onLibraryUpdate: (LibraryState) -> Unit,
    onMsg: (String) -> Unit,
    onClearPlayingContext: () -> Unit,
    onQualityUpdate: (String) -> Unit = {}
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
            val sourceColor = remember(t.source) {
                when(t.source) {
                    "netease" -> Color(0xFFE53935)
                    "qq" -> Color(0xFF43A047)
                    "kugou" -> Color(0xFF1E88E5)
                    "kuwo" -> Color(0xFFFDD835)
                    "migu" -> Color(0xFFF06292)
                    "itunes" -> Color(0xFF9C27B0)
                    else -> Color.Gray
                }
            }

            Surface(
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(8.dp),
                color = Color.Black.copy(alpha = 0.05f)
            ) {
                if (loadingUrl) {
                    CircularProgressIndicator(modifier = Modifier.padding(12.dp), strokeWidth = 2.dp)
                } else if (!t.coverUrl.isNullOrBlank()) {
                    KamelImage(
                        resource = asyncPainterResource(t.coverUrl, key = t.coverUrl),
                        contentDescription = "Cover",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        onLoading = { Box(Modifier.fillMaxSize().background(Color.Gray.copy(alpha = 0.1f))) },
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
                        color = sourceColor,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(t.source.uppercase(), modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp), style = MaterialTheme.typography.caption.copy(fontSize = 10.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = Color.White)
                }
                if (!t.quality.isNullOrBlank()) {
                    Spacer(Modifier.width(4.dp))
                    Surface(
                        color = MaterialTheme.colors.secondary.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(0.5.dp, MaterialTheme.colors.secondary.copy(alpha = 0.5f))
                    ) {
                        Text(
                            t.quality,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.caption.copy(fontSize = 9.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
                            color = MaterialTheme.colors.secondary
                        )
                    }
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
                        val stream = chain.streamUrlFor(t.source, t.id)
                        if (stream.url.isBlank()) {
                            throw Exception("未能获取到有效的播放地址")
                        }
                        if (stream.quality != null) {
                            onQualityUpdate(stream.quality)
                        }
                        onMsg("成功获取链接 (${stream.quality ?: "标准"}), 开始播放: ${t.title}")
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
                            previewUrl = stream.url,
                            coverUrl = actualCover,
                            quality = stream.quality ?: t.quality,
                            source = t.source,
                            videoUrl = stream.videoUrl,
                            videoWidth = stream.videoWidth,
                            videoHeight = stream.videoHeight,
                            headers = stream.headers
                        )
                        onTrackSelect(actualCover)
                        onClearPlayingContext()
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
                                coverUrl = t.coverUrl,
                                quality = t.quality,
                                videoUrl = t.videoUrl,
                                videoWidth = t.videoWidth,
                                videoHeight = t.videoHeight,
                                videoQuality = t.videoQuality,
                                headers = t.headers
                            )
                            val newState = PlaylistManager.addToPlaylist(pl.id, item, library)
                            onLibraryUpdate(newState)
                            onMsg("已添加到列表: ${pl.name}")
                            
                            // 后台触发缓存
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val stream = chain.streamUrlFor(t.source, t.id)
                                    val audioFile = Storage.getMusicFile(t.source, t.id)
                                    if (!audioFile.exists()) {
                                        Storage.downloadMusic(stream.url, audioFile)
                                    }
                                    if (t.source == "bilibili" && stream.videoUrl != null) {
                                        val videoFile = Storage.getVideoFile(t.source, t.id)
                                        if (!videoFile.exists()) {
                                            Storage.downloadMusic(stream.videoUrl, videoFile)
                                        }
                                    }
                                } catch (e: Exception) {
                                    println("Background cache failed: ${e.message}")
                                }
                            }
                            expanded = false
                        }) { Text(pl.name) }
                    }
                    Divider()
                    DropdownMenuItem({
                        val newPlaylistName = "新列表 ${library.playlists.size + 1}"
                        var newState = PlaylistManager.createPlaylist(newPlaylistName, library)
                        val newPlId = newState.playlists.last().id
                        val item = MusicItemId(
                            id = t.id,
                            source = t.source,
                            title = t.title,
                            artist = t.artist,
                            album = t.album,
                            durationMs = t.durationMs,
                            coverUrl = t.coverUrl,
                            quality = t.quality,
                            videoUrl = t.videoUrl,
                            videoWidth = t.videoWidth,
                            videoHeight = t.videoHeight,
                            videoQuality = t.videoQuality,
                            headers = t.headers
                        )
                        newState = PlaylistManager.addToPlaylist(newPlId, item, newState)
                        onLibraryUpdate(newState)
                        onMsg("已创建新列表并添加: $newPlaylistName")
                        
                        // 后台触发缓存
                        scope.launch(Dispatchers.IO) {
                            try {
                                val stream = chain.streamUrlFor(t.source, t.id)
                                val audioFile = Storage.getMusicFile(t.source, t.id)
                                if (!audioFile.exists()) {
                                    Storage.downloadMusic(stream.url, audioFile)
                                }
                                if (t.source == "bilibili" && stream.videoUrl != null) {
                                    val videoFile = Storage.getVideoFile(t.source, t.id)
                                    if (!videoFile.exists()) {
                                        Storage.downloadMusic(stream.videoUrl, videoFile)
                                    }
                                }
                            } catch (e: Exception) {
                                println("Background cache failed: ${e.message}")
                            }
                        }
                        expanded = false
                    }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("添加到新列表")
                        }
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
    playingPlaylistId: String?,
    playingIndex: Int,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRename: (String) -> Unit,
    onMoveUp: (String, Int) -> Unit,
    onMoveDown: (String, Int) -> Unit,
    onRemoveItem: (String, Int) -> Unit,
    onMoveToPlaylist: (String, Int, String) -> Unit,
    onPlayItem: (MusicItemId, Int) -> Unit
) {
    Column {
        val scroll = rememberScrollState()
        val scope = rememberCoroutineScope()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scroll)
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        scope.launch {
                            scroll.scrollTo(scroll.value - delta.toInt())
                        }
                    }
                )
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
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
                TextButton(onClick = { onRename(currentPlaylist.id) }) {
                    Text("重命名", style = MaterialTheme.typography.caption)
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { onDelete(currentPlaylist.id) }) {
                    Text("删除列表", color = Color.Red.copy(alpha = 0.7f), style = MaterialTheme.typography.caption)
                }
            }
            
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(currentPlaylist.items, key = { idx, item -> "${item.id}_${idx}" }) { idx, item ->
                    val isPlaying = playingPlaylistId == currentPlaylist.id && playingIndex == idx
                    PlaylistItemView(
                        idx, item, currentPlaylist.id, isPlaying, library,
                        onMoveUp, onMoveDown, onRemoveItem, onMoveToPlaylist, onPlayItem
                    )
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
    isPlaying: Boolean,
    library: LibraryState,
    onMoveUp: (String, Int) -> Unit,
    onMoveDown: (String, Int) -> Unit,
    onRemoveItem: (String, Int) -> Unit,
    onMoveToPlaylist: (String, Int, String) -> Unit,
    onPlayItem: (MusicItemId, Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onPlayItem(item, idx) },
        elevation = if (isPlaying) 2.dp else 0.dp,
        backgroundColor = if (isPlaying) MaterialTheme.colors.primary.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.05f),
        shape = RoundedCornerShape(8.dp),
        border = if (isPlaying) BorderStroke(1.dp, MaterialTheme.colors.primary.copy(alpha = 0.5f)) else null
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isPlaying) {
                Icon(
                    Icons.Default.PlayArrow, 
                    null, 
                    tint = MaterialTheme.colors.primary, 
                    modifier = Modifier.size(16.dp).padding(end = 4.dp)
                )
            } else {
                Text(
                    "${idx + 1}", 
                    style = MaterialTheme.typography.caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), 
                    modifier = Modifier.width(28.dp),
                    color = MaterialTheme.colors.primary.copy(alpha = 0.7f)
                )
            }
            
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
                IconButton(onClick = { onPlayItem(item, idx) }, modifier = Modifier.size(32.dp)) {
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
                        
                        // 移动到其他列表
                        var moveSubMenuExpanded by remember { mutableStateOf(false) }
                        DropdownMenuItem({ moveSubMenuExpanded = true }) {
                            Icon(Icons.Default.Send, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("移动到...")
                            
                            DropdownMenu(moveSubMenuExpanded, { moveSubMenuExpanded = false }) {
                                library.playlists.forEach { pl ->
                                    if (pl.id != playlistId) {
                                        DropdownMenuItem({
                                            onMoveToPlaylist(playlistId, idx, pl.id)
                                            moveSubMenuExpanded = false
                                            expanded = false
                                        }) {
                                            Text(pl.name)
                                        }
                                    }
                                }
                            }
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

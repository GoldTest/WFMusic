package com.workforboss.music

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.Alignment
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import java.io.File
import java.awt.Toolkit
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.input.key.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import javafx.application.Platform
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javafx.scene.layout.StackPane
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.scene.media.MediaView
import javafx.scene.paint.Color as JFXColor
import kotlin.math.abs

enum class PlayerState {
    IDLE, LOADING, READY, ERROR
}

class BilibiliVideoPlayer(
    private val audioPlayer: AudioPlayer
) {
    var isVisible by mutableStateOf(false)
        private set
    
    var currentTrack by mutableStateOf<OnlineTrack?>(null)
        private set

    var isFullscreen by mutableStateOf(false)
        private set

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        scope.launch {
            audioPlayer.currentOnlineTrack.collect { track ->
                if (track == null || track.source != "bilibili") {
                    if (isVisible) {
                        closeVideo()
                    }
                } else {
                    if (isVisible && currentTrack?.id != track.id) {
                        currentTrack = track
                    }
                }
            }
        }
    }

    fun toggleVideo(track: OnlineTrack) {
        if (isVisible && currentTrack?.id == track.id) {
            closeVideo()
            return
        }
        
        // 确保使用 var 以允许修改（如果传入的 track 属性是可变的，或者我们在这里处理副本）
        // 在 Kotlin 中参数是 val，所以我们应该处理 currentTrack 的逻辑
        
        closeVideo()
        currentTrack = track
        isVisible = true
    }

    @Composable
    fun VideoWindow() {
        if (!isVisible || currentTrack == null) return
        
        val track = currentTrack!!
        
        val videoWidth = track.videoWidth ?: 1920
        val videoHeight = track.videoHeight ?: 1080
        val videoQuality = track.videoQuality ?: ""

        val screenSize = Toolkit.getDefaultToolkit().screenSize
        val screenHeight = screenSize.height
        val targetHeightPx = (screenHeight * 0.8).toInt()
        
        val ratio = videoWidth.toFloat() / videoHeight.toFloat()
        val targetWidthPx = (targetHeightPx * ratio).toInt()

        val density = androidx.compose.ui.platform.LocalDensity.current
        val widthDp = with(density) { targetWidthPx.toDp() }
        val heightDp = with(density) { targetHeightPx.toDp() }

        val windowState = rememberWindowState(
            size = DpSize(widthDp, heightDp),
            position = androidx.compose.ui.window.WindowPosition(androidx.compose.ui.Alignment.Center),
            placement = if (isFullscreen) WindowPlacement.Fullscreen else WindowPlacement.Floating
        )
        
        LaunchedEffect(track.id, widthDp, heightDp) {
            if (!isFullscreen) {
                windowState.size = DpSize(widthDp, heightDp)
            }
        }

        key(track.id) {
            val title = remember(track.title, videoQuality) {
                val res = if (videoQuality.isNotBlank()) " [$videoQuality]" else ""
                "Bilibili Video - ${track.title}$res"
            }
            Window(
                onCloseRequest = { closeVideo() },
                state = windowState,
                title = title,
                alwaysOnTop = isFullscreen,
                undecorated = isFullscreen
            ) {
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
                    // 视频路径选择逻辑 - 增强版，支持轮询检查本地文件
                    var videoUrl by remember(track.id) { mutableStateOf<String?>(null) }
                    var isDownloading by remember(track.id) { mutableStateOf(false) }
                    
                    LaunchedEffect(track.id) {
                        while (true) {
                            val videoFile = Storage.getVideoFile(track.source, track.id)
                            val partFile = File(videoFile.absolutePath + ".part")
                            
                            val path = when {
                                videoFile.exists() -> videoFile.toURI().toString()
                                // 放宽限制：只要 part 文件超过 1MB 且不是 m4s 分段格式，就允许尝试播放
                                partFile.exists() && partFile.length() > 1 * 1024 * 1024 && !track.videoUrl.orEmpty().contains(".m4s") -> partFile.toURI().toString()
                                else -> null
                            }
                            
                            if (path != null) {
                                videoUrl = path
                                isDownloading = false
                                break
                            } else {
                                isDownloading = true
                                // 如果既没有本地文件，在线 URL 也为空，那可能真的没救了
                                if (track.videoUrl.isNullOrBlank()) {
                                    isDownloading = false
                                    break
                                }
                            }
                            delay(2000) // 每 2 秒检查一次本地文件是否准备好
                        }
                    }

                    if (videoUrl != null) {
                        var playerState by remember { mutableStateOf(PlayerState.IDLE) }
                        var errorMessage by remember { mutableStateOf("") }
                        var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
                        var retryCount by remember { mutableStateOf(0) }
                        
                        // 强制每次 track.id 变化时重新创建 JFXPanel，解决反复打开不恢复的问题
                        val jfxPanel = remember(track.id) { JFXPanel() }
                        
                        // 1. 初始化 JavaFX Platform
                        LaunchedEffect(Unit) {
                            try {
                                Platform.startup {}
                            } catch (e: Exception) {
                                // Already started
                            }
                        }

                        // 2. 创建和销毁 MediaPlayer 的核心逻辑
                        LaunchedEffect(videoUrl, retryCount) {
                            playerState = PlayerState.LOADING
                            
                            // 停止并清理旧的播放器
                            withContext(Dispatchers.Main) {
                                mediaPlayer?.let { oldPlayer ->
                                    Platform.runLater {
                                        oldPlayer.stop()
                                        oldPlayer.dispose()
                                    }
                                    mediaPlayer = null
                                }
                            }

                            delay(200) // 给系统一点喘息时间

                            Platform.runLater {
                                try {
                                    val media = Media(videoUrl)
                                    val player = MediaPlayer(media).apply {
                                        isMute = true
                                        cycleCount = MediaPlayer.INDEFINITE
                                        // 增加缓冲区设置，减少卡顿
                                        // 注意：JavaFX MediaPlayer 缓冲区设置有限，主要依赖系统网络层
                                    }

                                    player.onReady = Runnable {
                                        Platform.runLater {
                                            playerState = PlayerState.READY
                                            val startPos = audioPlayer.positionSec.value
                                            if (startPos > 0.1) {
                                                player.seek(javafx.util.Duration.seconds(startPos))
                                            }
                                            if (audioPlayer.isPlaying.value) {
                                                player.play()
                                            }
                                        }
                                    }

                                    player.onError = Runnable {
                                        val err = player.error
                                        Platform.runLater {
                                            println("VideoPlayer Media Error (retry=$retryCount): ${err?.message}")
                                            if (retryCount < 3) {
                                                retryCount++
                                            } else {
                                                playerState = PlayerState.ERROR
                                                errorMessage = err?.message ?: "未知错误"
                                            }
                                        }
                                    }

                                    val mediaView = MediaView(player)
                                    val root = StackPane(mediaView)
                                    root.style = "-fx-background-color: black;"
                                    mediaView.preserveRatioProperty().set(true)
                                    mediaView.fitWidthProperty().bind(root.widthProperty())
                                    mediaView.fitHeightProperty().bind(root.heightProperty())
                                    
                                    val scene = Scene(root, JFXColor.BLACK)
                                    jfxPanel.scene = scene
                                    mediaPlayer = player
                                } catch (e: Exception) {
                                    println("VideoPlayer Init Error: ${e.message}")
                                    playerState = PlayerState.ERROR
                                    errorMessage = e.message ?: "初始化失败"
                                }
                            }
                        }

                        // 3. 处理播放/暂停同步
                        DisposableEffect(mediaPlayer, audioPlayer.isPlaying.value) {
                            val player = mediaPlayer ?: return@DisposableEffect onDispose {}
                            
                            val statusListener = javafx.beans.value.ChangeListener<MediaPlayer.Status> { _, _, newStatus ->
                                if (newStatus == MediaPlayer.Status.READY || newStatus == MediaPlayer.Status.PLAYING || newStatus == MediaPlayer.Status.PAUSED) {
                                    Platform.runLater {
                                        if (audioPlayer.isPlaying.value) player.play() else player.pause()
                                    }
                                }
                            }
                            
                            Platform.runLater {
                                player.statusProperty().addListener(statusListener)
                                if (player.status == MediaPlayer.Status.READY || player.status == MediaPlayer.Status.PLAYING || player.status == MediaPlayer.Status.PAUSED) {
                                    if (audioPlayer.isPlaying.value) player.play() else player.pause()
                                }
                            }
                            
                            onDispose {
                                Platform.runLater {
                                    player.statusProperty().removeListener(statusListener)
                                }
                            }
                        }

                        // 4. 处理进度同步
                        LaunchedEffect(mediaPlayer) {
                            val player = mediaPlayer ?: return@LaunchedEffect
                            while (true) {
                                if (player.status == MediaPlayer.Status.PLAYING || player.status == MediaPlayer.Status.PAUSED) {
                                    val audioPos = audioPlayer.positionSec.value
                                    Platform.runLater {
                                        val videoPos = player.currentTime.toSeconds()
                                        if (abs(audioPos - videoPos) > 1.2) {
                                            player.seek(javafx.util.Duration.seconds(audioPos))
                                        }
                                    }
                                }
                                delay(1000)
                            }
                        }

                        // 5. 最终清理
                        DisposableEffect(track.id) {
                            onDispose {
                                Platform.runLater {
                                    mediaPlayer?.stop()
                                    mediaPlayer?.dispose()
                                    mediaPlayer = null
                                }
                            }
                        }

                        Box(Modifier.fillMaxSize()) {
                            SwingPanel(
                                factory = { jfxPanel },
                                modifier = Modifier.fillMaxSize()
                            )
                            
                            // 过渡遮罩，当正在加载或出错时显示
                            if (playerState == PlayerState.LOADING || playerState == PlayerState.IDLE) {
                                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.align(Alignment.Center).size(32.dp),
                                        color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.5f),
                                        strokeWidth = 2.dp
                                    )
                                }
                            } else if (playerState == PlayerState.ERROR) {
                                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
                                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(Icons.Default.Error, null, tint = androidx.compose.ui.graphics.Color.Red, modifier = Modifier.size(48.dp))
                                        Spacer(Modifier.height(8.dp))
                                        Text("视频加载失败: $errorMessage", color = androidx.compose.ui.graphics.Color.White)
                                        Button(onClick = { retryCount = 0; retryCount++ }, Modifier.padding(top = 16.dp)) {
                                            Text("重试")
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            if (isDownloading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(32.dp),
                                    color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.5f),
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.height(16.dp))
                                Text("正在下载并准备视频资源...", color = androidx.compose.ui.graphics.Color.White)
                                Text("请稍候，音频正在播放中", style = MaterialTheme.typography.caption, color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.5f))
                            } else {
                                Icon(Icons.Default.VideoLibrary, null, tint = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.3f), modifier = Modifier.size(48.dp))
                                Spacer(Modifier.height(16.dp))
                                Text("未找到视频资源", color = androidx.compose.ui.graphics.Color.White)
                            }
                        }
                    }

                    // 右下角控制区
                    Row(
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val quality = track.videoQuality ?: if (track.videoHeight != null) "${track.videoHeight}P" else "未知"
                        Surface(
                            color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = quality,
                                color = androidx.compose.ui.graphics.Color.White,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.caption
                            )
                        }
                        
                        Spacer(Modifier.width(8.dp))
                        
                        Surface(
                            color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            IconButton(
                                onClick = { isFullscreen = !isFullscreen },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                    contentDescription = "Toggle Fullscreen",
                                    tint = androidx.compose.ui.graphics.Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun closeVideo() {
        isVisible = false
        currentTrack = null
        isFullscreen = false
    }
}

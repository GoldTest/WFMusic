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
        val screenWidth = screenSize.width
        val screenHeight = screenSize.height
        
        // 设定最大占用屏幕的 80%
        val maxTargetWidth = (screenWidth * 0.8).toInt()
        val maxTargetHeight = (screenHeight * 0.8).toInt()
        
        val videoRatio = videoWidth.toFloat() / videoHeight.toFloat()
        
        var targetWidthPx: Int
        var targetHeightPx: Int
        
        // 根据视频比例计算最佳尺寸，确保不超出最大限制
        if (videoRatio > (maxTargetWidth.toFloat() / maxTargetHeight.toFloat())) {
            // 视频较宽，以宽度为准
            targetWidthPx = maxTargetWidth
            targetHeightPx = (targetWidthPx / videoRatio).toInt()
        } else {
            // 视频较窄（或比例一致），以高度为准
            targetHeightPx = maxTargetHeight
            targetWidthPx = (targetHeightPx * videoRatio).toInt()
        }

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

        val title = remember(track.title, videoQuality) {
            val res = if (videoQuality.isNotBlank()) " [$videoQuality]" else ""
            "Bilibili Video - ${track.title}$res"
        }

        // 使用单一 JFXPanel 实例，避免频繁初始化导致的 AWT 重入错误
        val jfxPanel = remember { JFXPanel() }

        Window(
            onCloseRequest = { closeVideo() },
            state = windowState,
            title = title,
            alwaysOnTop = isFullscreen,
            undecorated = isFullscreen
        ) {
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
                // 内部使用 key 确保切换视频时重置状态
                key(track.id) {
                    var videoUrl by remember { mutableStateOf<String?>(null) }
                    var isDownloading by remember { mutableStateOf(false) }
                    
                    LaunchedEffect(Unit) {
                        while (true) {
                            val videoFile = Storage.getVideoFile(track.source, track.id)
                            val partFile = File(videoFile.absolutePath + ".part")
                            
                            val path = when {
                                videoFile.exists() -> videoFile.toURI().toString()
                                partFile.exists() && partFile.length() > 2 * 1024 * 1024 && !track.videoUrl.orEmpty().contains(".m4s") -> partFile.toURI().toString()
                                else -> null
                            }
                            
                            if (path != null) {
                                videoUrl = path
                                isDownloading = false
                                break
                            } else {
                                isDownloading = true
                                if (track.videoUrl.isNullOrBlank()) {
                                    isDownloading = false
                                    break
                                }
                            }
                            delay(2000)
                        }
                    }

                    if (videoUrl != null) {
                        var playerState by remember { mutableStateOf(PlayerState.IDLE) }
                        var errorMessage by remember { mutableStateOf("") }
                        var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
                        var retryCount by remember { mutableStateOf(0) }
                        
                        LaunchedEffect(Unit) {
                            try {
                                Platform.startup {}
                            } catch (e: Exception) {}
                        }

                        LaunchedEffect(videoUrl, retryCount) {
                            playerState = PlayerState.LOADING
                            withContext(Dispatchers.Main) {
                                mediaPlayer?.let { oldPlayer ->
                                    Platform.runLater {
                                        oldPlayer.stop()
                                        oldPlayer.dispose()
                                    }
                                    mediaPlayer = null
                                }
                            }

                            delay(300)

                            Platform.runLater {
                                try {
                                    val media = Media(videoUrl)
                                    val player = MediaPlayer(media).apply {
                                        isMute = true
                                        cycleCount = MediaPlayer.INDEFINITE
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
                                            if (retryCount < 2) {
                                                retryCount++
                                                scope.launch {
                                                    delay(1000)
                                                    playerState = PlayerState.LOADING
                                                }
                                            } else {
                                                playerState = PlayerState.ERROR
                                                errorMessage = err?.message ?: "媒体格式不支持或加载失败"
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

                        DisposableEffect(Unit) {
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
                                        Button(onClick = { 
                                            retryCount = 0
                                            playerState = PlayerState.LOADING 
                                        }, Modifier.padding(top = 16.dp)) {
                                            Text("重试")
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black).wrapContentSize(Alignment.Center),
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
                                Text("未找到支持的视频流 (JavaFX 不支持 DASH 格式)", color = androidx.compose.ui.graphics.Color.White)
                            }
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

    private fun closeVideo() {
        isVisible = false
        currentTrack = null
        isFullscreen = false
    }
}

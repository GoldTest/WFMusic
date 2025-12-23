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
        
        closeVideo()
        currentTrack = track
        isVisible = true
    }

    @Composable
    fun VideoWindow() {
        if (!isVisible || currentTrack == null) return
        
        val track = currentTrack!!
        
        val screenSize = Toolkit.getDefaultToolkit().screenSize
        val screenHeight = screenSize.height
        val targetHeightPx = (screenHeight * 0.8).toInt()
        
        val videoWidth = track.videoWidth ?: 1920
        val videoHeight = track.videoHeight ?: 1080
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
        
        LaunchedEffect(track.id) {
            if (!isFullscreen) {
                windowState.size = DpSize(widthDp, heightDp)
            }
        }

            Window(
                onCloseRequest = { closeVideo() },
                state = windowState,
                title = "Bilibili Video - ${track.title}",
                alwaysOnTop = isFullscreen,
                undecorated = isFullscreen
            ) {
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
                    // 视频路径选择逻辑
                    val videoUrl = remember(track.id) {
                        val videoFile = Storage.getVideoFile(track.source, track.id)
                        val partFile = File(videoFile.absolutePath + ".part")
                        
                        when {
                            videoFile.exists() -> videoFile.toURI().toString()
                            partFile.exists() && partFile.length() > 1024 * 1024 -> partFile.toURI().toString()
                            else -> track.videoUrl ?: ""
                        }
                    }

                if (videoUrl.isNotBlank()) {
                    key(track.id) {

                        var isTransitioning by remember { mutableStateOf(true) }
                        val jfxPanel = remember { JFXPanel() }
                        var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
                        
                        // 切换视频时，先给一点缓冲时间
                        LaunchedEffect(track.id) {
                            isTransitioning = true
                            delay(400) // 停顿 400ms，确保旧资源释放且界面有明确切换感
                            isTransitioning = false
                        }

                        // Initialize JavaFX Platform
                        LaunchedEffect(Unit) {
                            try {
                                Platform.startup {}
                            } catch (e: Exception) {
                                // Already started
                            }
                        }

                        // Handle playback and sync
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
                                if (player.status != MediaPlayer.Status.UNKNOWN) {
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
                                if (player.status != MediaPlayer.Status.UNKNOWN && player.status != MediaPlayer.Status.STALLED) {
                                    val audioPos = audioPlayer.positionSec.value
                                    Platform.runLater {
                                        if (player.status == MediaPlayer.Status.PLAYING || player.status == MediaPlayer.Status.PAUSED) {
                                            val videoPos = player.currentTime.toSeconds()
                                            if (abs(audioPos - videoPos) > 1.2) {
                                                player.seek(javafx.util.Duration.seconds(audioPos))
                                            }
                                        }
                                    }
                                }
                                delay(1000)
                            }
                        }

                        // Cleanup
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
                            var isPlayerReady by remember { mutableStateOf(false) }

                            SwingPanel(
                                factory = {
                                    jfxPanel.apply {
                                        Platform.runLater {
                                            try {
                                                val media = Media(videoUrl)
                                                val player = MediaPlayer(media).apply {
                                                    isMute = true
                                                    cycleCount = MediaPlayer.INDEFINITE
                                                }
                                                
                                                player.onReady = Runnable {
                                                    Platform.runLater {
                                                        try {
                                                            val startPos = audioPlayer.positionSec.value
                                                            if (startPos > 0.1) {
                                                                player.seek(javafx.util.Duration.seconds(startPos))
                                                            }
                                                            if (audioPlayer.isPlaying.value) {
                                                                player.play()
                                                            }
                                                            isPlayerReady = true
                                                        } catch (e: Exception) {
                                                            println("VideoPlayer onReady Error: ${e.message}")
                                                        }
                                                    }
                                                }

                                                player.onError = Runnable {
                                                    println("VideoPlayer Media Error: ${player.error?.message}")
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
                                                println("VideoPlayer Factory Error: ${e.message}")
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                            
                            // 过渡遮罩，当正在切换或播放器还没准备好时显示
                            if (isTransitioning || !isPlayerReady) {
                                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.align(Alignment.Center).size(32.dp),
                                        color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.5f),
                                        strokeWidth = 2.dp
                                    )
                                }
                            }
                                                }
                        }
                } else {
                    Text("正在准备视频资源...", color = androidx.compose.ui.graphics.Color.White, modifier = Modifier.align(Alignment.Center))
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

    fun closeVideo() {
        isVisible = false
        currentTrack = null
    }
}

package com.workforboss.music

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.awt.SwingPanel
import javafx.application.Platform
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javafx.scene.layout.StackPane
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.scene.media.MediaView
import kotlinx.coroutines.*
import java.io.File
import javafx.scene.paint.Color
import javafx.scene.control.Label
import javax.swing.SwingUtilities

class BilibiliVideoPlayer(
    private val audioPlayer: AudioPlayer
) {
    var isVisible by mutableStateOf(false)
        private set
    
    var currentTrack by mutableStateOf<OnlineTrack?>(null)
        private set

    private var videoPlayer: MediaPlayer? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var syncJob: Job? = null
    private var fallbackJob: Job? = null
    private var jfxPanel: JFXPanel? = null
    private var rootPane: StackPane? = null

    init {
        scope.launch {
            audioPlayer.currentOnlineTrack.collect { track ->
                if (track == null || track.source != "bilibili") {
                    if (isVisible) {
                        closeVideo()
                    }
                } else {
                    // 如果视频窗口正开着，且切换到了另一个 B 站视频，自动更新视频内容
                    if (isVisible && currentTrack?.id != track.id) {
                        println("BilibiliVideoPlayer: Auto-switching video to ${track.title}")
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
        val windowState = rememberWindowState(
            size = DpSize(
                (track.videoWidth?.dp ?: 800.dp).coerceAtMost(1200.dp),
                (track.videoHeight?.dp ?: 450.dp).coerceAtMost(800.dp)
            )
        )

        Window(
            onCloseRequest = { closeVideo() },
            state = windowState,
            title = "Bilibili Video - ${track.title}",
            alwaysOnTop = true
        ) {
            key(track.id) {
                SwingPanel(
                    factory = {
                        JFXPanel().also { panel ->
                            jfxPanel = panel
                            Platform.runLater {
                                val root = StackPane()
                                root.style = "-fx-background-color: black;"
                                rootPane = root
                                val scene = Scene(root)
                                scene.fill = Color.BLACK
                                panel.scene = scene
                                
                                // 开始播放逻辑：优先使用本地缓存，因为 JavaFX 直接播放 B 站在线流速度较慢且不稳定
                                val videoFile = Storage.getVideoFile(track.source, track.id)
                                val partFile = File(videoFile.absolutePath + ".part")
                                
                                if (videoFile.exists()) {
                                    println("BilibiliVideoPlayer: Using local video file")
                                    startPlaying(videoFile.toURI().toString(), root)
                                } else if (partFile.exists() && partFile.length() > 1024 * 1024 * 1) { // 降低门槛：1MB 即可开始播放
                                    println("BilibiliVideoPlayer: Using part video file")
                                    startPlaying(partFile.toURI().toString(), root)
                                } else {
                                    // B 站流需要 Referer 才能播放，JavaFX Media 不支持 header。
                                    // 所以我们直接进入 fallback 模式，让 Storage 下载并播放文件。
                                    showStatus("正在请求视频流...", root)
                                    triggerFallback(root)
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    private fun showStatus(text: String, root: StackPane) {
        Platform.runLater {
            root.children.clear()
            val label = Label(text)
            label.setTextFill(Color.WHITE)
            label.setStyle("-fx-font-size: 16px;")
            root.children.add(label)
        }
    }

    private fun triggerFallback(root: StackPane) {
        val track = currentTrack ?: return
        val videoFile = Storage.getVideoFile(track.source, track.id)
        
        fallbackJob?.cancel()
        fallbackJob = scope.launch {
            showStatus("正在准备视频资源...", root)
            
            var attempts = 0
            val partFile = File(videoFile.absolutePath + ".part")
            
            while (!videoFile.exists() && attempts < 30 && isActive) {
                if (attempts % 2 == 0) {
                    val status = if (partFile.exists()) {
                        val sizeMB = partFile.length() / (1024 * 1024)
                        "视频缓冲中 ($sizeMB MB)..."
                    } else {
                        "等待视频流响应..."
                    }
                    showStatus(status, root)
                }
                delay(1000)
                attempts++
                
                // 如果缓冲够了，尝试播放
                if (partFile.exists() && partFile.length() > 1024 * 1024 * 1.5) {
                    break
                }
            }
            
            if (isActive) {
                if (videoFile.exists()) {
                    startPlaying(videoFile.toURI().toString(), root)
                } else if (partFile.exists() && partFile.length() > 1024 * 1024 * 1) {
                    startPlaying(partFile.toURI().toString(), root)
                } else {
                    showStatus("视频加载超时，请检查网络。", root)
                }
            }
        }
    }

    private fun startPlaying(url: String, root: StackPane) {
        syncJob?.cancel()
        syncJob = null
        fallbackJob?.cancel()
        fallbackJob = null

        val action = Runnable {
            try {
                val oldPlayer = videoPlayer
                if (oldPlayer != null) {
                    oldPlayer.onReady = null
                    oldPlayer.onError = null
                    oldPlayer.onEndOfMedia = null
                    
                    if (oldPlayer.status != MediaPlayer.Status.DISPOSED) {
                        try {
                            if (oldPlayer.status == MediaPlayer.Status.PLAYING) {
                                oldPlayer.pause()
                            }
                        } catch (e: Exception) {}
                        oldPlayer.dispose()
                    }
                    videoPlayer = null
                }

                val media = Media(url)
                val player = MediaPlayer(media)
                videoPlayer = player
                player.isMute = true // 必须静音，防止干扰音频播放器
                
                val mediaView = MediaView(player)
                mediaView.isPreserveRatio = true
                mediaView.fitWidthProperty().bind(root.widthProperty())
                mediaView.fitHeightProperty().bind(root.heightProperty())
                
                root.children.clear()
                root.children.add(mediaView)
                
                player.onReady = Runnable {
                    if (videoPlayer === player && player.status != MediaPlayer.Status.DISPOSED) {
                        player.play()
                        startSync()
                    }
                }

                player.onError = Runnable errorLabel@{
                    if (videoPlayer !== player || player.status == MediaPlayer.Status.DISPOSED) return@errorLabel
                    
                    val err = player.error?.message ?: "Unknown error"
                    println("BilibiliVideoPlayer: Video player error: $err")
                    
                    if (!url.startsWith("file:")) {
                        triggerFallback(root)
                    }
                }
            } catch (e: Exception) {
                println("BilibiliVideoPlayer: Failed to start playing: ${e.message}")
            }
        }

        if (Platform.isFxApplicationThread()) {
            action.run()
        } else {
            Platform.runLater(action)
        }
    }

    private fun startSync() {
        syncJob?.cancel()
        val currentPlayer = videoPlayer ?: return
        
        syncJob = scope.launch {
            launch {
                audioPlayer.isPlaying.collect { playing ->
                    Platform.runLater {
                        try {
                            if (videoPlayer === currentPlayer && currentPlayer.status != MediaPlayer.Status.DISPOSED) {
                                if (playing) currentPlayer.play() else currentPlayer.pause()
                            }
                        } catch (e: Exception) {}
                    }
                }
            }
            
            while (isActive) {
                delay(1000)
                val audioPos = audioPlayer.positionSec.value
                
                Platform.runLater {
                    try {
                        if (videoPlayer === currentPlayer && currentPlayer.status != MediaPlayer.Status.DISPOSED) {
                            val status = currentPlayer.status
                            if (status == MediaPlayer.Status.PLAYING || status == MediaPlayer.Status.PAUSED || status == MediaPlayer.Status.READY) {
                                val videoPos = currentPlayer.currentTime.toSeconds()
                                if (Math.abs(audioPos - videoPos) > 1.5) { 
                                    currentPlayer.seek(javafx.util.Duration.seconds(audioPos))
                                }
                            }
                        }
                    } catch (e: Exception) {}
                }
            }
        }
    }

    fun closeVideo() {
        if (!isVisible && videoPlayer == null) return
        
        println("BilibiliVideoPlayer: Closing video window and cleaning up...")
        
        isVisible = false
        currentTrack = null
        
        syncJob?.cancel()
        syncJob = null
        fallbackJob?.cancel()
        fallbackJob = null
        
        val playerToDispose = videoPlayer
        videoPlayer = null
        
        val action = Runnable {
            try {
                if (playerToDispose != null) {
                    playerToDispose.onReady = null
                    playerToDispose.onError = null
                    playerToDispose.onEndOfMedia = null
                    
                    if (playerToDispose.status != MediaPlayer.Status.DISPOSED) {
                        try {
                            playerToDispose.stop() 
                        } catch (e: Exception) {}
                        playerToDispose.dispose()
                    }
                    println("BilibiliVideoPlayer: Video player disposed")
                }
                
                rootPane?.children?.clear()
            } catch (e: Exception) {
                println("BilibiliVideoPlayer: Error during close cleanup: ${e.message}")
            }
        }

        if (Platform.isFxApplicationThread()) {
            action.run()
        } else {
            Platform.runLater(action)
        }
    }
}

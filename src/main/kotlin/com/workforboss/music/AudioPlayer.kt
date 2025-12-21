package com.workforboss.music

import javafx.application.Platform
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.util.Duration
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.net.URI

class AudioPlayer {
    private var mediaPlayer: MediaPlayer? = null
    private var currentMedia: Media? = null
    private var currentPath: String? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var progressListener: javafx.beans.value.ChangeListener<javafx.util.Duration>? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying
    private val _positionSec = MutableStateFlow(0.0)
    val positionSec: StateFlow<Double> = _positionSec
    private val _durationSec = MutableStateFlow<Double?>(null)
    val durationSec: StateFlow<Double?> = _durationSec
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _currentOnlineTrack = MutableStateFlow<OnlineTrack?>(null)
    val currentOnlineTrack: StateFlow<OnlineTrack?> = _currentOnlineTrack

    private var loadJob: Job? = null
    var onFinished: (() -> Unit)? = null
    private var currentVolume: Double = 0.8

    companion object {
        private var isJfxInitialized = false
        fun initJfx() {
            if (!isJfxInitialized) {
                try {
                    Platform.startup {}
                    Platform.setImplicitExit(false) // 重要：防止关闭视频窗口时 JavaFX 退出导致音频停止
                    isJfxInitialized = true
                    println("AudioPlayer: JavaFX initialized")
                } catch (e: Exception) {
                    isJfxInitialized = true // Already initialized
                }
            }
        }
    }

    init {
        initJfx()
    }

    fun setVolume(p: Int) {
        currentVolume = (p / 100.0).coerceIn(0.0, 1.5)
        val player = mediaPlayer ?: return
        Platform.runLater {
            if (player.status != MediaPlayer.Status.DISPOSED) {
                applyVolumeAndBoost(player, currentVolume)
            }
        }
    }

    private fun applyVolumeAndBoost(player: MediaPlayer, vol: Double) {
        if (vol <= 1.0) {
            player.volume = vol
            val eq = player.audioEqualizer
            if (eq.isEnabled) eq.isEnabled = false
        } else {
            player.volume = 1.0
            val eq = player.audioEqualizer
            if (!eq.isEnabled) eq.isEnabled = true
            // gain = 20 * log10(vol)
            // For 1.5, gain is ~3.52 dB
            val gainDb = 20.0 * Math.log10(vol)
            eq.bands.forEach { it.gain = gainDb }
        }
    }

    suspend fun loadOnline(track: OnlineTrack) {
        loadJob?.cancelAndJoin()
        val job = currentCoroutineContext()[Job]
        loadJob = job
        
        stop(fadeOut = false)
        _currentOnlineTrack.value = track
        _error.value = "正在缓冲..."
        
        val file = Storage.getMusicFile(track.source, track.id)
        if (!file.exists()) {
            withContext(Dispatchers.IO) {
                try {
                    // 先下载音频，尽快播放
                    Storage.downloadMusic(track.previewUrl, file)
                    
                    // 音频下载完成后，开启后台任务下载视频（如果是 B 站）
                    if (track.source == "bilibili" && track.videoUrl != null) {
                        val videoFile = Storage.getVideoFile(track.source, track.id)
                        if (!videoFile.exists()) {
                            // 后台异步下载视频，不阻塞音频播放
                            scope.launch(Dispatchers.IO) {
                                try {
                                    println("AudioPlayer: Background downloading video for ${track.id}")
                                    Storage.downloadMusic(track.videoUrl, videoFile)
                                    println("AudioPlayer: Background video download finished for ${track.id}")
                                } catch (e: Exception) {
                                    println("AudioPlayer: Background video download failed: ${e.message}")
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    _error.value = "下载失败: ${e.message}"
                    return@withContext
                }
            }
        }
        
        if (file.exists()) {
            loadLocal(LocalTrack(
                id = track.id,
                title = track.title,
                artist = track.artist,
                album = track.album,
                durationMillis = track.durationMillis,
                path = file.absolutePath,
                source = track.source
            ), onlineInfo = track)
        } else {
            _error.value = "无法获取音频文件"
        }
    }

    suspend fun loadLocal(track: LocalTrack, onlineInfo: OnlineTrack? = null) {
        // 如果不是由 loadOnline 调用的（loadJob 不匹配），则处理 Job
        val currentJob = currentCoroutineContext()[Job]
        if (loadJob != currentJob) {
            loadJob?.cancelAndJoin()
            loadJob = currentJob
        }

        // 确保旧的播放器完全释放 (不使用淡出效果)
        stop(fadeOut = false)
        
        // 更新状态
        _currentOnlineTrack.value = onlineInfo
        currentPath = track.path
        _durationSec.value = track.durationMillis?.let { it / 1000.0 }
        
        // 检查是否需要后台下载视频 (针对 B 站收藏播放)
        if (onlineInfo != null && onlineInfo.source == "bilibili" && onlineInfo.videoUrl != null) {
            val videoFile = Storage.getVideoFile(onlineInfo.source, onlineInfo.id)
            if (!videoFile.exists()) {
                scope.launch(Dispatchers.IO) {
                    try {
                        println("AudioPlayer: Background downloading video for ${onlineInfo.id} (from local audio play)")
                        Storage.downloadMusic(onlineInfo.videoUrl, videoFile)
                        println("AudioPlayer: Background video download finished for ${onlineInfo.id}")
                    } catch (e: Exception) {
                        println("AudioPlayer: Background video download failed: ${e.message}")
                    }
                }
            }
        }
        
        // 增加延迟，确保 native 资源彻底释放
        delay(300)
        
        withContext(Dispatchers.Main) {
            try {
                val file = File(track.path)
                if (!file.exists()) throw Exception("文件不存在: ${track.path}")
                
                val uri = file.toURI().toString()
                
                // 显式持有 Media 引用
                val media = Media(uri)
                currentMedia = media
                
                val player = MediaPlayer(media)
                
                applyVolumeAndBoost(player, currentVolume)
                player.onReady = Runnable {
                    if (mediaPlayer === player) {
                        _durationSec.value = media.duration.toSeconds()
                    }
                }
                player.onEndOfMedia = Runnable {
                    if (mediaPlayer === player) {
                        _isPlaying.value = false
                        _positionSec.value = _durationSec.value ?: 0.0
                        onFinished?.invoke()
                    }
                }
                player.onError = Runnable {
                    if (mediaPlayer === player) {
                        val err = player.error?.message ?: "Unknown error"
                        println("AudioPlayer: MediaPlayer Error: $err")
                        _error.value = "播放错误: $err"
                        _isPlaying.value = false
                    }
                }
                
                mediaPlayer = player
                _error.value = null
            } catch (e: Exception) {
                println("AudioPlayer: Load error: ${e.message}")
                _error.value = "加载失败: ${e.message}"
            }
        }
    }

    fun play(fadeIn: Boolean = false) {
        val player = mediaPlayer ?: return
        if (_isPlaying.value) return

        Platform.runLater {
            try {
                if (mediaPlayer === player && player.status != MediaPlayer.Status.DISPOSED) {
                    // 如果需要淡入，先将音量设为 0
                    if (fadeIn) {
                        applyVolumeAndBoost(player, 0.0)
                    } else {
                        applyVolumeAndBoost(player, currentVolume)
                    }
                    
                    player.play()
                    _isPlaying.value = true
                    
                    if (fadeIn) {
                        scope.launch {
                            smoothFadeVolume(currentVolume)
                        }
                    }
                    
                    // 移除旧的监听器
                    progressListener?.let { player.currentTimeProperty().removeListener(it) }
                    
                    // 创建新的监听器并保存引用
                    val listener = javafx.beans.value.ChangeListener<javafx.util.Duration> { _, _, newValue ->
                        if (mediaPlayer === player && player.status != MediaPlayer.Status.DISPOSED) {
                            _positionSec.value = newValue.toSeconds()
                        }
                    }
                    progressListener = listener
                    player.currentTimeProperty().addListener(listener)
                }
            } catch (e: Exception) {
                println("AudioPlayer: Error during play: ${e.message}")
                _error.value = "播放失败: ${e.message}"
            }
        }
    }

    fun pause() {
        val player = mediaPlayer ?: return
        if (!_isPlaying.value) return
        
        _isPlaying.value = false
        Platform.runLater {
            try {
                if (mediaPlayer === player && player.status == MediaPlayer.Status.PLAYING) {
                    player.pause()
                }
            } catch (e: Exception) {
                println("AudioPlayer: Error during pause: ${e.message}")
            }
        }
    }

    private suspend fun smoothFadeVolume(target: Double, durationMs: Long = 300) {
        val player = mediaPlayer ?: return
        // 这里的 startVolume 尽量获取当前的逻辑音量
        val startVolume = if (player.audioEqualizer.isEnabled) {
            // 如果开启了均衡器，说明音量 > 1.0
            // 这里简单通过 currentVolume 判断，或者从 gainDb 还原
            currentVolume 
        } else {
            player.volume
        }
        
        val steps = 10
        val stepDelta = (target - startVolume) / steps
        val stepDelay = durationMs / steps

        for (i in 1..steps) {
            val nextVol = startVolume + stepDelta * i
            Platform.runLater {
                if (player.status != MediaPlayer.Status.DISPOSED) {
                    applyVolumeAndBoost(player, nextVol.coerceIn(0.0, 1.5))
                }
            }
            delay(stepDelay)
        }
    }

    suspend fun stop(fadeOut: Boolean = false) {
        if (fadeOut && _isPlaying.value) {
            smoothFadeVolume(0.0)
        }
        withContext(Dispatchers.Main) {
            performStop()
        }
    }

    private fun performStop() {
        _isPlaying.value = false
        val player = mediaPlayer
        mediaPlayer = null
        currentMedia = null
        
        if (player == null) return

        // 移除监听器和回调必须在 JFX 线程
        val action = Runnable {
            try {
                // 先移除监听器
                progressListener?.let { 
                    try {
                        player.currentTimeProperty().removeListener(it)
                    } catch (e: Exception) {}
                }
                
                // 置空回调
                player.onReady = null
                player.onEndOfMedia = null
                player.onError = null
                
                if (player.status != MediaPlayer.Status.DISPOSED) {
                    if (player.status == MediaPlayer.Status.PLAYING) {
                        player.pause()
                    }
                    player.stop()
                    player.dispose()
                }
                println("AudioPlayer: MediaPlayer disposed")
            } catch (e: Exception) {
                println("AudioPlayer: Error during stop/dispose: ${e.message}")
            }
        }

        if (Platform.isFxApplicationThread()) {
             action.run()
        } else {
             Platform.runLater(action)
        }
    }

    fun fullStop() {
        scope.launch {
            stop(fadeOut = false)
            _positionSec.value = 0.0
        }
    }

    fun seekTo(sec: Double) {
        val player = mediaPlayer ?: return
        Platform.runLater {
            try {
                if (mediaPlayer === player && player.status != MediaPlayer.Status.DISPOSED) {
                    player.seek(Duration.seconds(sec))
                    _positionSec.value = sec
                }
            } catch (e: Exception) {
                println("AudioPlayer: Error during seek: ${e.message}")
            }
        }
    }

    fun dispose() {
        scope.launch {
            stop(fadeOut = false)
            scope.cancel()
        }
    }
}

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
    private var isPendingPlay = false
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

    private fun showSourceTip(source: String?) {
        val name = when(source?.lowercase()) {
            "netease" -> "网易云音乐"
            "qq" -> "QQ音乐"
            "kugou" -> "酷狗音乐"
            "kuwo" -> "酷我音乐"
            "migu" -> "咪咕音乐"
            "bilibili" -> "Bilibili"
            "itunes" -> "iTunes"
            "local" -> "本地音乐"
            else -> "未知来源"
        }
        _error.value = "正在播放: $name"
        // 3秒后自动清除提示，除非已经有了真正的错误
        scope.launch {
            delay(3000)
            if (_error.value?.startsWith("正在播放:") == true) {
                _error.value = null
            }
        }
    }

    suspend fun loadOnline(track: OnlineTrack) {
        loadJob?.cancelAndJoin()
        // 使用独立作用域启动加载任务，不受调用方生命周期直接限制
        val currentJob = Job()
        loadJob = currentJob
        
        stop(fadeOut = false, resetPlayingState = false)
        _currentOnlineTrack.value = track
        showSourceTip(track.source)
        
        scope.launch(currentJob) {
            try {
                val file = Storage.getMusicFile(track.source, track.id)
                if (!file.exists()) {
                    withContext(Dispatchers.IO) {
                        try {
                            // 先下载音频，尽快播放
                            Storage.downloadMusic(track.previewUrl, file, headers = track.headers)
                            
                            // 音频下载完成后，开启后台任务下载视频（如果是 B 站）
                            if (track.source == "bilibili" && track.videoUrl != null) {
                                val videoFile = Storage.getVideoFile(track.source, track.id)
                                if (!videoFile.exists()) {
                                    // 后台异步下载视频，不阻塞音频播放
                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            println("AudioPlayer: Background downloading video for ${track.id}")
                                            Storage.downloadMusic(track.videoUrl, videoFile, headers = track.headers)
                                            println("AudioPlayer: Background video download finished for ${track.id}")
                                        } catch (e: Exception) {
                                            println("AudioPlayer: Background video download failed: ${e.message}")
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            if (e !is CancellationException) {
                                _error.value = "下载失败: ${e.message}"
                            }
                            throw e
                        }
                    }
                }
                
                if (file.exists()) {
                    // 如果正在下载或文件还很小，loadLocal 内部会处理重试
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
            } catch (e: CancellationException) {
                // 正常取消，不更新错误状态
            } catch (e: Exception) {
                _error.value = "播放失败: ${e.message}"
            }
        }
    }

    suspend fun loadLocal(track: LocalTrack, onlineInfo: OnlineTrack? = null) {
        // 取消之前的加载任务（注意：如果是 loadOnline 调用的，这里的 cancel 会取消 loadOnline 的 currentJob）
        // 我们需要区分是“用户点击切歌”还是“系统内部流转”
        if (onlineInfo == null) {
            loadJob?.cancelAndJoin()
            isPendingPlay = false
        }
        
        val currentJob = Job()
        // 只有当没有活跃的 loadJob 或者这是顶层调用时才更新 loadJob
        if (onlineInfo == null || loadJob?.isActive != true) {
            loadJob = currentJob
        }
        
        // 内部加载函数
        suspend fun doLoad(attempt: Int = 0) {
            if (!currentJob.isActive) return
            
            // 顶层调用时显示来源提示
            if (attempt == 0 && onlineInfo == null) {
                showSourceTip(track.source)
            }
            
            // 确保旧的播放器完全释放，但不重置 UI 播放意图
            stop(fadeOut = false, resetPlayingState = false)
            
            _currentOnlineTrack.value = onlineInfo
            currentPath = track.path
            _durationSec.value = track.durationMillis?.let { it / 1000.0 }
            
            // 指数级退避延迟
            val waitTime = when(attempt) {
                0 -> 400L
                1 -> 1200L
                else -> 2500L
            }
            delay(waitTime)
            
            if (!currentJob.isActive) return

            withContext(Dispatchers.Main) {
                try {
                    val file = File(track.path)
                    if (!file.exists()) throw Exception("文件不存在: ${track.path}")
                    
                    // 检查文件是否可读且不为空（防止下载中的残留）
                    if (file.length() < 1024) {
                         // 如果文件太小，可能还没写完，重试
                         if (attempt < 2) {
                             scope.launch(currentJob) { doLoad(attempt + 1) }
                             return@withContext
                         }
                    }

                    val uri = file.toURI().toString()
                    val media = Media(uri)
                    currentMedia = media
                    
                    val player = MediaPlayer(media)
                    applyVolumeAndBoost(player, currentVolume)
                    
                    player.onReady = Runnable {
                        if (mediaPlayer === player && currentJob.isActive) {
                            _durationSec.value = media.duration.toSeconds()
                            _error.value = null // 加载成功，清除所有报错或“缓冲中”提示
                            
                            // 修正：只要 _isPlaying 为 true，或者有待播放标记，就强制播放
                            if (isPendingPlay || _isPlaying.value) {
                                println("AudioPlayer: Auto-starting playback onReady (pending=$isPendingPlay, isPlaying=${_isPlaying.value})")
                                isPendingPlay = false
                                _isPlaying.value = true
                                
                                Platform.runLater {
                                    if (mediaPlayer === player && player.status != MediaPlayer.Status.DISPOSED) {
                                        applyVolumeAndBoost(player, currentVolume)
                                        player.play()
                                        
                                        // 绑定进度监听器
                                        progressListener?.let { player.currentTimeProperty().removeListener(it) }
                                        val listener = javafx.beans.value.ChangeListener<javafx.util.Duration> { _, _, newValue ->
                                            if (mediaPlayer === player && player.status != MediaPlayer.Status.DISPOSED) {
                                                _positionSec.value = newValue.toSeconds()
                                            }
                                        }
                                        progressListener = listener
                                        player.currentTimeProperty().addListener(listener)
                                    }
                                }
                            }
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
                            val err = player.error
                            val errMsg = err?.message ?: "Unknown error"
                            val errType = err?.type?.name ?: "UNKNOWN"
                            
                            println("AudioPlayer: MediaPlayer Error (attempt $attempt): [$errType] $errMsg")
                            
                            // 针对特定错误进行重试，必须在 currentJob 仍然活跃的情况下
                            if (currentJob.isActive && attempt < 3 && 
                                (errMsg.contains("ERROR_MEDIA_INVALID") || 
                                 errType == "MEDIA_INACCESSIBLE" || 
                                 errMsg.contains("0x80040265") ||
                                 errMsg.contains("HALT"))) {
                                
                                println("AudioPlayer: Transient error detected, retrying...")
                                // 在主线程之外启动重试，以避免阻塞
                                scope.launch {
                                    if (currentJob.isActive) {
                                        doLoad(attempt + 1)
                                    }
                                }
                                return@Runnable
                            }

                            _error.value = "播放错误: $errMsg"
                            _isPlaying.value = false
                        }
                    }
                    
                    mediaPlayer = player
                    _error.value = null
                    
                } catch (e: Exception) {
                    if (currentJob.isActive) {
                        println("AudioPlayer: Load error: ${e.message}")
                        _error.value = "加载失败: ${e.message}"
                    }
                }
            }
        }

        // 检查视频下载（B站专用）
        if (onlineInfo != null && onlineInfo.source == "bilibili" && onlineInfo.videoUrl != null) {
            val videoFile = Storage.getVideoFile(onlineInfo.source, onlineInfo.id)
            if (!videoFile.exists()) {
                scope.launch(Dispatchers.IO) {
                    try {
                        Storage.downloadMusic(onlineInfo.videoUrl, videoFile, headers = onlineInfo.headers)
                    } catch (e: Exception) {}
                }
            }
        }

        // 开始加载
        scope.launch(currentJob) {
            try {
                doLoad(0)
            } catch (e: CancellationException) {
                // 任务被取消，正常退出
            }
        }
    }

    fun play(fadeIn: Boolean = false) {
        _isPlaying.value = true // 立即同步 UI 状态
        val player = mediaPlayer
        if (player == null) {
            // 如果播放器还没准备好，标记为待播放
            if (loadJob?.isActive == true) {
                isPendingPlay = true
            }
            return
        }
        
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
        isPendingPlay = false
        val player = mediaPlayer
        if (player == null) {
            _isPlaying.value = false
            return
        }
        
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

    suspend fun stop(fadeOut: Boolean = false, resetPlayingState: Boolean = true) {
        if (fadeOut && _isPlaying.value) {
            smoothFadeVolume(0.0)
        }
        withContext(Dispatchers.Main) {
            performStop(resetPlayingState)
        }
    }

    private fun performStop(resetPlayingState: Boolean = true) {
        if (resetPlayingState) {
            _isPlaying.value = false
        }
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

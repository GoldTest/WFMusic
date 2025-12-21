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
    private var currentPath: String? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying
    private val _positionSec = MutableStateFlow(0.0)
    val positionSec: StateFlow<Double> = _positionSec
    private val _durationSec = MutableStateFlow<Double?>(null)
    val durationSec: StateFlow<Double?> = _durationSec
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    var onFinished: (() -> Unit)? = null
    private var currentVolume: Double = 0.8

    companion object {
        private var isJfxInitialized = false
        fun initJfx() {
            if (!isJfxInitialized) {
                try {
                    Platform.startup {}
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
        currentVolume = (p / 100.0).coerceIn(0.0, 1.0)
        mediaPlayer?.volume = currentVolume
    }

    suspend fun loadOnline(track: OnlineTrack) {
        stop()
        _error.value = "正在缓冲..."
        
        val file = Storage.getMusicFile(track.source, track.id)
        if (!file.exists()) {
            withContext(Dispatchers.IO) {
                try {
                    Storage.downloadMusic(track.previewUrl, file)
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
            ))
        } else {
            _error.value = "无法获取音频文件"
        }
    }

    suspend fun loadLocal(track: LocalTrack) {
        stop()
        currentPath = track.path
        _durationSec.value = track.durationMillis?.let { it / 1000.0 }
        
        withContext(Dispatchers.Main) {
            try {
                val file = File(track.path)
                val uri = file.toURI().toString()
                val media = Media(uri)
                val player = MediaPlayer(media)
                
                player.volume = currentVolume
                player.onReady = Runnable {
                    _durationSec.value = media.duration.toSeconds()
                }
                player.onEndOfMedia = Runnable {
                    _isPlaying.value = false
                    _positionSec.value = _durationSec.value ?: 0.0
                    onFinished?.invoke()
                }
                player.onError = Runnable {
                    _error.value = "播放错误: ${player.error?.message}"
                    _isPlaying.value = false
                }
                
                mediaPlayer = player
                _error.value = null
            } catch (e: Exception) {
                _error.value = "加载失败: ${e.message}"
            }
        }
    }

    fun play() {
        val player = mediaPlayer ?: return
        if (_isPlaying.value) return

        Platform.runLater {
            player.play()
            _isPlaying.value = true
            
            // 使用 currentTimeProperty 监听进度，比 coroutine loop 更准确且高效
            player.currentTimeProperty().addListener { _, _, newValue ->
                _positionSec.value = newValue.toSeconds()
            }
        }
    }

    fun pause() {
        mediaPlayer?.pause()
        _isPlaying.value = false
    }

    fun stop() {
        mediaPlayer?.stop()
        mediaPlayer?.dispose()
        mediaPlayer = null
        _isPlaying.value = false
    }

    fun fullStop() {
        stop()
        _positionSec.value = 0.0
    }

    fun seekTo(sec: Double) {
        mediaPlayer?.let { player ->
            Platform.runLater {
                player.seek(Duration.seconds(sec))
                _positionSec.value = sec
            }
        }
    }

    fun dispose() {
        fullStop()
        scope.cancel()
    }
}

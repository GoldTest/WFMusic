package com.workforboss.music

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javazoom.jl.player.Player
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.net.URL
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.FloatControl
import javax.sound.sampled.Port

class AudioPlayer {
    private var player: Player? = null
    private var currentPath: String? = null
    private var playerJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying
    private val _positionSec = MutableStateFlow(0.0)
    val positionSec: StateFlow<Double> = _positionSec
    private val _durationSec = MutableStateFlow<Double?>(null)
    val durationSec: StateFlow<Double?> = _durationSec
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    var onFinished: (() -> Unit)? = null

    private var seekOffsetSec = 0.0
    private var contentLength: Long? = null
    private var currentVolume: Float = 0.8f

    fun setVolume(p: Int) {
        currentVolume = p / 100f
        updateDeviceVolume()
    }

    private fun updateDeviceVolume() {
        try {
            val p = player ?: return
            
            // 方案 1: 尝试通过反射 JLayer 的 device -> line 设置音量 (针对 SourceDataLine)
            try {
                val deviceField = p.javaClass.getDeclaredField("device")
                deviceField.isAccessible = true
                val device = deviceField.get(p)
                
                val lineField = device.javaClass.getDeclaredField("line")
                lineField.isAccessible = true
                val line = lineField.get(device) as? javax.sound.sampled.SourceDataLine
                
                if (line != null && line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                    val gainControl = line.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl
                    val volume = currentVolume.coerceIn(0.0001f, 1f)
                    val dB = (Math.log10(volume.toDouble()) * 20.0).toFloat()
                    gainControl.value = dB.coerceIn(gainControl.minimum, gainControl.maximum)
                    return // 成功设置，退出
                }
            } catch (e: Exception) {
                // 方案 1 失败，尝试方案 2
            }

            // 方案 2: 尝试直接控制系统的 MASTER_GAIN (针对某些系统环境)
            try {
                val mixers = AudioSystem.getMixerInfo()
                for (mixerInfo in mixers) {
                    val mixer = AudioSystem.getMixer(mixerInfo)
                    val lineInfos = mixer.sourceLineInfo
                    for (lineInfo in lineInfos) {
                        val line = mixer.getLine(lineInfo)
                        if (line is javax.sound.sampled.SourceDataLine && line.isOpen) {
                            if (line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                                val gainControl = line.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl
                                val volume = currentVolume.coerceIn(0.0001f, 1f)
                                val dB = (Math.log10(volume.toDouble()) * 20.0).toFloat()
                                gainControl.value = dB.coerceIn(gainControl.minimum, gainControl.maximum)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                println("Failed to set volume via Mixer: ${e.message}")
            }

        } catch (e: Exception) {
            println("Failed to set volume: ${e.message}")
        }
    }

    suspend fun loadOnline(track: OnlineTrack) {
        stop()
        currentPath = track.previewUrl
        _durationSec.value = track.durationMillis?.let { it / 1000.0 }
        seekOffsetSec = 0.0
        
        // Try to get content length for seeking
        withContext(Dispatchers.IO) {
            runCatching {
                val conn = java.net.URL(track.previewUrl).openConnection()
                contentLength = conn.contentLengthLong.takeIf { it > 0 }
            }
        }
    }

    suspend fun loadLocal(track: LocalTrack) {
        stop()
        currentPath = File(track.path).absolutePath
        _durationSec.value = track.durationMillis?.let { it / 1000.0 }
        seekOffsetSec = 0.0
        contentLength = File(track.path).length()
    }

    fun play() {
        val path = currentPath ?: return
        if (_isPlaying.value) return

        playerJob = scope.launch {
            try {
                val inputStream = if (path.startsWith("http")) {
                    val conn = java.net.URL(path).openConnection()
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                    if (path.contains("qqmusic") || path.contains("qq.com")) {
                        conn.setRequestProperty("Referer", "https://y.qq.com/")
                    } else if (path.contains("migu.cn")) {
                        conn.setRequestProperty("Referer", "https://m.music.migu.cn/")
                    }
                    conn.connect()
                    conn.getInputStream()
                } else {
                    java.io.FileInputStream(path)
                }
                
                // 模拟 Seek: 跳过字节
                if (seekOffsetSec > 0 && _durationSec.value != null && _durationSec.value!! > 0) {
                    val total = _durationSec.value!!
                    val cl = contentLength
                    if (cl != null) {
                        val skipBytes = (cl * (seekOffsetSec / total)).toLong()
                        inputStream.skip(skipBytes)
                    }
                }

                val bufferedStream = java.io.BufferedInputStream(inputStream)
                val p = Player(bufferedStream)
                player = p
                
                // 延迟一小会儿等待 JLayer 初始化底层音频设备
                scope.launch {
                    delay(500)
                    updateDeviceVolume()
                }
                
                _isPlaying.value = true
                _error.value = null
                
                // 如果外部没传时长，尝试从 JLayer 解析 (JLayer 的 position 是基于解码帧的，这里只能作为兜底)
                if (_durationSec.value == null || _durationSec.value == 0.0) {
                    // 注意：JLayer Player 很难直接拿总时长，通常需要预解析 MP3 Header
                    // 这里我们设置一个较大的默认值，或者保持 null 让 UI 处理
                }

                // 启动进度更新协程
                val progressJob = launch {
                    while (isActive) {
                        val currentPos = seekOffsetSec + (p.position.toDouble() / 1000.0)
                        _positionSec.value = currentPos
                        
                        // 兜底逻辑：如果当前进度超过了已知总时长，自动更新总时长（防止进度条不走）
                        val currentDur = _durationSec.value ?: 0.0
                        if (currentPos > currentDur) {
                            _durationSec.value = currentPos + 30.0 // 动态预估
                        }
                        
                        delay(200)
                    }
                }

                try {
                    println("AudioPlayer: Starting playback of $path")
                    p.play()
                    println("AudioPlayer: Playback finished normally")
                } catch (e: Exception) {
                    println("AudioPlayer: Error during playback: ${e.message}")
                    e.printStackTrace()
                    throw e
                } finally {
                    progressJob.cancel()
                }
                
                // When finished normally
                if (_isPlaying.value) {
                    _isPlaying.value = false
                    _positionSec.value = _durationSec.value ?: 0.0
                    onFinished?.invoke()
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    _error.value = "播放失败: ${e.message}"
                }
                _isPlaying.value = false
            } finally {
                player = null
            }
        }
    }

    fun pause() {
        // JLayer basic Player doesn't support pause/resume.
        // We save the current position and stop.
        pollStatus()
        seekOffsetSec = _positionSec.value
        stop()
    }

    fun stop() {
        playerJob?.cancel()
        player?.close()
        player = null
        _isPlaying.value = false
        // 不在这里重置 _positionSec.value = 0.0，因为它由内部协程或 fullStop 处理
    }

    fun fullStop() {
        stop()
        seekOffsetSec = 0.0
        _positionSec.value = 0.0
    }

    fun pollStatus() {
        // 此方法已废弃，由内部协程自动更新
    }

    fun seekTo(sec: Double) {
        val total = _durationSec.value ?: 0.0
        seekOffsetSec = sec.coerceIn(0.0, total)
        val wasPlaying = _isPlaying.value
        stop()
        if (wasPlaying) {
            play()
        } else {
            _positionSec.value = seekOffsetSec
        }
    }

    fun dispose() {
        fullStop()
        scope.cancel()
    }
}

package com.workforboss.music

import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

object Storage {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val appDir: File by lazy {
        // 使用用户文档目录下的 WFMusic 文件夹，而不是用户根目录下的隐藏文件夹
        val documentsDir = File(System.getProperty("user.home"), "Documents")
        val dir = File(documentsDir, "WFMusic")
        if (!dir.exists()) dir.mkdirs()
        dir
    }
    private val libraryFile: File by lazy { File(appDir, "music_library.json") }
    private val musicDir: File by lazy { File(appDir, "Music").apply { if (!exists()) mkdirs() } }
    private val cacheDir: File by lazy { File(appDir, "cache").apply { if (!exists()) mkdirs() } }

    fun getMusicFile(source: String, id: String): File {
        val sourceDir = File(musicDir, source).apply { if (!exists()) mkdirs() }
        return when (source) {
            "local" -> {
                if (id.contains(".")) File(sourceDir, id)
                else File(sourceDir, "$id.mp3")
            }
            "bilibili", "itunes" -> File(sourceDir, "$id.m4a")
            else -> File(sourceDir, "$id.mp3")
        }
    }

    fun getVideoFile(source: String, id: String): File {
        val sourceDir = File(musicDir, source).apply { if (!exists()) mkdirs() }
        return File(sourceDir, "$id.mp4")
    }

    fun loadLibrary(): LibraryState {
        if (!libraryFile.exists()) return LibraryState()
        return json.decodeFromString(LibraryState.serializer(), libraryFile.readText())
    }

    fun saveLibrary(state: LibraryState) {
        libraryFile.writeText(json.encodeToString(LibraryState.serializer(), state))
    }


    fun downloadMusic(url: String, target: File, onProgress: ((Float) -> Unit)? = null) {
        if (target.exists()) return
        val parent = target.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        
        // 直接下载到目标文件，这样部分下载的内容也可以尝试播放
        val tmp = File(target.absolutePath + ".part")
        try {
            val conn = URI(url).toURL().openConnection()
            // 使用更像真实浏览器的 User-Agent
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            if (url.contains("qqmusic") || url.contains("qq.com")) {
                conn.setRequestProperty("Referer", "https://y.qq.com/")
            } else if (url.contains("migu.cn")) {
                conn.setRequestProperty("Referer", "https://m.music.migu.cn/")
            } else if (url.contains("bilibili.com") || url.contains("bilivideo.com")) {
                conn.setRequestProperty("Referer", "https://www.bilibili.com/")
                // B 站视频有时需要特定的 Origin
                conn.setRequestProperty("Origin", "https://www.bilibili.com")
            }
            
            val totalSize = conn.contentLengthLong
            conn.getInputStream().use { input ->
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalRead: Long = 0
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        if (totalSize > 0) {
                            onProgress?.invoke(totalRead.toFloat() / totalSize)
                        }
                    }
                }
            }
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            if (tmp.exists()) tmp.delete()
            throw e
        }
    }

    fun moveLocalTrackFile(path: String, toDir: File): Boolean {
        val f = File(path)
        if (!f.exists()) return false
        if (!toDir.exists()) toDir.mkdirs()
        return runCatching {
            Files.move(f.toPath(), File(toDir, f.name).toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.isSuccess
    }

    fun deleteLocalTrackFile(path: String): Boolean {
        val f = File(path)
        if (f.exists()) {
            return f.delete()
        }
        return false
    }
}

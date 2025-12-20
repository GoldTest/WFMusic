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
        // 使用用户文档目录下的 WorkForBoss 文件夹，而不是用户根目录下的隐藏文件夹
        val documentsDir = File(System.getProperty("user.home"), "Documents")
        val dir = File(documentsDir, "WorkForBoss")
        if (!dir.exists()) dir.mkdirs()
        dir
    }
    private val libraryFile: File by lazy { File(appDir, "music_library.json") }
    private val musicDir: File by lazy { File(appDir, "Music").apply { if (!exists()) mkdirs() } }
    private val cacheDir: File by lazy { File(appDir, "cache").apply { if (!exists()) mkdirs() } }

    fun getMusicFile(source: String, id: String): File {
        // 按照 来源/ID.mp3 存储，确保唯一性
        val sourceDir = File(musicDir, source).apply { if (!exists()) mkdirs() }
        // 如果是本地音乐，ID 可能已经包含了扩展名，或者我们统一使用 ID
        return if (source == "local") {
            // 检查是否已经有扩展名，如果没有则补充
            if (id.contains(".")) File(sourceDir, id)
            else File(sourceDir, "$id.mp3")
        } else {
            File(sourceDir, "$id.mp3")
        }
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
        val tmp = File.createTempFile("download", ".tmp", cacheDir)
        try {
            val conn = URI(url).toURL().openConnection()
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
            if (url.contains("qqmusic") || url.contains("qq.com")) {
                conn.setRequestProperty("Referer", "https://y.qq.com/")
            } else if (url.contains("migu.cn")) {
                conn.setRequestProperty("Referer", "https://m.music.migu.cn/")
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
            tmp.delete()
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

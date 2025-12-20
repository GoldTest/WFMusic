package com.workforboss.music

import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

object Storage {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val appDir: File by lazy {
        val dir = File(System.getProperty("user.home"), ".workforboss")
        if (!dir.exists()) dir.mkdirs()
        dir
    }
    private val libraryFile: File by lazy { File(appDir, "music_library.json") }
    private val cacheDir: File by lazy { File(appDir, "cache").apply { if (!exists()) mkdirs() } }

    fun loadLibrary(): LibraryState {
        if (!libraryFile.exists()) return LibraryState()
        return json.decodeFromString(LibraryState.serializer(), libraryFile.readText())
    }

    fun saveLibrary(state: LibraryState) {
        libraryFile.writeText(json.encodeToString(LibraryState.serializer(), state))
    }

    fun cacheFileNameFor(url: String): File {
        val name = url.hashCode().toString() + ".mp3"
        return File(cacheDir, name)
    }

    fun cacheOnlineAudio(url: String) : File {
        val target = cacheFileNameFor(url)
        if (target.exists()) return target
        val tmp = File.createTempFile("audio", ".tmp", cacheDir)
        java.net.URL(url).openStream().use { input ->
            Files.copy(input, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return target
    }

    fun deleteLocalTrackFile(path: String): Boolean {
        return runCatching { Files.deleteIfExists(Path.of(path)) }.isSuccess
    }

    fun moveLocalTrackFile(path: String, toDir: File): Boolean {
        val f = File(path)
        if (!f.exists()) return false
        if (!toDir.exists()) toDir.mkdirs()
        return runCatching {
            Files.move(f.toPath(), File(toDir, f.name).toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.isSuccess
    }
}

package com.workforboss.music

import java.io.File
import java.util.UUID

object LocalLibrary {
    fun importFiles(files: List<File>, state: LibraryState): LibraryState {
        val newTracks = files.mapNotNull { f ->
            runCatching {
                LocalTrack(
                    id = UUID.randomUUID().toString(),
                    path = f.absolutePath,
                    title = f.nameWithoutExtension,
                    artist = null,
                    album = null,
                    durationMillis = null
                )
            }.getOrElse { null }
        }
        val merged = state.localTracks + newTracks
        return state.copy(localTracks = merged)
    }

    fun deleteTrack(trackId: String, state: LibraryState): LibraryState {
        val track = state.localTracks.find { it.id == trackId } ?: return state
        Storage.deleteLocalTrackFile(track.path)
        return state.copy(localTracks = state.localTracks.filterNot { it.id == trackId })
    }

    fun moveTrack(trackId: String, toDir: File, state: LibraryState): LibraryState {
        val track = state.localTracks.find { it.id == trackId } ?: return state
        val ok = Storage.moveLocalTrackFile(track.path, toDir)
        if (!ok) return state
        val movedPath = File(toDir, File(track.path).name).absolutePath
        val updated = state.localTracks.map {
            if (it.id == trackId) it.copy(path = movedPath) else it
        }
        return state.copy(localTracks = updated)
    }

    fun updateMeta(trackId: String, title: String?, artist: String?, album: String?, state: LibraryState): LibraryState {
        val updated = state.localTracks.map {
            if (it.id == trackId) it.copy(title = title, artist = artist, album = album) else it
        }
        return state.copy(localTracks = updated)
    }
}

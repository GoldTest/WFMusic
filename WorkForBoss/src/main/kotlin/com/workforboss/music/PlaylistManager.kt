package com.workforboss.music

import java.util.UUID

object PlaylistManager {
    fun createPlaylist(name: String, state: LibraryState): LibraryState {
        val p = Playlist(id = UUID.randomUUID().toString(), name = name)
        return state.copy(playlists = state.playlists + p)
    }

    fun renamePlaylist(id: String, name: String, state: LibraryState): LibraryState {
        return state.copy(playlists = state.playlists.map {
            if (it.id == id) it.copy(name = name) else it
        })
    }

    fun deletePlaylist(id: String, state: LibraryState): LibraryState {
        return state.copy(playlists = state.playlists.filterNot { it.id == id })
    }

    fun addToPlaylist(playlistId: String, item: MusicItemId, state: LibraryState): LibraryState {
        return state.copy(playlists = state.playlists.map {
            if (it.id == playlistId) it.copy(items = it.items + item) else it
        })
    }

    fun removeFromPlaylist(playlistId: String, index: Int, state: LibraryState): LibraryState {
        return state.copy(playlists = state.playlists.map {
            if (it.id == playlistId) it.copy(items = it.items.toMutableList().also { l ->
                if (index in l.indices) l.removeAt(index)
            }) else it
        })
    }

    fun reorderPlaylist(playlistId: String, from: Int, to: Int, state: LibraryState): LibraryState {
        return state.copy(playlists = state.playlists.map {
            if (it.id == playlistId) {
                val items = it.items.toMutableList()
                if (from in items.indices && to in items.indices) {
                    val item = items.removeAt(from)
                    items.add(to, item)
                }
                it.copy(items = items)
            } else it
        })
    }

    fun recordPlay(item: MusicItemId, state: LibraryState): LibraryState {
        val hist = PlayHistoryItem(at = System.currentTimeMillis(), item = item)
        val newHist = (listOf(hist) + state.playHistory).take(200)
        return state.copy(playHistory = newHist)
    }
}


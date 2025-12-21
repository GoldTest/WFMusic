package com.workforboss.music

import kotlinx.serialization.Serializable

@Serializable
data class MusicItemId(
    val id: String,
    val source: String, // "netease", "qq", "kugou", "local", etc.
    val title: String = "",
    val artist: String = "",
    val album: String? = null,
    val durationMs: Long? = null,
    val coverUrl: String? = null
)

@Serializable
data class OnlineTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMillis: Long? = null,
    val previewUrl: String,
    val coverUrl: String? = null,
    val source: String
)

@Serializable
data class LocalTrack(
    val id: String,
    val path: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMillis: Long? = null,
    val source: String = "local"
)

@Serializable
data class LibraryState(
    val localTracks: List<LocalTrack> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val playHistory: List<PlayHistoryItem> = emptyList()
)

@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val items: List<MusicItemId> = emptyList()
)

@Serializable
data class PlayHistoryItem(
    val at: Long,
    val item: MusicItemId
)


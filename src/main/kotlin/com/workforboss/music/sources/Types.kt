package com.workforboss.music.sources

import kotlinx.serialization.Serializable

@Serializable
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long? = null,
    val coverUrl: String? = null,
    val source: String
)

interface SourceAdapter {
    val name: String
    suspend fun search(q: String, page: Int = 1): List<Track>
    suspend fun streamUrl(id: String): String
    suspend fun lyrics(id: String): String?
}


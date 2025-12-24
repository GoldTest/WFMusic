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
    val quality: String? = null,
    val source: String,
    val videoUrl: String? = null,
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
    val videoQuality: String? = null,
    val headers: Map<String, String>? = null
)

@Serializable
data class StreamResult(
    val url: String,
    val quality: String? = null,
    val videoUrl: String? = null,
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
    val videoQuality: String? = null,
    val headers: Map<String, String>? = null
)

interface SourceAdapter {
    val name: String
    suspend fun search(q: String, page: Int = 1): List<Track>
    suspend fun streamUrl(id: String): StreamResult
    suspend fun lyrics(id: String): String?
    suspend fun recommendations(page: Int = 1): List<Track> = emptyList()
}


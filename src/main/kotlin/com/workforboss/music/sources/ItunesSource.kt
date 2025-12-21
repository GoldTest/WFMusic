package com.workforboss.music.sources

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder

class ItunesSource : SourceAdapter {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }
    override val name: String = "itunes"

    override suspend fun search(q: String, page: Int): List<Track> {
        val limit = 30
        val offset = (page - 1) * limit
        val url = "https://itunes.apple.com/search?media=music&entity=song&limit=$limit&offset=$offset&term=${URLEncoder.encode(q, "UTF-8")}"
        val resp: SearchResponse = client.get(url).body()
        return resp.results.map {
            Track(
                id = it.trackId?.toString() ?: "${it.artistName}-${it.trackName}",
                title = it.trackName ?: "",
                artist = it.artistName ?: "",
                album = it.collectionName,
                durationMs = it.trackTimeMillis,
                coverUrl = it.artworkUrl100,
                source = "itunes"
            )
        }
    }

    override suspend fun streamUrl(id: String): String {
        val url = "https://itunes.apple.com/lookup?id=$id"
        val data: SearchResponse = client.get(url).body()
        val item = data.results.firstOrNull()
        return item?.previewUrl ?: throw IllegalStateException("preview url not found")
    }

    override suspend fun lyrics(id: String): String? = null
}

@Serializable
data class SearchResponse(val resultCount: Int, val results: List<SearchItem>)
@Serializable
data class SearchItem(
    @SerialName("trackId") val trackId: Long? = null,
    @SerialName("trackName") val trackName: String? = null,
    @SerialName("artistName") val artistName: String? = null,
    @SerialName("collectionName") val collectionName: String? = null,
    @SerialName("artworkUrl100") val artworkUrl100: String? = null,
    @SerialName("previewUrl") val previewUrl: String? = null,
    @SerialName("trackTimeMillis") val trackTimeMillis: Long? = null
)


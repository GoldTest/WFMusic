package com.workforboss.music

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class ItunesApi {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                }
            )
        }
    }

    suspend fun search(term: String, limit: Int = 30): List<OnlineTrack> {
        val url =
            "https://itunes.apple.com/search?media=music&entity=song&limit=$limit&term=${term.encodeUrl()}"
        val resp: SearchResponse = client.get(url).body()
        return resp.results.map {
            OnlineTrack(
                id = it.trackId?.toString() ?: "${it.artistName}-${it.trackName}",
                title = it.trackName ?: "",
                artist = it.artistName ?: "",
                album = it.collectionName,
                durationMillis = it.trackTimeMillis,
                previewUrl = it.previewUrl ?: "",
                coverUrl = it.artworkUrl100,
                source = "itunes"
            )
        }
    }
}

private fun String.encodeUrl(): String = java.net.URLEncoder.encode(this, Charsets.UTF_8)

@Serializable
data class SearchResponse(
    val resultCount: Int,
    val results: List<SearchItem>
)

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


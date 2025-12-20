package com.workforboss.music.sources

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class NeteaseSource : SourceAdapter {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }
    override val name: String = "netease"
    private val bases: List<String> = run {
        val env = System.getenv("NETEASE_API_BASE")
        if (!env.isNullOrBlank()) listOf(env) else listOf(
            "https://music.163.com/api", // 直连
            "https://netease-cloud-music-api-ptr.vercel.app" // 备选
        )
    }

    override suspend fun search(q: String): List<Track> {
        // 先尝试直连搜索接口 (不需要 Vercel 代理)
        val directResult = runCatching {
            val resp: NeteaseDirectSearchResp = client.get("https://music.163.com/api/search/get/web") {
                url {
                    parameters.append("s", q)
                    parameters.append("type", "1")
                    parameters.append("offset", "0")
                    parameters.append("limit", "30")
                }
            }.body()
            resp.result?.songs ?: emptyList()
        }.getOrNull()

        if (directResult != null && directResult.isNotEmpty()) {
            // 直连搜索不带封面，需要批量获取详情
            val ids = directResult.map { it.id }
            val details = runCatching {
                val resp: NeteaseSongDetailResp = client.get("https://music.163.com/api/song/detail") {
                    url { parameters.append("ids", ids.joinToString(",", "[", "]")) }
                }.body()
                resp.songs.associateBy { it.id }
            }.getOrNull() ?: emptyMap()

            return directResult.map { s ->
                val detail = details[s.id]
                Track(
                    id = s.id.toString(),
                    title = s.name ?: "",
                    artist = s.artists?.joinToString("/") { it.name ?: "Unknown" } ?: "Unknown",
                    album = s.album?.name,
                    durationMs = s.duration,
                    coverUrl = detail?.al?.picUrl ?: detail?.album?.picUrl ?: s.album?.picUrl,
                    source = "netease"
                )
            }
        }

        // 如果直连失败，回退到代理
        for (b in bases) {
            if (b.contains("music.163.com")) continue
            val result = runCatching {
                val data: NeteaseSearchResp = client.get("$b/search") {
                    url { parameters.append("keywords", q); parameters.append("limit", "30") }
                }.body()
                data.result?.songs ?: emptyList()
            }.getOrNull() ?: emptyList()
            
            if (result.isNotEmpty()) {
                return result.map { s ->
                    Track(
                        id = s.id.toString(),
                        title = s.name,
                        artist = s.ar.joinToString("/") { a -> a.name },
                        album = s.al?.name,
                        durationMs = s.dt,
                        coverUrl = s.al?.picUrl,
                        source = "netease"
                    )
                }
            }
        }
        return emptyList()
    }

    override suspend fun streamUrl(id: String): String {
        // 先尝试直连官方接口获取播放地址
        val directUrl = runCatching {
            val data: NeteaseUrlResp = client.get("https://music.163.com/api/song/enhance/player/url") {
                url {
                    parameters.append("id", id)
                    parameters.append("ids", "[$id]")
                    parameters.append("br", "320000")
                }
            }.body()
            data.data?.firstOrNull()?.url
        }.getOrNull()

        if (!directUrl.isNullOrBlank()) return directUrl

        // 回退到代理
        for (b in bases) {
            if (b.contains("music.163.com")) continue
            val url = runCatching {
                val data: NeteaseUrlResp = client.get("$b/song/url/v1") {
                    url { parameters.append("id", id); parameters.append("level", "standard") }
                }.body()
                data.data?.firstOrNull()?.url
            }.getOrNull()
            if (!url.isNullOrBlank()) return url
        }
        throw IllegalStateException("netease url not found")
    }

    override suspend fun lyrics(id: String): String? {
        // 先尝试直连官方接口获取歌词
        val directLrc = runCatching {
            val data: NeteaseLyricResp = client.get("https://music.163.com/api/song/lyric") {
                url {
                    parameters.append("id", id)
                    parameters.append("lv", "-1")
                    parameters.append("kv", "-1")
                    parameters.append("tv", "-1")
                }
            }.body()
            data.lrc?.lyric
        }.getOrNull()

        if (!directLrc.isNullOrBlank()) return directLrc

        // 回退到代理
        for (b in bases) {
            if (b.contains("music.163.com")) continue
            val lrc = runCatching {
                val data: NeteaseLyricResp = client.get("$b/lyric") { url { parameters.append("id", id) } }.body()
                data.lrc?.lyric
            }.getOrNull()
            if (!lrc.isNullOrBlank()) return lrc
        }
        return null
    }
}

@Serializable
data class NeteaseSongDetailResp(val songs: List<Song> = emptyList())

@Serializable
data class NeteaseDirectSearchResp(val result: DirectSearchResult? = null)
@Serializable
data class DirectSearchResult(val songs: List<DirectSong>? = null)
@Serializable
data class DirectSong(val id: Long, val name: String? = null, val artists: List<DirectArtist>? = null, val album: DirectAlbum? = null, val duration: Long? = null)
@Serializable
data class DirectArtist(val name: String? = null)
@Serializable
data class DirectAlbum(val name: String? = null, val picUrl: String? = null)

@Serializable
data class NeteaseSearchResp(val result: SearchResult?)
@Serializable
data class SearchResult(val songs: List<Song> = emptyList())
@Serializable
data class Song(
    val id: Long, 
    val name: String, 
    val ar: List<Artist> = emptyList(), 
    val al: Album? = null,
    val artists: List<Artist> = emptyList(),
    val album: Album? = null,
    val dt: Long? = null,
    val duration: Long? = null
)
@Serializable
data class Artist(val name: String)
@Serializable
data class Album(val name: String? = null, val picUrl: String? = null)
@Serializable
data class NeteaseUrlResp(val data: List<UrlItem>? = null)
@Serializable
data class UrlItem(val url: String? = null)
@Serializable
data class NeteaseLyricResp(val lrc: Lyric? = null)
@Serializable
data class Lyric(val lyric: String? = null)

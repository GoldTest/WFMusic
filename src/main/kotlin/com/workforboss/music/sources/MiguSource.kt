package com.workforboss.music.sources

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

class MiguSource : SourceAdapter {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }
    override val name: String = "migu"

    override suspend fun search(q: String, page: Int): List<Track> {
        val limit = 30
        val proxies = listOf(
            "https://api.paugram.com/music",
            "https://api.liuzhijin.cn",
            "https://api.v0.pw/music"
        )
        // 1. 尝试直连新版搜索接口
        val directResult = runCatching {
            val resp: String = client.get("https://c.musicapp.migu.cn/MIGUM2.0/v1.0/content/search_all.do") {
                url {
                    parameters.append("text", q)
                    parameters.append("searchSwitch", "{song:1}")
                    parameters.append("pageSize", limit.toString())
                    parameters.append("pageNo", page.toString())
                }
                header("Referer", "https://m.music.migu.cn/")
                header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
            }.body()
            
            val json = Json { ignoreUnknownKeys = true }.parseToJsonElement(resp)
            val list = json.jsonObject["songResultData"]?.jsonObject?.get("result")?.jsonArray ?: emptyJsonArray
            list.map { it.jsonObject }
        }.getOrNull()

        if (directResult != null && directResult.isNotEmpty()) {
            return directResult.map { s ->
                val copyrightId = s["copyrightId"]?.jsonPrimitive?.content ?: ""
                val contentId = s["contentId"]?.jsonPrimitive?.content ?: ""
                Track(
                    id = if (contentId.isNotEmpty()) "$copyrightId|$contentId" else copyrightId,
                    title = s["name"]?.jsonPrimitive?.content ?: "",
                    artist = s["singers"]?.jsonArray?.get(0)?.jsonObject?.get("name")?.jsonPrimitive?.content ?: "Unknown",
                    album = s["albums"]?.jsonArray?.get(0)?.jsonObject?.get("name")?.jsonPrimitive?.content,
                    durationMs = s["duration"]?.jsonPrimitive?.content?.toLongOrNull() 
                        ?: s["length"]?.jsonPrimitive?.content?.toLongOrNull() 
                        ?: s["time"]?.jsonPrimitive?.content?.let { t ->
                            // 有些接口返回 "04:30" 格式
                            if (t.contains(":")) {
                                val parts = t.split(":")
                                if (parts.size == 2) {
                                    (parts[0].toLong() * 60 + parts[1].toLong()) * 1000
                                } else null
                            } else t.toLongOrNull()
                        },
                    coverUrl = s["imgItems"]?.jsonArray?.get(0)?.jsonObject?.get("img")?.jsonPrimitive?.content,
                    source = "migu"
                )
            }
        }
        // ... 其他代理实现暂时不支持分页
        return emptyList()
    }

    private val emptyJsonArray = JsonArray(emptyList())

    override suspend fun streamUrl(id: String): StreamResult {
        val ids = id.split("|")
        val copyrightId = ids[0]
        val contentId = if (ids.size > 1) ids[1] else ""

        // 1. 优先尝试移动端播放接口 (listenSong.do)，它通常能返回带 Key 和 Tim 的完整播放链接
        if (contentId.isNotEmpty()) {
            val listenUrl = runCatching {
                val resp: String = client.get("https://c.musicapp.migu.cn/MIGUM2.0/v1.0/content/listenSong.do") {
                    parameter("netType", "01")
                    parameter("resourceType", "E")
                    parameter("songId", contentId)
                    parameter("rateType", "1")
                    header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 13_2_3 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/13.0.3 Mobile/15E148 Safari/604.1")
                }.body()
                val json = Json { ignoreUnknownKeys = true }.parseToJsonElement(resp)
                json.jsonObject["url"]?.jsonPrimitive?.content?.let {
                    if (it.startsWith("//")) "https:$it" else it
                }
            }.getOrNull()
            if (!listenUrl.isNullOrBlank()) return StreamResult(listenUrl, "标准")
        }

        // 2. 尝试官方 v3 播放接口 (模拟网页版点击)
        val v3Url = runCatching {
            val resp: String = client.get("https://music.migu.cn/v3/api/music/audioPlayer/getPlayInfo") {
                parameter("dataType", "2")
                parameter("copyrightId", copyrightId)
                header("Referer", "https://music.migu.cn/v3/music/player/audio?from=migu")
                header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/110.0.0.0 Safari/537.36")
            }.body()
            val json = Json { ignoreUnknownKeys = true }.parseToJsonElement(resp)
            json.jsonObject["data"]?.jsonObject?.get("playUrl")?.jsonPrimitive?.content?.let {
                if (it.startsWith("//")) "https:$it" else it
            }
        }.getOrNull()
        if (!v3Url.isNullOrBlank()) return StreamResult(v3Url, "标准")

        // 3. 尝试资源详情接口获取
        val infoUrl = runCatching {
            val resp: String = client.get("https://app.c.nf.migu.cn/MIGUM2.0/v1.0/content/resourceinfo.do") {
                parameter("copyrightId", copyrightId)
                parameter("resourceType", "2")
                header("Referer", "https://m.music.migu.cn/")
                header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 13_2_3 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/13.0.3 Mobile/15E148 Safari/604.1")
            }.body()
            val json = Json { ignoreUnknownKeys = true }.parseToJsonElement(resp)
            val resource = json.jsonObject["resource"]?.jsonArray?.getOrNull(0)?.jsonObject
            
            // 尝试从 rateFormats 中提取 (通常包含免费试听或标准音质)
            val rateFormats = resource?.get("rateFormats")?.jsonArray
            val url = rateFormats?.firstOrNull { 
                val type = it.jsonObject["formatType"]?.jsonPrimitive?.content
                type == "PQ" || type == "LQ" 
            }?.jsonObject?.get("url")?.jsonPrimitive?.content
            
            url?.replace("ftp://218.200.160.122:21", "https://freetyst.nf.migu.cn")
               ?.replace("http://", "https://")
        }.getOrNull()
        if (!infoUrl.isNullOrBlank()) return StreamResult(infoUrl, "标准")

        // 4. 兜底尝试代理接口
        val proxies = listOf(
            "https://api.injahow.cn/meting/",
            "https://api.paugram.com/music",
            "https://api.v0.pw/music"
        )
        for (p in proxies) {
            val url = runCatching {
                if (p.contains("injahow.cn")) {
                    val resp: String = client.get(p) {
                        parameter("type", "url"); parameter("id", copyrightId); parameter("source", "migu")
                    }.body()
                    val json = Json { ignoreUnknownKeys = true }.parseToJsonElement(resp)
                    json.jsonObject["url"]?.jsonPrimitive?.content
                } else {
                    val resp: String = client.get(p) {
                        parameter("source", "migu"); parameter("id", copyrightId)
                    }.body()
                    val json = Json { ignoreUnknownKeys = true }.parseToJsonElement(resp)
                    json.jsonObject["url"]?.jsonPrimitive?.content ?: json.jsonObject["play_url"]?.jsonPrimitive?.content
                }
            }.getOrNull()
            if (!url.isNullOrBlank() && url.startsWith("http")) return StreamResult(url, "标准")
        }

        return StreamResult("")
    }

    override suspend fun lyrics(id: String): String? = null
}

@Serializable
data class MiguSearchResp(val musics: List<MiguMusic>? = null)
@Serializable
data class MiguMusic(
    val id: String? = null,
    val copyrightId: String? = null,
    val songName: String? = null,
    val singerName: String? = null,
    val albumName: String? = null,
    val cover: String? = null
)

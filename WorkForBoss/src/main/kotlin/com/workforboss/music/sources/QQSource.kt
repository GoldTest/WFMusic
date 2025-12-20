package com.workforboss.music.sources

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

class QQSource : SourceAdapter {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }
    override val name: String = "qq"

    private val bases: List<String> = run {
        val env = System.getenv("QQ_API_BASE")
        if (!env.isNullOrBlank()) listOf(env) else listOf(
            "https://qq-music-api.vercel.app",
            "https://api.paugram.com/music",
            "https://api.liuzhijin.cn",
            "https://api.v0.pw/music",
            "https://m.music.migu.cn" // 借用咪咕的逻辑（如果可用）
        )
    }

    override suspend fun search(q: String): List<Track> {
        // 1. 尝试直连获取基本信息 (不含播放链接)
        val directResult = runCatching {
            val resp: String = client.get("https://c.y.qq.com/soso/fcgi-bin/client_search_cp") {
                url {
                    parameters.append("p", "1")
                    parameters.append("n", "30")
                    parameters.append("w", q)
                    parameters.append("format", "json")
                }
                header("Referer", "https://y.qq.com/")
                header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
            }.body()
            
            val json = Json { ignoreUnknownKeys = true }.parseToJsonElement(resp)
            json.jsonObject["data"]?.jsonObject?.get("song")?.jsonObject?.get("list")?.jsonArray ?: emptyJsonArray
        }.getOrNull() ?: emptyJsonArray

        if (directResult.isNotEmpty()) {
            return directResult.map { it.jsonObject }.map { s ->
                Track(
                    id = s["songmid"]?.jsonPrimitive?.content ?: "",
                    title = s["songname"]?.jsonPrimitive?.content ?: "",
                    artist = s["singer"]?.jsonArray?.joinToString("/") { it.jsonObject["name"]?.jsonPrimitive?.content ?: "Unknown" } ?: "Unknown",
                    album = s["albumname"]?.jsonPrimitive?.content,
                    durationMs = s["interval"]?.jsonPrimitive?.longOrNull?.let { it * 1000L },
                    coverUrl = s["albummid"]?.jsonPrimitive?.content?.let { "https://y.gtimg.cn/music/photo_new/T002R300x300M000${it}.jpg" },
                    source = "qq"
                )
            }
        }
        return emptyList()
    }

    private val emptyJsonArray = JsonArray(emptyList())

    override suspend fun streamUrl(id: String): String {
        // 1. 尝试直接获取 (使用官方 musicu 接口)
        val directUrl = runCatching {
            val guid = "10000"
            val data = """
                {
                    "req_0": {
                        "module": "vkey.GetVkeyServer",
                        "method": "CgiGetVkey",
                        "param": {
                            "guid": "$guid",
                            "songmid": ["$id"],
                            "songtype": [0],
                            "uin": "0",
                            "loginflag": 1,
                            "platform": "20"
                        }
                    }
                }
            """.trimIndent()
            
            val resp: String = client.post("https://u.y.qq.com/cgi-bin/musicu.fcg") {
                setBody(data)
                header("Content-Type", "application/json")
                header("Referer", "https://y.qq.com/")
                header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, length Gecko) Chrome/91.0.4472.124 Safari/537.36")
            }.body()
            
            val json = Json { ignoreUnknownKeys = true }.parseToJsonElement(resp)
            val purl = json.jsonObject["req_0"]?.jsonObject?.get("data")?.jsonObject?.get("midurlinfo")?.jsonArray?.get(0)?.jsonObject?.get("purl")?.jsonPrimitive?.content
            
            if (!purl.isNullOrBlank()) {
                "http://ws.stream.qqmusic.qq.com/$purl"
            } else null
        }.getOrNull()

        if (!directUrl.isNullOrBlank()) return directUrl

        // 2. 尝试代理接口
        val bases = listOf(
            "https://api.paugram.com/music",
            "https://api.liuzhijin.cn",
            "https://api.v0.pw/music",
            "https://api.leafone.cn/api/music",
            "https://api.yujn.cn/api/qqmusic"
        )
        for (b in bases) {
            val url = runCatching {
                if (b.contains("paugram") || b.contains("v0.pw")) {
                    val resp: String = client.get(b) {
                        parameter("source", "qq")
                        parameter("id", id)
                    }.body()
                    val json = Json { ignoreUnknownKeys = true }.parseToJsonElement(resp)
                    val playUrl = json.jsonObject["url"]?.jsonPrimitive?.content ?: json.jsonObject["play_url"]?.jsonPrimitive?.content
                    if (!playUrl.isNullOrBlank() && playUrl.startsWith("http")) playUrl else null
                } else if (b.contains("liuzhijin")) {
                    val resp: String = client.get(b) {
                        parameter("type", "url")
                        parameter("id", id)
                        parameter("source", "qq")
                    }.body()
                    val json = Json { ignoreUnknownKeys = true }.parseToJsonElement(resp)
                    val playUrl = json.jsonObject["data"]?.jsonObject?.get("url")?.jsonPrimitive?.content
                    if (!playUrl.isNullOrBlank() && playUrl.startsWith("http")) playUrl else null
                } else if (b.contains("leafone")) {
                    val resp: String = client.get(b) {
                        parameter("type", "qq")
                        parameter("id", id)
                    }.body()
                    val json = Json { ignoreUnknownKeys = true }.parseToJsonElement(resp)
                    json.jsonObject["data"]?.jsonObject?.get("url")?.jsonPrimitive?.content
                } else if (b.contains("yujn")) {
                    val resp: String = client.get(b) {
                        parameter("id", id)
                        parameter("type", "json")
                    }.body()
                    val json = Json { ignoreUnknownKeys = true }.parseToJsonElement(resp)
                    json.jsonObject["data"]?.jsonObject?.get("url")?.jsonPrimitive?.content
                } else null
            }.getOrNull()
            if (url != null) return url
        }
        
        // 最后尝试直连预览链接
        return "http://ws.stream.qqmusic.qq.com/C400$id.m4a?guid=10000&vkey=0&uin=0&fromtag=66"
    }

    override suspend fun lyrics(id: String): String? {
        for (b in bases) {
            if (b.contains("c.y.qq.com")) continue
            val lrc = runCatching {
                val data: QQLyricResp = client.get("$b/lyric") { parameter("id", id) }.body()
                data.data?.lyric
            }.getOrNull()
            if (!lrc.isNullOrBlank()) return lrc
        }
        return null
    }
}

@Serializable
data class QQDirectSearchResp(val data: QQDirectSearchData? = null)
@Serializable
data class QQDirectSearchData(val song: QQDirectSongList? = null)
@Serializable
data class QQDirectSongList(val list: List<QQDirectSong>? = null)
@Serializable
data class QQDirectSong(val songmid: String? = null, val songname: String? = null, val singer: List<QQDirectSinger>? = null, val albumname: String? = null, val interval: Int? = null, val albummid: String? = null)
@Serializable
data class QQDirectSinger(val name: String? = null)

@Serializable
data class QQSearchResp(val data: QQSearchData? = null)
@Serializable
data class QQSearchData(val list: List<QQSearchItem> = emptyList())
@Serializable
data class QQSearchItem(val songmid: String, val songname: String, val singer: List<QQSinger> = emptyList(), val albumname: String? = null, val interval: Int? = null, val albummid: String? = null)
@Serializable
data class QQSinger(val name: String)
@Serializable
data class QQUrlResp(val data: QQUrlData? = null)
@Serializable
data class QQUrlData(val url: String? = null)
@Serializable
data class QQLyricResp(val data: QQLyricData? = null)
@Serializable
data class QQLyricData(val lyric: String? = null)

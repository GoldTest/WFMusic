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
            "https://api.paugram.com/music",
            "https://api.liuzhijin.cn",
            "https://api.v0.pw/music",
            "https://api.leafone.cn/api/music",
            "https://api.yujn.cn/api/qqmusic"
        )
    }

    override suspend fun search(q: String, page: Int): List<Track> {
        val limit = 30
        val offset = (page - 1) * limit
        // 尝试直连官方搜索接口 (部分地区可用)
        val directResult = runCatching {
            val resp: String = client.get("https://c.y.qq.com/soso/fcgi-bin/client_search_cp") {
                url {
                    parameters.append("w", q)
                    parameters.append("n", limit.toString())
                    parameters.append("p", page.toString())
                    parameters.append("format", "json")
                    parameters.append("cr", "1")
                    parameters.append("g_tk", "5381")
                }
            }.body()
            val data = Json { ignoreUnknownKeys = true }.decodeFromString<QQDirectSearchResp>(resp)
            data.data?.song?.list ?: emptyList()
        }.getOrNull()

        if (directResult != null && directResult.isNotEmpty()) {
            return directResult.map { s ->
                Track(
                    id = s.songmid ?: "",
                    title = s.songname ?: "",
                    artist = s.singer?.joinToString("/") { it.name ?: "Unknown" } ?: "Unknown",
                    album = s.albumname,
                    durationMs = s.interval?.toLong()?.times(1000),
                    coverUrl = s.albummid?.let { "https://y.gtimg.cn/music/photo_new/T002R300x300M000${it}.jpg" },
                    source = "qq"
                )
            }
        }

        // 回退到代理
        for (b in bases) {
            val result = runCatching {
                val url = if (b.contains("leafone") || b.contains("yujn")) {
                    "$b?name=$q"
                } else {
                    "$b?source=qq&name=$q"
                }
                val resp: String = client.get(url).body()
                val json = Json { ignoreUnknownKeys = true }.parseToJsonElement(resp)
                
                // 适配不同代理返回格式
                val list = when {
                    json is JsonArray -> json
                    json is JsonObject && json.containsKey("data") -> {
                        val data = json["data"]
                        if (data is JsonArray) data else data?.jsonObject?.get("list")?.jsonArray
                    }
                    else -> null
                }
                list?.map { it.jsonObject }
            }.getOrNull() ?: emptyList()

            if (result.isNotEmpty()) {
                return result.map { s ->
                    Track(
                        id = s["id"]?.jsonPrimitive?.content ?: s["songid"]?.jsonPrimitive?.content ?: s["mid"]?.jsonPrimitive?.content ?: "",
                        title = s["title"]?.jsonPrimitive?.content ?: s["name"]?.jsonPrimitive?.content ?: s["songname"]?.jsonPrimitive?.content ?: "",
                        artist = s["artist"]?.jsonPrimitive?.content ?: s["singer"]?.jsonPrimitive?.content ?: "Unknown",
                        album = s["album"]?.jsonPrimitive?.content ?: s["albumname"]?.jsonPrimitive?.content,
                        durationMs = s["duration"]?.jsonPrimitive?.content?.toLongOrNull()?.times(1000) ?: s["interval"]?.jsonPrimitive?.content?.toLongOrNull()?.times(1000),
                        coverUrl = s["pic"]?.jsonPrimitive?.content ?: s["cover"]?.jsonPrimitive?.content ?: s["img"]?.jsonPrimitive?.content,
                        source = "qq"
                    )
                }
            }
        }
        return emptyList()
    }

    private val emptyJsonArray = JsonArray(emptyList())

    override suspend fun streamUrl(id: String): String {
        // 1. 尝试直接获取 (使用官方 musicu 接口, 使用 GET 方式)
        val platforms = listOf(
            // platform, ua, filename_prefix
            // 优先尝试 M500 (MP3 格式)，因为当前播放器 JLayer 仅支持 MP3
            Triple("h5", "Mozilla/5.0 (iPhone; CPU iPhone OS 13_2_3 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/13.0.3 Mobile/15E148 Safari/604.1", "M500"),
            Triple("11", "Mozilla/5.0 (Linux; Android 10; SM-G973F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/81.0.4044.138 Mobile Safari/537.36", "M500"),
            Triple("20", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36", "M500"),
            
            // 备选 C400 (M4A 格式)，如果只有 M4A，播放器可能需要升级
            Triple("h5", "Mozilla/5.0 (iPhone; CPU iPhone OS 13_2_3 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/13.0.3 Mobile/15E148 Safari/604.1", "C400"),
            Triple("11", "Mozilla/5.0 (Linux; Android 10; SM-G973F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/81.0.4044.138 Mobile Safari/537.36", "C400"),
            Triple("iphone", "Mozilla/5.0 (iPhone; CPU iPhone OS 13_2_3 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/13.0.3 Mobile/15E148 Safari/604.1", "C400"),
            Triple("android", "Mozilla/5.0 (Linux; Android 10; SM-G973F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/81.0.4044.138 Mobile Safari/537.36", "C400")
        )

        val guid = "533186940" // 使用一个相对固定的 9-10 位 guid
        
        for ((plat, ua, prefix) in platforms) {
            val url = runCatching {
                val ext = if (prefix.startsWith("C")) "m4a" else "mp3"
                val filename = "$prefix$id.$ext"
                val data = """{"req_0":{"module":"vkey.GetVkeyServer","method":"CgiGetVkey","param":{"guid":"$guid","songmid":["$id"],"songtype":[0],"uin":"0","loginflag":0,"platform":"$plat","filename":["$filename"]}}}"""
                
                val resp: String = client.get("https://u.y.qq.com/cgi-bin/musicu.fcg") {
                    url {
                        parameters.append("format", "json")
                        parameters.append("data", data)
                    }
                    header("Referer", "https://y.qq.com/")
                    header("User-Agent", ua)
                }.body()
                
                val json = Json { ignoreUnknownKeys = true }.parseToJsonElement(resp)
                val dataObj = json.jsonObject["req_0"]?.jsonObject?.get("data")?.jsonObject
                val sip = dataObj?.get("sip")?.jsonArray?.get(0)?.jsonPrimitive?.content ?: "https://u.y.qq.com/"
                val purl = dataObj?.get("midurlinfo")?.jsonArray?.get(0)?.jsonObject?.get("purl")?.jsonPrimitive?.content
                
                if (!purl.isNullOrBlank() && !purl.contains("trial=1") && (purl.startsWith("C400") || purl.startsWith("M500"))) {
                    val finalSip = if (sip.startsWith("http://")) sip.replace("http://", "https://") else sip
                    "${finalSip}${purl}"
                } else null
            }.getOrNull()
            
            if (!url.isNullOrBlank()) return url
        }

        // 2. 尝试代理接口
        val proxies = listOf(
            "https://api.paugram.com/music",
            "https://api.injahow.cn/meting/",
            "https://api.liuzhijin.cn",
            "https://api.v0.pw/music",
            "https://api.leafone.cn/api/music",
            "https://api.yujn.cn/api/qqmusic"
        )
        for (b in proxies) {
            val url = runCatching {
                if (b.contains("paugram") || b.contains("v0.pw")) {
                    val resp: String = client.get(b) {
                        parameter("source", "qq")
                        parameter("id", id)
                    }.body()
                    val json = Json { ignoreUnknownKeys = true }.parseToJsonElement(resp)
                    val playUrl = json.jsonObject["url"]?.jsonPrimitive?.content ?: json.jsonObject["play_url"]?.jsonPrimitive?.content
                    if (!playUrl.isNullOrBlank() && playUrl.startsWith("http")) playUrl else null
                } else if (b.contains("injahow.cn")) {
                    val resp: String = client.get(b) {
                        parameter("type", "url")
                        parameter("id", id)
                        parameter("source", "tencent")
                        parameter("level", "standard") // 强制标准音质，通常是 mp3
                    }.body()
                    val json = Json { ignoreUnknownKeys = true }.parseToJsonElement(resp)
                    val playUrl = json.jsonObject["url"]?.jsonPrimitive?.content
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
        
        return ""
    }

    override suspend fun lyrics(id: String): String? {
        // 1. 尝试从代理接口获取歌词
        for (b in bases) {
            val lrc = runCatching {
                if (b.contains("paugram")) {
                    val resp: String = client.get(b) {
                        parameter("source", "qq")
                        parameter("id", id)
                    }.body()
                    val json = Json { ignoreUnknownKeys = true }.parseToJsonElement(resp)
                    json.jsonObject["lyric"]?.jsonPrimitive?.content ?: json.jsonObject["lrc"]?.jsonPrimitive?.content
                } else if (b.contains("liuzhijin")) {
                    val resp: String = client.get(b) {
                        parameter("type", "lrc")
                        parameter("id", id)
                        parameter("source", "qq")
                    }.body()
                    val json = Json { ignoreUnknownKeys = true }.parseToJsonElement(resp)
                    json.jsonObject["data"]?.jsonObject?.get("lrc")?.jsonPrimitive?.content
                } else {
                    val resp: String = client.get("$b/lyric") { parameter("id", id) }.body()
                    val json = Json { ignoreUnknownKeys = true }.parseToJsonElement(resp)
                    json.jsonObject["data"]?.jsonObject?.get("lyric")?.jsonPrimitive?.content
                }
            }.getOrNull()
            if (!lrc.isNullOrBlank()) return lrc
        }
        
        // 2. 尝试官方歌词接口
        val officialLrc = runCatching {
            val resp: String = client.get("https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg") {
                url {
                    parameters.append("songmid", id)
                    parameters.append("format", "json")
                    parameters.append("nobase64", "1")
                }
                header("Referer", "https://y.qq.com/")
            }.body()
            val json = Json { ignoreUnknownKeys = true }.parseToJsonElement(resp)
            json.jsonObject["lyric"]?.jsonPrimitive?.content
        }.getOrNull()
        
        return officialLrc
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

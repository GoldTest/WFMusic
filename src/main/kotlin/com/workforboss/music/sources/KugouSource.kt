package com.workforboss.music.sources

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

class KugouSource : SourceAdapter {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }
    override val name: String = "kugou"

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    override suspend fun search(q: String, page: Int): List<Track> {
        val limit = 30
        val offset = (page - 1) * limit
        val result = runCatching {
            val resp: String = client.get("http://songsearch.kugou.com/song_search_v2") {
                url {
                    parameters.append("keyword", q)
                    parameters.append("page", page.toString())
                    parameters.append("pagesize", limit.toString())
                    parameters.append("format", "json")
                }
                header(HttpHeaders.UserAgent, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
            }.body()
            
            val json = Json { ignoreUnknownKeys = true }.parseToJsonElement(resp)
            json.jsonObject["data"]?.jsonObject?.get("lists")?.jsonArray ?: emptyJsonArray
        }.getOrNull() ?: emptyJsonArray

        return result.map { it.jsonObject }.map { item ->
            Track(
                id = item["FileHash"]?.jsonPrimitive?.content ?: "",
                title = item["SongName"]?.jsonPrimitive?.content ?: "",
                artist = item["SingerName"]?.jsonPrimitive?.content ?: "",
                album = item["AlbumName"]?.jsonPrimitive?.content,
                durationMs = item["Duration"]?.jsonPrimitive?.longOrNull?.let { it * 1000L },
                coverUrl = item["Image"]?.jsonPrimitive?.content?.replace("{size}", "400"),
                source = "kugou"
            )
        }
    }

    private val emptyJsonArray = JsonArray(emptyList())

    override suspend fun streamUrl(id: String): String {
        // 1. 尝试酷狗官方接口 (含签名)
        val hash = id.lowercase()
        val key = md5("${hash}kgcloudv2")
        val urls = listOf(
            "http://trackercdn.kugou.com/i/v2/?cmd=25&hash=$hash&key=$key&pid=1&behavior=play&br=128&index=0&vip=0",
            "https://www.kugou.com/yy/index.php?r=play/getdata&hash=$id",
            "https://m.kugou.com/app/i/getSongInfo.php?cmd=playInfo&hash=$id"
        )

        for (u in urls) {
            val res = runCatching {
                val resp: String = client.get(u) {
                    header(HttpHeaders.Referrer, "https://www.kugou.com/")
                    header(HttpHeaders.UserAgent, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                }.body()
                
                if (u.contains("play/getdata")) {
                    val json = Json { ignoreUnknownKeys = true }.parseToJsonElement(resp)
                    val data = json.jsonObject["data"]
                    if (data is JsonObject) {
                        data["play_url"]?.jsonPrimitive?.content
                    } else null
                } else if (u.contains("getSongInfo")) {
                    val json = Json { ignoreUnknownKeys = true }.parseToJsonElement(resp)
                    json.jsonObject["url"]?.jsonPrimitive?.content
                } else if (u.contains("trackercdn")) {
                    val json = Json { ignoreUnknownKeys = true }.parseToJsonElement(resp)
                    json.jsonObject["url"]?.jsonArray?.get(0)?.jsonPrimitive?.content ?: json.jsonObject["url"]?.jsonPrimitive?.content
                } else null
            }.getOrNull()
            
            if (!res.isNullOrBlank()) return res
        }

        // 2. 尝试代理接口
        val proxies = listOf(
            "https://api.paugram.com/music",
            "https://api.liuzhijin.cn"
        )
        for (p in proxies) {
            val url = runCatching {
                if (p.contains("paugram")) {
                    val resp: String = client.get(p) {
                        parameter("source", "kugou")
                        parameter("id", id)
                    }.body()
                    val json = Json { ignoreUnknownKeys = true }.parseToJsonElement(resp)
                    val playUrl = json.jsonObject["url"]?.jsonPrimitive?.content
                    if (!playUrl.isNullOrBlank() && playUrl.startsWith("http")) playUrl else null
                } else if (p.contains("liuzhijin")) {
                    val resp: String = client.get(p) {
                        parameter("type", "url")
                        parameter("id", id)
                        parameter("source", "kugou")
                    }.body()
                    val json = Json { ignoreUnknownKeys = true }.parseToJsonElement(resp)
                    val playUrl = json.jsonObject["data"]?.jsonObject?.get("url")?.jsonPrimitive?.content
                    if (!playUrl.isNullOrBlank() && playUrl.startsWith("http")) playUrl else null
                } else null
            }.getOrNull()
            if (url != null) return url
        }

        return ""
    }

    suspend fun getCover(id: String): String? {
        val resp: String = client.get("https://www.kugou.com/yy/index.php") {
            url { parameters.append("r", "play/getdata"); parameters.append("hash", id) }
            header(HttpHeaders.Referrer, "https://www.kugou.com/")
            header(HttpHeaders.UserAgent, "Mozilla/5.0")
        }.body()
        val json = Json { ignoreUnknownKeys = true }.parseToJsonElement(resp)
        val data = json.jsonObject["data"]
        return if (data is JsonObject) {
            data["img"]?.jsonPrimitive?.content
        } else null
    }

    override suspend fun lyrics(id: String): String? = null
}

@Serializable
data class KugouSearchResp(val data: KugouSearchData? = null)
@Serializable
data class KugouSearchData(
    val info: List<KugouSearchItem> = emptyList(),
    val lists: List<KugouSearchItem> = emptyList()
)
@Serializable
data class KugouSearchItem(
    val songname: String? = null,
    val singername: String? = null,
    val album_name: String? = null,
    val duration: Int? = null,
    val hash: String? = null,
    val trans_param: KugouTransParam? = null
)
@Serializable
data class KugouTransParam(val union_cover: String? = null)


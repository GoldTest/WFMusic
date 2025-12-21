package com.workforboss.music.sources

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

class KuwoSource : SourceAdapter {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }
    override val name: String = "kuwo"

    override suspend fun search(q: String, page: Int): List<Track> {
        val limit = 30
        val offset = (page - 1) * limit
        val result = runCatching {
            // 酷我公开搜索接口
            val resp: String = client.get("http://search.kuwo.cn/r.s") {
                url {
                    parameters.append("client", "kt")
                    parameters.append("all", q)
                    parameters.append("pn", (page - 1).toString())
                    parameters.append("rn", limit.toString())
                    parameters.append("uid", "794")
                    parameters.append("ver", "kwplayer_ar_9.2.2.1")
                    parameters.append("vipver", "1")
                    parameters.append("show_fallback", "0")
                    parameters.append("ft", "music")
                    parameters.append("cluster", "0")
                    parameters.append("strategy", "2012")
                    parameters.append("encoding", "utf8")
                    parameters.append("rformat", "json")
                    parameters.append("verid", "2")
                }
            }.body()
            
            // 酷我返回的 JSON 比较特殊，可能包含单引号或非标准格式
            val jsonStr = resp.replace("'", "\"")
            val data = Json { ignoreUnknownKeys = true }.decodeFromString<KuwoSearchResp>(jsonStr)
            data.abslist ?: emptyList()
        }.getOrNull() ?: emptyList()

        return result.map { s ->
            Track(
                id = s.MUSICRID?.replace("MUSIC_", "") ?: "",
                title = s.SONGNAME ?: "",
                artist = s.ARTIST ?: "Unknown",
                album = s.ALBUM,
                durationMs = s.DURATION?.toLongOrNull()?.times(1000),
                coverUrl = null, // 酷我搜索结果通常不带封面，需要 streamUrl 时获取
                source = "kuwo"
            )
        }
    }

    override suspend fun streamUrl(id: String): String {
        // 尝试多个酷我公开链接获取地址
        val urls = listOf(
            "http://antiserver.kuwo.cn/anti.s?format=mp3&rid=MUSIC_$id&type=convert_url&response=url",
            "http://player.kuwo.cn/webmusic/st/getMuiseByRid?rid=MUSIC_$id"
        )

        for (u in urls) {
            val res = runCatching {
                val resp: String = client.get(u).body()
                if (resp.startsWith("http")) resp else null
            }.getOrNull()
            if (!res.isNullOrBlank()) return res
        }

        throw IllegalStateException("kuwo url not found")
    }

    override suspend fun lyrics(id: String): String? = null

    private fun parseDuration(d: String): Long {
        return runCatching {
            if (d.contains(":")) {
                val parts = d.split(":")
                if (parts.size == 2) {
                    (parts[0].toLong() * 60 + parts[1].toLong()) * 1000L
                } else 0L
            } else {
                d.toLong() * 1000L
            }
        }.getOrNull() ?: 0L
    }
}

@Serializable
data class KuwoSearchResp(val abslist: List<KuwoSearchItem>? = null)
@Serializable
data class KuwoSearchItem(
    val MUSICRID: String? = null,
    val SONGNAME: String? = null,
    val ARTIST: String? = null,
    val ALBUM: String? = null,
    val DURATION: String? = null
)

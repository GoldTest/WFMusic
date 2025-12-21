package com.workforboss.music.sources

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import kotlin.random.Random
import java.net.URLEncoder

class BilibiliSource : SourceAdapter {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
            })
        }
    }

    override val name: String = "bilibili"

    private var buvid3: String? = null
    private var buvid4: String? = null
    private var imgKey: String? = null
    private var subKey: String? = null
    private var lastKeyUpdate: Long = 0

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
        "sec-ch-ua" to "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\"",
        "sec-ch-ua-mobile" to "?0",
        "sec-ch-ua-platform" to "\"Windows\"",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "same-site",
    )

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private suspend fun updateWbiKeys() {
        if (System.currentTimeMillis() - lastKeyUpdate < 3600_000 && imgKey != null) return
        runCatching {
            val respStr = client.get("https://api.bilibili.com/x/web-interface/nav") {
                headers { commonHeaders.forEach { (k, v) -> append(k, v) } }
            }.bodyAsText()
            
            val resp = json.decodeFromString<BilibiliNavResp>(respStr)
            if (resp.code == 0 || resp.code == -101) { // -101 means not logged in, but we still get keys
                val imgUrl = resp.data?.wbi_img?.img_url ?: ""
                val subUrl = resp.data?.wbi_img?.sub_url ?: ""
                imgKey = imgUrl.substringAfterLast("/").substringBefore(".")
                subKey = subUrl.substringAfterLast("/").substringBefore(".")
                lastKeyUpdate = System.currentTimeMillis()
                println("Bilibili: Wbi keys updated: imgKey=$imgKey, subKey=$subKey")
            } else {
                println("Bilibili: Nav API returned code ${resp.code}: ${resp.message}")
            }
        }.onFailure {
            println("Bilibili: Failed to update Wbi keys: ${it.message}")
        }
    }

    private suspend fun ensureBuvid() {
        if (buvid3 != null) return
        runCatching {
            val respStr = client.get("https://api.bilibili.com/x/frontend/finger/spi") {
                headers { commonHeaders.forEach { (k, v) -> append(k, v) } }
            }.bodyAsText()
            
            val resp = json.decodeFromString<BilibiliSpiResp>(respStr)
            if (resp.code == 0 && resp.data != null) {
                buvid3 = resp.data.b_3
                buvid4 = resp.data.b_4
                println("Bilibili: Fetched buvid3: $buvid3")
            } else {
                println("Bilibili: SPI API returned code ${resp.code}")
            }
        }.onFailure {
            println("Bilibili: Failed to fetch buvid: ${it.message}")
        }
        
        if (buvid3 == null) {
            buvid3 = generateBuvid()
            println("Bilibili: Generated fallback buvid3: $buvid3")
        }
    }

    private fun generateBuvid(): String {
        val r = { Random.nextInt(0x10000).toString(16).padStart(4, '0').uppercase() }
        return "${r()}${r()}-${r()}-${r()}-${r()}-${r()}${r()}${r()}infoc"
    }

    private fun wbiEncode(v: String): String {
        return URLEncoder.encode(v, "UTF-8")
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~")
    }

    private fun getSignedUrl(baseUrl: String, params: Map<String, String>, imgKey: String, subKey: String): String {
        val mixKey = getMixKey(imgKey + subKey)
        val wts = (System.currentTimeMillis() / 1000).toString()
        val newParams = params.toMutableMap().apply { put("wts", wts) }
        
        val chrFilter = Regex("[!'()*]")
        val query = newParams.toSortedMap().map { (k, v) ->
            "${wbiEncode(k)}=${wbiEncode(v.replace(chrFilter, ""))}"
        }.joinToString("&")
        
        val wRid = md5(query + mixKey)
        return "$baseUrl?$query&w_rid=$wRid"
    }

    private fun getMixKey(raw: String): String {
        val indices = intArrayOf(46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52)
        return indices.filter { it < raw.length }.map { raw[it] }.joinToString("").take(32)
    }

    private fun md5(input: String) = MessageDigest.getInstance("MD5").digest(input.toByteArray()).joinToString("") { "%02x".format(it) }

    override suspend fun search(q: String, page: Int): List<Track> {
        ensureBuvid()
        updateWbiKeys()
        
        return runCatching {
            val baseParams = mapOf(
                "search_type" to "video",
                "keyword" to q,
                "page" to page.toString(),
                "page_size" to "20",
                "platform" to "pc",
                "order" to "totalrank"
            )

            val finalUrl = if (imgKey != null && subKey != null) {
                getSignedUrl("https://api.bilibili.com/x/web-interface/wbi/search/type", baseParams, imgKey!!, subKey!!)
            } else {
                "https://api.bilibili.com/x/web-interface/wbi/search/type?" + baseParams.map { "${it.key}=${wbiEncode(it.value)}" }.joinToString("&")
            }

            println("Bilibili: Searching with URL: $finalUrl")

            val response: HttpResponse = client.get(finalUrl) {
                headers {
                    commonHeaders.forEach { (k, v) -> append(k, v) }
                    append("Cookie", "buvid3=$buvid3; buvid4=$buvid4")
                    append("Referer", "https://search.bilibili.com/all?keyword=${wbiEncode(q)}")
                }
            }

            val respStr = response.bodyAsText()
            println("Bilibili: Search Response: ${respStr.take(500)}")
            
            val resp = json.decodeFromString<BilibiliSearchResp>(respStr)

            if (resp.code != 0) {
                println("Bilibili search error: ${resp.code} ${resp.message}")
                return emptyList()
            }

            val resultList = resp.data?.result ?: return emptyList()
            
            resultList.filter { it.bvid.isNotBlank() }.map { item ->
                Track(
                    id = item.bvid,
                    title = cleanTitle(item.title),
                    artist = item.author,
                    album = "Bilibili",
                    durationMs = parseDuration(item.duration),
                    coverUrl = if (item.pic.startsWith("//")) "https:${item.pic}" else item.pic,
                    quality = "点击解析",
                    source = "bilibili"
                )
            }
        }.getOrElse { 
            println("Bilibili search failed: ${it.message}")
            it.printStackTrace()
            emptyList() 
        }
    }

    private fun cleanTitle(title: String) = title.replace(Regex("<[^>]*>"), "").replace("&amp;", "&").replace("&quot;", "\"")

    override suspend fun streamUrl(id: String): StreamResult {
        ensureBuvid()
        println("Bilibili: Getting stream URL for $id")
        val viewResp: BilibiliViewResp = client.get("https://api.bilibili.com/x/web-interface/view") {
            headers { 
                commonHeaders.forEach { (k, v) -> append(k, v) }
                append("Cookie", "buvid3=$buvid3; buvid4=$buvid4")
            }
            url { parameters.append("bvid", id) }
        }.body()
        
        if (viewResp.code != 0) {
            println("Bilibili: View API error ${viewResp.code}")
            throw IllegalStateException("Bilibili View API error: ${viewResp.code}")
        }
        
        val cid = viewResp.data?.cid ?: throw IllegalStateException("CID not found")
        val playResp: BilibiliPlayResp = client.get("https://api.bilibili.com/x/player/playurl") {
            headers { 
                commonHeaders.forEach { (k, v) -> append(k, v) }
                append("Cookie", "buvid3=$buvid3; buvid4=$buvid4")
                append("Referer", "https://www.bilibili.com/video/$id")
            }
            url {
                parameters.append("bvid", id)
                parameters.append("cid", cid.toString())
                parameters.append("fnval", "16")
            }
        }.body()
        
        if (playResp.code != 0) {
            println("Bilibili: PlayURL API error ${playResp.code}")
            throw IllegalStateException("Bilibili PlayURL API error: ${playResp.code}")
        }
        
        // 优先获取音频流，选择 ID 最大的（音质最高）
        val bestAudio = playResp.data?.dash?.audio?.maxByOrNull { it.id }
        val audioUrl = bestAudio?.baseUrl ?: playResp.data?.durl?.firstOrNull()?.url
            
        if (audioUrl == null) {
            println("Bilibili: No audio stream found for $id. Response: $playResp")
            throw IllegalStateException("Audio URL not found")
        }

        // 获取视频流，同样选择最高清晰度的
        val bestVideo = playResp.data?.dash?.video?.maxByOrNull { it.id }
        val videoUrl = bestVideo?.baseUrl

        val qualityLabel = when (bestAudio?.id) {
            30216 -> "64k"
            30232 -> "128k"
            30280 -> "320k"
            30250 -> "杜比全景声"
            30251 -> "Hi-Res"
            else -> if (bestAudio != null) "Unknown(${bestAudio.id})" else "默认"
        }
        
        println("Bilibili: Found stream URL ($qualityLabel): ${audioUrl.take(100)}...")
        return StreamResult(
            url = audioUrl,
            quality = qualityLabel,
            videoUrl = videoUrl,
            videoWidth = bestVideo?.width,
            videoHeight = bestVideo?.height,
            headers = mapOf(
                "User-Agent" to commonHeaders["User-Agent"]!!,
                "Referer" to "https://www.bilibili.com/video/$id",
                "Cookie" to "buvid3=$buvid3; buvid4=$buvid4"
            )
        )
    }

    override suspend fun lyrics(id: String): String? = null

    private fun parseDuration(duration: String): Long {
        if (duration.isBlank()) return 0
        return try {
            val parts = duration.split(":").map { it.trim().toLongOrNull() ?: 0L }
            when (parts.size) {
                1 -> parts[0] * 1000
                2 -> (parts[0] * 60 + parts[1]) * 1000
                3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000
                else -> 0
            }
        } catch (e: Exception) {
            0
        }
    }
}

@Serializable
data class BilibiliNavResp(val code: Int, val message: String? = null, val data: BilibiliNavData? = null)
@Serializable
data class BilibiliNavData(val wbi_img: BilibiliWbiImg? = null)
@Serializable
data class BilibiliWbiImg(val img_url: String, val sub_url: String)

@Serializable
data class BilibiliSpiResp(val code: Int = 0, val data: BilibiliSpiData? = null)
@Serializable
data class BilibiliSpiData(val b_3: String? = null, val b_4: String? = null)

@Serializable
data class BilibiliSearchResp(val code: Int = 0, val message: String? = null, val data: BilibiliSearchData? = null)
@Serializable
data class BilibiliSearchData(val result: List<BilibiliSearchResultItem>? = null)
@Serializable
data class BilibiliSearchResultItem(
    val bvid: String = "", 
    val title: String = "", 
    val author: String = "", 
    val pic: String = "", 
    val duration: String = "",
    val type: String = ""
)

@Serializable
data class BilibiliViewResp(val code: Int = 0, val data: BilibiliViewData? = null)
@Serializable
data class BilibiliViewData(val cid: Long? = null)

@Serializable
data class BilibiliPlayResp(val code: Int = 0, val data: BilibiliPlayData? = null)
@Serializable
data class BilibiliPlayData(val dash: BilibiliDash? = null, val durl: List<BilibiliDurl>? = null)
@Serializable
data class BilibiliDash(
    val audio: List<BilibiliAudioItem>? = null,
    val video: List<BilibiliVideoItem>? = null
)
@Serializable
data class BilibiliAudioItem(val baseUrl: String = "", val id: Int = 0)
@Serializable
data class BilibiliVideoItem(
    val baseUrl: String = "",
    val id: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val frameRate: String = ""
)
@Serializable
data class BilibiliDurl(val url: String = "")

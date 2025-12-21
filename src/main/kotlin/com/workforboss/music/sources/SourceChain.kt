package com.workforboss.music.sources

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class SourceChain {
    private val adapters: Map<String, SourceAdapter> = mapOf(
        "netease" to NeteaseSource(),
        "qq" to QQSource(),
        "kugou" to KugouSource(),
        "kuwo" to KuwoSource(),
        "migu" to MiguSource(),
        "itunes" to ItunesSource()
    )
    private val order = listOf("netease", "qq", "kugou", "kuwo", "migu", "itunes")

    suspend fun search(q: String, page: Int = 1): List<Track> = coroutineScope {
        val jobs = order.map { name ->
            async {
                searchBySource(name, q, page)
            }
        }
        jobs.awaitAll().flatten()
    }

    suspend fun searchBySource(source: String, q: String, page: Int = 1): List<Track> {
        val ad = adapters[source] ?: return emptyList()
        return runCatching {
            ad.search(q, page).map { it.copy(source = source) }
        }.getOrDefault(emptyList())
    }

    suspend fun streamUrlFor(source: String, id: String): String {
        val ad = adapters[source] ?: throw IllegalArgumentException("unknown source: $source")
        return ad.streamUrl(id)
    }

    suspend fun lyricsFor(source: String, id: String): String? {
        val ad = adapters[source] ?: return null
        return runCatching { ad.lyrics(id) }.getOrNull()
    }

    suspend fun coverFor(source: String, id: String): String? {
        val ad = adapters[source] ?: return null
        if (ad is KugouSource) return ad.getCover(id)
        // 其他源通常在 search 时已经有了
        return null
    }
}

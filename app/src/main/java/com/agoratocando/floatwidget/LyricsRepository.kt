package com.agoratocando.floatwidget

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Uma linha de letra sincronizada: em que milissegundo ela comeca a valer. */
data class LyricsLine(val timeMs: Long, val text: String)

/** Resultado de uma busca de letra: sincronizada (linha a linha) e/ou so o texto corrido. */
data class LyricsResult(val synced: List<LyricsLine>?, val plain: String?)

/**
 * Busca letras no lrclib.net — o mesmo banco de dados livre usado no widget de
 * Windows deste projeto. Nao precisa de chave de API nem de login. So funciona
 * se o celular tiver internet; sem rede, ou sem a musica no banco (acontece
 * bastante com faixas nao-inglesas ou bem nichadas), simplesmente nao acha, e
 * quem chama trata isso mostrando "letra nao encontrada" — nunca e um erro
 * fatal, so uma busca que nao deu resultado.
 *
 * Usa HttpURLConnection + org.json (ja vem no Android) de proposito, pra nao
 * acrescentar nenhuma biblioteca nova so pra isso.
 */
object LyricsRepository {
    private const val TAG = "Compasso"
    private const val BASE = "https://lrclib.net/api"
    private const val MAX_CACHE = 24

    // chave "titulo|artista" -> resultado (ou null se ja tentou e nao achou)
    private val cache = object : LinkedHashMap<String, LyricsResult?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LyricsResult?>?): Boolean =
            size > MAX_CACHE
    }
    suspend fun fetch(title: String, artist: String, album: String?, durationMs: Long): LyricsResult? {
        val key = cacheKey(title, artist)
        synchronized(cache) {
            if (cache.containsKey(key)) return cache[key]
        }
        return withContext(Dispatchers.IO) {
            val result = try {
                fetchFromNetwork(title, artist, album, (durationMs / 1000L).toInt())
            } catch (e: Exception) {
                Log.w(TAG, "Busca de letra falhou para '$title' / '$artist'", e)
                null
            }
            synchronized(cache) { cache[key] = result }
            result
        }
    }

    private fun cacheKey(title: String, artist: String) = "${title.trim().lowercase()}|${artist.trim().lowercase()}"

    private fun fetchFromNetwork(title: String, artist: String, album: String?, durationSec: Int): LyricsResult? {
        tryGetExact(title, artist, album, durationSec)?.let { return it }

        val cleanedTitle = cleanQuery(title)
        val onlyFirstArtist = firstArtist(artist)
        trySearch(cleanedTitle, onlyFirstArtist)?.let { return it }
        trySearch(title, artist)?.let { return it }
        return null
    }

    private fun tryGetExact(title: String, artist: String, album: String?, durationSec: Int): LyricsResult? {
        val url = buildString {
            append(BASE).append("/get?track_name=").append(enc(title))
            append("&artist_name=").append(enc(artist))
            if (!album.isNullOrBlank()) append("&album_name=").append(enc(album))
            if (durationSec > 0) append("&duration=").append(durationSec)
        }
        val body = callApi(url) ?: return null
        return try {
            parseResponseObject(JSONObject(body))
        } catch (e: Exception) {
            null
        }
    }

    private fun trySearch(title: String, artist: String): LyricsResult? {
        if (title.isBlank()) return null
        val url = buildString {
            append(BASE).append("/search?track_name=").append(enc(title))
            if (artist.isNotBlank()) append("&artist_name=").append(enc(artist))
        }
        val body = callApi(url) ?: return null
        return try {
            val arr = JSONArray(body)
            if (arr.length() == 0) null else parseResponseObject(arr.getJSONObject(0))
        } catch (e: Exception) {
            null
        }
    }

    private fun callApi(urlString: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 6000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Compasso-Android (+https://github.com)")
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseResponseObject(obj: JSONObject): LyricsResult {
        val syncedRaw = obj.optString("syncedLyrics", "").takeIf { it.isNotBlank() }
        val plain = obj.optString("plainLyrics", "").takeIf { it.isNotBlank() }
        val synced = syncedRaw?.let { parseLrc(it) }?.takeIf { it.isNotEmpty() }
        return LyricsResult(synced = synced, plain = plain)
    }

    private val lrcLineRegex = Regex("""\[(\d{1,2}):(\d{2}(?:\.\d{1,3})?)](.*)""")

    private fun parseLrc(lrc: String): List<LyricsLine> {
        val lines = mutableListOf<LyricsLine>()
        for (rawLine in lrc.lineSequence()) {
            val match = lrcLineRegex.find(rawLine) ?: continue
            val minutes = match.groupValues[1].toLongOrNull() ?: continue
            val seconds = match.groupValues[2].toDoubleOrNull() ?: continue
            val text = match.groupValues[3].trim()
            if (text.isEmpty()) continue
            val timeMs = minutes * 60_000L + (seconds * 1000.0).toLong()
            lines.add(LyricsLine(timeMs, text))
        }
        return lines.sortedBy { it.timeMs }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun cleanQuery(s: String): String = s
        .replace(Regex("""\(feat\.?[^)]*\)""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\(official[^)]*\)""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\[[^]]*]"""), "")
        .replace(Regex("""\s*-\s*(radio edit|live|acoustic|remaster(ed)?(\s*\d{4})?)\s*$""", RegexOption.IGNORE_CASE), "")
        .trim()

    private fun firstArtist(s: String): String = s
        .split(Regex("""[,&/]|\bfeat\.?\b|\bft\.?\b|\bfeaturing\b""", RegexOption.IGNORE_CASE))
        .firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: s
}

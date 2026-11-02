package com.vibemusic.app

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class ArchiveRepository {
    suspend fun search(query: String, bySubject: Boolean = false): List<Song> = withContext(Dispatchers.IO) {
        val clean = query.trim().take(100)
        require(clean.isNotBlank()) { "Введи название трека или исполнителя" }
        val escaped = clean.replace("\\", " ").replace("\"", " ").trim()
        val expression = if (bySubject) {
            "mediatype:audio AND licenseurl:* AND subject:(\"$escaped\")"
        } else {
            "mediatype:audio AND licenseurl:* AND (title:(\"$escaped\") OR creator:(\"$escaped\") OR subject:(\"$escaped\"))"
        }
        val requestUrl = "https://archive.org/advancedsearch.php?q=${enc(expression)}&fl[]=${enc("identifier")}" +
            "&fl[]=${enc("title")}&fl[]=${enc("creator")}&fl[]=${enc("year")}" +
            "&fl[]=${enc("subject")}&fl[]=${enc("licenseurl")}&rows=30&page=1&output=json"
        val root = JSONObject(httpGet(requestUrl))
        val docs = root.optJSONObject("response")?.optJSONArray("docs") ?: JSONArray()
        val songs = ArrayList<Song>(docs.length())
        for (i in 0 until docs.length()) {
            val doc = docs.optJSONObject(i) ?: continue
            val id = doc.optString("identifier").takeIf { it.isNotBlank() } ?: continue
            songs += Song(
                id = id,
                title = doc.optString("title").ifBlank { "Без названия" },
                artist = readText(doc.opt("creator")).ifBlank { "Неизвестный исполнитель" },
                album = doc.optString("year"),
                licenseUrl = doc.optString("licenseurl"),
                tags = readList(doc.opt("subject")).take(8)
            )
        }
        songs
    }

    suspend fun resolveStream(song: Song): Song = withContext(Dispatchers.IO) {
        if (song.local || song.url.isNotBlank()) return@withContext song
        val root = JSONObject(httpGet("https://archive.org/metadata/${enc(song.id)}"))
        val files = root.optJSONArray("files") ?: JSONArray()
        val candidates = ArrayList<Pair<Int, String>>()
        for (i in 0 until files.length()) {
            val file = files.optJSONObject(i) ?: continue
            if (file.optString("private") == "true") continue
            val name = file.optString("name")
            if (name.isBlank()) continue
            val format = file.optString("format").lowercase()
            val lower = name.lowercase()
            val score = when {
                lower.endsWith(".mp3") || format.contains("mp3") -> 0
                lower.endsWith(".m4a") || lower.endsWith(".aac") -> 1
                lower.endsWith(".ogg") || format.contains("ogg") -> 2
                lower.endsWith(".wav") -> 3
                lower.endsWith(".flac") -> 4
                else -> -1
            }
            if (score >= 0) candidates += score to name
        }
        val fileName = candidates.sortedBy { it.first }.firstOrNull()?.second
            ?: throw IllegalStateException("В этой записи не найден подходящий аудиофайл")
        val safeId = Uri.encode(song.id)
        val safePath = fileName.split('/').joinToString("/") { Uri.encode(it) }
        song.copy(url = "https://archive.org/download/$safeId/$safePath")
    }

    fun itemPage(song: Song): String = "https://archive.org/details/${enc(song.id)}"

    private fun httpGet(address: String): String {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("User-Agent", "AuralisAndroid/0.1")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("Сервис ответил кодом $code")
            return body
        } finally { connection.disconnect() }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
    private fun readText(value: Any?): String = when (value) {
        is JSONArray -> (0 until value.length()).map { value.optString(it) }.joinToString(", ")
        is String -> value
        else -> ""
    }
    private fun readList(value: Any?): List<String> = when (value) {
        is JSONArray -> (0 until value.length()).map { value.optString(it) }.filter { it.isNotBlank() }
        is String -> value.split(Regex("[,;]")).map { it.trim() }.filter { it.isNotBlank() }
        else -> emptyList()
    }
}

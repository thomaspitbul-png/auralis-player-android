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
        val variants = (listOf(clean) + suggestionsFor(clean)).map { it.trim() }
            .filter { it.isNotBlank() }.distinct().take(5)
        val clauses = variants.flatMap { variant ->
            val safe = variant.replace("\\", " ").replace("\"", " ").replace("(", " ").replace(")", " ").trim()
            if (safe.isBlank()) emptyList() else if (bySubject) {
                listOf("subject:(\"$safe\")")
            } else {
                val fields = listOf("title", "creator", "subject").map { "$it:(\"$safe\")" }
                val words = safe.split(Regex("\\s+")).filter { it.length >= 3 }.take(5)
                val wordClauses = if (words.size >= 2) {
                    listOf("title:(" + words.joinToString(" AND ") { it.replace(":", " ") } + ")",
                        "creator:(" + words.joinToString(" AND ") { it.replace(":", " ") } + ")")
                } else emptyList()
                fields + wordClauses
            }
        }.distinct().take(24)
        val expression = "mediatype:audio AND licenseurl:* AND (" + clauses.joinToString(" OR ") + ")"
        val requestUrl = "https://archive.org/advancedsearch.php?q=${enc(expression)}&fl[]=${enc("identifier")}" +
            "&fl[]=${enc("title")}&fl[]=${enc("creator")}&fl[]=${enc("year")}" +
            "&fl[]=${enc("subject")}&fl[]=${enc("licenseurl")}&rows=40&page=1&output=json"
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
        songs.distinctBy { it.id }
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
            setRequestProperty("User-Agent", "AuralisAndroid/0.2")
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

    companion object {
        private val knownVariants = mapOf(
            "майкл джексон" to listOf("Michael Jackson"),
            "майкл джексон билли джин" to listOf("Michael Jackson Billie Jean", "Billie Jean"),
            "билли джин" to listOf("Billie Jean", "Michael Jackson"),
            "билли джинс" to listOf("Billie Jean", "Michael Jackson"),
            "уикенд" to listOf("The Weeknd"),
            "линкин парк" to listOf("Linkin Park"),
            "эминем" to listOf("Eminem"),
            "квин" to listOf("Queen"),
            "битлз" to listOf("The Beatles"),
            "тейлор свифт" to listOf("Taylor Swift"),
            "эд ширен" to listOf("Ed Sheeran"),
            "эд шеран" to listOf("Ed Sheeran"),
            "билли айлиш" to listOf("Billie Eilish"),
            "дрэке" to listOf("Drake"),
            "дрейк" to listOf("Drake")
        )

        fun suggestionsFor(query: String): List<String> {
            val clean = query.trim()
            if (clean.isBlank()) return emptyList()
            val key = clean.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()
            val known = knownVariants[key].orEmpty()
            val latin = transliterate(key)
            val plain = clean.split(Regex("\\s+")).filter { it.length > 2 }.joinToString(" ")
            return (known + listOf(latin, plain).filter { it.isNotBlank() && !it.equals(clean, true) }).distinct().take(4)
        }

        private fun transliterate(value: String): String {
            val map = mapOf(
                'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e",
                'ё' to "yo", 'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k",
                'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r",
                'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "kh", 'ц' to "ts",
                'ч' to "ch", 'ш' to "sh", 'щ' to "shch", 'ъ' to "", 'ы' to "y", 'ь' to "",
                'э' to "e", 'ю' to "yu", 'я' to "ya"
            )
            return value.map { map[it] ?: it.toString() }.joinToString("")
                .replace("maykl dzhekson", "Michael Jackson")
                .replace("billi dzhin", "Billie Jean")
                .replace("billi dzin", "Billie Jean")
        }
    }
}

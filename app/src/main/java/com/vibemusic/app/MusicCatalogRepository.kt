package com.vibemusic.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer

enum class CatalogScope(val label: String) {
    ALL("Всё"), TITLE("Треки"), ARTIST("Исполнители"), GENRE("Жанры")
}

data class CatalogSearchResult(
    val songs: List<Song>,
    val jamendoError: String? = null,
    val archiveError: String? = null
)

class MusicCatalogRepository {
    suspend fun search(
        query: String,
        scope: CatalogScope = CatalogScope.ALL,
        jamendoClientId: String = ""
    ): CatalogSearchResult = withContext(Dispatchers.IO) {
        val clean = query.trim().take(100)
        require(clean.isNotBlank()) { "Введи название трека или исполнителя" }

        coroutineScope {
            val archiveTask = async { runCatching { searchArchive(clean, scope) } }
            val jamendoTask = async {
                if (jamendoClientId.isBlank()) Result.success(emptyList())
                else runCatching { searchJamendo(clean, scope, jamendoClientId.trim()) }
            }

            val archiveResult = archiveTask.await()
            val jamendoResult = jamendoTask.await()
            val archiveSongs = archiveResult.getOrDefault(emptyList())
            val jamendoSongs = jamendoResult.getOrDefault(emptyList())
            val merged = (jamendoSongs + archiveSongs)
                .distinctBy { it.id }
                .sortedWith(
                    compareByDescending<Song> { relevanceScore(it, clean) }
                        .thenBy { if (it.source == "jamendo") 0 else 1 }
                )
                .take(80)

            CatalogSearchResult(
                songs = merged,
                jamendoError = jamendoResult.exceptionOrNull()?.message,
                archiveError = archiveResult.exceptionOrNull()?.message
            )
        }
    }

    private fun searchJamendo(query: String, scope: CatalogScope, clientId: String): List<Song> {
        val params = linkedMapOf(
            "client_id" to clientId,
            "format" to "json",
            "limit" to "40",
            "order" to "relevance",
            "audioformat" to "mp32",
            "include" to "licenses",
            "type" to "single albumtrack"
        )
        when (scope) {
            CatalogScope.ALL -> params["search"] = query
            CatalogScope.TITLE -> params["namesearch"] = query
            CatalogScope.ARTIST -> params["artist_name"] = query
            CatalogScope.GENRE -> params["fuzzytags"] = query
        }

        val address = "https://api.jamendo.com/v3.0/tracks/?" +
            params.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        val root = JSONObject(httpGet(address))
        val headers = root.optJSONObject("headers")
        if (headers?.optString("status")?.equals("success", true) == false) {
            throw IllegalStateException(headers.optString("error_message").ifBlank { "Jamendo отклонил запрос. Проверь Client ID." })
        }
        val tracks = root.optJSONArray("results") ?: JSONArray()
        val songs = ArrayList<Song>()

        for (index in 0 until tracks.length()) {
            val item = tracks.optJSONObject(index) ?: continue
            val id = item.optString("id").trim()
            val title = item.optString("name").trim()
            val artist = item.optString("artist_name").trim()
            val stream = item.optString("audio").trim()
            val page = item.optString("shareurl").trim()
            val license = item.optString("license_ccurl").trim()
            if (id.isBlank() || title.isBlank() || artist.isBlank() || stream.isBlank() || license.isBlank()) continue

            val seconds = item.optLong("duration", 0L).coerceAtLeast(0L)
            songs += Song(
                id = "jamendo_$id",
                title = title,
                artist = artist,
                album = item.optString("album_name"),
                url = stream,
                licenseUrl = license,
                tags = listOf(item.optString("musicinfo"))
                    .filter { it.isNotBlank() && it != "null" },
                source = "jamendo",
                durationMs = seconds * 1000L,
                artworkUrl = item.optString("image"),
                sourcePageUrl = page.ifBlank { "https://www.jamendo.com/track/$id" },
                licenseName = item.optString("license_cc")
            )
        }

        return songs.sortedByDescending { relevanceScore(it, query) }
    }

    private fun searchArchive(query: String, scope: CatalogScope): List<Song> {
        val fields = when (scope) {
            CatalogScope.TITLE -> listOf("title")
            CatalogScope.ARTIST -> listOf("creator")
            CatalogScope.GENRE -> listOf("subject")
            CatalogScope.ALL -> listOf("title", "creator", "subject")
        }
        val variants = (listOf(query) + transliterationSuggestions(query)).distinct().take(3)
        val expressions = variants.mapNotNull { variant ->
            val safe = cleanLuceneText(variant)
            if (safe.isBlank()) null
            else {
                val exact = fields.joinToString(" OR ") { """$it:("$safe")""" }
                "mediatype:audio AND licenseurl:* AND ($exact)"
            }
        }.distinct()

        val found = LinkedHashMap<String, Pair<Song, Long>>()
        var lastError: Throwable? = null
        var successfulRequests = 0

        for (expression in expressions.take(3)) {
            val params = listOf(
                "q" to expression,
                "fl[]" to "identifier",
                "fl[]" to "title",
                "fl[]" to "creator",
                "fl[]" to "year",
                "fl[]" to "subject",
                "fl[]" to "licenseurl",
                "fl[]" to "downloads",
                "sort[]" to "downloads desc",
                "rows" to "50",
                "page" to "1",
                "output" to "json"
            )
            val address = "https://archive.org/advancedsearch.php?" +
                params.joinToString("&") { "${enc(it.first)}=${enc(it.second)}" }
            val response = runCatching { JSONObject(httpGet(address)) }
            if (response.isFailure) {
                lastError = response.exceptionOrNull()
                continue
            }
            successfulRequests++
            val docs = response.getOrThrow().optJSONObject("response")?.optJSONArray("docs") ?: JSONArray()
            for (index in 0 until docs.length()) {
                val doc = docs.optJSONObject(index) ?: continue
                val id = doc.optString("identifier").trim()
                val license = doc.optString("licenseurl").trim()
                if (id.isBlank() || !isOpenLicense(license)) continue
                val song = Song(
                    id = id,
                    title = doc.optString("title").ifBlank { "Без названия" },
                    artist = readText(doc.opt("creator")).ifBlank { "Неизвестный исполнитель" },
                    album = doc.optString("year"),
                    licenseUrl = license,
                    tags = readList(doc.opt("subject")).take(8),
                    source = "archive",
                    sourcePageUrl = "https://archive.org/details/${enc(id)}",
                    licenseName = license.substringAfterLast('/').ifBlank { "Открытая лицензия" }
                )
                val downloads = doc.optLong("downloads", 0L)
                val previous = found[id]
                if (previous == null || relevanceScore(song, query) > relevanceScore(previous.first, query)) {
                    found[id] = song to downloads
                }
            }
            if (found.size >= 60) break
        }

        if (successfulRequests == 0 && lastError != null) {
            throw IllegalStateException(lastError.message ?: "Internet Archive временно недоступен", lastError)
        }

        return found.values
            .sortedWith(
                compareByDescending<Pair<Song, Long>> { relevanceScore(it.first, query) }
                    .thenByDescending { it.second }
            )
            .take(60)
            .map { it.first }
    }

    suspend fun resolveStream(song: Song): Song = withContext(Dispatchers.IO) {
        if (song.url.isNotBlank()) return@withContext song
        if (song.local) throw IllegalStateException("Не удалось прочитать локальный файл")
        if (song.id.startsWith("itunes_")) {
            throw IllegalStateException("Старые короткие превью Apple больше не используются. Найди трек заново.")
        }

        val root = JSONObject(httpGet("https://archive.org/metadata/${enc(song.id)}"))
        val files = root.optJSONArray("files") ?: JSONArray()
        val candidates = ArrayList<Pair<Int, String>>()
        for (index in 0 until files.length()) {
            val file = files.optJSONObject(index) ?: continue
            if (file.optString("private") == "true") continue
            val name = file.optString("name")
            if (name.isBlank()) continue
            val format = file.optString("format").lowercase()
            val lower = name.lowercase()
            val score = when {
                lower.endsWith(".mp3") || format.contains("mp3") -> 0
                lower.endsWith(".m4a") || lower.endsWith(".aac") || format.contains("aac") -> 1
                lower.endsWith(".ogg") || lower.endsWith(".opus") || format.contains("ogg") -> 2
                lower.endsWith(".flac") || format.contains("flac") -> 3
                lower.endsWith(".wav") || format.contains("wav") -> 4
                else -> -1
            }
            if (score >= 0) candidates += score to name
        }
        val fileName = candidates.sortedBy { it.first }.firstOrNull()?.second
            ?: throw IllegalStateException("В этой записи не найден поддерживаемый аудиофайл")
        val safeId = enc(song.id)
        val safePath = fileName.split('/').joinToString("/") { enc(it) }
        song.copy(url = "https://archive.org/download/$safeId/$safePath")
    }

    private fun isOpenLicense(value: String): Boolean {
        val license = value.lowercase()
        return license.startsWith("https://creativecommons.org/licenses/") ||
            license.startsWith("http://creativecommons.org/licenses/") ||
            license.startsWith("https://creativecommons.org/publicdomain/") ||
            license.startsWith("http://creativecommons.org/publicdomain/")
    }

    private fun cleanLuceneText(value: String): String =
        value.replace('\\', ' ')
            .replace('"', ' ')
            .replace('(', ' ')
            .replace(')', ' ')
            .replace(':', ' ')
            .replace('*', ' ')
            .replace('?', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun relevanceScore(song: Song, query: String): Int {
        val q = normalize(query)
        val title = normalize(song.title)
        val artist = normalize(song.artist)
        val combined = "$title $artist ${normalize(song.tags.joinToString(" "))}"
        var score = 0
        if (title == q) score += 1200 else if (title.contains(q)) score += 900
        if (artist == q) score += 850 else if (artist.contains(q)) score += 700
        val words = q.split(' ').filter { it.length >= 2 }.distinct()
        val matched = words.count { it in combined }
        score += matched * 100
        if (words.isNotEmpty() && matched == words.size) score += 150
        return score
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun transliterationSuggestions(value: String): List<String> {
        val map = mapOf(
            'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e",
            'ё' to "yo", 'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k",
            'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r",
            'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "kh", 'ц' to "ts",
            'ч' to "ch", 'ш' to "sh", 'щ' to "shch", 'ъ' to "", 'ы' to "y", 'ь' to "",
            'э' to "e", 'ю' to "yu", 'я' to "ya"
        )
        val clean = value.lowercase().trim()
        val latin = clean.map { map[it] ?: it.toString() }.joinToString("")
        return if (latin != clean) listOf(latin) else emptyList()
    }

    private fun httpGet(address: String): String {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12000
            readTimeout = 18000
            setRequestProperty("User-Agent", "AuralisAndroid/0.3")
            setRequestProperty("Accept", "application/json")
            instanceFollowRedirects = true
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("Музыкальный каталог ответил кодом $code")
            return body
        } finally {
            connection.disconnect()
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun readText(value: Any?): String = when (value) {
        is JSONArray -> (0 until value.length()).map { value.optString(it) }.filter { it.isNotBlank() }.joinToString(", ")
        is String -> value
        else -> ""
    }

    private fun readList(value: Any?): List<String> = when (value) {
        is JSONArray -> (0 until value.length()).map { value.optString(it) }.filter { it.isNotBlank() }
        is String -> value.split(Regex("[,;]")).map { it.trim() }.filter { it.isNotBlank() }
        else -> emptyList()
    }
}

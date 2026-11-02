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
    val audiusError: String? = null,
    val archiveError: String? = null
)

class MusicCatalogRepository {
    private val audiusBases = listOf(
        "https://api.audius.co/v1",
        "https://discoveryprovider.audius.co/v1"
    )

    suspend fun search(
        query: String,
        scope: CatalogScope = CatalogScope.ALL,
        audiusApiKey: String = ""
    ): CatalogSearchResult = withContext(Dispatchers.IO) {
        val clean = query.trim().take(100)
        require(clean.isNotBlank()) { "Введи название трека или исполнителя" }

        coroutineScope {
            val audiusTask = async { runCatching { searchAudius(clean, scope, audiusApiKey.trim()) } }
            val archiveTask = async { runCatching { searchArchive(clean, scope) } }
            val audiusResult = audiusTask.await()
            val archiveResult = archiveTask.await()
            val audiusSongs = audiusResult.getOrDefault(emptyList())
            val archiveSongs = archiveResult.getOrDefault(emptyList())
            val merged = (audiusSongs + archiveSongs)
                .distinctBy { it.id }
                .sortedWith(
                    compareByDescending<Song> { relevanceScore(it, clean, scope) }
                        .thenBy { if (it.source == "audius") 0 else 1 }
                )
                .take(100)

            CatalogSearchResult(
                songs = merged,
                audiusError = audiusResult.exceptionOrNull()?.message,
                archiveError = archiveResult.exceptionOrNull()?.message
            )
        }
    }

    private fun searchAudius(query: String, scope: CatalogScope, apiKey: String): List<Song> {
        val params = linkedMapOf(
            "query" to query,
            "limit" to "50",
            "sort_method" to "relevant",
            "app_name" to "Auralis"
        )
        if (scope == CatalogScope.GENRE) audiusGenre(query)?.let { params["genre"] = it }
        val queryString = params.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        val headers = if (apiKey.isBlank()) emptyMap() else mapOf("X-API-Key" to apiKey)
        var lastError: Throwable? = null
        for (base in audiusBases) {
            val response = runCatching { JSONObject(httpGet("$base/tracks/search?$queryString", headers)) }
            if (response.isFailure) {
                lastError = response.exceptionOrNull()
                continue
            }
            val root = response.getOrThrow()
            val tracks = root.optJSONArray("data")
                ?: root.optJSONObject("data")?.let { JSONArray().put(it) }
                ?: JSONArray()
            val songs = ArrayList<Song>()
            for (index in 0 until tracks.length()) {
                val item = tracks.optJSONObject(index) ?: continue
                val id = item.optString("id").trim()
                val title = item.optString("title").trim()
                if (id.isBlank() || title.isBlank()) continue
                if (isExplicitlyFalse(item.opt("isStreamable")) || item.optBoolean("isStreamGated", false)) continue
                val user = item.optJSONObject("user")
                val artist = user?.optString("name").orEmpty().ifBlank {
                    user?.optString("handle").orEmpty().ifBlank { "Неизвестный исполнитель" }
                }
                val handle = user?.optString("handle").orEmpty()
                val permalink = item.optString("permalink").trim()
                val sourcePage = when {
                    permalink.startsWith("https://") || permalink.startsWith("http://") -> permalink
                    handle.isNotBlank() && permalink.isNotBlank() -> "https://audius.co/${enc(handle)}/${enc(permalink)}"
                    else -> "https://audius.co"
                }
                val artwork = item.optJSONObject("artwork")
                val artworkUrl = artwork?.optString("_480x480").orEmpty()
                    .ifBlank { artwork?.optString("_1000x1000").orEmpty() }
                val tags = readList(item.opt("tags")).toMutableList()
                listOf(item.optString("genre"), item.optString("mood")).forEach {
                    if (it.isNotBlank() && it != "null") tags += it
                }
                songs += Song(
                    id = "audius_$id",
                    title = title,
                    artist = artist,
                    album = item.optString("album"),
                    tags = tags.distinct().take(12),
                    source = "audius",
                    durationMs = item.optLong("duration", 0L).coerceAtLeast(0L) * 1000L,
                    artworkUrl = artworkUrl,
                    sourcePageUrl = sourcePage
                )
            }
            return songs.sortedWith(
                compareByDescending<Song> { relevanceScore(it, query, scope) }
                    .thenByDescending { it.tags.size }
            )
        }
        throw IllegalStateException(lastError?.message ?: "Audius временно недоступен", lastError)
    }

    private fun audiusGenre(query: String): String? {
        val genres = listOf(
            "Electronic", "Rock", "Metal", "Alternative", "Hip-Hop/Rap", "Experimental",
            "Punk", "Folk", "Pop", "Ambient", "Soundtrack", "World", "Jazz", "Acoustic",
            "Funk", "R&B/Soul", "Devotional", "Classical", "Reggae", "Podcasts",
            "Country", "Spoken Word", "Comedy", "Blues", "Kids", "Audiobooks", "Latin",
            "Lo-Fi", "Hyperpop", "Dancehall", "Techno", "Trap", "House", "Tech House",
            "Deep House", "Disco", "Electro", "Jungle", "Progressive House", "Hardstyle",
            "Glitch Hop", "Trance", "Future Bass", "Future House", "Tropical House",
            "Downtempo", "Drum & Bass", "Dubstep", "Jersey Club", "Vaporwave", "Moombahton"
        )
        return genres.firstOrNull { it.equals(query.trim(), ignoreCase = true) }
    }

    private fun isExplicitlyFalse(value: Any?): Boolean = when (value) {
        is Boolean -> !value
        is String -> value.equals("false", true) || value == "0"
        is Number -> value.toInt() == 0
        else -> false
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
                val words = safe.split(Regex("\\s+")).filter { it.length >= 2 }.distinct()
                val broad = fields.flatMap { field -> words.map { "$field:$it" } }.joinToString(" OR ")
                if (broad.isBlank()) "mediatype:audio AND ($exact)"
                else "mediatype:audio AND ($exact OR $broad)"
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

    suspend fun resolveStream(song: Song, audiusApiKey: String = ""): Song = withContext(Dispatchers.IO) {
        if (song.local) {
            if (song.url.isBlank()) throw IllegalStateException("Не удалось прочитать локальный файл")
            return@withContext song
        }
        if (song.source == "audius" || song.id.startsWith("audius_")) {
            val trackId = song.id.removePrefix("audius_")
            return@withContext song.copy(url = resolveAudiusStream(trackId, audiusApiKey.trim()))
        }
        if (song.url.isNotBlank()) return@withContext song
        if (song.id.startsWith("itunes_")) {
            throw IllegalStateException("Короткие превью Apple не поддерживаются. Найди трек заново.")
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

    private fun resolveAudiusStream(trackId: String, apiKey: String): String {
        val headers = if (apiKey.isBlank()) emptyMap() else mapOf("X-API-Key" to apiKey)
        var lastError: Throwable? = null
        for (base in audiusBases) {
            var current = URL("$base/tracks/${enc(trackId)}/stream?app_name=Auralis")
            try {
                for (hop in 0..5) {
                    val connection = (current.openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 12000
                        readTimeout = 18000
                        instanceFollowRedirects = false
                        setRequestProperty("Accept", "audio/mpeg, audio/*;q=0.9, application/json;q=0.5")
                        setRequestProperty("User-Agent", "AuralisAndroid/0.3")
                        headers.forEach { (name, value) -> setRequestProperty(name, value) }
                    }
                    val code = connection.responseCode
                    if (code in 300..399) {
                        val location = connection.getHeaderField("Location")
                        connection.disconnect()
                        if (location.isNullOrBlank()) throw IllegalStateException("Audius не вернул адрес аудиопотока")
                        current = URL(current, location)
                        continue
                    }
                    if (code !in 200..299) {
                        val detail = connection.errorStream?.bufferedReader()?.use { it.readText().take(500) }.orEmpty()
                        connection.disconnect()
                        throw IllegalStateException("Audius не смог открыть трек ($code): $detail")
                    }
                    val contentType = connection.contentType.orEmpty().lowercase()
                    if (contentType.contains("json")) {
                        val detail = connection.inputStream.bufferedReader().use { it.readText().take(500) }
                        connection.disconnect()
                        throw IllegalStateException("Audius вернул ошибку вместо аудио: $detail")
                    }
                    connection.disconnect()
                    return current.toString()
                }
                throw IllegalStateException("Слишком много перенаправлений потока Audius")
            } catch (error: Exception) {
                lastError = error
            }
        }
        throw IllegalStateException(lastError?.message ?: "Не удалось получить поток Audius", lastError)
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

    private fun relevanceScore(song: Song, query: String, scope: CatalogScope = CatalogScope.ALL): Int {
        val q = normalize(query)
        val title = normalize(song.title)
        val artist = normalize(song.artist)
        val tags = normalize(song.tags.joinToString(" "))
        val combined = "$title $artist $tags"
        var score = 0
        if (title == q) score += 1200 else if (title.contains(q)) score += 900
        if (artist == q) score += 850 else if (artist.contains(q)) score += 700
        if (tags.contains(q)) score += 450
        when (scope) {
            CatalogScope.TITLE -> if (title.contains(q)) score += 500
            CatalogScope.ARTIST -> if (artist.contains(q)) score += 500
            CatalogScope.GENRE -> if (tags.contains(q)) score += 700
            CatalogScope.ALL -> Unit
        }
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

    private fun httpGet(address: String, headers: Map<String, String> = emptyMap()): String {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12000
            readTimeout = 18000
            setRequestProperty("User-Agent", "AuralisAndroid/0.3")
            setRequestProperty("Accept", "application/json")
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
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

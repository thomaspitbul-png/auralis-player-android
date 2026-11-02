package com.vibemusic.app

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer

enum class SearchScope(val label: String) {
    ALL("Всё"),
    TITLE("Треки"),
    ARTIST("Исполнители"),
    SUBJECT("Жанры")
}

class ArchiveRepository {
    suspend fun search(query: String, scope: SearchScope = SearchScope.ALL): List<Song> =
        withContext(Dispatchers.IO) {
            val parsed = parseSearch(query, scope)
            val clean = parsed.term.trim().take(100)
            require(clean.isNotBlank()) { "Введи название трека или исполнителя" }

            // Prefer a real music catalog for mainstream songs; keep Internet Archive as fallback.
            val catalogResults = runCatching { searchAppleCatalog(clean, parsed.scope) }
                .getOrDefault(emptyList())
            if (catalogResults.isNotEmpty()) return@withContext catalogResults

            val variants = (listOf(clean) + suggestionsFor(clean))
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .take(5)

            val fields = when (parsed.scope) {
                SearchScope.TITLE -> listOf("title")
                SearchScope.ARTIST -> listOf("creator")
                SearchScope.SUBJECT -> listOf("subject")
                SearchScope.ALL -> listOf("title", "creator", "subject")
            }

            val queries = linkedSetOf<String>()
            variants.forEach { variant ->
                val safe = cleanLuceneText(variant)
                if (safe.isBlank()) return@forEach
                val exactFields = fields.map { "$it:(\"$safe\")" }
                queries += buildArchiveQuery(exactFields, parsed.year)

                val words = safe.split(Regex("\\s+")).filter { it.length >= 2 }.distinct().take(6)
                if (words.size >= 2 && queries.size < 4) {
                    val andFields = fields.map { field ->
                        "$field:(" + words.joinToString(" AND ") { "\"$it\"" } + ")"
                    }
                    queries += buildArchiveQuery(andFields, parsed.year)
                }
            }

            val baseWords = cleanLuceneText(clean)
                .split(Regex("\\s+"))
                .filter { it.length >= 2 }
                .distinct()
                .take(5)
            if (baseWords.size >= 2 && queries.size < 5) {
                val looseFields = fields.map { field ->
                    "$field:(" + baseWords.joinToString(" OR ") { "\"$it\"" } + ")"
                }
                queries += buildArchiveQuery(looseFields, parsed.year)
            }

            val found = LinkedHashMap<String, Candidate>()
            var successfulRequests = 0
            var lastError: Throwable? = null

            for (expression in queries.take(5)) {
                val requestUrl = buildRequestUrl(expression)
                val response = runCatching { JSONObject(httpGet(requestUrl)) }
                if (response.isFailure) {
                    lastError = response.exceptionOrNull()
                    continue
                }
                successfulRequests++

                val docs = response.getOrThrow()
                    .optJSONObject("response")
                    ?.optJSONArray("docs") ?: JSONArray()

                for (index in 0 until docs.length()) {
                    val doc = docs.optJSONObject(index) ?: continue
                    val id = doc.optString("identifier").takeIf { it.isNotBlank() } ?: continue
                    val license = doc.optString("licenseurl").trim()
                    if (license.isBlank()) continue

                    val song = Song(
                        id = id,
                        title = doc.optString("title").ifBlank { "Без названия" },
                        artist = readText(doc.opt("creator")).ifBlank { "Неизвестный исполнитель" },
                        album = doc.optString("year"),
                        licenseUrl = license,
                        tags = readList(doc.opt("subject")).take(8)
                    )
                    val candidate = Candidate(
                        song = song,
                        relevance = relevanceScore(song, clean),
                        downloads = doc.optLong("downloads", 0L)
                    )
                    val previous = found[id]
                    if (previous == null || candidate.relevance > previous.relevance) {
                        found[id] = candidate
                    }
                }
                if (found.size >= 60) break
            }

            if (successfulRequests == 0 && lastError != null) {
                throw IllegalStateException(
                    lastError.message ?: "Не удалось связаться с музыкальным каталогом",
                    lastError
                )
            }

            found.values
                .sortedWith(
                    compareByDescending<Candidate> { it.relevance }
                        .thenByDescending { it.downloads }
                )
                .take(60)
                .map { it.song }
        }

    private fun searchAppleCatalog(query: String, scope: SearchScope): List<Song> {
        val attribute = when (scope) {
            SearchScope.TITLE -> "&attribute=songTerm"
            SearchScope.ARTIST -> "&attribute=artistTerm"
            SearchScope.SUBJECT -> "&attribute=genreIndex"
            SearchScope.ALL -> ""
        }
        val address = "https://itunes.apple.com/search?term=${enc(query)}&entity=song&media=music&country=US&limit=50$attribute"
        val root = JSONObject(httpGet(address))
        val array = root.optJSONArray("results") ?: JSONArray()
        val songs = ArrayList<Pair<Int, Song>>()
        val queryNormalized = normalizeForMatch(query)
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val trackId = item.optLong("trackId", 0L)
            val title = item.optString("trackName").trim()
            val artist = item.optString("artistName").trim()
            val preview = item.optString("previewUrl").trim()
            if (trackId <= 0L || title.isBlank() || artist.isBlank() || preview.isBlank()) continue
            val genre = item.optString("primaryGenreName").trim()
            val year = item.optString("releaseDate").take(4)
            val song = Song(
                id = "itunes_$trackId",
                title = title,
                artist = artist,
                album = item.optString("collectionName"),
                url = preview,
                licenseUrl = item.optString("trackViewUrl"),
                tags = listOfNotNull(genre.takeIf { it.isNotBlank() }, year.takeIf { it.isNotBlank() })
            )
            val titleNormalized = normalizeForMatch(title)
            val artistNormalized = normalizeForMatch(artist)
            val score = when {
                titleNormalized == queryNormalized || artistNormalized == queryNormalized -> 1200
                titleNormalized.startsWith(queryNormalized) || artistNormalized.startsWith(queryNormalized) -> 950
                titleNormalized.contains(queryNormalized) || artistNormalized.contains(queryNormalized) -> 800
                else -> queryNormalized.split(' ').count { it.length >= 2 && "$titleNormalized $artistNormalized".contains(it) } * 100
            }
            songs += score to song
        }
        return songs.sortedByDescending { it.first }.map { it.second }.distinctBy { it.id }
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

    private fun buildArchiveQuery(fieldQueries: List<String>, year: String?): String {
        val filters = buildList {
            add("mediatype:audio")
            add("licenseurl:*")
            if (!year.isNullOrBlank()) add("year:$year")
        }
        return filters.joinToString(" AND ") + " AND (" + fieldQueries.joinToString(" OR ") + ")"
    }

    private fun buildRequestUrl(expression: String): String {
        val fields = listOf("identifier", "title", "creator", "year", "subject", "licenseurl", "downloads")
        val fieldParams = fields.joinToString("") { "&fl[]=${enc(it)}" }
        return "https://archive.org/advancedsearch.php?q=${enc(expression)}" +
            fieldParams +
            "&sort[]=${enc("downloads desc")}&rows=60&page=1&output=json"
    }

    private fun parseSearch(raw: String, defaultScope: SearchScope): ParsedSearch {
        var term = raw.trim()
        var scope = defaultScope
        val prefix = Regex(
            "^(artist|исполнитель|track|title|трек|genre|жанр):\\s*",
            RegexOption.IGNORE_CASE
        ).find(term)

        if (prefix != null) {
            scope = when (prefix.groupValues[1].lowercase()) {
                "artist", "исполнитель" -> SearchScope.ARTIST
                "genre", "жанр" -> SearchScope.SUBJECT
                else -> SearchScope.TITLE
            }
            term = term.substring(prefix.range.last + 1).trim()
        }

        val yearMatch = Regex("\\byear:(\\d{4})\\b", RegexOption.IGNORE_CASE).find(term)
        val year = yearMatch?.groupValues?.getOrNull(1)
        if (yearMatch != null) term = term.replace(yearMatch.value, " ").trim()

        return ParsedSearch(term, scope, year)
    }

    private fun cleanLuceneText(value: String): String =
        value.replace("\\", " ")
            .replace("\"", " ")
            .replace("(", " ")
            .replace(")", " ")
            .replace(":", " ")
            .replace("*", " ")
            .replace("?", " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun relevanceScore(song: Song, query: String): Int {
        val q = normalizeForMatch(query)
        val title = normalizeForMatch(song.title)
        val artist = normalizeForMatch(song.artist)
        val all = "$title $artist ${song.tags.joinToString(" ").let(::normalizeForMatch)}"
        var score = 0

        if (title == q) score += 1200
        else if (title.contains(q)) score += 900
        if (artist == q) score += 850
        else if (artist.contains(q)) score += 700

        val words = q.split(' ').filter { it.length >= 2 }.distinct()
        val matched = words.count { word -> word in all }
        score += matched * 110
        if (words.isNotEmpty() && matched == words.size) score += 180
        return score
    }

    private fun normalizeForMatch(value: String): String =
        Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun httpGet(address: String): String {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12000
            readTimeout = 16000
            setRequestProperty("User-Agent", "AuralisAndroid/0.2")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("Сервис ответил кодом $code")
            return body
        } finally {
            connection.disconnect()
        }
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

    private data class ParsedSearch(val term: String, val scope: SearchScope, val year: String?)
    private data class Candidate(val song: Song, val relevance: Int, val downloads: Long)

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
            "дрейк" to listOf("Drake"),
            "майли сайрус" to listOf("Miley Cyrus"),
            "бруно марс" to listOf("Bruno Mars"),
            "дэвид гетта" to listOf("David Guetta"),
            "авичи" to listOf("Avicii"),
            "имэджин дрэгонс" to listOf("Imagine Dragons")
        )

        fun suggestionsFor(query: String): List<String> {
            val clean = query.trim()
            if (clean.isBlank()) return emptyList()
            val key = clean.lowercase()
                .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            val known = knownVariants[key].orEmpty()
            val latin = transliterate(key)
            val plain = clean.split(Regex("\\s+"))
                .filter { it.length > 2 }
                .joinToString(" ")
            return (known + listOf(latin, plain).filter {
                it.isNotBlank() && !it.equals(clean, true)
            }).distinct().take(4)
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

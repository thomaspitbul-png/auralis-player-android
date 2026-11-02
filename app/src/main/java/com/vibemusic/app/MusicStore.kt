package com.vibemusic.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class MusicStore(context: Context) {
    private val prefs = context.getSharedPreferences("vibe_music_store", Context.MODE_PRIVATE)

    @Synchronized fun saveSong(song: Song) {
        val all = readSongs().toMutableMap()
        all[song.id] = song
        val json = JSONArray()
        all.values.forEach { json.put(songToJson(it)) }
        prefs.edit().putString("songs", json.toString()).apply()
    }

    @Synchronized fun getSong(id: String): Song? = readSongs()[id]
    @Synchronized fun allSongs(): List<Song> = readSongs().values.toList()

    @Synchronized fun toggleFavorite(song: Song): Boolean {
        saveSong(song)
        val ids = readIds("favorites").toMutableList()
        val isFavorite = if (song.id in ids) { ids.remove(song.id); false } else { ids.add(0, song.id); true }
        writeIds("favorites", ids)
        return isFavorite
    }

    @Synchronized fun isFavorite(id: String): Boolean = id in readIds("favorites")
    @Synchronized fun favorites(): List<Song> = readIds("favorites").mapNotNull { getSong(it) }

    @Synchronized fun addHistory(song: Song) {
        saveSong(song)
        val ids = readIds("history").toMutableList()
        ids.remove(song.id); ids.add(0, song.id)
        writeIds("history", ids.take(60))
    }

    @Synchronized fun history(): List<Song> = readIds("history").mapNotNull { getSong(it) }
    @Synchronized fun localSongs(): List<Song> = allSongs().filter { it.local }

    @Synchronized fun playlists(): List<MusicPlaylist> {
        val array = runCatching { JSONArray(prefs.getString("playlists", "[]")) }.getOrDefault(JSONArray())
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            MusicPlaylist(item.optString("id"), item.optString("name"), jsonArrayToStrings(item.optJSONArray("songs")))
        }
    }

    @Synchronized fun createPlaylist(name: String): MusicPlaylist {
        val playlist = MusicPlaylist("pl_${System.currentTimeMillis()}", name.trim())
        writePlaylists(playlists() + playlist)
        return playlist
    }

    @Synchronized fun addToPlaylist(song: Song, playlistId: String) {
        saveSong(song)
        writePlaylists(playlists().map { p -> if (p.id == playlistId && song.id !in p.songIds) p.copy(songIds = p.songIds + song.id) else p })
    }

    @Synchronized fun removeFromPlaylist(songId: String, playlistId: String) {
        writePlaylists(playlists().map { p -> if (p.id == playlistId) p.copy(songIds = p.songIds - songId) else p })
    }

    @Synchronized fun songsInPlaylist(playlist: MusicPlaylist): List<Song> = playlist.songIds.mapNotNull { getSong(it) }
    @Synchronized fun deletePlaylist(playlistId: String) { writePlaylists(playlists().filterNot { it.id == playlistId }) }

    private fun readSongs(): Map<String, Song> {
        val array = runCatching { JSONArray(prefs.getString("songs", "[]")) }.getOrDefault(JSONArray())
        val result = LinkedHashMap<String, Song>()
        for (i in 0 until array.length()) {
            val song = jsonToSong(array.optJSONObject(i) ?: continue)
            result[song.id] = song
        }
        return result
    }

    private fun readIds(key: String): List<String> = runCatching { jsonArrayToStrings(JSONArray(prefs.getString(key, "[]"))) }.getOrDefault(emptyList())
    private fun writeIds(key: String, ids: List<String>) { prefs.edit().putString(key, JSONArray(ids).toString()).apply() }

    private fun writePlaylists(playlists: List<MusicPlaylist>) {
        val array = JSONArray()
        playlists.forEach { p -> array.put(JSONObject().apply { put("id", p.id); put("name", p.name); put("songs", JSONArray(p.songIds)) }) }
        prefs.edit().putString("playlists", array.toString()).apply()
    }

    private fun songToJson(song: Song) = JSONObject().apply {
        put("id", song.id); put("title", song.title); put("artist", song.artist); put("album", song.album)
        put("url", song.url); put("licenseUrl", song.licenseUrl); put("tags", JSONArray(song.tags)); put("local", song.local)
    }

    private fun jsonToSong(item: JSONObject) = Song(
        id = item.optString("id"), title = item.optString("title"), artist = item.optString("artist"),
        album = item.optString("album"), url = item.optString("url"), licenseUrl = item.optString("licenseUrl"),
        tags = jsonArrayToStrings(item.optJSONArray("tags")), local = item.optBoolean("local", false)
    )

    private fun jsonArrayToStrings(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }
    }
}

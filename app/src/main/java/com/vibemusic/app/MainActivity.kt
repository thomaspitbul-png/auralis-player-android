package com.vibemusic.app

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.launch

private val Ink = Color(0xFF101014)
private val Panel = Color(0xFF1D1B25)
private val Purple = Color(0xFF9B7BFF)
private val Soft = Color(0xFFB9B4C8)

class MainActivity : ComponentActivity() {
    private val store by lazy { MusicStore(this) }
    private val archive = ArchiveRepository()
    private var controller by mutableStateOf<MediaController?>(null)
    private var playing by mutableStateOf(false)
    private var current by mutableStateOf<Song?>(null)
    private var loading by mutableStateOf(false)
    private var message by mutableStateOf("Ищи музыку в открытом аудиоархиве")
    private var results = mutableStateListOf<Song>()
    private var favorites = mutableStateListOf<Song>()
    private var query by mutableStateOf("")
    private var tab by mutableIntStateOf(0)
    private var future: ListenableFuture<MediaController>? = null

    private val openAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: "Аудиофайл"
            val song = Song("local_${uri.hashCode()}", name.substringBeforeLast('.', name), "На устройстве", url=uri.toString(), local=true)
            store.saveSong(song)
            results.add(0, song)
            message = "Трек добавлен в медиатеку"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        favorites.addAll(store.favorites())
        val token = SessionToken(this, ComponentName(this, MusicPlaybackService::class.java))
        future = MediaController.Builder(this, token).buildAsync()
        future?.addListener({
            runCatching { future?.get() }.onSuccess { c ->
                if (c != null) {
                    controller = c
                    c.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
                    override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                        current = item?.mediaId?.let { store.getSong(it) }
                        current?.let { store.addHistory(it) }
                    }
                    })
                }
            }
        }, mainExecutor)
        setContent { AuralisScreen() }
    }

    @Composable
    private fun AuralisScreen() {
        MaterialTheme(colorScheme = darkColorScheme(primary=Purple, background=Ink, surface=Panel)) {
            Column(Modifier.fillMaxSize().background(Ink).padding(horizontal=20.dp)) {
                Spacer(Modifier.height(24.dp))
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("AURALIS", color=Purple, style=MaterialTheme.typography.labelLarge, fontWeight=FontWeight.Bold)
                        Text("Твоя музыка.", color=Color.White, style=MaterialTheme.typography.headlineMedium, fontWeight=FontWeight.Bold)
                    }
                    Icon(Icons.Default.LibraryMusic, null, tint=Purple, modifier=Modifier.size(34.dp))
                }
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) {
                    OutlinedTextField(value=query, onValueChange={query=it}, modifier=Modifier.weight(1f),
                        placeholder={Text("Трек или исполнитель")}, singleLine=true, shape=RoundedCornerShape(16.dp))
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick={ searchMusic() }, enabled=!loading) {
                        Icon(Icons.Default.Search, "Поиск", tint=Purple)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    listOf("Поиск", "Избранное", "История").forEachIndexed { i, label ->
                        FilterChip(selected=tab==i, onClick={tab=i}, label={Text(label)})
                    }
                    TextButton(onClick={openAudio.launch(arrayOf("audio/*"))}) { Text("+ Файл") }
                }
                Text(message, color=Soft, style=MaterialTheme.typography.bodySmall, modifier=Modifier.padding(vertical=8.dp))
                val list = when(tab) { 1 -> favorites.toList(); 2 -> store.history(); else -> results.toList() }
                if (list.isEmpty()) {
                    Column(Modifier.fillMaxWidth().weight(1f), horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.Center) {
                        Text("♫", color=Purple, style=MaterialTheme.typography.displayLarge)
                        Text(if(tab==1) "Пока нет избранного" else if(tab==2) "История пока пуста" else "Найди свой следующий трек", color=Color.White, fontWeight=FontWeight.SemiBold)
                        Text("Можно искать музыку или открыть файл с устройства", color=Soft, style=MaterialTheme.typography.bodySmall)
                    }
                } else {
                    LazyColumn(Modifier.weight(1f), verticalArrangement=Arrangement.spacedBy(8.dp), contentPadding=PaddingValues(bottom=12.dp)) {
                        items(list, key={it.id}) { song ->
                            Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(16.dp)).clickable { playSong(song) }.padding(12.dp), verticalAlignment=Alignment.CenterVertically) {
                                Box(Modifier.size(44.dp).background(Color(0xFF332A4B), RoundedCornerShape(12.dp)), contentAlignment=Alignment.Center) {
                                    Icon(Icons.Default.PlayArrow, null, tint=Purple)
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(song.title, color=Color.White, fontWeight=FontWeight.SemiBold, maxLines=1)
                                    Text(song.artist, color=Soft, style=MaterialTheme.typography.bodySmall, maxLines=1)
                                }
                                IconButton(onClick={
                                    store.toggleFavorite(song)
                                    favorites.clear(); favorites.addAll(store.favorites())
                                }) {
                                    Icon(if(store.isFavorite(song.id)) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Избранное", tint=if(store.isFavorite(song.id)) Purple else Soft)
                                }
                            }
                        }
                    }
                }
                current?.let { song ->
                    Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(18.dp)).padding(12.dp), verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(song.title, color=Color.White, fontWeight=FontWeight.Bold, maxLines=1)
                            Text(song.artist, color=Soft, style=MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick={controller?.let { if(it.isPlaying) it.pause() else it.play() }}) {
                            Icon(if(playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Воспроизведение", tint=Purple)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    private fun searchMusic() {
        val text = query.trim()
        if(text.isBlank()) { message="Введи название трека или исполнителя"; return }
        lifecycleScope.launch {
            loading=true; message="Ищем музыку…"
            runCatching { archive.search(text) }
                .onSuccess { songs -> results.clear(); results.addAll(songs); songs.forEach(store::saveSong); message="Найдено треков: ${songs.size}"; tab=0 }
                .onFailure { message=it.message ?: "Не удалось выполнить поиск" }
            loading=false
        }
    }

    private fun playSong(song: Song) {
        lifecycleScope.launch {
            message="Подготавливаем трек…"
            runCatching {
                val ready = archive.resolveStream(song)
                store.saveSong(ready)
                val c = controller ?: error("Плеер ещё запускается")
                c.setMediaItem(MediaItem.Builder().setMediaId(ready.id).setUri(ready.url)
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(ready.title).setArtist(ready.artist).build()).build())
                c.prepare(); c.play()
                current=ready; playing=true; store.addHistory(ready); message="Сейчас играет"
            }.onFailure { message=it.message ?: "Не удалось запустить трек" }
        }
    }

    override fun onDestroy() {
        future?.let { MediaController.releaseFuture(it) }
        controller=null
        super.onDestroy()
    }
}

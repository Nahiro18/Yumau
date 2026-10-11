package moe.rukamori.archivetune.desktop.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.desktop.runtime.DesktopMediaItem
import moe.rukamori.archivetune.desktop.runtime.DesktopPlayer
import moe.rukamori.archivetune.desktop.runtime.DesktopPlayerState
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.SongItem

private fun formatTime(ms: Long): String {
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}

fun main() {
    val player = DesktopPlayer()
    player.init()

    application {
        Window(
            onCloseRequest = {
                player.release()
                exitApplication()
            },
            state = rememberWindowState(width = 1180.dp, height = 760.dp),
            title = "YumaPlayer - Linux Mint",
        ) {
            var searchQuery by remember { mutableStateOf("") }
            var searchResults by remember { mutableStateOf<List<SongItem>>(emptyList()) }
            var isLoading by remember { mutableStateOf(false) }
            var selectedTab by remember { mutableStateOf(0) }
            val coroutineScope = rememberCoroutineScope()

            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize()) {
                        TabRow(selectedTabIndex = selectedTab) {
                            Tab(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                text = { Text("Buscar") },
                                icon = { Icon(Icons.Default.Search, contentDescription = "Buscar") }
                            )
                            Tab(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                text = { Text("Cola de Reproducción") },
                                icon = { Icon(Icons.Default.QueueMusic, contentDescription = "Cola") }
                            )
                        }

                        Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp)) {
                            when (selectedTab) {
                                0 -> {
                                    Column(Modifier.fillMaxSize()) {
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            OutlinedTextField(
                                                value = searchQuery,
                                                onValueChange = { searchQuery = it },
                                                label = { Text("Buscar canciones, artistas o álbumes...") },
                                                modifier = Modifier.weight(1f),
                                                singleLine = true,
                                                shape = RoundedCornerShape(12.dp)
                                            )
                                            Spacer(Modifier.width(12.dp))
                                            Button(
                                                onClick = {
                                                    if (searchQuery.isNotBlank()) {
                                                        isLoading = true
                                                        coroutineScope.launch {
                                                            try {
                                                                val result = YouTube.search(searchQuery, filter = moe.rukamori.archivetune.innertube.SearchFilter.Song).getOrNull()
                                                                searchResults = result?.items?.filterIsInstance<SongItem>() ?: emptyList()
                                                            } catch (e: Exception) {
                                                                searchResults = emptyList()
                                                            } finally {
                                                                isLoading = false
                                                            }
                                                        }
                                                    }
                                                },
                                                shape = RoundedCornerShape(12.dp)
                                            ) {
                                                Icon(Icons.Default.Search, contentDescription = null)
                                                Spacer(Modifier.width(6.dp))
                                                Text("Buscar")
                                            }
                                        }

                                        Spacer(Modifier.height(16.dp))

                                        if (isLoading) {
                                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                CircularProgressIndicator()
                                            }
                                        } else if (searchResults.isEmpty()) {
                                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                Text("Ingresa una búsqueda para encontrar música.", color = Color.Gray)
                                            }
                                        } else {
                                            LazyColumn(Modifier.fillMaxSize()) {
                                                items(searchResults) { song ->
                                                    Card(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(vertical = 4.dp)
                                                            .clickable {
                                                                val mediaItem = DesktopMediaItem(
                                                                    id = song.id,
                                                                    title = song.title,
                                                                    artist = song.artists.joinToString(", ") { it.name },
                                                                    mediaUrl = "https://www.youtube.com/watch?v=${song.id}"
                                                                )
                                                                player.setQueue(listOf(mediaItem), startIndex = 0)
                                                                player.playPause()
                                                            },
                                                        shape = RoundedCornerShape(8.dp)
                                                    ) {
                                                        Row(
                                                            Modifier
                                                                .fillMaxWidth()
                                                                .padding(12.dp),
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Icon(
                                                                Icons.Default.MusicNote,
                                                                contentDescription = null,
                                                                tint = MaterialTheme.colorScheme.primary,
                                                                modifier = Modifier.size(36.dp)
                                                            )
                                                            Spacer(Modifier.width(12.dp))
                                                            Column(Modifier.weight(1f)) {
                                                                Text(song.title, style = MaterialTheme.typography.titleMedium)
                                                                Text(
                                                                    song.artists.joinToString(", ") { it.name },
                                                                    style = MaterialTheme.typography.bodySmall,
                                                                    color = Color.Gray
                                                                )
                                                            }
                                                            IconButton(
                                                                onClick = {
                                                                    val mediaItem = DesktopMediaItem(
                                                                        id = song.id,
                                                                        title = song.title,
                                                                        artist = song.artists.joinToString(", ") { it.name },
                                                                        mediaUrl = "https://www.youtube.com/watch?v=${song.id}"
                                                                    )
                                                                    player.setQueue(listOf(mediaItem), startIndex = 0)
                                                                    player.playPause()
                                                                }
                                                            ) {
                                                                Icon(Icons.Default.PlayArrow, contentDescription = "Reproducir")
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                1 -> {
                                    val queue by player.queue.collectAsState()
                                    if (queue.isEmpty()) {
                                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            Text("La cola de reproducción está vacía.", color = Color.Gray)
                                        }
                                    } else {
                                        LazyColumn(Modifier.fillMaxSize()) {
                                            items(queue) { item ->
                                                Row(
                                                    Modifier
                                                        .fillMaxWidth()
                                                        .padding(12.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(Icons.Default.MusicNote, contentDescription = null)
                                                    Spacer(Modifier.width(12.dp))
                                                    Column(Modifier.weight(1f)) {
                                                        Text(item.title, style = MaterialTheme.typography.titleMedium)
                                                        Text(item.artist, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        PlayerBar(player)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerBar(player: DesktopPlayer) {
    val state by player.state.collectAsState()
    val position by player.positionMs.collectAsState()
    val duration by player.durationMs.collectAsState()
    val volume by player.volume.collectAsState()
    val queue by player.queue.collectAsState()
    val index by player.currentIndex.collectAsState()
    val current = queue.getOrNull(index)

    Surface(shadowElevation = 8.dp) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                current?.title ?: if (state == DesktopPlayerState.IDLE) "Nada en reproducción" else "",
                style = MaterialTheme.typography.titleMedium,
            )
            if (current != null) Text(current.artist, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(formatTime(position), modifier = Modifier.width(52.dp))
                Slider(
                    value = if (duration > 0) position.toFloat() / duration.toFloat() else 0f,
                    onValueChange = { frac -> player.seekTo((frac * duration).toLong()) },
                    modifier = Modifier.weight(1f),
                )
                Text(formatTime(duration), modifier = Modifier.width(52.dp))
            }
            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = { player.prev() }) { Icon(Icons.Default.SkipPrevious, "Anterior") }
                IconButton(
                    onClick = { player.playPause() },
                    modifier = Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                ) {
                    Icon(
                        if (state == DesktopPlayerState.PLAYING) Icons.Default.Pause else Icons.Default.PlayArrow,
                        "Play/Pausa",
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
                IconButton(onClick = { player.next() }) { Icon(Icons.Default.SkipNext, "Siguiente") }
                Spacer(Modifier.width(24.dp))
                Text("Vol")
                Slider(value = volume / 100f, onValueChange = { player.setVolume((it * 100).toInt()) }, modifier = Modifier.width(140.dp))
            }
        }
    }
}

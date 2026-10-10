/*
 * YumaPlayer Desktop (Linux) entry point.
 * Compose-for-Desktop window with a player UI mirroring the Android app's mini-player
 * and full-player layouts, driven by the mpv-backed DesktopPlayer.
 */
package moe.rukamori.archivetune.desktop.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import moe.rukamori.archivetune.desktop.runtime.DesktopMediaItem
import moe.rukamori.archivetune.desktop.runtime.DesktopPlayer
import moe.rukamori.archivetune.desktop.runtime.DesktopPlayerState

private fun formatTime(ms: Long): String {
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}

fun main() {
    val player = DesktopPlayer()
    player.init()
    // Demo queue; real stream URLs are resolved through InnerTube/NewPipe (core modules).
    player.setQueue(
        listOf(
            DesktopMediaItem("1", "YumaPlayer Desktop", "Linux port", "https://example.com/stream.mp3"),
        ),
        startIndex = -1,
    )

    application {
        Window(
            onCloseRequest = {
                player.release()
                exitApplication()
            },
            state = rememberWindowState(width = 1100.dp, height = 720.dp),
            title = "YumaPlayer",
        ) {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
                            LazyColumn(Modifier.fillMaxSize()) {
                                items(player.queue.collectAsState().value) { item ->
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(item.title)
                                        Spacer(Modifier.width(8.dp))
                                        Text(item.artist, color = Color.Gray)
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

@androidx.compose.runtime.Composable
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

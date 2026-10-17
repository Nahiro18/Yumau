package moe.rukamori.archivetune.desktop.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.spotify.models.SpotifyAlbum
import moe.rukamori.archivetune.spotify.models.SpotifyArtist
import moe.rukamori.archivetune.spotify.models.SpotifyTrack
import java.util.Calendar

private val HomeGlassCard = Color(0x352A2E3D)
private val HomeSpotifyGreen = Color(0xFF1DB954)
private val LikedHeroGradient = Brush.linearGradient(
    listOf(Color(0xFF450AF5), Color(0xFF8E8EE5), Color(0xFF1DB954))
)

private fun getGreeting(): String {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when (hour) {
        in 5..11 -> "Buenos días"
        in 12..19 -> "Buenas tardes"
        else -> "Buenas noches"
    }
}

@Composable
fun DesktopHomeScreen(
    onPlayTrack: (songId: String, title: String, artist: String, durationSeconds: Int?, thumbnailUrl: String?) -> Unit,
    onPlaySpotifyTrack: (track: SpotifyTrack) -> Unit,
    onNavigateToSettings: () -> Unit,
) {
    val session by DesktopSpotifyStore.session.collectAsState()
    val recentlyPlayed by DesktopSpotifyStore.recentlyPlayed.collectAsState()
    val recentItems by DesktopSpotifyStore.recentItems.collectAsState()
    val totalLikedSongs by DesktopSpotifyStore.totalLikedSongs.collectAsState()
    val statsTimeRange by DesktopSpotifyStore.statsTimeRange.collectAsState()
    val statsTracks by DesktopSpotifyStore.statsTracks.collectAsState()
    val statsArtists by DesktopSpotifyStore.statsArtists.collectAsState()
    val isStatsLoading by DesktopSpotifyStore.isStatsLoading.collectAsState()
    val newReleases by DesktopSpotifyStore.newReleases.collectAsState()
    val isHomeLoading by DesktopSpotifyStore.isHomeLoading.collectAsState()

    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        if (session.isLoggedIn) {
            DesktopSpotifyStore.refreshHomeFeed()
        }
    }

    LaunchedEffect(session.isLoggedIn) {
        if (session.isLoggedIn) {
            DesktopSpotifyStore.refreshHomeFeed()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(22.dp),
        contentPadding = PaddingValues(bottom = 36.dp)
    ) {
        // 1. Top Greeting Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (session.isLoggedIn && session.displayName.isNotBlank()) {
                            "${getGreeting()}, ${session.displayName}"
                        } else {
                            getGreeting()
                        },
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = if (session.isLoggedIn) {
                            "Tu música personalizada de Spotify en alta fidelidad"
                        } else {
                            "Conecta tu cuenta de Spotify para acceder a tu biblioteca, listas y estadísticas"
                        },
                        fontSize = 13.sp,
                        color = Color(0xFF9CA3AF)
                    )
                }

                if (session.isLoggedIn) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val avatar = rememberNetworkImage(session.avatarUrl)
                        if (avatar != null) {
                            Image(
                                bitmap = avatar,
                                contentDescription = "Avatar",
                                modifier = Modifier.size(38.dp).clip(CircleShape),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(
                                Icons.Default.AccountCircle,
                                contentDescription = null,
                                tint = HomeSpotifyGreen,
                                modifier = Modifier.size(38.dp)
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        IconButton(
                            onClick = { scope.launch { DesktopSpotifyStore.refreshHomeFeed(force = true) } },
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Recargar", tint = Color.LightGray)
                        }
                    }
                }
            }
        }

        // 2. Banner if Not Logged In
        if (!session.isLoggedIn) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0x221DB954)),
                    border = BorderStroke(1.dp, Color(0x331DB954))
                ) {
                    Row(
                        modifier = Modifier.padding(24.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(HomeSpotifyGreen),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color.Black, modifier = Modifier.size(32.dp))
                        }
                        Spacer(Modifier.width(18.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Conecta tu cuenta de Spotify", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
                            Text(
                                "Vincula tu sesión de Spotify desde Ajustes mediante tu navegador para sincronizar tus canciones favoritas, playlists, artistas más escuchados y estadísticas.",
                                fontSize = 13.sp,
                                color = Color.LightGray
                            )
                        }
                        Spacer(Modifier.width(18.dp))
                        Button(
                            onClick = onNavigateToSettings,
                            colors = ButtonDefaults.buttonColors(containerColor = HomeSpotifyGreen),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Ir a Ajustes para Conectar", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        } else {
            // Logged in: 100% Spotify Experience!

            // 3. Top Quick Grid: Recently Played Playlists & Mixes (Like Mobile App)
            item {
                Text("Recientes y Accesos Rápidos", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Row 1: Liked Songs Hero Card + First Recent Item
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Liked Songs Quick Card
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .height(64.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    val firstLiked = DesktopSpotifyStore.likedSongs.value.firstOrNull()?.track
                                    if (firstLiked != null) {
                                        onPlaySpotifyTrack(firstLiked)
                                    }
                                },
                            colors = CardDefaults.cardColors(containerColor = HomeGlassCard)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(64.dp)
                                        .background(LikedHeroGradient),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Favorite, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Canciones que te gustan", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                                    Text(
                                        "${if (totalLikedSongs > 0) totalLikedSongs else DesktopSpotifyStore.likedSongs.value.size} canciones",
                                        fontSize = 11.sp,
                                        color = Color(0xFF9CA3AF)
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .padding(end = 10.dp)
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(HomeSpotifyGreen),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black, modifier = Modifier.size(22.dp))
                                }
                            }
                        }

                        // Second Item (First from recentItems or first recent track)
                        val secondItem = recentItems.firstOrNull()
                        if (secondItem != null) {
                            QuickRecentItemCard(
                                item = secondItem,
                                modifier = Modifier.weight(1f),
                                onPlay = {
                                    scope.launch {
                                        val tracks = DesktopSpotifyStore.fetchPlaylistTracks(secondItem.id)
                                        val first = tracks.firstOrNull()?.track
                                        if (first != null) onPlaySpotifyTrack(first)
                                    }
                                }
                            )
                        } else {
                            val firstRecentTrack = recentlyPlayed.firstOrNull()
                            if (firstRecentTrack != null) {
                                QuickPickCard(
                                    track = firstRecentTrack,
                                    modifier = Modifier.weight(1f),
                                    onPlay = { onPlaySpotifyTrack(firstRecentTrack) }
                                )
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }

                    // Row 2 & beyond: Remaining recent items (up to 6 more)
                    val remainingRecent = recentItems.drop(1).take(6)
                    val chunked = remainingRecent.chunked(2)
                    for (pair in chunked) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            for (item in pair) {
                                QuickRecentItemCard(
                                    item = item,
                                    modifier = Modifier.weight(1f),
                                    onPlay = {
                                        scope.launch {
                                            val tracks = DesktopSpotifyStore.fetchPlaylistTracks(item.id)
                                            val first = tracks.firstOrNull()?.track
                                            if (first != null) onPlaySpotifyTrack(first)
                                        }
                                    }
                                )
                            }
                            if (pair.size == 1) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            // 4. Tus Estadísticas de Spotify (Top Tracks & Artists with Time Range Filter)
            item {
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Tus Estadísticas de Spotify", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("Tus hábitos de escucha y artistas más reproducidos", fontSize = 12.sp, color = Color(0xFF9CA3AF))
                    }

                    // Time Range Filter Chips (4 Semanas, 6 Meses, Histórico)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val ranges = listOf(
                            "short_term" to "4 Semanas",
                            "medium_term" to "6 Meses",
                            "long_term" to "Histórico"
                        )
                        ranges.forEach { (code, label) ->
                            FilterChip(
                                selected = statsTimeRange == code,
                                onClick = { scope.launch { DesktopSpotifyStore.setStatsTimeRange(code) } },
                                label = { Text(label, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = HomeSpotifyGreen.copy(alpha = 0.25f),
                                    selectedLabelColor = HomeSpotifyGreen
                                )
                            )
                        }
                    }
                }
            }

            if (isStatsLoading && statsTracks.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = HomeSpotifyGreen)
                    }
                }
            } else {
                // Top Tracks Ranked List
                if (statsTracks.isNotEmpty()) {
                    item {
                        Text("Canciones Más Escuchadas", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.LightGray)
                    }
                    item {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            contentPadding = PaddingValues(horizontal = 2.dp)
                        ) {
                            itemsIndexed(statsTracks.take(15)) { index, track ->
                                RankedTrackCard(
                                    rank = index + 1,
                                    track = track,
                                    onPlay = { onPlaySpotifyTrack(track) }
                                )
                            }
                        }
                    }
                }

                // Top Artists Circles
                if (statsArtists.isNotEmpty()) {
                    item {
                        Text("Tus Artistas Más Escuchados", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.LightGray)
                    }
                    item {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(18.dp),
                            contentPadding = PaddingValues(horizontal = 2.dp)
                        ) {
                            items(statsArtists.take(15)) { artist ->
                                ArtistCard(artist = artist)
                            }
                        }
                    }
                }
            }

            // 5. Escuchado recientemente (Recently Played Tracks)
            if (recentlyPlayed.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(4.dp))
                    Text("Escuchado Recientemente", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                item {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = PaddingValues(horizontal = 2.dp)
                    ) {
                        items(recentlyPlayed) { track ->
                            TrackCard(
                                track = track,
                                onPlay = { onPlaySpotifyTrack(track) }
                            )
                        }
                    }
                }
            }

            // 6. Nuevos lanzamientos (New Releases)
            if (newReleases.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(4.dp))
                    Text("Nuevos Lanzamientos Para Ti", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                item {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = PaddingValues(horizontal = 2.dp)
                    ) {
                        items(newReleases) { album ->
                            AlbumCard(album = album)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickRecentItemCard(
    item: DesktopRecentItem,
    modifier: Modifier = Modifier,
    onPlay: () -> Unit
) {
    Card(
        modifier = modifier
            .height(64.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable { onPlay() },
        colors = CardDefaults.cardColors(containerColor = HomeGlassCard)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val img = rememberNetworkImage(item.thumbnailUrl)
            if (img != null) {
                Image(
                    bitmap = img,
                    contentDescription = item.title,
                    modifier = Modifier.size(64.dp),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier.size(64.dp).background(Color(0xFF2A2E3D)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (item.isPlaylist) Icons.AutoMirrored.Filled.QueueMusic else Icons.Default.Album,
                        contentDescription = null,
                        tint = Color.Gray
                    )
                }
            }

            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f).padding(end = 8.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = item.title,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.subtitle,
                    color = Color(0xFF9CA3AF),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            IconButton(
                onClick = onPlay,
                modifier = Modifier.padding(end = 6.dp).size(34.dp)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Reproducir", tint = HomeSpotifyGreen)
            }
        }
    }
}

@Composable
private fun QuickPickCard(
    track: SpotifyTrack,
    modifier: Modifier = Modifier,
    onPlay: () -> Unit
) {
    Card(
        modifier = modifier
            .height(64.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable { onPlay() },
        colors = CardDefaults.cardColors(containerColor = HomeGlassCard)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val img = rememberNetworkImage(track.album?.images?.firstOrNull()?.url)
            if (img != null) {
                Image(
                    bitmap = img,
                    contentDescription = track.name,
                    modifier = Modifier.size(64.dp),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier.size(64.dp).background(Color(0xFF2A2E3D)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Gray)
                }
            }

            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f).padding(end = 8.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = track.name,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = track.artists.joinToString(", ") { it.name },
                    color = Color(0xFF9CA3AF),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            IconButton(
                onClick = onPlay,
                modifier = Modifier.padding(end = 6.dp).size(34.dp)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Reproducir", tint = HomeSpotifyGreen)
            }
        }
    }
}

@Composable
private fun RankedTrackCard(
    rank: Int,
    track: SpotifyTrack,
    onPlay: () -> Unit
) {
    Card(
        modifier = Modifier
            .width(150.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onPlay() },
        colors = CardDefaults.cardColors(containerColor = HomeGlassCard)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Box {
                val img = rememberNetworkImage(track.album?.images?.firstOrNull()?.url)
                Box(
                    modifier = Modifier
                        .size(130.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF2A2E3D)),
                    contentAlignment = Alignment.Center
                ) {
                    if (img != null) {
                        Image(
                            bitmap = img,
                            contentDescription = track.name,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Gray)
                    }
                }

                // Rank Badge (#1, #2, etc.)
                Surface(
                    modifier = Modifier.padding(6.dp),
                    color = if (rank <= 3) HomeSpotifyGreen else Color(0xDD000000),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "#$rank",
                        color = if (rank <= 3) Color.Black else Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = track.name,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = track.artists.joinToString(", ") { it.name },
                fontSize = 11.sp,
                color = Color.Gray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun TrackCard(
    track: SpotifyTrack,
    onPlay: () -> Unit
) {
    // Prefetch especulativo (como StreamPrefetcher en Android): al verse la tarjeta
    // pre-resolvemos el match Spotify->YouTube y la URL del stream, para que el click
    // sea un acierto de cache (0 ms) en vez de una busqueda + resolucion.
    LaunchedEffect(track.id) {
        runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val matchStartedAt = System.currentTimeMillis()
                val matched = DesktopSpotifyStore.matchSpotifyTrackToYouTube(track)
                DesktopDiag.log(
                    "prefetch", System.currentTimeMillis() - matchStartedAt,
                    "home match ${track.name.take(28)}"
                )
                if (matched != null) {
                    val s = DesktopSettingsStore.settings.value
                    DesktopStreamResolver.prefetch(
                        videoId = matched.id,
                        title = matched.title,
                        artist = matched.artists.joinToString(", ") { it.name },
                        durationMs = (matched.duration ?: 0) * 1000L,
                        playbackSource = s.playbackSource,
                        flacQuality = s.flacQuality,
                        preferredQuality = s.audioQuality,
                        preferredClientName = s.streamingClient,
                        flacFallbackToYoutube = s.flacFallbackToYoutube
                    )
                }
            }
        }
    }

    Card(
        modifier = Modifier
            .width(140.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onPlay() },
        colors = CardDefaults.cardColors(containerColor = HomeGlassCard)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            val img = rememberNetworkImage(track.album?.images?.firstOrNull()?.url)
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF2A2E3D)),
                contentAlignment = Alignment.Center
            ) {
                if (img != null) {
                    Image(
                        bitmap = img,
                        contentDescription = track.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Gray)
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = track.name,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = track.artists.joinToString(", ") { it.name },
                fontSize = 11.sp,
                color = Color.Gray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ArtistCard(artist: SpotifyArtist) {
    Card(
        modifier = Modifier.width(110.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val img = rememberNetworkImage(artist.images.firstOrNull()?.url)
            Box(
                modifier = Modifier
                    .size(90.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF2A2E3D)),
                contentAlignment = Alignment.Center
            ) {
                if (img != null) {
                    Image(
                        bitmap = img,
                        contentDescription = artist.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(Icons.Default.AccountCircle, contentDescription = null, tint = HomeSpotifyGreen, modifier = Modifier.size(48.dp))
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = artist.name,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "Artista",
                fontSize = 10.sp,
                color = Color.Gray
            )
        }
    }
}

@Composable
private fun AlbumCard(album: SpotifyAlbum) {
    Card(
        modifier = Modifier.width(140.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = HomeGlassCard)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            val img = rememberNetworkImage(album.images.firstOrNull()?.url)
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF2A2E3D)),
                contentAlignment = Alignment.Center
            ) {
                if (img != null) {
                    Image(
                        bitmap = img,
                        contentDescription = album.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(Icons.Default.Album, contentDescription = null, tint = Color.Gray)
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = album.name,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = album.artists.joinToString(", ") { it.name },
                fontSize = 11.sp,
                color = Color.Gray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

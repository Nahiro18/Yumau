package moe.rukamori.archivetune.desktop.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Whatshot
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.innertube.SearchFilter
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.spotify.models.SpotifyAlbum
import moe.rukamori.archivetune.spotify.models.SpotifyArtist
import moe.rukamori.archivetune.spotify.models.SpotifyTrack
import java.util.Calendar

private val HomeGlassCard = Color(0x352A2E3D)
private val HomeSpotifyGreen = Color(0xFF1DB954)
private val HomeCardHover = Color(0x503F4457)

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
    val topTracks by DesktopSpotifyStore.topTracks.collectAsState()
    val topArtists by DesktopSpotifyStore.topArtists.collectAsState()
    val newReleases by DesktopSpotifyStore.newReleases.collectAsState()
    val isHomeLoading by DesktopSpotifyStore.isHomeLoading.collectAsState()

    var selectedSource by remember { mutableStateOf(if (session.isLoggedIn) 0 else 1) } // 0: Spotify, 1: YouTube
    var ytTrendingSongs by remember { mutableStateOf<List<SongItem>>(emptyList()) }
    var isYtLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        if (session.isLoggedIn) {
            DesktopSpotifyStore.refreshHomeFeed()
        }
        isYtLoading = true
        withContext(Dispatchers.IO) {
            try {
                val chartResult = YouTube.getChartsPage().getOrNull()
                val songs = chartResult?.sections?.flatMap { it.items }?.filterIsInstance<SongItem>() ?: emptyList()
                ytTrendingSongs = if (songs.isNotEmpty()) {
                    songs
                } else {
                    YouTube.search("Top Hits 2026", filter = SearchFilter.FILTER_SONG).getOrNull()
                        ?.items?.filterIsInstance<SongItem>() ?: emptyList()
                }
            } catch (_: Exception) {
                ytTrendingSongs = YouTube.search("Top Music", filter = SearchFilter.FILTER_SONG).getOrNull()
                    ?.items?.filterIsInstance<SongItem>() ?: emptyList()
            } finally {
                isYtLoading = false
            }
        }
    }

    LaunchedEffect(session.isLoggedIn) {
        if (session.isLoggedIn) {
            selectedSource = 0
            DesktopSpotifyStore.refreshHomeFeed()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        // Top Greeting Header
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
                        text = if (session.isLoggedIn) "Música personalizada de tu cuenta de Spotify y YouTube" else "Explora tendencias y recomendaciones en alta fidelidad",
                        fontSize = 13.sp,
                        color = Color(0xFF9CA3AF)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (session.isLoggedIn) {
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
                            onClick = { scope.launch { DesktopSpotifyStore.refreshHomeFeed() } },
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Recargar", tint = Color.LightGray)
                        }
                    }
                }
            }
        }

        // Source Switcher Chips
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(
                    selected = selectedSource == 0,
                    onClick = { selectedSource = 0 },
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (session.isLoggedIn) HomeSpotifyGreen else Color.Gray)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Tu Spotify")
                        }
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0x331DB954),
                        selectedLabelColor = Color.White
                    )
                )

                FilterChip(
                    selected = selectedSource == 1,
                    onClick = { selectedSource = 1 },
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Whatshot, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFFFF453A))
                            Spacer(Modifier.width(6.dp))
                            Text("Tendencias YouTube")
                        }
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0x33FF453A),
                        selectedLabelColor = Color.White
                    )
                )
            }
        }

        // Spotify Content
        if (selectedSource == 0) {
            if (!session.isLoggedIn) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0x221DB954))
                    ) {
                        Row(
                            modifier = Modifier.padding(20.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(54.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(HomeSpotifyGreen),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.TrendingUp, contentDescription = null, tint = Color.Black, modifier = Modifier.size(32.dp))
                            }
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Conecta tu cuenta de Spotify", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                                Text(
                                    "Vincula tu cuenta desde Ajustes para escuchar tus canciones recientes, listas y recomendaciones personalizadas.",
                                    fontSize = 12.sp,
                                    color = Color.LightGray
                                )
                            }
                            Spacer(Modifier.width(16.dp))
                            Button(
                                onClick = onNavigateToSettings,
                                colors = ButtonDefaults.buttonColors(containerColor = HomeSpotifyGreen),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Conectar ahora", color = Color.Black, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }

                // Show YouTube trends below if not logged in
                item {
                    Text("Tendencias Populares", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                items(ytTrendingSongs) { song ->
                    SongRowItem(
                        song = song,
                        onPlay = {
                            onPlayTrack(song.id, song.title, song.artists.joinToString(", ") { it.name }, song.duration, song.thumbnail)
                        }
                    )
                }
            } else {
                // User is logged into Spotify!
                if (isHomeLoading && recentlyPlayed.isEmpty() && topTracks.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = HomeSpotifyGreen)
                        }
                    }
                } else {
                    // 1. Escuchado recientemente (Quick Picks Grid)
                    if (recentlyPlayed.isNotEmpty()) {
                        item {
                            Text("Escuchado recientemente", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        item {
                            val chunked = recentlyPlayed.take(8).chunked(2)
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (pair in chunked) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        for (track in pair) {
                                            QuickPickCard(
                                                track = track,
                                                modifier = Modifier.weight(1f),
                                                onPlay = { onPlaySpotifyTrack(track) }
                                            )
                                        }
                                        if (pair.size == 1) {
                                            Spacer(Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 2. Tus más escuchadas (Top Tracks)
                    if (topTracks.isNotEmpty()) {
                        item {
                            Spacer(Modifier.height(8.dp))
                            Text("Tus canciones más escuchadas", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        item {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                contentPadding = PaddingValues(horizontal = 2.dp)
                            ) {
                                items(topTracks) { track ->
                                    TrackCard(
                                        track = track,
                                        onPlay = { onPlaySpotifyTrack(track) }
                                    )
                                }
                            }
                        }
                    }

                    // 3. Tus artistas favoritos (Top Artists)
                    if (topArtists.isNotEmpty()) {
                        item {
                            Spacer(Modifier.height(8.dp))
                            Text("Tus artistas favoritos", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        item {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                contentPadding = PaddingValues(horizontal = 2.dp)
                            ) {
                                items(topArtists) { artist ->
                                    ArtistCard(artist = artist)
                                }
                            }
                        }
                    }

                    // 4. Nuevos lanzamientos (New Releases)
                    if (newReleases.isNotEmpty()) {
                        item {
                            Spacer(Modifier.height(8.dp))
                            Text("Nuevos lanzamientos para ti", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
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
        } else {
            // YouTube Trending
            if (isYtLoading) {
                item {
                    Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
            } else {
                items(ytTrendingSongs) { song ->
                    SongRowItem(
                        song = song,
                        onPlay = {
                            onPlayTrack(song.id, song.title, song.artists.joinToString(", ") { it.name }, song.duration, song.thumbnail)
                        }
                    )
                }
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
            .height(58.dp)
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
                    modifier = Modifier.size(58.dp),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier.size(58.dp).background(Color(0xFF2A2E3D)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Gray)
                }
            }

            Spacer(Modifier.width(10.dp))
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
                modifier = Modifier.padding(end = 4.dp).size(32.dp)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Reproducir", tint = HomeSpotifyGreen)
            }
        }
    }
}

@Composable
private fun TrackCard(
    track: SpotifyTrack,
    onPlay: () -> Unit
) {
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

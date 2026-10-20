package moe.rukamori.archivetune.desktop.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.desktop.runtime.DesktopMediaItem
import moe.rukamori.archivetune.spotify.models.SpotifyPlaylist
import moe.rukamori.archivetune.spotify.models.SpotifyPlaylistTrack
import moe.rukamori.archivetune.spotify.models.SpotifyTrack

private val LibGlassCard = Color(0x352A2E3D)
private val LibSpotifyGreen = Color(0xFF1DB954)
private val LikedSongsGradient = Brush.linearGradient(listOf(Color(0xFF4A154B), Color(0xFF1DB954)))

@Composable
fun DesktopLibraryScreen(
    onPlayTrack: (songId: String, title: String, artist: String, durationSeconds: Int?, thumbnailUrl: String?) -> Unit,
    onPlaySpotifyTrack: (track: SpotifyTrack) -> Unit,
    onPlayQueue: (items: List<DesktopMediaItem>, startIndex: Int) -> Unit,
    onNavigateToSettings: () -> Unit,
) {
    var selectedFilter by remember { mutableStateOf(0) } // 0: Todo, 1: Spotify, 2: Playlists, 3: Favoritos, 4: Historial
    val favorites by DesktopLibraryStore.favorites.collectAsState()
    val history by DesktopLibraryStore.history.collectAsState()
    val localPlaylists by DesktopLibraryStore.playlists.collectAsState()
    val spotifySession by DesktopSpotifyStore.session.collectAsState()
    val spotifyLiked by DesktopSpotifyStore.likedSongs.collectAsState()
    val totalLikedSongs by DesktopSpotifyStore.totalLikedSongs.collectAsState()
    val spotifyPlaylists by DesktopSpotifyStore.playlists.collectAsState()
    val totalPlaylists by DesktopSpotifyStore.totalPlaylists.collectAsState()
    val isLibraryLoading by DesktopSpotifyStore.isLibraryLoading.collectAsState()

    var activePlaylist by remember { mutableStateOf<SpotifyPlaylist?>(null) }
    var activePlaylistTracks by remember { mutableStateOf<List<SpotifyPlaylistTrack>?>(null) }
    var isLoadingPlaylistTracks by remember { mutableStateOf(false) }
    var showingLikedSongsDetail by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(spotifySession.isLoggedIn) {
        if (spotifySession.isLoggedIn) {
            DesktopSpotifyStore.refreshLibrary()
        }
    }

    // Detail view for a Spotify Playlist
    if (activePlaylist != null) {
        SpotifyPlaylistDetailView(
            playlist = activePlaylist!!,
            tracks = activePlaylistTracks.orEmpty(),
            isLoading = isLoadingPlaylistTracks,
            onBack = {
                activePlaylist = null
                activePlaylistTracks = null
            },
            onPlayTrack = { track -> onPlaySpotifyTrack(track) },
            onPlayAll = { tracks, shuffle ->
                val items = tracks.mapNotNull { it.track }.map { track ->
                    DesktopMediaItem(
                        id = track.id,
                        title = track.name,
                        artist = track.artists.joinToString(", ") { it.name },
                        uri = "",
                        durationMs = track.durationMs.toLong(),
                        thumbnailUrl = track.album?.images?.firstOrNull()?.url,
                        audioFormat = null
                    )
                }
                val finalItems = if (shuffle) items.shuffled() else items
                if (finalItems.isNotEmpty()) {
                    onPlayQueue(finalItems, 0)
                }
            }
        )
        return
    }

    // Detail view for Liked Songs
    if (showingLikedSongsDetail) {
        LikedSongsDetailView(
            spotifyLiked = spotifyLiked,
            totalLikedCount = if (totalLikedSongs > 0) totalLikedSongs else spotifyLiked.size,
            localFavorites = favorites,
            onBack = { showingLikedSongsDetail = false },
            onPlaySpotifyTrack = onPlaySpotifyTrack,
            onPlayTrack = onPlayTrack,
            onPlayAll = {
                val items = spotifyLiked.map { it.track }.map { track ->
                    DesktopMediaItem(
                        id = track.id,
                        title = track.name,
                        artist = track.artists.joinToString(", ") { it.name },
                        uri = "",
                        durationMs = track.durationMs.toLong(),
                        thumbnailUrl = track.album?.images?.firstOrNull()?.url,
                        audioFormat = null
                    )
                }
                if (items.isNotEmpty()) {
                    onPlayQueue(items, 0)
                }
            }
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Tu Biblioteca", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("Tus canciones guardadas, playlists y reproducciones", fontSize = 13.sp, color = Color(0xFF9CA3AF))
            }
            if (isLibraryLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = LibSpotifyGreen
                )
                Spacer(Modifier.width(8.dp))
            }
            IconButton(
                onClick = { scope.launch { DesktopSpotifyStore.refreshLibrary(force = true) } },
                enabled = spotifySession.isLoggedIn && !isLibraryLoading
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Recargar biblioteca", tint = Color.LightGray)
            }
        }
        if (isLibraryLoading) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = LibSpotifyGreen,
                trackColor = Color(0xFF2A2E3D)
            )
        }
        Spacer(Modifier.height(14.dp))

        // Unified Filter Chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val displaySpotifyTracksCount = if (totalLikedSongs > 0) totalLikedSongs else spotifyLiked.size
            val displaySpotifyPlsCount = if (totalPlaylists > 0) totalPlaylists else spotifyPlaylists.size
            val filterOptions = listOf(
                "Todo",
                "Spotify (${if (spotifySession.isLoggedIn) displaySpotifyPlsCount + displaySpotifyTracksCount else 0})",
                "Playlists (${localPlaylists.size + (if (spotifySession.isLoggedIn) displaySpotifyPlsCount else 0)})",
                "Favoritos (${favorites.size})",
                "Historial (${history.size})"
            )
            filterOptions.forEachIndexed { idx, label ->
                FilterChip(
                    selected = selectedFilter == idx,
                    onClick = { selectedFilter = idx },
                    label = { Text(label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = if (idx == 1 && spotifySession.isLoggedIn) Color(0x331DB954) else MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = Color.White
                    )
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        when (selectedFilter) {
            0 -> { // Todo
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(bottom = 32.dp)
                ) {
                    // Hero Card: Canciones que te gustan
                    item {
                        LikedSongsHeroCard(
                            spotifyCount = if (totalLikedSongs > 0) totalLikedSongs else spotifyLiked.size,
                            favoritesCount = favorites.size,
                            isLoggedIn = spotifySession.isLoggedIn,
                            onClick = { showingLikedSongsDetail = true },
                            onPlayAll = {
                                val items = spotifyLiked.map { it.track }.map { track ->
                                    DesktopMediaItem(
                                        id = track.id,
                                        title = track.name,
                                        artist = track.artists.joinToString(", ") { it.name },
                                        uri = "",
                                        durationMs = track.durationMs.toLong(),
                                        thumbnailUrl = track.album?.images?.firstOrNull()?.url,
                                        audioFormat = null
                                    )
                                }
                                if (items.isNotEmpty()) onPlayQueue(items, 0)
                            }
                        )
                    }

                    // Spotify Playlists Grid Preview
                    if (spotifySession.isLoggedIn && spotifyPlaylists.isNotEmpty()) {
                        item {
                            Text("Tus Playlists de Spotify", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        item {
                            val chunked = spotifyPlaylists.chunked(4)
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                for (row in chunked) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        for (pl in row) {
                                            SpotifyPlaylistCard(
                                                playlist = pl,
                                                modifier = Modifier.weight(1f),
                                                onClick = {
                                                    activePlaylist = pl
                                                    isLoadingPlaylistTracks = true
                                                    scope.launch {
                                                        activePlaylistTracks = DesktopSpotifyStore.fetchPlaylistTracks(pl.id)
                                                        isLoadingPlaylistTracks = false
                                                    }
                                                }
                                            )
                                        }
                                        repeat(4 - row.size) {
                                            Spacer(Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Local Playlists Preview
                    if (localPlaylists.isNotEmpty()) {
                        item {
                            Text("Playlists Locales", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        items(localPlaylists) { pl ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = LibGlassCard)
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.QueueMusic, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(pl.name, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.White)
                                        Text("${pl.songs.size} canciones", color = Color.Gray, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }

                    // Favorites Preview
                    if (favorites.isNotEmpty()) {
                        item {
                            Text("Canciones Favoritas", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        items(favorites.take(10)) { song ->
                            StoredSongRow(
                                stored = song,
                                onPlay = {
                                    onPlayTrack(song.id, song.title, song.artist, (song.durationMs / 1000).toInt(), song.thumbnailUrl)
                                }
                            )
                        }
                    }
                }
            }

            1 -> { // Spotify
                if (!spotifySession.isLoggedIn) {
                    SpotifyNotConnectedBox(onNavigateToSettings = onNavigateToSettings)
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(bottom = 32.dp)
                    ) {
                        item {
                            LikedSongsHeroCard(
                                spotifyCount = spotifyLiked.size,
                                favoritesCount = 0,
                                isLoggedIn = true,
                                onClick = { showingLikedSongsDetail = true },
                                onPlayAll = {
                                    val items = spotifyLiked.map { it.track }.map { track ->
                                        DesktopMediaItem(
                                            id = track.id,
                                            title = track.name,
                                            artist = track.artists.joinToString(", ") { it.name },
                                            uri = "",
                                            durationMs = track.durationMs.toLong(),
                                            thumbnailUrl = track.album?.images?.firstOrNull()?.url,
                                            audioFormat = null
                                        )
                                    }
                                    if (items.isNotEmpty()) onPlayQueue(items, 0)
                                }
                            )
                        }

                        item {
                            Text("Playlists Guardadas (${spotifyPlaylists.size})", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }

                        val chunked = spotifyPlaylists.chunked(4)
                        items(chunked) { row ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                for (pl in row) {
                                    SpotifyPlaylistCard(
                                        playlist = pl,
                                        modifier = Modifier.weight(1f),
                                        onClick = {
                                            activePlaylist = pl
                                            isLoadingPlaylistTracks = true
                                            scope.launch {
                                                activePlaylistTracks = DesktopSpotifyStore.fetchPlaylistTracks(pl.id)
                                                isLoadingPlaylistTracks = false
                                            }
                                        }
                                    )
                                }
                                repeat(4 - row.size) {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }

            2 -> { // Playlists
                var newPlName by remember { mutableStateOf("") }
                Column(Modifier.fillMaxSize()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = newPlName,
                            onValueChange = { newPlName = it },
                            placeholder = { Text("Nombre de nueva playlist...") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Button(
                            onClick = {
                                if (newPlName.isNotBlank()) {
                                    scope.launch {
                                        DesktopLibraryStore.createPlaylist(newPlName)
                                        newPlName = ""
                                    }
                                }
                            },
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Crear Playlist")
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(bottom = 32.dp)
                    ) {
                        if (spotifySession.isLoggedIn && spotifyPlaylists.isNotEmpty()) {
                            item {
                                Text("Playlists de Spotify", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                            val chunked = spotifyPlaylists.chunked(4)
                            items(chunked) { row ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    for (pl in row) {
                                        SpotifyPlaylistCard(
                                            playlist = pl,
                                            modifier = Modifier.weight(1f),
                                            onClick = {
                                                activePlaylist = pl
                                                isLoadingPlaylistTracks = true
                                                scope.launch {
                                                    activePlaylistTracks = DesktopSpotifyStore.fetchPlaylistTracks(pl.id)
                                                    isLoadingPlaylistTracks = false
                                                }
                                            }
                                        )
                                    }
                                    repeat(4 - row.size) {
                                        Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                        }

                        item {
                            Spacer(Modifier.height(12.dp))
                            Text("Playlists Locales", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }

                        if (localPlaylists.isEmpty()) {
                            item {
                                Text("No tienes playlists locales aún. Crea una arriba.", color = Color.Gray)
                            }
                        } else {
                            items(localPlaylists) { pl ->
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = LibGlassCard)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.QueueMusic, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(pl.name, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.White)
                                            Text("${pl.songs.size} canciones", color = Color.Gray, fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            3 -> { // Favoritos
                if (favorites.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Aún no tienes canciones favoritas guardadas.", color = Color.Gray)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(favorites) { song ->
                            StoredSongRow(
                                stored = song,
                                onPlay = {
                                    onPlayTrack(song.id, song.title, song.artist, (song.durationMs / 1000).toInt(), song.thumbnailUrl)
                                }
                            )
                        }
                    }
                }
            }

            4 -> { // Historial
                if (history.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No hay historial de reproducción aún.", color = Color.Gray)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(history) { song ->
                            StoredSongRow(
                                stored = song,
                                onPlay = {
                                    onPlayTrack(song.id, song.title, song.artist, (song.durationMs / 1000).toInt(), song.thumbnailUrl)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LikedSongsHeroCard(
    spotifyCount: Int,
    favoritesCount: Int,
    isLoggedIn: Boolean,
    onClick: () -> Unit,
    onPlayAll: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(130.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(LikedSongsGradient)
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0x40FFFFFF)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Favorite, contentDescription = null, tint = Color.White, modifier = Modifier.size(38.dp))
                }

                Spacer(Modifier.width(18.dp))

                Column(Modifier.weight(1f)) {
                    Text("Canciones que te gustan", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(
                        if (isLoggedIn) "$spotifyCount canciones en Spotify" else "$favoritesCount canciones favoritas",
                        fontSize = 13.sp,
                        color = Color(0xCCFFFFFF)
                    )
                }

                Button(
                    onClick = onPlayAll,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black)
                    Spacer(Modifier.width(6.dp))
                    Text("Reproducir", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun SpotifyPlaylistCard(
    playlist: SpotifyPlaylist,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Card(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = LibGlassCard)
    ) {
        Column(Modifier.padding(10.dp)) {
            val img = rememberNetworkImage(playlist.images.firstOrNull()?.url)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF2A2E3D)),
                contentAlignment = Alignment.Center
            ) {
                if (img != null) {
                    Image(
                        bitmap = img,
                        contentDescription = playlist.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(Icons.Default.MusicNote, contentDescription = null, tint = LibSpotifyGreen, modifier = Modifier.size(40.dp))
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                playlist.name,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${playlist.tracks?.total ?: 0} canciones",
                fontSize = 11.sp,
                color = Color.Gray,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun SpotifyPlaylistDetailView(
    playlist: SpotifyPlaylist,
    tracks: List<SpotifyPlaylistTrack>,
    isLoading: Boolean,
    onBack: () -> Unit,
    onPlayTrack: (SpotifyTrack) -> Unit,
    onPlayAll: (List<SpotifyPlaylistTrack>, Boolean) -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    val filteredTracks = remember(tracks, searchQuery) {
        if (searchQuery.isBlank()) tracks
        else tracks.filter { item ->
            val t = item.track ?: return@filter false
            t.name.contains(searchQuery, ignoreCase = true) ||
            t.artists.any { it.name.contains(searchQuery, ignoreCase = true) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Volver", tint = Color.White)
            }
            Spacer(Modifier.width(8.dp))
            Text("Volver a la Biblioteca", fontSize = 14.sp, color = Color.Gray)
        }

        Spacer(Modifier.height(12.dp))

        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom
        ) {
            val img = rememberNetworkImage(playlist.images.firstOrNull()?.url)
            Box(
                modifier = Modifier
                    .size(160.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF2A2E3D)),
                contentAlignment = Alignment.Center
            ) {
                if (img != null) {
                    Image(
                        bitmap = img,
                        contentDescription = playlist.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(Icons.Default.MusicNote, contentDescription = null, tint = LibSpotifyGreen, modifier = Modifier.size(60.dp))
                }
            }

            Spacer(Modifier.width(20.dp))

            Column {
                Text("PLAYLIST", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF9CA3AF))
                val desc = playlist.description
                if (!desc.isNullOrBlank()) {
                    Text(desc, fontSize = 12.sp, color = Color.LightGray, maxLines = 2)
                }
                Spacer(Modifier.height(6.dp))
                Text("${playlist.tracks?.total ?: tracks.size} canciones", fontSize = 13.sp, color = Color.Gray)
            }
        }

        Spacer(Modifier.height(18.dp))

        // Actions Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { onPlayAll(tracks, false) },
                colors = ButtonDefaults.buttonColors(containerColor = LibSpotifyGreen),
                shape = RoundedCornerShape(24.dp)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black)
                Spacer(Modifier.width(6.dp))
                Text("Reproducir Playlist", color = Color.Black, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.width(12.dp))

            OutlinedButton(
                onClick = { onPlayAll(tracks, true) },
                shape = RoundedCornerShape(24.dp)
            ) {
                Icon(Icons.Default.Shuffle, contentDescription = null, tint = Color.White)
                Spacer(Modifier.width(6.dp))
                Text("Aleatorio", color = Color.White)
            }

            Spacer(Modifier.weight(1f))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Buscar en esta lista...") },
                modifier = Modifier.width(260.dp),
                singleLine = true,
                shape = RoundedCornerShape(10.dp)
            )
        }

        Spacer(Modifier.height(16.dp))

        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = LibSpotifyGreen)
            }
        } else if (filteredTracks.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No hay canciones disponibles en esta playlist", color = Color.Gray)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                itemsIndexed(filteredTracks) { index, item ->
                    val track = item.track
                    if (track != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onPlayTrack(track) }
                                .padding(vertical = 8.dp, horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${index + 1}", color = Color.Gray, fontSize = 13.sp, modifier = Modifier.width(28.dp))
                            val trackImg = rememberNetworkImage(track.album?.images?.firstOrNull()?.url)
                            if (trackImg != null) {
                                Image(
                                    bitmap = trackImg,
                                    contentDescription = track.name,
                                    modifier = Modifier.size(42.dp).clip(RoundedCornerShape(6.dp)),
                                    contentScale = ContentScale.Crop
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(track.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Color.White, maxLines = 1)
                                Text(track.artists.joinToString(", ") { it.name }, fontSize = 12.sp, color = Color.Gray, maxLines = 1)
                            }
                            Text(formatDuration(track.durationMs), color = Color.Gray, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LikedSongsDetailView(
    spotifyLiked: List<moe.rukamori.archivetune.spotify.models.SpotifySavedTrack>,
    totalLikedCount: Int = spotifyLiked.size,
    localFavorites: List<StoredSong>,
    onBack: () -> Unit,
    onPlaySpotifyTrack: (SpotifyTrack) -> Unit,
    onPlayTrack: (String, String, String, Int?, String?) -> Unit,
    onPlayAll: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Volver", tint = Color.White)
            }
            Spacer(Modifier.width(8.dp))
            Text("Volver a la Biblioteca", fontSize = 14.sp, color = Color.Gray)
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(LikedSongsGradient),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Favorite, contentDescription = null, tint = Color.White, modifier = Modifier.size(54.dp))
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text("Canciones que te gustan", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("$totalLikedCount canciones en Spotify", fontSize = 13.sp, color = Color.Gray)
            }
            Button(
                onClick = onPlayAll,
                colors = ButtonDefaults.buttonColors(containerColor = LibSpotifyGreen),
                shape = RoundedCornerShape(24.dp)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black)
                Spacer(Modifier.width(6.dp))
                Text("Reproducir todo", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(18.dp))

        if (spotifyLiked.isEmpty() && localFavorites.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No tienes canciones que te gusten guardadas aún.", color = Color.Gray)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                items(spotifyLiked) { saved ->
                    SpotifyTrackRow(
                        track = saved.track,
                        onPlay = { onPlaySpotifyTrack(saved.track) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SpotifyNotConnectedBox(onNavigateToSettings: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Radio, contentDescription = null, tint = LibSpotifyGreen, modifier = Modifier.size(54.dp))
            Spacer(Modifier.height(16.dp))
            Text("Tu cuenta de Spotify no está conectada", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
            Text("Conéctate fácilmente desde Ajustes con tu navegador para ver tus playlists y canciones guardadas.", color = Color.Gray, fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onNavigateToSettings,
                colors = ButtonDefaults.buttonColors(containerColor = LibSpotifyGreen),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Ir a Ajustes para Conectar", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun formatDuration(ms: Int): String {
    val sec = (ms / 1000)
    return "%d:%02d".format(sec / 60, sec % 60)
}

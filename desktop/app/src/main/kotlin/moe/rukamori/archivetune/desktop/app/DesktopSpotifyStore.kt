package moe.rukamori.archivetune.desktop.app

import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import moe.rukamori.archivetune.innertube.SearchFilter
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.spotify.Spotify
import moe.rukamori.archivetune.spotify.SpotifyAuth
import moe.rukamori.archivetune.spotify.models.SpotifyAlbum
import moe.rukamori.archivetune.spotify.models.SpotifyArtist
import moe.rukamori.archivetune.spotify.models.SpotifyHomeFeedItem
import moe.rukamori.archivetune.spotify.models.SpotifyPlaylist
import moe.rukamori.archivetune.spotify.models.SpotifyPlaylistTrack
import moe.rukamori.archivetune.spotify.models.SpotifySavedTrack
import moe.rukamori.archivetune.spotify.models.SpotifyTrack
import moe.rukamori.archivetune.spotify.models.SpotifyUser
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class SpotifySessionData(
    val spDc: String = "",
    val spKey: String = "",
    val spT: String = "",
    val fullCookieHeader: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    val displayName: String = "",
    val email: String = "",
    val avatarUrl: String = "",
    val isLoggedIn: Boolean = false,
    // Epoch ms en que caduca accessToken. 0 = desconocido (sesiones antiguas):
    // se renueva la primera vez y se guarda la caducidad real.
    val accessTokenExpiresAt: Long = 0L,
)

data class DesktopRecentItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val isPlaylist: Boolean,
    val trackCount: Int? = null,
)

object DesktopSpotifyStore {
    private val configDir = File(System.getProperty("user.home"), ".config/YumaPlayer").apply { mkdirs() }
    private val sessionFile = File(configDir, "spotify_session.json")

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val _session = MutableStateFlow(SpotifySessionData())
    val session: StateFlow<SpotifySessionData> = _session.asStateFlow()

    private val _likedSongs = MutableStateFlow<List<SpotifySavedTrack>>(emptyList())
    val likedSongs: StateFlow<List<SpotifySavedTrack>> = _likedSongs.asStateFlow()

    private val _totalLikedSongs = MutableStateFlow(0)
    val totalLikedSongs: StateFlow<Int> = _totalLikedSongs.asStateFlow()

    private val _playlists = MutableStateFlow<List<SpotifyPlaylist>>(emptyList())
    val playlists: StateFlow<List<SpotifyPlaylist>> = _playlists.asStateFlow()

    private val _totalPlaylists = MutableStateFlow(0)
    val totalPlaylists: StateFlow<Int> = _totalPlaylists.asStateFlow()

    private val _recentlyPlayed = MutableStateFlow<List<SpotifyTrack>>(emptyList())
    val recentlyPlayed: StateFlow<List<SpotifyTrack>> = _recentlyPlayed.asStateFlow()

    private val _recentItems = MutableStateFlow<List<DesktopRecentItem>>(emptyList())
    val recentItems: StateFlow<List<DesktopRecentItem>> = _recentItems.asStateFlow()

    private val _topTracks = MutableStateFlow<List<SpotifyTrack>>(emptyList())
    val topTracks: StateFlow<List<SpotifyTrack>> = _topTracks.asStateFlow()

    private val _topArtists = MutableStateFlow<List<SpotifyArtist>>(emptyList())
    val topArtists: StateFlow<List<SpotifyArtist>> = _topArtists.asStateFlow()

    private val _statsTimeRange = MutableStateFlow("medium_term") // "short_term", "medium_term", "long_term"
    val statsTimeRange: StateFlow<String> = _statsTimeRange.asStateFlow()

    private val _statsTracks = MutableStateFlow<List<SpotifyTrack>>(emptyList())
    val statsTracks: StateFlow<List<SpotifyTrack>> = _statsTracks.asStateFlow()

    private val _statsArtists = MutableStateFlow<List<SpotifyArtist>>(emptyList())
    val statsArtists: StateFlow<List<SpotifyArtist>> = _statsArtists.asStateFlow()

    private val _isStatsLoading = MutableStateFlow(false)
    val isStatsLoading: StateFlow<Boolean> = _isStatsLoading.asStateFlow()

    private val _newReleases = MutableStateFlow<List<SpotifyAlbum>>(emptyList())
    val newReleases: StateFlow<List<SpotifyAlbum>> = _newReleases.asStateFlow()

    private val _isHomeLoading = MutableStateFlow(false)
    val isHomeLoading: StateFlow<Boolean> = _isHomeLoading.asStateFlow()

    private val _isLibraryLoading = MutableStateFlow(false)
    val isLibraryLoading: StateFlow<Boolean> = _isLibraryLoading.asStateFlow()

    init {
        loadSession()
    }

    private fun loadSession() {
        try {
            if (sessionFile.exists()) {
                val data = json.decodeFromString<SpotifySessionData>(sessionFile.readText())
                _session.value = data
                if (data.accessToken.isNotBlank()) {
                    Spotify.accessToken = data.accessToken
                }
            }
        } catch (_: Exception) {}
    }

    fun openBrowserLogin() {
        DesktopSpotifyBrowserAuth.openUrl(DesktopSpotifyBrowserAuth.LOGIN_URL)
    }

    suspend fun loginWithBrowserSession(session: BrowserSpotifySession): Result<SpotifyUser> = withContext(Dispatchers.IO) {
        val cleanSpDc = session.spDc.trim()
        val tokenResult = SpotifyAuth.fetchAccessToken(
            spDc = cleanSpDc,
            spKey = session.spKey.trim(),
            spT = session.spT.trim(),
            extraCookies = session.fullCookieHeader.trim()
        )
        val token = tokenResult.getOrElse { return@withContext Result.failure(it) }

        Spotify.accessToken = token.accessToken

        val userResult = Spotify.me()
        val user = userResult.getOrNull()

        val newSession = SpotifySessionData(
            spDc = cleanSpDc,
            spKey = session.spKey.trim(),
            spT = session.spT.trim(),
            fullCookieHeader = session.fullCookieHeader.trim(),
            accessToken = token.accessToken,
            refreshToken = "",
            displayName = user?.displayName ?: "Usuario de Spotify",
            email = user?.email.orEmpty(),
            avatarUrl = user?.images?.firstOrNull()?.url.orEmpty(),
            isLoggedIn = true,
            accessTokenExpiresAt = token.accessTokenExpirationTimestampMs
        )
        _session.value = newSession
        saveSession(newSession)
        Result.success(user ?: SpotifyUser(id = "spotify_user", displayName = newSession.displayName))
    }

    suspend fun loginWithSpDc(spDc: String): Result<SpotifyUser> = withContext(Dispatchers.IO) {
        loginWithBrowserSession(BrowserSpotifySession(spDc = spDc, fullCookieHeader = "sp_dc=$spDc"))
    }

    private const val DEFAULT_CLIENT_ID = "d420a117a32841c2b3474932e49fb54b"

    suspend fun loginWithToken(token: String, refreshToken: String? = null): Result<SpotifyUser> = withContext(Dispatchers.IO) {
        val cleanToken = token.trim()
        if (cleanToken.startsWith("AQ") || cleanToken.length in 50..250) {
            val spDcRes = loginWithSpDc(cleanToken)
            if (spDcRes.isSuccess) return@withContext spDcRes
        }

        Spotify.accessToken = cleanToken

        val userResult = Spotify.me()
        val user = userResult.getOrElse {
            val spDcRes = loginWithSpDc(cleanToken)
            if (spDcRes.isSuccess) return@withContext spDcRes
            return@withContext Result.failure(it)
        }

        val newSession = SpotifySessionData(
            spDc = "",
            accessToken = cleanToken,
            refreshToken = refreshToken.orEmpty(),
            displayName = user.displayName ?: "Usuario de Spotify",
            email = user.email.orEmpty(),
            avatarUrl = user.images.firstOrNull()?.url.orEmpty(),
            isLoggedIn = true
        )
        _session.value = newSession
        saveSession(newSession)
        Result.success(user)
    }

    suspend fun refreshWithRefreshToken(refreshToken: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val formBody = buildString {
                append("grant_type=refresh_token")
                append("&refresh_token=").append(URLEncoder.encode(refreshToken, "UTF-8"))
                append("&client_id=").append(URLEncoder.encode(DEFAULT_CLIENT_ID, "UTF-8"))
            }

            val client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build()
            val request = HttpRequest.newBuilder()
                .uri(URI.create("https://accounts.spotify.com/api/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() in 200..299) {
                val jsonTree = json.parseToJsonElement(response.body()) as? JsonObject
                val newAccess = jsonTree?.get("access_token")?.jsonPrimitive?.content
                if (!newAccess.isNullOrBlank()) {
                    val newRefresh = jsonTree.get("refresh_token")?.jsonPrimitive?.contentOrNull ?: refreshToken
                    val expiresIn = jsonTree.get("expires_in")?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 3600L
                    val updated = _session.value.copy(
                        accessToken = newAccess,
                        refreshToken = newRefresh,
                        accessTokenExpiresAt = System.currentTimeMillis() + expiresIn * 1000L
                    )
                    _session.value = updated
                    saveSession(updated)
                    Spotify.accessToken = newAccess
                    return@withContext Result.success(newAccess)
                }
            }
            Result.failure(Exception("HTTP ${response.statusCode()}: ${response.body()}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        Spotify.accessToken = null
        val cleared = SpotifySessionData()
        _session.value = cleared
        _likedSongs.value = emptyList()
        _playlists.value = emptyList()
        _recentlyPlayed.value = emptyList()
        _topTracks.value = emptyList()
        _topArtists.value = emptyList()
        _newReleases.value = emptyList()
        saveSession(cleared)
    }

    private fun saveSession(data: SpotifySessionData) {
        try {
            sessionFile.writeText(json.encodeToString(data))
        } catch (_: Exception) {}
    }

    private val tokenRefreshMutex = Mutex()

    /**
     * Renueva el accessToken si caducó o si no conocemos su caducidad (sesiones
     * guardadas por versiones anteriores). Antes solo lo hacía Biblioteca; por eso
     * Home arrancaba con un token muerto y no cargaba hasta entrar allí y volver.
     */
    private suspend fun ensureValidAccessToken() = tokenRefreshMutex.withLock {
        val current = _session.value
        if (!current.isLoggedIn) return@withLock
        val now = System.currentTimeMillis()
        // Margen de 2 min: evita usar un token que caduca a mitad de la carga.
        if (current.accessTokenExpiresAt > now + 120_000L) return@withLock

        if (current.spDc.isNotBlank() || current.fullCookieHeader.isNotBlank()) {
            val tokenRes = SpotifyAuth.fetchAccessToken(
                spDc = current.spDc,
                spKey = current.spKey,
                spT = current.spT,
                extraCookies = current.fullCookieHeader
            )
            tokenRes.getOrNull()?.let { token ->
                Spotify.accessToken = token.accessToken
                _session.value = _session.value.copy(
                    accessToken = token.accessToken,
                    accessTokenExpiresAt = token.accessTokenExpirationTimestampMs
                )
                saveSession(_session.value)
                return@withLock
            }
        }

        if (current.refreshToken.isNotBlank()) {
            refreshWithRefreshToken(current.refreshToken)
        }
    }

    private var lastLibraryFetchTime: Long = 0L

    suspend fun refreshLibrary(force: Boolean = false) = withContext(Dispatchers.IO) {
        if (!_session.value.isLoggedIn) return@withContext
        if (_isLibraryLoading.value) return@withContext
        val now = System.currentTimeMillis()
        // Evita repetir la carga entera cada vez que se entra a la pestaña.
        if (!force && now - lastLibraryFetchTime < 60_000L &&
            (_likedSongs.value.isNotEmpty() || _playlists.value.isNotEmpty())
        ) {
            return@withContext
        }

        _isLibraryLoading.value = true
        try {
            ensureValidAccessToken()

            runCatching {
                val liked = Spotify.likedSongs(limit = 50, offset = 0).getOrNull()
                if (liked != null) {
                    _totalLikedSongs.value = liked.total
                    val allLiked = liked.items.toMutableList()
                    _likedSongs.value = allLiked
                    if (liked.total > 50) {
                        var offset = 50
                        while (offset < liked.total && offset < 500) {
                            val more = Spotify.likedSongs(limit = 50, offset = offset).getOrNull() ?: break
                            if (more.items.isEmpty()) break
                            allLiked.addAll(more.items)
                            _likedSongs.value = allLiked.toList()
                            offset += 50
                        }
                    }
                }
            }

            runCatching {
                val pls = Spotify.myPlaylists(limit = 50, offset = 0).getOrNull()
                if (pls != null) {
                    _totalPlaylists.value = pls.total
                    val allPls = pls.items.toMutableList()
                    _playlists.value = allPls
                    if (pls.total > 50) {
                        var offset = 50
                        while (offset < pls.total && offset < 200) {
                            val more = Spotify.myPlaylists(limit = 50, offset = offset).getOrNull() ?: break
                            if (more.items.isEmpty()) break
                            allPls.addAll(more.items)
                            _playlists.value = allPls.toList()
                            offset += 50
                        }
                    }
                }
            }

            refreshHomeFeed()
            lastLibraryFetchTime = System.currentTimeMillis()
        } finally {
            _isLibraryLoading.value = false
        }
    }

    private var lastHomeFetchTime: Long = 0L

    suspend fun refreshHomeFeed(force: Boolean = false) = withContext(Dispatchers.IO) {
        if (!_session.value.isLoggedIn) return@withContext
        if (_isHomeLoading.value) return@withContext
        val now = System.currentTimeMillis()
        if (!force && now - lastHomeFetchTime < 60_000L && _recentlyPlayed.value.isNotEmpty()) {
            return@withContext
        }
        _isHomeLoading.value = true
        lastHomeFetchTime = now
        try {
            // Home también renueva el token: antes solo lo hacía Biblioteca y, si el
            // token guardado había caducado, la portada se quedaba vacía hasta volver.
            ensureValidAccessToken()
            val recentDeferred = async { runCatching { Spotify.recentlyPlayed(limit = 20).getOrNull() } }
            val topTracksDeferred = async { runCatching { Spotify.topTracks(limit = 25).getOrNull() } }
            val topArtistsDeferred = async { runCatching { Spotify.topArtists(limit = 20).getOrNull() } }
            val newReleasesDeferred = async { runCatching { Spotify.newReleases(limit = 20).getOrNull() } }
            val homeFeedDeferred = async { runCatching { Spotify.home(sectionItemsLimit = 10).getOrNull() } }

            recentDeferred.await().getOrNull()?.let { res ->
                val tracks = res.items.map { it.track }.distinctBy { it.id }
                if (tracks.isNotEmpty()) _recentlyPlayed.value = tracks
            }
            topTracksDeferred.await().getOrNull()?.let { res ->
                val tracks = res.items
                if (tracks.isNotEmpty()) {
                    _topTracks.value = tracks
                    if (_statsTracks.value.isEmpty()) _statsTracks.value = tracks
                }
            }
            topArtistsDeferred.await().getOrNull()?.let { res ->
                val artists = res.items
                if (artists.isNotEmpty()) {
                    _topArtists.value = artists
                    if (_statsArtists.value.isEmpty()) _statsArtists.value = artists
                }
            }
            newReleasesDeferred.await().getOrNull()?.let { res ->
                val albums = res.albums?.items.orEmpty()
                if (albums.isNotEmpty()) _newReleases.value = albums
            }

            // Extract recent items from Spotify Home feed
            homeFeedDeferred.await().getOrNull()?.let { feed ->
                val foundItems = mutableListOf<DesktopRecentItem>()
                for (section in feed.sections) {
                    if (section.isShortsOrRecent) {
                        for (item in section.items) {
                            when (item) {
                                is SpotifyHomeFeedItem.Playlist -> {
                                    foundItems.add(
                                        DesktopRecentItem(
                                            id = item.id,
                                            title = item.name,
                                            subtitle = item.ownerName ?: "Playlist",
                                            thumbnailUrl = item.imageUrl,
                                            isPlaylist = true,
                                            trackCount = item.totalCount
                                        )
                                    )
                                }
                                is SpotifyHomeFeedItem.Album -> {
                                    foundItems.add(
                                        DesktopRecentItem(
                                            id = item.id,
                                            title = item.name,
                                            subtitle = item.artists.joinToString(", ") { it.name },
                                            thumbnailUrl = item.imageUrl,
                                            isPlaylist = false,
                                            trackCount = null
                                        )
                                    )
                                }
                                else -> {}
                            }
                        }
                    }
                }
                if (foundItems.isNotEmpty()) {
                    _recentItems.value = foundItems.distinctBy { it.id }.take(8)
                }
            }

            // Fallback for recent items if home feed had no recent section: use user's playlists
            if (_recentItems.value.isEmpty() && _playlists.value.isNotEmpty()) {
                _recentItems.value = _playlists.value.take(8).map { pl ->
                    DesktopRecentItem(
                        id = pl.id,
                        title = pl.name,
                        subtitle = "${pl.tracks?.total ?: 0} canciones",
                        thumbnailUrl = pl.images.firstOrNull()?.url,
                        isPlaylist = true,
                        trackCount = pl.tracks?.total
                    )
                }
            }
        } finally {
            _isHomeLoading.value = false
        }
    }

    suspend fun setStatsTimeRange(range: String) = withContext(Dispatchers.IO) {
        _statsTimeRange.value = range
        _isStatsLoading.value = true
        try {
            val tracksRes = Spotify.topTracks(timeRange = range, limit = 20).getOrNull()
            if (tracksRes != null && tracksRes.items.isNotEmpty()) {
                _statsTracks.value = tracksRes.items
            }
            val artistsRes = Spotify.topArtists(timeRange = range, limit = 20).getOrNull()
            if (artistsRes != null && artistsRes.items.isNotEmpty()) {
                _statsArtists.value = artistsRes.items
            }
        } finally {
            _isStatsLoading.value = false
        }
    }

    suspend fun fetchPlaylistTracks(playlistId: String): List<SpotifyPlaylistTrack> = withContext(Dispatchers.IO) {
        val firstPage = Spotify.playlistTracks(playlistId, limit = 100, offset = 0).getOrNull()
            ?: return@withContext emptyList()
        val allTracks = firstPage.items.toMutableList()
        val total = firstPage.total
        var offset = 100
        while (offset < total && offset < 500) {
            val page = Spotify.playlistTracks(playlistId, limit = 100, offset = offset).getOrNull() ?: break
            if (page.items.isEmpty()) break
            allTracks.addAll(page.items)
            offset += 100
        }
        allTracks
    }

    private const val MAX_MATCH_CACHE = 250
    private val matchCache = java.util.Collections.synchronizedMap(
        object : java.util.LinkedHashMap<String, SongItem>(MAX_MATCH_CACHE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SongItem>?): Boolean {
                return size > MAX_MATCH_CACHE
            }
        }
    )

    suspend fun matchSpotifyTrackToYouTube(track: SpotifyTrack): SongItem? = withContext(Dispatchers.IO) {
        val cacheKey = track.id.ifBlank { "${track.name}_${track.artists.firstOrNull()?.name}" }
        matchCache[cacheKey]?.let { return@withContext it }

        val artistName = track.artists.firstOrNull()?.name.orEmpty()
        val query = "${track.name} $artistName"
        try {
            val res = YouTube.search(query, filter = SearchFilter.FILTER_SONG).getOrNull()
            val songs = res?.items?.filterIsInstance<SongItem>().orEmpty()
            if (songs.isNotEmpty()) {
                val exact = songs.find { s ->
                    s.title.contains(track.name, ignoreCase = true) ||
                    track.name.contains(s.title, ignoreCase = true)
                } ?: songs.first()
                matchCache[cacheKey] = exact
                return@withContext exact
            }
        } catch (_: Exception) {}
        null
    }

    suspend fun matchTitleArtistToYouTube(title: String, artist: String): SongItem? = withContext(Dispatchers.IO) {
        val cacheKey = "${title}_${artist}".trim()
        matchCache[cacheKey]?.let { return@withContext it }
        val query = "$title $artist".trim()
        try {
            val res = YouTube.search(query, filter = SearchFilter.FILTER_SONG).getOrNull()
            val songs = res?.items?.filterIsInstance<SongItem>().orEmpty()
            if (songs.isNotEmpty()) {
                val exact = songs.find { s ->
                    s.title.contains(title, ignoreCase = true) ||
                    title.contains(s.title, ignoreCase = true)
                } ?: songs.first()
                matchCache[cacheKey] = exact
                return@withContext exact
            }
        } catch (_: Exception) {}
        null
    }
}

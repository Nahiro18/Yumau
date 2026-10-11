package moe.rukamori.archivetune.desktop.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class StoredSong(
    val id: String,
    val title: String,
    val artist: String,
    val durationMs: Long = 0,
    val thumbnailUrl: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class StoredPlaylist(
    val id: String,
    val name: String,
    val songs: List<StoredSong> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
)

object DesktopLibraryStore {
    private val configDir = File(System.getProperty("user.home"), ".config/YumaPlayer").apply { mkdirs() }
    private val favoritesFile = File(configDir, "favorites.json")
    private val historyFile = File(configDir, "history.json")
    private val playlistsFile = File(configDir, "playlists.json")

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val _favorites = MutableStateFlow<List<StoredSong>>(emptyList())
    val favorites: StateFlow<List<StoredSong>> = _favorites.asStateFlow()

    private val _history = MutableStateFlow<List<StoredSong>>(emptyList())
    val history: StateFlow<List<StoredSong>> = _history.asStateFlow()

    private val _playlists = MutableStateFlow<List<StoredPlaylist>>(emptyList())
    val playlists: StateFlow<List<StoredPlaylist>> = _playlists.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        try {
            if (favoritesFile.exists()) {
                val list = json.decodeFromString<List<StoredSong>>(favoritesFile.readText())
                _favorites.value = list
            }
        } catch (_: Exception) {}

        try {
            if (historyFile.exists()) {
                val list = json.decodeFromString<List<StoredSong>>(historyFile.readText())
                _history.value = list
            }
        } catch (_: Exception) {}

        try {
            if (playlistsFile.exists()) {
                val list = json.decodeFromString<List<StoredPlaylist>>(playlistsFile.readText())
                _playlists.value = list
            }
        } catch (_: Exception) {}
    }

    suspend fun toggleFavorite(song: StoredSong) = withContext(Dispatchers.IO) {
        val current = _favorites.value
        val updated = if (current.any { it.id == song.id }) {
            current.filterNot { it.id == song.id }
        } else {
            listOf(song) + current
        }
        _favorites.value = updated
        try {
            favoritesFile.writeText(json.encodeToString(updated))
        } catch (_: Exception) {}
    }

    fun isFavorite(songId: String): Boolean {
        return _favorites.value.any { it.id == songId }
    }

    suspend fun recordHistory(song: StoredSong) = withContext(Dispatchers.IO) {
        val current = _history.value.filterNot { it.id == song.id }
        val updated = (listOf(song) + current).take(200)
        _history.value = updated
        try {
            historyFile.writeText(json.encodeToString(updated))
        } catch (_: Exception) {}
    }

    suspend fun createPlaylist(name: String) = withContext(Dispatchers.IO) {
        val newPl = StoredPlaylist(
            id = "pl_${System.currentTimeMillis()}",
            name = name,
            songs = emptyList()
        )
        val updated = _playlists.value + newPl
        _playlists.value = updated
        try {
            playlistsFile.writeText(json.encodeToString(updated))
        } catch (_: Exception) {}
    }

    suspend fun addSongToPlaylist(playlistId: String, song: StoredSong) = withContext(Dispatchers.IO) {
        val updated = _playlists.value.map { pl ->
            if (pl.id == playlistId && pl.songs.none { it.id == song.id }) {
                pl.copy(songs = pl.songs + song)
            } else pl
        }
        _playlists.value = updated
        try {
            playlistsFile.writeText(json.encodeToString(updated))
        } catch (_: Exception) {}
    }

    suspend fun removeSongFromPlaylist(playlistId: String, songId: String) = withContext(Dispatchers.IO) {
        val updated = _playlists.value.map { pl ->
            if (pl.id == playlistId) {
                pl.copy(songs = pl.songs.filterNot { it.id == songId })
            } else pl
        }
        _playlists.value = updated
        try {
            playlistsFile.writeText(json.encodeToString(updated))
        } catch (_: Exception) {}
    }
}

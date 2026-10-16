/*
 * YumaPlayer Desktop (Linux) port.
 * Desktop replacement for the Android Media3/ExoPlayer pipeline: a small queue-based
 * player driven through mpv's JSON IPC. The UI layer only needs play/pause/seek/volume
 * plus observable state, which this class provides with plain Kotlin coroutines.
 */
package moe.rukamori.archivetune.desktop.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.booleanOrNull
import java.io.File

data class DesktopMediaItem(
    val id: String,
    val title: String,
    val artist: String,
    /** Remote URL (stream link) or local file path. */
    val uri: String,
    val durationMs: Long = 0,
    val thumbnailUrl: String? = null,
    val audioFormat: String? = null,
)

enum class DesktopPlayerState { IDLE, PLAYING, PAUSED, ENDED, ERROR }

class DesktopPlayer(
    mpvBinary: String = "mpv",
) {
    private val mpv = MpvProcess(mpvBinary)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _queue = MutableStateFlow<List<DesktopMediaItem>>(emptyList())
    val queue: StateFlow<List<DesktopMediaItem>> = _queue.asStateFlow()

    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _state = MutableStateFlow(DesktopPlayerState.IDLE)
    val state: StateFlow<DesktopPlayerState> = _state.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _currentFormat = MutableStateFlow<String?>(null)
    val currentFormat: StateFlow<String?> = _currentFormat.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _volume = MutableStateFlow(100)
    val volume: StateFlow<Int> = _volume.asStateFlow()

    private val _repeatMode = MutableStateFlow(0)
    val repeatModeFlow: StateFlow<Int> = _repeatMode.asStateFlow()
    var repeatMode: Int
        get() = _repeatMode.value
        set(value) {
            _repeatMode.value = value.coerceIn(0, 2)
        }

    private val _shuffleEnabled = MutableStateFlow(false)
    val shuffleEnabledFlow: StateFlow<Boolean> = _shuffleEnabled.asStateFlow()
    var shuffleEnabled: Boolean
        get() = _shuffleEnabled.value
        set(value) {
            _shuffleEnabled.value = value
        }

    fun toggleShuffle() {
        shuffleEnabled = !shuffleEnabled
    }

    fun cycleRepeatMode() {
        repeatMode = (repeatMode + 1) % 3
    }

    fun init() {
        mpv.start()
        mpv.setProperty("volume", _volume.value)
        mpv.observeProperty("time-pos") { el ->
            val d = el?.jsonPrimitiveDoubleOrNull()
            if (d != null && d >= 0) _positionMs.value = (d * 1000).toLong()
        }
        mpv.observeProperty("duration") { el ->
            val d = el?.jsonPrimitiveDoubleOrNull()
            if (d != null && d > 0) _durationMs.value = (d * 1000).toLong()
        }
        mpv.observeProperty("idle-active") { el ->
            // When mpv goes idle after finishing a file, advance the queue.
            if (el == kotlinx.serialization.json.JsonPrimitive(true) &&
                _state.value == DesktopPlayerState.PLAYING
            ) {
                onTrackFinished()
            }
        }
        // Un error de carga (p.ej. URL googlevideo caducada => 403) NO debe colgar la app:
        // invalidamos la cache de ese tema y limpiamos su uri para re-resolver la proxima vez.
        mpv.observeEndFile { el ->
            val reason = runCatching {
                ((el as? kotlinx.serialization.json.JsonObject)
                    ?.get("reason") as? kotlinx.serialization.json.JsonPrimitive)?.content
            }.getOrNull()
            if (reason == "error") {
                val idx = _currentIndex.value
                val item = _queue.value.getOrNull(idx)
                if (item != null) {
                    runCatching { onStreamError?.invoke(item) }
                    val updated = _queue.value.toMutableList()
                    if (idx in updated.indices && updated[idx].id == item.id) {
                        updated[idx] = item.copy(uri = "")
                        _queue.value = updated
                    }
                    _errorMessage.value = "No se pudo cargar el audio (URL caducada). Se reintentara."
                }
            }
        }
        mpv.observeProperty("pause") { el ->
            val isPaused = (el as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull
            if (isPaused != null) {
                if (isPaused && _state.value == DesktopPlayerState.PLAYING) {
                    _state.value = DesktopPlayerState.PAUSED
                } else if (!isPaused && _state.value == DesktopPlayerState.PAUSED) {
                    _state.value = DesktopPlayerState.PLAYING
                }
            }
        }
        mpv.observeProperty("volume") { el ->
            val v = (el as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()
            if (v != null && v >= 0) {
                _volume.value = v.toInt().coerceIn(0, 100)
            }
        }
        mpv.observeProperty("audio-codec-name") { el ->
            val codec = (el as? kotlinx.serialization.json.JsonPrimitive)?.content?.uppercase()
            if (!codec.isNullOrBlank() && _currentFormat.value.isNullOrBlank()) {
                _currentFormat.value = codec
            }
        }

        // Active poller to guarantee real-time seekbar progress even if IPC events stall
        scope.launch {
            while (isActive) {
                delay(250)
                if (_state.value == DesktopPlayerState.PLAYING) {
                    runCatching {
                        val posEl = mpv.getProperty("time-pos")
                        val posSec = posEl?.jsonPrimitiveDoubleOrNull()
                        if (posSec != null && posSec >= 0) {
                            _positionMs.value = (posSec * 1000).toLong()
                        }
                        val durEl = mpv.getProperty("duration")
                        val durSec = durEl?.jsonPrimitiveDoubleOrNull()
                        if (durSec != null && durSec > 0) {
                            _durationMs.value = (durSec * 1000).toLong()
                        }
                    }
                }
            }
        }
    }

    private fun kotlinx.serialization.json.JsonElement?.jsonPrimitiveDoubleOrNull(): Double? =
        (this as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()

    fun setQueue(
        items: List<DesktopMediaItem>,
        startIndex: Int = 0,
    ) {
        _queue.value = items
        playIndex(startIndex)
    }

    fun addToQueue(item: DesktopMediaItem) {
        _queue.value = _queue.value + item
        if (_currentIndex.value < 0) playIndex(_queue.value.lastIndex)
    }

    fun addToQueue(items: List<DesktopMediaItem>) {
        if (items.isEmpty()) return
        _queue.value = _queue.value + items
        if (_currentIndex.value < 0) playIndex(_queue.value.lastIndex - items.size + 1)
    }

    var autoSkipNextOnError: Boolean = true
    var itemResolver: (suspend (DesktopMediaItem) -> DesktopMediaItem?)? = null

    /** Invocado cuando mpv reporta un error de carga (403 por URL caducada, etc.). */
    var onStreamError: ((DesktopMediaItem) -> Unit)? = null

    /** Muestra un error claro en la UI sin dejar la app colgada en "Cargando...". */
    fun markError(message: String) {
        _errorMessage.value = message
        _state.value = DesktopPlayerState.ERROR
        _currentFormat.value = null
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun setPreparing(item: DesktopMediaItem) {
        _queue.value = listOf(item)
        _currentIndex.value = 0
        _currentFormat.value = item.audioFormat ?: "Cargando audio..."
        _positionMs.value = 0L
        _durationMs.value = item.durationMs
    }

    fun updateAudioFilters(volumeNormalization: Boolean, skipSilence: Boolean) {
        scope.launch {
            runCatching {
                if (volumeNormalization) {
                    mpv.command(listOf("set_property", "replaygain", "track"))
                } else {
                    mpv.command(listOf("set_property", "replaygain", "no"))
                }

                val filters = mutableListOf<String>()
                if (skipSilence) {
                    filters.add("silenceremove=start_periods=1:start_duration=0.5:start_threshold=-50dB:detection=peak")
                }
                mpv.command(listOf("set_property", "af", filters.joinToString(",")))
            }
        }
    }

    private var playJob: kotlinx.coroutines.Job? = null

    fun playIndex(index: Int) {
        val items = _queue.value
        if (index < 0 || index >= items.size) return
        _currentIndex.value = index
        val item = items[index]
        _positionMs.value = 0L
        _errorMessage.value = null
        _currentFormat.value = item.audioFormat ?: "Cargando audio..."
        if (item.durationMs > 0) {
            _durationMs.value = item.durationMs
        }
        // Estado optimista: la UI muestra "reproduciendo" al instante (estilo Android),
        // sin esperar al roundtrip bloqueante de mpv.
        _state.value = DesktopPlayerState.PLAYING
        playJob?.cancel()
        playJob = scope.launch {
            val resolvedItem = if (item.uri.isBlank()) {
                itemResolver?.invoke(item)
            } else {
                item
            }
            if (resolvedItem != null && resolvedItem.uri.isNotBlank()) {
                val currentList = _queue.value.toMutableList()
                if (index < currentList.size) {
                    currentList[index] = resolvedItem
                    _queue.value = currentList
                }
                _currentFormat.value = resolvedItem.audioFormat
                if (resolvedItem.durationMs > 0) {
                    _durationMs.value = resolvedItem.durationMs
                }
                // loadfile fire-and-forget: no bloqueamos 10s esperando reply de mpv.
                // El audio arranca en background mientras la UI ya muestra PLAYING.
                mpv.commandAsync(listOf("loadfile", resolvedItem.uri, "replace"))
                mpv.commandAsync(listOf("set_property", "pause", false))
                _state.value = DesktopPlayerState.PLAYING
                _errorMessage.value = null

                // Prefetch agresivo: resuelve los siguientes 3 temas en paralelo
                // para que "Siguiente" sea instantaneo (0ms, cache hit).
                val snapshot = _queue.value
                for (offset in 1..3) {
                    val nextIdx = index + offset
                    if (shuffleEnabled && offset > 1) break
                    if (nextIdx in snapshot.indices && snapshot[nextIdx].uri.isBlank()) {
                        val nextItem = snapshot[nextIdx]
                        launch {
                            runCatching {
                                val nextResolved = itemResolver?.invoke(nextItem)
                                if (nextResolved != null && nextResolved.uri.isNotBlank()) {
                                    val updated = _queue.value.toMutableList()
                                    if (nextIdx < updated.size && updated[nextIdx].id == nextResolved.id &&
                                        updated[nextIdx].uri.isBlank()
                                    ) {
                                        updated[nextIdx] = nextResolved
                                        _queue.value = updated
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                _state.value = DesktopPlayerState.ERROR
                _errorMessage.value = "No se pudo obtener el audio de \"${item.title}\". Revisa la fuente en Ajustes."
                _currentFormat.value = null
                if (autoSkipNextOnError && items.size > 1) {
                    delay(500)
                    next()
                }
            }
        }
    }

    fun playPause() {
        when (_state.value) {
            DesktopPlayerState.PLAYING -> {
                mpv.commandAsync(listOf("set_property", "pause", true))
                _state.value = DesktopPlayerState.PAUSED
            }
            DesktopPlayerState.PAUSED -> {
                mpv.commandAsync(listOf("set_property", "pause", false))
                _state.value = DesktopPlayerState.PLAYING
            }
            DesktopPlayerState.ENDED, DesktopPlayerState.IDLE ->
                playIndex(_currentIndex.value.coerceAtLeast(0))
            else -> {}
        }
    }

    fun next() {
        val size = _queue.value.size
        if (size == 0) return
        val idx =
            if (shuffleEnabled) {
                (0 until size).random()
            } else {
                (_currentIndex.value + 1) % size
            }
        playIndex(idx)
    }

    fun prev() {
        val size = _queue.value.size
        if (size == 0) return
        if (_positionMs.value > 3000L) {
            seekTo(0L)
        } else {
            playIndex((_currentIndex.value - 1 + size) % size)
        }
    }

    fun seekTo(ms: Long) {
        _positionMs.value = ms
        val seconds = (ms / 1000.0).coerceAtLeast(0.0)
        mpv.commandAsync(listOf("seek", seconds, "absolute"))
    }

    fun seekRelative(seconds: Double) {
        mpv.commandAsync(listOf("seek", seconds, "relative"))
    }

    fun setVolume(percent: Int) {
        _volume.value = percent.coerceIn(0, 100)
        mpv.commandAsync(listOf("set_property", "volume", _volume.value))
    }

    private fun onTrackFinished() {
        when (repeatMode) {
            2 -> playIndex(_currentIndex.value)
            else -> {
                if (_currentIndex.value + 1 < _queue.value.size) {
                    next()
                } else if (repeatMode == 1) {
                    playIndex(0)
                } else {
                    _state.value = DesktopPlayerState.ENDED
                }
            }
        }
    }

    fun release() {
        mpv.quit()
        scope.cancel()
    }
}

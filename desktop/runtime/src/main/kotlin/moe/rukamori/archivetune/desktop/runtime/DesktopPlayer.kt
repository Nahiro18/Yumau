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
import java.io.File

data class DesktopMediaItem(
    val id: String,
    val title: String,
    val artist: String,
    /** Remote URL (stream link) or local file path. */
    val uri: String,
    val durationMs: Long = 0,
    val thumbnailUrl: String? = null,
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

    fun playIndex(index: Int) {
        val items = _queue.value
        if (index < 0 || index >= items.size) return
        _currentIndex.value = index
        val item = items[index]
        _positionMs.value = 0L
        if (item.durationMs > 0) {
            _durationMs.value = item.durationMs
        }
        val target = if (File(item.uri).isAbsolute) item.uri else item.uri
        scope.launch {
            runCatching {
                mpv.command(listOf("loadfile", target, "replace"))
                mpv.command(listOf("set_property", "pause", false))
                _state.value = DesktopPlayerState.PLAYING
            }.onFailure {
                _state.value = DesktopPlayerState.ERROR
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
        playIndex((_currentIndex.value - 1 + size) % size)
    }

    fun seekTo(ms: Long) {
        _positionMs.value = ms
        val seconds = (ms / 1000.0).coerceAtLeast(0.0)
        mpv.commandAsync(listOf("seek", seconds, "absolute"))
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

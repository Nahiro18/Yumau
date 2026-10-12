/*
 * YumaPlayer Desktop (Linux) port.
 * Talks to a `mpv --input-ipc-server=...` process over the JSON IPC protocol.
 * No native bindings required: works with any mpv build available in Linux Mint
 * (`sudo apt install mpv`).
 */
package moe.rukamori.archivetune.desktop.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Lightweight mpv controller. Commands are written as JSON lines to the mpv IPC socket
 * and replies are matched by request id. Property change events (id 60) are forwarded
 * to [listeners].
 */
class MpvProcess(
    private val mpvBinary: String = "mpv",
    private val extraArgs: List<String> = emptyList(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val socketPath: String =
        File(System.getProperty("java.io.tmpdir"), "yumaplayer-mpv-${ProcessHandle.current().pid()}-${System.nanoTime()}.sock")
        .absolutePath

    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, (JsonElement) -> Unit>()
    private val listeners = ConcurrentHashMap<String, MutableList<(JsonElement?) -> Unit>>()
    private val running = AtomicBoolean(false)

    @Volatile
    private var writer: java.io.OutputStream? = null

    private var process: Process? = null

    fun start() {
        if (running.getAndSet(true)) return
        val args =
            buildList {
                add(mpvBinary)
                add("--input-ipc-server=$socketPath")
                add("--no-config")
                add("--idle=yes")
                add("--force-window=no")
                add("--video=no")
                add("--audio-buffer=0.2")
                add("--demuxer-lavf-probesize=32768")
                add("--demuxer-lavf-analyzeduration=0.1")
                add("--cache=yes")
                add("--cache-pause=no")
                add("--cache-pause-wait=0.1")
                add("--demuxer-max-bytes=32M")
                add("--demuxer-readahead-secs=20")
                add("--network-timeout=5")
                add("--user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                add("--stream-lavf-o=reconnect=1,reconnect_streamed=1,reconnect_delay_max=3")
                add("--ytdl-format=bestaudio/best")
                addAll(extraArgs)
            }
        process =
            ProcessBuilder(args)
                .redirectErrorStream(true)
                .start()

        // Wait for the IPC socket to appear.
        val deadline = System.currentTimeMillis() + 5_000
        while (!File(socketPath).exists() && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
        }
        check(File(socketPath).exists()) { "mpv IPC socket was not created; is mpv installed? ($mpvBinary)" }

        connect()
    }

    private fun connect() {
        var attempt = 0
        while (attempt < 20) {
            try {
                val channel = java.nio.channels.SocketChannel.open(java.net.UnixDomainSocketAddress.of(socketPath))
                writer = java.nio.channels.Channels.newOutputStream(channel)
                val readerThread =
                    Thread {
                        try {
                            val reader =
                                BufferedReader(InputStreamReader(java.nio.channels.Channels.newInputStream(channel)))
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                handleMessage(line!!)
                            }
                        } catch (_: Exception) {
                            // socket closed
                        }
                    }
                readerThread.isDaemon = true
                readerThread.name = "mpv-ipc-reader"
                readerThread.start()
                return
            } catch (e: Exception) {
                attempt++
                Thread.sleep(100)
            }
        }
        error("Could not connect to mpv IPC socket after retries")
    }

    private fun handleMessage(line: String) {
        val el =
            runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return
        val asyncId = el["async_pid"]?.jsonPrimitive?.longOrNull
        if (asyncId != null) {
            pending.remove(asyncId)?.invoke(el)
            return
        }
        val requestId = el["request_id"]?.jsonPrimitive?.longOrNull
        if (requestId != null) {
            pending.remove(requestId)?.invoke(el)
            return
        }
        when (el["event"]?.jsonPrimitive?.contentOrNullSafe) {
            "property-change" -> {
                val name = el["name"]?.jsonPrimitive?.contentOrNullSafe ?: return
                listeners[name]?.forEach { it(el["data"]) }
            }
            "end-file" -> {
                listeners["end-file"]?.forEach { it(el) }
            }
        }
    }

    private val JsonPrimitive.contentOrNullSafe: String?
        get() = runCatching { content }.getOrNull()

    /** Send an mpv command and wait for its reply. Returns the "data"/"error" element. */
    fun command(args: List<Any>, timeoutMs: Long = 10_000): JsonElement {
        val out = writer ?: error("Not connected to mpv")
        val id = nextId.getAndIncrement()
        val completer = java.util.concurrent.CountDownLatch(1)
        var result: JsonElement? = null
        pending[id] = { el ->
            result = el
            completer.countDown()
        }
        val payload =
            buildJsonObject {
                put("command", JsonArray(args.map { a -> JsonPrimitive(a.toString()) }))
                put("request_id", id)
                put("async", true)
            }
        synchronized(out) {
            out.write((payload.toString() + "\n").toByteArray())
            out.flush()
        }
        completer.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        pending.remove(id)
        return result ?: error("mpv command timed out: $args")
    }

    /** Fire-and-forget async command. */
    fun commandAsync(args: List<Any>) {
        val out = writer ?: return
        val payload =
            buildJsonObject {
                put("command", JsonArray(args.map { a -> JsonPrimitive(a.toString()) }))
                put("async", true)
            }
        synchronized(out) {
            out.write((payload.toString() + "\n").toByteArray())
            out.flush()
        }
    }

    fun setProperty(name: String, value: Any) = command(listOf("set_property", name, value))

    fun getProperty(name: String): JsonElement? {
        val reply = command(listOf("get_property", name))
        return reply.jsonObject["data"]
    }

    fun observeProperty(
        name: String,
        listener: (JsonElement?) -> Unit,
    ) {
        listeners.getOrPut(name) { mutableListOf() }.add(listener)
        command(listOf("observe_property", nextId.getAndIncrement(), name))
    }

    fun quit() {
        runCatching { commandAsync(listOf("quit")) }
        running.set(false)
        runCatching { process?.destroy() }
        runCatching { Files.deleteIfExists(File(socketPath).toPath()) }
    }
}

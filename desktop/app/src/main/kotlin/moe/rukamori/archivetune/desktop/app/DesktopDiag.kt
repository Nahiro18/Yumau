package moe.rukamori.archivetune.desktop.app

import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Registro simple de tiempos en `~/.config/YumaPlayer/yumau_diag.log`.
 * Sirve para ver, con datos reales de la maquina, donde se va el tiempo al
 * reproducir (resolucion, descifrado, primer sample de mpv, prefetch...).
 *
 * Coste nulo en la UI: se encola la linea y un hilo demonio escribe el fichero.
 */
object DesktopDiag {
    private val logFile: File by lazy {
        File(System.getProperty("user.home"), ".config/YumaPlayer/yumau_diag.log")
    }
    private val queue = ConcurrentLinkedQueue<String>()
    private val writing = AtomicBoolean(false)
    private const val MAX_LINES = 500

    fun log(stage: String, ms: Long, extra: String = "") {
        queue.add("${System.currentTimeMillis()} | $stage | ${ms}ms | $extra")
        if (writing.compareAndSet(false, true)) {
            Thread({
                while (true) {
                    val line = queue.poll() ?: break
                    runCatching {
                        logFile.parentFile?.mkdirs()
                        val previous = if (logFile.exists() && logFile.length() < 512_000) logFile.readText() else ""
                        val merged = (previous + line).lines().filter { it.isNotBlank() }
                        val trimmed = if (merged.size > MAX_LINES) merged.takeLast(MAX_LINES) else merged
                        logFile.writeText(trimmed.joinToString("\n") + "\n")
                    }
                }
            }, "yuma-diag").apply {
                isDaemon = true
                start()
            }
        }
    }

    /** Limpia el log (lo llama el usuario desde Ajustes si quiere). */
    fun clear() {
        runCatching { logFile.writeText("") }
    }

    fun path(): String = logFile.absolutePath
}
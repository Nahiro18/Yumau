package moe.rukamori.archivetune.desktop.app

import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Registro simple de tiempos en `~/.config/YumaPlayer/yumau_diag.log`.
 * Sirve para ver, con datos reales de la maquina, donde se va el tiempo al
 * reproducir (resolucion, descifrado, primer sample de mpv, prefetch...).
 *
 * Un unico hilo demonio consume una cola bloqueante: nunca se pierden lineas
 * aunque el primer mensaje llegue mucho antes que el siguiente.
 */
object DesktopDiag {
    private val logFile: File by lazy {
        File(System.getProperty("user.home"), ".config/YumaPlayer/yumau_diag.log")
    }
    private val queue = LinkedBlockingQueue<String>()
    private val started = AtomicBoolean(false)
    private const val MAX_LINES = 600

    fun log(stage: String, ms: Long, extra: String = "") {
        queue.offer("${System.currentTimeMillis()} | $stage | ${ms}ms | $extra")
        if (started.compareAndSet(false, true)) {
            Thread({
                while (true) {
                    val line = runCatching { queue.take() }.getOrNull() ?: continue
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

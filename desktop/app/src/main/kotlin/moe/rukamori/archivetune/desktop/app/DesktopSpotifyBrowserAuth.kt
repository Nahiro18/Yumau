package moe.rukamori.archivetune.desktop.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI

object DesktopSpotifyBrowserAuth {
    private const val LOGIN_URL = "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F"

    private var authJob: Job? = null

    fun startBrowserLogin(
        scope: CoroutineScope,
        onSuccess: (token: String, refreshToken: String?) -> Unit,
        onError: (String) -> Unit
    ) {
        cancel()
        authJob = scope.launch(Dispatchers.IO) {
            try {
                val existingCookie = scanAllBrowserCookies()
                if (!existingCookie.isNullOrBlank()) {
                    withContext(Dispatchers.Main) {
                        onSuccess(existingCookie, null)
                    }
                    return@launch
                }

                openUrl(LOGIN_URL)

                val startTime = System.currentTimeMillis()
                val timeoutMs = 180_000L

                while (isActive && System.currentTimeMillis() - startTime < timeoutMs) {
                    delay(2000)
                    val cookie = scanAllBrowserCookies()
                    if (!cookie.isNullOrBlank()) {
                        withContext(Dispatchers.Main) {
                            onSuccess(cookie, null)
                        }
                        return@launch
                    }
                }

                if (isActive) {
                    withContext(Dispatchers.Main) {
                        onError("Tiempo de espera agotado. Inicia sesión en Spotify en tu navegador e intenta de nuevo.")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onError(e.message ?: "Error al sincronizar con el navegador")
                }
            }
        }
    }

    fun cancel() {
        authJob?.cancel()
        authJob = null
    }

    private fun openUrl(url: String) {
        try {
            if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(URI(url))
                return
            }
        } catch (_: Exception) {}
        try {
            ProcessBuilder("xdg-open", url).start()
        } catch (_: Exception) {}
    }

    fun scanAllBrowserCookies(): String? {
        val home = File(System.getProperty("user.home"))
        val candidateDirs = listOf(
            File(home, ".config/mozilla/firefox"),
            File(home, ".mozilla/firefox"),
            File(home, ".var/app/org.mozilla.firefox/.mozilla/firefox"),
            File(home, "snap/firefox/common/.mozilla/firefox"),
            File(home, ".config/google-chrome"),
            File(home, ".config/chromium"),
            File(home, ".config/BraveSoftware/Brave-Browser"),
            File(home, ".var/app/com.brave.Browser/config/BraveSoftware/Brave-Browser"),
            File(home, ".config/microsoft-edge"),
            File(home, ".config/opera"),
            File(home, ".config/vivaldi")
        )

        val filesToScan = mutableListOf<File>()
        for (dir in candidateDirs) {
            if (!dir.exists()) continue
            try {
                dir.walkTopDown().maxDepth(3).forEach { file ->
                    if (file.isFile && (file.name == "cookies.sqlite" || file.name == "Cookies")) {
                        filesToScan.add(file)
                    }
                }
            } catch (_: Exception) {}
        }

        val regex = Regex("""sp_dc(AQ[a-zA-Z0-9_\-]{50,250})""")
        for (file in filesToScan) {
            try {
                val bytes = file.readBytes()
                val text = bytes.toString(Charsets.ISO_8859_1)
                val match = regex.find(text)
                if (match != null) {
                    return match.groupValues[1]
                }
            } catch (_: Exception) {}
        }

        try {
            val pyScript = """
                import sqlite3, glob, os, shutil, tempfile
                home = os.path.expanduser('~')
                paths = (
                    glob.glob(f'{home}/.mozilla/firefox/*/cookies.sqlite') +
                    glob.glob(f'{home}/.config/mozilla/firefox/*/cookies.sqlite') +
                    glob.glob(f'{home}/.var/app/org.mozilla.firefox/.mozilla/firefox/*/cookies.sqlite') +
                    glob.glob(f'{home}/snap/firefox/common/.mozilla/firefox/*/cookies.sqlite')
                )
                for p in paths:
                    if not os.path.exists(p): continue
                    try:
                        tmp = tempfile.mktemp('.db')
                        shutil.copy2(p, tmp)
                        conn = sqlite3.connect(tmp)
                        c = conn.cursor()
                        c.execute("SELECT value FROM moz_cookies WHERE host LIKE '%spotify.com%' AND name='sp_dc'")
                        row = c.fetchone()
                        conn.close()
                        os.remove(tmp)
                        if row and row[0] and row[0].startswith('AQ'):
                            print(row[0])
                            exit(0)
                    except:
                        pass
            """.trimIndent()
            val process = ProcessBuilder("python3", "-c", pyScript).start()
            val line = process.inputStream.bufferedReader().readLine()?.trim()
            if (!line.isNullOrBlank() && line.startsWith("AQ")) {
                return line
            }
        } catch (_: Exception) {}

        return null
    }
}


/*
 * Desktop shim: minimal android.content.Context surface used by the
 * database module when compiled for JVM (Linux desktop port).
 */
package android.content

import java.io.File

/**
 * Very small stand-in for [android.content.Context]. The desktop runtime installs a
 * [DesktopContext] pointing at the per-user data directory, so platform-independent code
 * (e.g. Room database creation) can keep using the same API shape as on Android.
 */
abstract class Context {
    abstract val filesDir: File

    open fun getDatabasePath(name: String): File = File(filesDir, name)

    open fun deleteDatabase(name: String): Boolean {
        var ok = File(filesDir, name).delete()
        ok = File(filesDir, "$name-wal").delete() && ok
        ok = File(filesDir, "$name-shm").delete() && ok
        return ok
    }

    open val applicationContext: Context
        get() = this
}

/** Context implementation used on desktop; backed by an arbitrary data directory. */
class DesktopContext(override val filesDir: File) : Context() {
    init {
        if (!filesDir.exists()) filesDir.mkdirs()
    }
}

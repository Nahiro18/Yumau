/*
 * YumaPlayer Desktop (Linux) port.
 *
 * Bridge between the Android-style Room API (shimmed in :desktop:shims) and the
 * pure-JVM androidx.room runtime used on desktop. The JVM Room runtime cannot be
 * fed a SupportSQLiteOpenHelper.Factory, so the builder returned here is backed
 * directly by our JDBC connection provider via setDriver (see JdbcSqlite.kt).
 */
package androidx.room.desktop

import android.content.Context
import androidx.room.DesktopRoomBridge
import androidx.room.RoomDatabase
import moe.rukamori.archivetune.desktop.sqlite.jdbcRoomDriver

/**
 * Installs the JVM Room provider into [DesktopRoomBridge] so verbatim Android
 * sources calling Room.databaseBuilder(...) / Room.inMemoryDatabaseBuilder(...)
 * work on desktop. Called from DesktopSqliteInstaller.install().
 */
object DesktopRoomInstaller {
    fun install() {
        DesktopRoomBridge.provider = JdbcRoomProvider
        // Legacy hook kept in sync for any code still reading it.
        androidx.sqlite.db.framework.DesktopSqliteBridge.roomProvider = JdbcRoomProvider
    }
}

private object JdbcRoomProvider : DesktopRoomBridge.RoomProvider {
    override fun <T : RoomDatabase> builderFor(
        context: Context,
        klass: Class<T>,
        name: String?,
    ): RoomDatabase.Builder<T> {
        val path =
            if (name == null) {
                ":memory:"
            } else {
                val file = context.getDatabasePath(name)
                file.parentFile?.mkdirs()
                file.absolutePath
            }
        val builder: RoomDatabase.Builder<T> =
            androidx.room.Room.databaseBuilder<T>(path) {
                klass.getDeclaredConstructor().newInstance()
            }
        return builder.setDriver(jdbcRoomDriver(path))
    }
}

/*
 * YumaPlayer Desktop (Linux) port.
 *
 * Bridge between the Android-style Room API (shimmed in :desktop:shims) and the
 * pure-JVM androidx.room runtime used on desktop. Instead of trying to implement
 * androidx.room's internal SQLiteDriver contract by hand, we delegate to
 * `Room.createApplicationBuilder`, which accepts a standard
 * androidx.sqlite SupportSQLiteOpenHelper.Factory — our JDBC/sqlite-jdbc one.
 */
package androidx.room.desktop

import android.content.Context
import androidx.room.RoomDatabase
import androidx.sqlite.db.framework.DesktopSqliteBridge
import moe.rukamori.archivetune.desktop.sqlite.JdbcSQLiteOpenHelper

/**
 * Installs a JVM Room provider into [DesktopSqliteBridge] so verbatim Android
 * sources calling Room.databaseBuilder(...) / Room.inMemoryDatabaseBuilder(...)
 * work on desktop. Called from DesktopSqliteInstaller.install().
 */
object DesktopRoomInstaller {
    fun install() {
        DesktopSqliteBridge.roomProvider = DesktopSqliteBridgeRoomProvider
    }
}

private object DesktopSqliteBridgeRoomProvider : DesktopSqliteBridge.RoomProvider {
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
        return androidx.room.Room.createApplicationBuilder(klass) {
            factory(JdbcSQLiteOpenHelper.Factory)
            this.name(path)
        }
    }
}

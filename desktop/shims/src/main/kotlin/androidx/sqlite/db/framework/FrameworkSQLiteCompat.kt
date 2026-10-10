/*
 * YumaPlayer Desktop (Linux) port shim.
 *
 * Source-compatible stand-in for androidx.sqlite.db.framework.* which only ships in
 * the Android flavour of androidx.sqlite. On desktop these types delegate to the
 * JDBC-backed factory installed by :desktop:sqlite-jdbc-factory via
 * [DesktopSqliteBridge.install].
 */
package androidx.sqlite.db.framework

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper

class FrameworkSQLiteDatabase(
    @Suppress("unused") private val delegate: SupportSQLiteDatabase,
) : SupportSQLiteDatabase by delegate

/**
 * Desktop replacement for the Android framework factory. Delegates to whichever
 * [SupportSQLiteOpenHelper.Factory] the desktop runtime installs via
 * [DesktopSqliteBridge] (a JDBC/sqlite-jdbc backed factory in production).
 */
class FrameworkSQLiteOpenHelperFactory : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper =
        DesktopSqliteBridge.factory.create(configuration)
}

/** Hook used by the desktop app module to plug the real JDBC factory into this shim. */
object DesktopSqliteBridge {
    @Volatile
    var factory: SupportSQLiteOpenHelper.Factory =
        UnsupportedFactory

    /**
     * Kept for source compatibility with older shared code; the Room provider
     * now lives on [androidx.room.DesktopRoomBridge].
     */
    @Deprecated("Use androidx.room.DesktopRoomBridge.RoomProvider")
    interface RoomProvider {
        fun <T : androidx.room.RoomDatabase> builderFor(
            context: android.content.Context,
            klass: Class<T>,
            name: String?,
        ): androidx.room.RoomDatabase.Builder<T>
    }

    fun install(newFactory: SupportSQLiteOpenHelper.Factory) {
        factory = newFactory
    }

    var roomProvider: androidx.room.DesktopRoomBridge.RoomProvider? = null

    private object UnsupportedFactory : SupportSQLiteOpenHelper.Factory {
        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper =
            throw UnsupportedOperationException(
                "No desktop SQLite factory installed; call DesktopSqliteBridge.install() first.",
            )
    }
}

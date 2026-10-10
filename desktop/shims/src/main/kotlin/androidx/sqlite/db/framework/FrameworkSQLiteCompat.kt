/*
 * YumaPlayer Desktop (Linux) port shim.
 *
 * The shared `database` module references androidx.sqlite.db.framework.* which only exists
 * in the AAR flavour of androidx.sqlite (backed by the android framework). On desktop we
 * compile against the pure-JVM flavour of androidx.sqlite and provide a source-compatible
 * stand-in for these two types so the verbatim sources keep compiling.
 */
package androidx.sqlite.db.framework

import android.database.SupportSQLiteDatabase
import android.database.SupportSQLiteOpenHelper

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
     * Installs a JVM-only Room database provider so the verbatim `database` sources
     * (which call Room.databaseBuilder/inMemoryDatabaseBuilder) work on desktop.
     */
    @Volatile
    var roomProvider: RoomProvider? = null

    fun install(
        newFactory: SupportSQLiteOpenHelper.Factory,
        provider: RoomProvider? = null,
    ) {
        factory = newFactory
        if (provider != null) roomProvider = provider
    }

    private object UnsupportedFactory : SupportSQLiteOpenHelper.Factory {
        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper =
            throw UnsupportedOperationException(
                "No desktop SQLite factory installed; call DesktopSqliteBridge.install() first.",
            )
    }

    /**
     * Not a functional interface (the builder method is generic, which SAM
     * interfaces cannot express); implement as an object.
     */
    interface RoomProvider {
        fun <T : androidx.room.RoomDatabase> builderFor(
            context: android.content.Context,
            klass: Class<T>,
            name: String?,
        ): androidx.room.RoomDatabase.Builder<T>
    }
}
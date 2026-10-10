/*
 * YumaPlayer Desktop (Linux) port shim.
 *
 * The shared `database` sources import `androidx.room.Room` and call
 * Room.databaseBuilder(...) / Room.inMemoryDatabaseBuilder(...). Those entry
 * points only exist in the Android flavour of room-runtime; the pure-JVM
 * flavour exposes `Room.databaseBuilder(path) { constructor }` plus a
 * `setDriver(androidx.sqlite.SQLiteDriver)` on the builder instead.
 *
 * This shim keeps the Android-style API source-compatible on the JVM by
 * delegating to the provider installed by the desktop runtime through
 * [DesktopRoomBridge] (see :desktop:sqlite-jdbc-factory).
 */
package androidx.room

import android.content.Context
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.DesktopSqliteBridge

/**
 * Hook used by :desktop:sqlite-jdbc-factory to translate Android-style builder
 * calls into real JVM Room builders backed by the JDBC SQLite driver.
 */
object DesktopRoomBridge {
    /**
     * Not a functional interface (the builder method is generic, which SAM
     * interfaces cannot express); implement as an object.
     */
    interface RoomProvider {
        fun <T : RoomDatabase> builderFor(
            context: Context,
            klass: Class<T>,
            name: String?,
        ): RoomDatabase.Builder<T>
    }

    @Volatile
    var provider: RoomProvider? = null

    fun install(newProvider: RoomProvider) {
        provider = newProvider
    }

    /**
     * Adapts a JVM Room builder (configured with an [androidx.sqlite.SQLiteDriver])
     * so it can also satisfy code paths that expect a SupportSQLiteOpenHelper-based
     * factory. Currently the JVM builder ignores this argument; kept for parity.
     */
    @Suppress("unused")
    fun configureFactory(
        builder: RoomDatabase.Builder<*>,
        factory: SupportSQLiteOpenHelper.Factory,
    ) {
        // No-op: the JDBC driver is installed directly via setDriver in the provider.
    }
}

object Room {
    fun <T : RoomDatabase> databaseBuilder(
        context: Context,
        klass: Class<T>,
        name: String,
    ): RoomDatabase.Builder<T> = requireProvider().builderFor(context, klass, name)

    fun <T : RoomDatabase> inMemoryDatabaseBuilder(
        context: Context,
        klass: Class<T>,
    ): RoomDatabase.Builder<T> = requireProvider().builderFor(context, klass, null)

    private fun requireProvider(): DesktopRoomBridge.RoomProvider =
        DesktopRoomBridge.provider
            ?: throw IllegalStateException(
                "Room provider not installed. Call DesktopRoomInstaller.install() before opening databases.",
            )
}

// Re-export so existing imports of DesktopSqliteBridge keep resolving while the
// Room-provider half migrates to DesktopRoomBridge.
typealias DesktopSqliteBridgeAlias = DesktopSqliteBridge

/*
 * YumaPlayer Desktop (Linux) port shim.
 *
 * The shared `database` sources import `androidx.room.Room` and call
 * Room.databaseBuilder(...) / Room.inMemoryDatabaseBuilder(...). Those entry
 * points only exist in the Android flavour of room-runtime; the pure-JVM
 * flavour exposes Room.createApplicationBuilder + generated `*Impl` classes
 * instead. This shim keeps the Android-style API source-compatible on the JVM
 * by delegating to the provider installed by the desktop runtime through
 * DesktopSqliteBridge.install().
 */
package androidx.room

import android.content.Context
import androidx.sqlite.db.framework.DesktopSqliteBridge

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

    private fun requireProvider(): DesktopSqliteBridge.RoomProvider =
        DesktopSqliteBridge.roomProvider
            ?: throw IllegalStateException(
                "Room provider not installed. Call DesktopSqliteBridge.install() before opening databases.",
            )
}

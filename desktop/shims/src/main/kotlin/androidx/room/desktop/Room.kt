/*
 * YumaPlayer Desktop (Linux) port shim.
 *
 * The shared `database` sources call Room.databaseBuilder(...) / Room.inMemoryDatabaseBuilder(...).
 * On desktop we compile against androidx.room:room-runtime-jvm, whose public API is
 * Room.createApplicationBuilder + generated `*Impl` classes instead. This shim keeps the
 * Android-style entry points source-compatible and delegates to the provider installed by the
 * desktop runtime via DesktopSqliteBridge.install().
 */
package androidx.room.desktop

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

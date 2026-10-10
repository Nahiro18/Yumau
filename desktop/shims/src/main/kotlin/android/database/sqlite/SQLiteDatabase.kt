/*
 * YumaPlayer Desktop (Linux) port shim.
 * Conflict constants from android.database.sqlite.SQLiteDatabase used by
 * SupportSQLiteDatabase.insert(...) calls in Room migration code.
 */
package android.database.sqlite

object SQLiteDatabase {
    // Constants mirror the real android.database.sqlite.SQLiteDatabase values;
    // androidx.sqlite (and Room-generated code) uses these same ordinals.
    const val CONFLICT_NONE = 0
    const val CONFLICT_REPLACE = 1
    const val CONFLICT_FAIL = 2
    const val CONFLICT_IGNORE = 4
    const val CONFLICT_ABORT = 5
}

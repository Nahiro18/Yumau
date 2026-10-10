/*
 * YumaPlayer Desktop (Linux) port shim.
 * Conflict constants from android.database.sqlite.SQLiteDatabase used by
 * SupportSQLiteDatabase.insert(...) calls in Room migration code.
 */
package android.database.sqlite

object SQLiteDatabase {
    const val CONFLICT_NONE = 0
    const val CONFLICT_ABORT = 4
    const val CONFLICT_FAIL = 3
    const val CONFLICT_IGNORE = 5
    const val CONFLICT_REPLACE = 1
}

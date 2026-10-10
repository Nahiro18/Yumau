/*
 * YumaPlayer Desktop (Linux) port shim.
 * Minimal android.util.Log replacement backed by java.util.logging.
 */
package android.util

object Log {
    private fun emit(
        level: java.util.logging.Level,
        tag: String,
        message: String,
        tr: Throwable?,
    ): Int {
        val logger = java.util.logging.Logger.getLogger("YumaPlayer.$tag")
        if (tr != null) logger.log(level, message, tr) else logger.log(level, message)
        return 0
    }

    fun v(tag: String, msg: String): Int = emit(java.util.logging.Level.FINE, tag, msg, null)

    fun v(tag: String, msg: String, tr: Throwable): Int = emit(java.util.logging.Level.FINE, tag, msg, tr)

    fun d(tag: String, msg: String): Int = emit(java.util.logging.Level.FINE, tag, msg, null)

    fun d(tag: String, msg: String, tr: Throwable): Int = emit(java.util.logging.Level.FINE, tag, msg, tr)

    fun i(tag: String, msg: String): Int = emit(java.util.logging.Level.INFO, tag, msg, null)

    fun i(tag: String, msg: String, tr: Throwable): Int = emit(java.util.logging.Level.INFO, tag, msg, tr)

    fun w(tag: String, msg: String): Int = emit(java.util.logging.Level.WARNING, tag, msg, null)

    fun w(tag: String, msg: String, tr: Throwable): Int = emit(java.util.logging.Level.WARNING, tag, msg, tr)

    fun e(tag: String, msg: String): Int = emit(java.util.logging.Level.SEVERE, tag, msg, null)

    fun e(tag: String, msg: String, tr: Throwable): Int = emit(java.util.logging.Level.SEVERE, tag, msg, tr)

    fun println(wtf: Boolean, tag: String, msg: String) {
        emit(if (wtf) java.util.logging.Level.SEVERE else java.util.logging.Level.INFO, tag, msg, null)
    }
}

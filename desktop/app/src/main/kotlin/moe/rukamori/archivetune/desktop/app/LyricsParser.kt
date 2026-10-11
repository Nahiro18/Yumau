package moe.rukamori.archivetune.desktop.app

data class LyricLine(
    val timestampMs: Long,
    val text: String,
)

object LyricsParser {
    private val lrcRegex = Regex("""\[(\d{2}):(\d{2})(?:\.(\d{2,3}))?](.*)""")

    fun parse(lrcContent: String): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()
        for (rawLine in lrcContent.lineSequence()) {
            val match = lrcRegex.find(rawLine.trim()) ?: continue
            val (minStr, secStr, msStr, text) = match.destructured
            val min = minStr.toLongOrNull() ?: 0L
            val sec = secStr.toLongOrNull() ?: 0L
            val ms = when {
                msStr.isEmpty() -> 0L
                msStr.length == 2 -> (msStr.toLongOrNull() ?: 0L) * 10
                else -> msStr.toLongOrNull() ?: 0L
            }
            val totalMs = min * 60000 + sec * 1000 + ms
            lines.add(LyricLine(totalMs, text.trim()))
        }
        return lines.sortedBy { it.timestampMs }
    }

    fun getCurrentLineIndex(lines: List<LyricLine>, currentMs: Long): Int {
        if (lines.isEmpty()) return -1
        var index = -1
        for (i in lines.indices) {
            if (lines[i].timestampMs <= currentMs) {
                index = i
            } else {
                break
            }
        }
        return index
    }
}

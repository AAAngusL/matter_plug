package app.matterplug.guard.logic

/** The text of a percent box, checked against the allowed range. No Android imports, so it is unit-tested. */
object PercentInput {
    fun isValid(text: String, range: IntRange): Boolean = text.toIntOrNull()?.let { it in range } == true

    /** The value to save, or null when the text is invalid or equals [current] (saving it would only re-arm a check for nothing). */
    fun toCommit(text: String, range: IntRange, current: Int): Int? =
        if (isValid(text, range)) text.toInt().takeIf { it != current } else null
}

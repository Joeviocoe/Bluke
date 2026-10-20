package dev.arnv.bluke.ui

/**
 * What the Phone Keyboard text box has put on the host, so it can be corrected or erased later.
 *
 * The text box is edited locally - with autocorrect, selection, copy and paste - and only the
 * changes are typed on the host. Each code point is remembered together with whether it actually
 * reached the host: a character the host layout cannot produce is skipped there, and one that was
 * already in the box before auto-typing started was never sent at all. Backspace counts only ever
 * cover characters that were sent, so neither case leaves the host and the box out of step.
 *
 * Assumes the host's caret sits at the end of what was typed, which holds as long as nothing else
 * moves it; arrow keys from the key bar do, which is why Erase and Clear exist.
 */
class TextBoxMirror {

    private class Entry(val text: String, val sent: Boolean)

    private val entries = mutableListOf<Entry>()

    /** How many Backspaces would remove everything this box sent. */
    val sentCount: Int get() = entries.count { it.sent }

    /**
     * Brings the host's copy in line with [target]: everything after the common prefix is
     * backspaced and retyped, which is how autocorrect rewriting an earlier word is handled.
     *
     * [type] is called once per code point and reports whether it reached the host.
     */
    fun sync(target: String, backspace: (count: Int) -> Unit, type: (String) -> Boolean) {
        val targetUnits = codePoints(target)
        var common = 0
        while (common < entries.size && common < targetUnits.size &&
            entries[common].text == targetUnits[common]
        ) common++
        val stale = entries.subList(common, entries.size)
        val backspaces = stale.count { it.sent }
        stale.clear()
        if (backspaces > 0) backspace(backspaces)
        targetUnits.drop(common).forEach { entries += Entry(it, type(it)) }
    }

    /** Adds [text] typed in one go at the host's caret, as the Type button does. */
    fun append(text: String, type: (String) -> Boolean) {
        codePoints(text).forEach { entries += Entry(it, type(it)) }
    }

    /** Takes [text] as already in the box but not on the host, so deleting it sends nothing. */
    fun adopt(text: String) {
        entries.clear()
        codePoints(text).forEach { entries += Entry(it, sent = false) }
    }

    fun clear() = entries.clear()

    companion object {
        /** Splits [text] into code points, so an emoji is never cut in half by a diff. */
        fun codePoints(text: String): List<String> {
            val out = ArrayList<String>(text.length)
            var i = 0
            while (i < text.length) {
                val end = text.offsetByCodePoints(i, 1)
                out += text.substring(i, end)
                i = end
            }
            return out
        }

        /** The text inserted to turn [old] into [new], or null if anything was removed or replaced. */
        fun insertedText(old: String, new: String): String? {
            if (new.length <= old.length) return null
            var prefix = 0
            while (prefix < old.length && old[prefix] == new[prefix]) prefix++
            var suffix = 0
            while (suffix < old.length - prefix &&
                old[old.length - 1 - suffix] == new[new.length - 1 - suffix]
            ) suffix++
            if (prefix + suffix < old.length) return null
            return new.substring(prefix, new.length - suffix)
        }
    }
}

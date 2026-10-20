package dev.arnv.bluke.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The text box mirror decides how many Backspaces each edit costs on the host, so these replay
 * edits against a fake host and check the host ends up holding exactly what the box does.
 */
class TextBoxMirrorTest {

    private val mirror = TextBoxMirror()
    private val host = StringBuilder()
    private var backspaceCount = 0

    /** Characters the fake host's layout has no key for. */
    private val untypable = setOf("€", "😀")

    private fun sync(target: String) = mirror.sync(
        target,
        backspace = { count ->
            backspaceCount += count
            repeat(count) { host.setLength(host.offsetByCodePoints(host.length, -1)) }
        },
        type = { unit -> (unit !in untypable).also { if (it) host.append(unit) } },
    )

    private fun erase() {
        repeat(mirror.sentCount) { host.setLength(host.offsetByCodePoints(host.length, -1)) }
        mirror.clear()
    }

    @Test
    fun appendingTypesOnlyTheNewText() {
        sync("hel")
        sync("hello")
        assertEquals("hello", host.toString())
        assertEquals(0, backspaceCount)
    }

    @Test
    fun autocorrectRewritesFromTheFirstDifference() {
        sync("teh ")
        sync("the ")
        assertEquals("the ", host.toString())
        assertEquals(3, backspaceCount)
    }

    @Test
    fun editInTheMiddleRetypesTheTail() {
        sync("one three")
        sync("one two three")
        assertEquals("one two three", host.toString())
    }

    @Test
    fun deletingAllOfTheBoxEmptiesTheHost() {
        sync("hello")
        sync("")
        assertEquals("", host.toString())
        assertEquals(5, backspaceCount)
    }

    @Test
    fun untypableCharactersCostNoBackspaces() {
        sync("a€b")
        assertEquals("ab", host.toString())
        sync("a")
        // Only "b" is on the host after the "a"; deleting the skipped € must not eat the "a".
        assertEquals("a", host.toString())
        assertEquals(1, backspaceCount)
    }

    @Test
    fun untypableCharactersAreNotRetriedOnLaterEdits() {
        var attempts = 0
        val counting: (String) -> Boolean = { unit -> if (unit == "€") attempts++; unit != "€" }
        mirror.sync("€", backspace = {}, type = counting)
        mirror.sync("€x", backspace = {}, type = counting)
        mirror.sync("€xy", backspace = {}, type = counting)
        assertEquals(1, attempts)
    }

    @Test
    fun emojiAreOneUnit() {
        assertEquals(listOf("a", "😀", "b"), TextBoxMirror.codePoints("a😀b"))
        sync("hi 😀!")
        sync("hi ")
        assertEquals("hi ", host.toString())
        assertEquals(1, backspaceCount)
    }

    @Test
    fun adoptedTextIsNeverBackspacedOnTheHost() {
        host.append("prompt> ")
        mirror.adopt("draft")
        sync("dra")
        assertEquals("prompt> ", host.toString())
        sync("drat")
        assertEquals("prompt> t", host.toString())
        assertEquals(1, mirror.sentCount)
    }

    @Test
    fun eraseRemovesEverythingTheBoxTyped() {
        host.append("$ ")
        sync("ls -la")
        erase()
        assertEquals("$ ", host.toString())
    }

    @Test
    fun appendAccumulatesForErase() {
        val type: (String) -> Boolean = { host.append(it); true }
        mirror.append("abc", type)
        mirror.append("de", type)
        assertEquals(5, mirror.sentCount)
        erase()
        assertEquals("", host.toString())
    }

    @Test
    fun insertedTextFindsPureInsertions() {
        assertEquals("c", TextBoxMirror.insertedText("ab", "abc"))
        assertEquals("X", TextBoxMirror.insertedText("ab", "aXb"))
        assertEquals("aa", TextBoxMirror.insertedText("a", "aaa"))
        assertEquals("hi", TextBoxMirror.insertedText("", "hi"))
    }

    @Test
    fun insertedTextRejectsReplacementsAndDeletions() {
        assertNull(TextBoxMirror.insertedText("abc", "ab"))
        assertNull(TextBoxMirror.insertedText("abc", "abd"))
        assertNull(TextBoxMirror.insertedText("teh", "the "))
    }
}

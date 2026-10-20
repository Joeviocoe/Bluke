package dev.arnv.bluke.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The Phone Keyboard text box: a full multi-line field typed into with the phone's own keyboard,
 * with its autocorrect, selection, copy and paste, and sent to the host either as it changes
 * (auto-type) or on demand with Type.
 *
 * Unlike the direct mode, the text stays put until cleared, so Erase can always take back exactly
 * what the box typed. See [TextBoxMirror] for how edits become keystrokes.
 */
class PhoneKeyboardTextBoxState(
    private val bridge: PhoneKeyboardBridge,
    autoType: Boolean,
    /** Characters the host layout could not produce, reported once per edit. */
    private val onDropped: (String) -> Unit,
    /** Called whenever keystrokes went to the host, for sound and vibration feedback. */
    private val onKeystroke: () -> Unit,
) {
    var value by mutableStateOf(TextFieldValue())
        private set

    var autoType by mutableStateOf(autoType)
        private set

    /** How many characters Erase would remove from the host; drives whether it is enabled. */
    var erasable by mutableIntStateOf(0)
        private set

    private val mirror = TextBoxMirror()

    fun onValueChange(new: TextFieldValue) {
        // A latched modifier turns the next key into a host shortcut, as in the direct mode,
        // rather than writing a letter into the box that never reached the host as a letter.
        if (bridge.modifiers.active().isNotEmpty()) {
            val inserted = TextBoxMirror.insertedText(value.text, new.text)
            if (!inserted.isNullOrEmpty()) {
                bridge.typeText(inserted)
                onKeystroke()
                return
            }
        }
        value = new
        sync()
    }

    /** Puts [text] at the caret, replacing any selection, as if it had been typed. */
    fun insert(text: String) {
        val start = value.selection.min
        val end = value.selection.max
        onValueChange(
            TextFieldValue(
                text = value.text.replaceRange(start, end, text),
                selection = TextRange(start + text.length),
            )
        )
    }

    /**
     * Backspace from the key bar. With auto-type on the box *is* the host's text, so it edits the
     * box (and the change goes out from there); an empty box passes it on to the host.
     * Returns false when the caller should send it to the host itself.
     */
    fun backspace(): Boolean {
        if (!autoType || value.text.isEmpty() || bridge.modifiers.active().isNotEmpty()) return false
        val sel = value.selection
        val (start, end) = if (sel.collapsed) {
            if (sel.start == 0) return false
            value.text.offsetByCodePoints(sel.start, -1) to sel.start
        } else {
            sel.min to sel.max
        }
        onValueChange(TextFieldValue(value.text.removeRange(start, end), TextRange(start)))
        return true
    }

    /** Types the selection, or the whole box, on the host. For use with auto-type off. */
    fun type() {
        val sel = value.selection
        val text = if (sel.collapsed) value.text else value.text.substring(sel.min, sel.max)
        if (text.isEmpty()) return
        send { type -> mirror.append(text, type) }
    }

    /** Backspaces everything this box has typed on the host. */
    fun erase() {
        bridge.backspacePlain(mirror.sentCount)
        if (mirror.sentCount > 0) onKeystroke()
        mirror.clear()
        // With auto-type on, the box must keep matching the host, so it empties too. Otherwise the
        // text stays, ready to be fixed and typed again.
        if (autoType) value = TextFieldValue()
        erasable = 0
    }

    /** Empties the box without touching the host. */
    fun clear() {
        mirror.clear()
        value = TextFieldValue()
        erasable = 0
    }

    fun updateAutoType(enabled: Boolean) {
        autoType = enabled
        // Whatever is in the box already was never sent; deleting it must not send Backspaces.
        if (enabled) {
            mirror.adopt(value.text)
            erasable = 0
        }
    }

    private fun sync() {
        // Words still being composed are left to settle locally, so suggestions do not spray
        // corrections at the host; they go out when the keyboard commits them.
        if (!autoType || value.composition != null) return
        var backspaced = false
        val typed = send { type ->
            mirror.sync(value.text, backspace = { count ->
                bridge.backspacePlain(count)
                backspaced = true
            }, type = type)
        }
        if (backspaced && !typed) onKeystroke()
    }

    /**
     * Runs [block] with a per-character typer, then reports what was dropped.
     * Returns whether anything was typed.
     */
    private fun send(block: (type: (String) -> Boolean) -> Unit): Boolean {
        val dropped = StringBuilder()
        var typedAny = false
        block { unit ->
            bridge.typePlain(unit).also { if (it) typedAny = true else dropped.append(unit) }
        }
        if (typedAny) onKeystroke()
        if (dropped.isNotEmpty()) onDropped(dropped.toString())
        erasable = mirror.sentCount
        return typedAny
    }
}

@Composable
fun PhoneKeyboardTextBox(
    state: PhoneKeyboardTextBoxState,
    focusRequester: FocusRequester,
    onAutoTypeChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = state.value,
            onValueChange = state::onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .heightIn(min = 72.dp)
                .focusRequester(focusRequester)
                .testTag("phone_keyboard_text_box"),
            placeholder = {
                Text(
                    if (state.autoType) "Type here - it goes to the host as you type"
                    else "Type here, then press Type to send it",
                    color = Color.White.copy(alpha = 0.4f),
                )
            },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = accent,
                unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                cursorColor = accent,
                focusedContainerColor = Color.White.copy(alpha = 0.05f),
                unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
            ),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextBoxButton(
                label = "Type",
                icon = Icons.AutoMirrored.Filled.Send,
                enabled = !state.autoType && state.value.text.isNotEmpty(),
                modifier = Modifier.weight(1f),
                testTag = "phone_keyboard_text_box_type",
                onClick = state::type,
            )
            TextBoxButton(
                label = "Erase",
                icon = Icons.AutoMirrored.Filled.Backspace,
                enabled = state.erasable > 0,
                modifier = Modifier.weight(1f),
                testTag = "phone_keyboard_text_box_erase",
                onClick = state::erase,
            )
            TextBoxButton(
                label = "Clear",
                icon = Icons.Default.DeleteSweep,
                enabled = state.value.text.isNotEmpty() || state.erasable > 0,
                modifier = Modifier.weight(1f),
                testTag = "phone_keyboard_text_box_clear",
                onClick = state::clear,
            )
            TextBoxButton(
                label = "Auto",
                icon = Icons.Default.Bolt,
                enabled = true,
                selected = state.autoType,
                modifier = Modifier.weight(1f),
                testTag = "phone_keyboard_text_box_auto",
                onClick = {
                    state.updateAutoType(!state.autoType)
                    onAutoTypeChange(state.autoType)
                },
            )
        }
    }
}

@Composable
private fun TextBoxButton(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    testTag: String,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(8.dp)
    val content = if (selected) MaterialTheme.colorScheme.onPrimary else Color.White
    Row(
        modifier = modifier
            .height(40.dp)
            .clip(shape)
            .background(if (selected) accent else Color.White.copy(alpha = 0.12f))
            .border(1.dp, if (selected) accent else Color.White.copy(alpha = 0.08f), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .testTag(testTag),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = content, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = content, fontSize = 12.sp, maxLines = 1)
    }
}

package dev.arnv.bluke.ui

import android.content.Intent
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.LayoutModifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import dev.arnv.bluke.QuickCycleActivity

internal val InputMode.icon: ImageVector
    get() = when (this) {
        InputMode.KEYBOARD -> Icons.Default.Keyboard
        InputMode.TOUCHPAD -> Icons.Default.Mouse
        InputMode.GAMEPAD -> Icons.Default.SportsEsports
        InputMode.MEDIA_PRESENTATION -> Icons.Default.SmartDisplay
        InputMode.PHONE_KEYBOARD -> Icons.Default.PhoneAndroid
    }

/**
 * Wraps a mode button so tapping it opens a menu of the enabled input modes instead of
 * cycling to the next one. [anchor] receives the callback that opens the menu.
 * [rotateContent] turns the menu items -90 degrees for the upright Multimedia posture.
 */
@Composable
internal fun InputModeDropdown(
    currentMode: Int,
    sharedPrefs: SharedPreferences,
    onModeSelected: (Int) -> Unit,
    rotateContent: Boolean = false,
    anchor: @Composable (openMenu: () -> Unit) -> Unit,
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    Box {
        anchor { expanded = true }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.testTag("input_mode_dropdown"),
        ) {
            Column(
                (if (rotateContent) Modifier.rotateLayoutCounterClockwise() else Modifier)
                    .width(IntrinsicSize.Max),
            ) {
                sharedPrefs.enabledInputModes().forEach { mode ->
                    DropdownMenuItem(
                        text = { Text(mode.displayName) },
                        leadingIcon = { Icon(mode.icon, contentDescription = null) },
                        trailingIcon = if (mode.id == currentMode) {
                            { Icon(Icons.Default.Check, contentDescription = "Current mode") }
                        } else {
                            null
                        },
                        onClick = {
                            expanded = false
                            if (mode.id != currentMode) onModeSelected(mode.id)
                        },
                        modifier = Modifier.testTag("input_mode_option_${mode.preferenceKey}"),
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Edit modes…") },
                    leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    onClick = {
                        expanded = false
                        context.startActivity(Intent(context, QuickCycleActivity::class.java))
                    },
                )
            }
        }
    }
}

// Lays the content out sideways: its width becomes our height and vice versa, then draws it
// turned a quarter turn. Intrinsics are swapped too, since DropdownMenu sizes its column from them.
internal fun Modifier.rotateLayoutQuarterTurn(clockwise: Boolean): Modifier =
    this.then(if (clockwise) RotateQuarterTurn.Clockwise else RotateQuarterTurn.CounterClockwise)

private fun Modifier.rotateLayoutCounterClockwise(): Modifier = rotateLayoutQuarterTurn(clockwise = false)

private enum class RotateQuarterTurn(val degrees: Float) : LayoutModifier {
    Clockwise(90f),
    CounterClockwise(-90f),
    ;

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(
            Constraints(
                minWidth = constraints.minHeight,
                maxWidth = constraints.maxHeight,
                minHeight = constraints.minWidth,
                maxHeight = constraints.maxWidth,
            ),
        )
        return layout(placeable.height, placeable.width) {
            placeable.placeWithLayer(
                x = (placeable.height - placeable.width) / 2,
                y = (placeable.width - placeable.height) / 2,
            ) { rotationZ = degrees }
        }
    }

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurable: IntrinsicMeasurable, height: Int) =
        measurable.minIntrinsicHeight(height)

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurable: IntrinsicMeasurable, height: Int) =
        measurable.maxIntrinsicHeight(height)

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurable: IntrinsicMeasurable, width: Int) =
        measurable.minIntrinsicWidth(width)

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurable: IntrinsicMeasurable, width: Int) =
        measurable.maxIntrinsicWidth(width)
}

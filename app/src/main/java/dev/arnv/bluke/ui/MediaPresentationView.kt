package dev.arnv.bluke.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.hardware.SensorManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.KeyEvent
import android.view.OrientationEventListener
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.arnv.bluke.QuickCycleActivity
import dev.arnv.bluke.RemoteVolumeKeyHost
import dev.arnv.bluke.bluetooth.BluetoothKeyboardManager
import dev.arnv.bluke.bluetooth.ConsumerControl
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min

private val RemoteCanvasColor = Color(0xFF1D1D1D)
private val RemoteTouchpadColor = Color(0xFF333333)
private val RemoteContentColor = Color(0xFFF4F4F6)
// Slight tints that group the buttons by what they do.
private val MediaButtonColor = Color(0xFF2B3A4E)
private val NavigationButtonColor = Color(0xFF2C4436)
private val ConfirmButtonColor = Color(0xFF22484D)
private val PageNavigationButtonColor = Color(0xFF4A2E3A)
private val PresentationButtonColor = Color(0xFF4A3D28)
private val SystemButtonColor = Color(0xFF3A3A3A)
private val MouseButtonColor = Color(0xFF3C3349)
private val SymbolButtonColor = Color(0xFF3D4428)
private val RemoteIconSize = 60.dp
private val RemoteLabelSize = 15.sp

internal enum class MultimediaPosture {
    LANDSCAPE,
    PORTRAIT_HELD,
}

internal fun multimediaPostureForDegrees(
    degrees: Int,
    current: MultimediaPosture,
): MultimediaPosture {
    if (degrees == OrientationEventListener.ORIENTATION_UNKNOWN) return current
    val normalized = ((degrees % 360) + 360) % 360
    fun distanceTo(target: Int): Int {
        val direct = abs(normalized - target)
        return min(direct, 360 - direct)
    }
    val portraitDistance = min(distanceTo(0), distanceTo(180))
    val landscapeDistance = min(distanceTo(90), distanceTo(270))
    return when {
        portraitDistance <= POSTURE_ENTRY_DEGREES -> MultimediaPosture.PORTRAIT_HELD
        landscapeDistance <= POSTURE_ENTRY_DEGREES -> MultimediaPosture.LANDSCAPE
        else -> current
    }
}

internal class MultimediaPostureStabilizer(
    initial: MultimediaPosture,
    private val stabilityMillis: Long = POSTURE_STABILITY_MILLIS,
) {
    var current: MultimediaPosture = initial
        private set
    private var candidate: MultimediaPosture? = null
    private var candidateSinceMillis = 0L

    fun update(degrees: Int, nowMillis: Long): MultimediaPosture {
        val next = multimediaPostureForDegrees(degrees, current)
        if (next == current) {
            candidate = null
            return current
        }
        if (candidate != next) {
            candidate = next
            candidateSinceMillis = nowMillis
            return current
        }
        if (nowMillis - candidateSinceMillis >= stabilityMillis) {
            current = next
            candidate = null
        }
        return current
    }
}

internal fun consumerControlForHardwareVolumeKey(keyCode: Int): ConsumerControl? = when (keyCode) {
    KeyEvent.KEYCODE_VOLUME_UP -> ConsumerControl.VOLUME_UP
    KeyEvent.KEYCODE_VOLUME_DOWN -> ConsumerControl.VOLUME_DOWN
    else -> null
}

@Composable
internal fun MediaPresentationView(
    btManager: BluetoothKeyboardManager,
    onClose: () -> Unit,
    launchMode: Int,
    onModeChange: (Int) -> Unit,
    sharedPrefs: SharedPreferences,
    isConnected: Boolean,
    postureOverride: MultimediaPosture? = null,
    // True when the screen itself rotates (fullscreen off): posture then comes from the screen
    // orientation, and the upright layout is turned to fit a real portrait screen.
    followsScreenRotation: Boolean = false,
) {
    val context = LocalContext.current
    val isPortraitScreen = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    val lifecycleOwner = LocalLifecycleOwner.current
    val volumeKeyHost = remember(context) { context.findRemoteVolumeKeyHost() }
    val useHardwareVolumeButtons = sharedPrefs.getBoolean(HARDWARE_VOLUME_REMOTE_PREFERENCE, false)
    var isForeground by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    var posture by rememberSaveable { mutableStateOf(MultimediaPosture.LANDSCAPE) }
    var sensitivity by remember {
        mutableFloatStateOf(sharedPrefs.getFloat("touchpad_sensitivity", 1.5f))
    }
    var scrollSensitivity by remember {
        mutableFloatStateOf(sharedPrefs.getFloat("touchpad_scroll_sensitivity", 1f))
    }
    var isVibrationEnabled by remember {
        mutableStateOf(sharedPrefs.getBoolean("touchpad_vibration_enabled", true))
    }
    var isTrackpadVisible by remember {
        mutableStateOf(sharedPrefs.getBoolean(MULTIMEDIA_TRACKPAD_VISIBLE_PREFERENCE, false))
    }

    @Suppress("DEPRECATION")
    fun triggerVibration(milliseconds: Long) {
        if (!isVibrationEnabled) return
        runCatching {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            vibrator?.vibrate(
                VibrationEffect.createOneShot(milliseconds, VibrationEffect.DEFAULT_AMPLITUDE),
            )
        }
    }

    DisposableEffect(context, postureOverride, followsScreenRotation) {
        if (postureOverride != null || followsScreenRotation) {
            postureOverride?.let { posture = it }
            onDispose {}
        } else {
            val stabilizer = MultimediaPostureStabilizer(posture)
            val listener = object : OrientationEventListener(context, SensorManager.SENSOR_DELAY_UI) {
                override fun onOrientationChanged(orientation: Int) {
                    posture = stabilizer.update(orientation, SystemClock.elapsedRealtime())
                }
            }
            if (listener.canDetectOrientation()) listener.enable()
            onDispose { listener.disable() }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> isForeground = true
                Lifecycle.Event.ON_STOP -> isForeground = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(volumeKeyHost, useHardwareVolumeButtons, isForeground, btManager) {
        if (useHardwareVolumeButtons && isForeground) {
            volumeKeyHost?.setRemoteVolumeKeyHandler { keyCode, isPressed ->
                consumerControlForHardwareVolumeKey(keyCode)?.let { control ->
                    btManager.sendConsumerControl(if (isPressed) control else null)
                }
            }
        }
        onDispose {
            volumeKeyHost?.setRemoteVolumeKeyHandler(null)
            btManager.sendConsumerControl(null)
        }
    }

    val resolvedPosture = when {
        postureOverride != null -> postureOverride
        followsScreenRotation -> if (isPortraitScreen) MultimediaPosture.PORTRAIT_HELD else MultimediaPosture.LANDSCAPE
        else -> posture
    }
    // The upright layout is drawn for a landscape screen held on its side. On a real portrait
    // screen, turn it a quarter clockwise so it lands the way it would be held.
    val turnUprightLayout = followsScreenRotation && isPortraitScreen
    val cycleSensitivity = {
        sensitivity = nextTouchpadSpeed(sensitivity)
        sharedPrefs.edit { putFloat("touchpad_sensitivity", sensitivity) }
        triggerVibration(15)
    }
    val cycleScrollSensitivity = {
        scrollSensitivity = nextTouchpadSpeed(scrollSensitivity)
        sharedPrefs.edit { putFloat("touchpad_scroll_sensitivity", scrollSensitivity) }
        triggerVibration(15)
    }
    val toggleTrackpad = {
        isTrackpadVisible = !isTrackpadVisible
        sharedPrefs.edit { putBoolean(MULTIMEDIA_TRACKPAD_VISIBLE_PREFERENCE, isTrackpadVisible) }
        triggerVibration(15)
    }
    val toggleVibration = {
        isVibrationEnabled = !isVibrationEnabled
        sharedPrefs.edit { putBoolean("touchpad_vibration_enabled", isVibrationEnabled) }
        triggerVibration(35)
    }
    val rootModifier = Modifier
        .fillMaxSize()
        .background(RemoteCanvasColor)
        .testTag("media_presentation_view_root")

    if (resolvedPosture == MultimediaPosture.LANDSCAPE) {
        Column(modifier = rootModifier) {
            MultimediaTopBar(
                btManager = btManager,
                onClose = onClose,
                launchMode = launchMode,
                onModeChange = onModeChange,
                sharedPrefs = sharedPrefs,
                isConnected = isConnected,
                sensitivity = sensitivity,
                onSensitivityChange = cycleSensitivity,
                scrollSensitivity = scrollSensitivity,
                onScrollSensitivityChange = cycleScrollSensitivity,
                isVibrationEnabled = isVibrationEnabled,
                onToggleVibration = toggleVibration,
                isTrackpadVisible = isTrackpadVisible,
                onToggleTrackpad = toggleTrackpad,
            )
            MultimediaControlCanvas(
                btManager = btManager,
                posture = resolvedPosture,
                showTrackpad = isTrackpadVisible,
                sensitivity = sensitivity,
                scrollSensitivity = scrollSensitivity,
                triggerVibration = ::triggerVibration,
                modifier = Modifier.fillMaxSize(),
            )
        }
    } else {
        Row(
            modifier = if (turnUprightLayout) {
                Modifier
                    .fillMaxSize()
                    .background(RemoteCanvasColor)
                    .rotateLayoutQuarterTurn(clockwise = true)
                    .then(rootModifier)
            } else {
                rootModifier
            },
        ) {
            MultimediaUprightBar(
                btManager = btManager,
                onClose = onClose,
                launchMode = launchMode,
                onModeChange = onModeChange,
                sharedPrefs = sharedPrefs,
                isConnected = isConnected,
                sensitivity = sensitivity,
                onSensitivityChange = cycleSensitivity,
                scrollSensitivity = scrollSensitivity,
                onScrollSensitivityChange = cycleScrollSensitivity,
                isVibrationEnabled = isVibrationEnabled,
                onToggleVibration = toggleVibration,
                isTrackpadVisible = isTrackpadVisible,
                onToggleTrackpad = toggleTrackpad,
                // A popup opens in screen orientation, so it only needs turning on a landscape screen.
                rotateMenu = !turnUprightLayout,
            )
            MultimediaControlCanvas(
                btManager = btManager,
                posture = resolvedPosture,
                showTrackpad = isTrackpadVisible,
                sensitivity = sensitivity,
                scrollSensitivity = scrollSensitivity,
                triggerVibration = ::triggerVibration,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun MultimediaControlCanvas(
    btManager: BluetoothKeyboardManager,
    posture: MultimediaPosture,
    showTrackpad: Boolean,
    sensitivity: Float,
    scrollSensitivity: Float,
    triggerVibration: (Long) -> Unit,
    modifier: Modifier,
) {
    val startPadding = if (posture == MultimediaPosture.LANDSCAPE) 32.dp else 24.dp
    // With the trackpad shown, both decks share the canvas edge to edge.
    val canvasPadding = if (showTrackpad) {
        PaddingValues(8.dp)
    } else {
        PaddingValues(
            start = startPadding,
            top = if (posture == MultimediaPosture.LANDSCAPE) 4.dp else 28.dp,
            end = startPadding,
            bottom = if (posture == MultimediaPosture.LANDSCAPE) 22.dp else 28.dp,
        )
    }
    BoxWithConstraints(modifier = modifier.padding(canvasPadding)) {
        val compact = maxHeight < 280.dp
        val horizontalGap = when {
            posture == MultimediaPosture.PORTRAIT_HELD -> 7.5f.dp
            compact -> 7.dp
            else -> 8.dp
        }
        val verticalGap = if (compact) 7.dp else 8.dp
        if (!showTrackpad) {
            MultimediaButtonDeck(
                btManager = btManager,
                posture = posture,
                horizontalGap = horizontalGap,
                verticalGap = verticalGap,
                triggerVibration = triggerVibration,
                modifier = Modifier.fillMaxSize(),
            )
            return@BoxWithConstraints
        }
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(horizontalGap * 2),
        ) {
            MultimediaButtonDeck(
                btManager = btManager,
                posture = posture,
                horizontalGap = horizontalGap,
                verticalGap = verticalGap,
                triggerVibration = triggerVibration,
                modifier = Modifier
                    .weight(if (posture == MultimediaPosture.LANDSCAPE) 1.025f else 1f)
                    .fillMaxHeight(),
            )
            MultimediaPointerDeck(
                btManager = btManager,
                posture = posture,
                sensitivity = sensitivity,
                scrollSensitivity = scrollSensitivity,
                triggerVibration = triggerVibration,
                compact = compact,
                modifier = Modifier
                    .weight(if (posture == MultimediaPosture.LANDSCAPE) 0.975f else 1.08f)
                    .fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun MultimediaButtonDeck(
    btManager: BluetoothKeyboardManager,
    posture: MultimediaPosture,
    horizontalGap: Dp,
    verticalGap: Dp,
    triggerVibration: (Long) -> Unit,
    modifier: Modifier,
) {
    val contentRotation = if (posture == MultimediaPosture.PORTRAIT_HELD) -90f else 0f
    // The buttons fill the deck: two groups of three columns (two gaps inside each,
    // a triple gap between them) by four rows.
    BoxWithConstraints(modifier = modifier) {
        val buttonWidth = (maxWidth - horizontalGap * 7) / 6
        val buttonHeight = (maxHeight - verticalGap * 3) / 4
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(horizontalGap * 3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val mediaRows = if (posture == MultimediaPosture.LANDSCAPE) {
                landscapeMediaRows
            } else {
                portraitHeldMediaRows
            }
            Column(verticalArrangement = Arrangement.spacedBy(verticalGap)) {
                mediaRows.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(horizontalGap)) {
                        row.forEach { action ->
                            MultimediaActionButton(
                                action = action,
                                btManager = btManager,
                                width = buttonWidth,
                                height = buttonHeight,
                                contentRotation = contentRotation,
                                triggerVibration = triggerVibration,
                            )
                        }
                    }
                }
            }

            // Upright content is drawn rotated -90 degrees, so the holder's up is the screen's left.
            // Rotate the D-pad with it so each arrow sends the direction it appears to point.
            val (topArrow, leftArrow, rightArrow, bottomArrow) = if (posture == MultimediaPosture.PORTRAIT_HELD) {
                listOf(rightArrowAction, upArrowAction, downArrowAction, leftArrowAction)
            } else {
                listOf(upArrowAction, leftArrowAction, rightArrowAction, downArrowAction)
            }
            // Small keys in the pad's corners, by screen corner: speed (< > / [ ]) above and , . below as held.
            val (topStartKey, topEndKey, bottomStartKey, bottomEndKey) = if (posture == MultimediaPosture.PORTRAIT_HELD) {
                listOf(fasterAction, periodAction, slowerAction, commaAction)
            } else {
                listOf(slowerAction, fasterAction, commaAction, periodAction)
            }
            @Composable
            fun CornerKey(action: RemoteAction, alignment: Alignment) {
                MultimediaCornerButton(
                    action, btManager, buttonWidth, buttonHeight, alignment, contentRotation, triggerVibration,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(verticalGap)) {
                Row(horizontalArrangement = Arrangement.spacedBy(horizontalGap)) {
                    CornerKey(topStartKey, Alignment.TopStart)
                    MultimediaActionButton(topArrow, btManager, buttonWidth, buttonHeight, contentRotation, triggerVibration)
                    CornerKey(topEndKey, Alignment.TopEnd)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(horizontalGap)) {
                    MultimediaActionButton(leftArrow, btManager, buttonWidth, buttonHeight, contentRotation, triggerVibration)
                    MultimediaActionButton(
                        RemoteAction.Key("OK", null, KeyboardLayouts.KEY_ENTER),
                        btManager,
                        buttonWidth,
                        buttonHeight,
                        contentRotation,
                        triggerVibration,
                    )
                    MultimediaActionButton(rightArrow, btManager, buttonWidth, buttonHeight, contentRotation, triggerVibration)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(horizontalGap)) {
                    CornerKey(bottomStartKey, Alignment.BottomStart)
                    MultimediaActionButton(bottomArrow, btManager, buttonWidth, buttonHeight, contentRotation, triggerVibration)
                    CornerKey(bottomEndKey, Alignment.BottomEnd)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(horizontalGap)) {
                    MultimediaActionButton(
                        RemoteAction.Key("Back", null, KeyboardLayouts.KEY_BACKSPACE),
                        btManager,
                        buttonWidth,
                        buttonHeight,
                        contentRotation,
                        triggerVibration,
                    )
                    MultimediaActionButton(
                        RemoteAction.Key("Start", null, KeyboardLayouts.KEY_F5),
                        btManager,
                        buttonWidth,
                        buttonHeight,
                        contentRotation,
                        triggerVibration,
                    )
                    MultimediaActionButton(
                        RemoteAction.Key("Menu", null, KEY_APPLICATION),
                        btManager,
                        buttonWidth,
                        buttonHeight,
                        contentRotation,
                        triggerVibration,
                    )
                }
            }
        }
    }
}

@Composable
private fun MultimediaPointerDeck(
    btManager: BluetoothKeyboardManager,
    posture: MultimediaPosture,
    sensitivity: Float,
    scrollSensitivity: Float,
    triggerVibration: (Long) -> Unit,
    compact: Boolean,
    modifier: Modifier,
) {
    val gap = if (compact) 7.dp else 8.dp
    val navigationWidth = if (compact) 52.dp else 60.dp
    val mouseThickness = if (compact) 38.dp else 42.dp
    if (posture == MultimediaPosture.LANDSCAPE) {
        Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(gap)) {
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                MultimediaTouchpad(
                    btManager,
                    posture,
                    sensitivity,
                    scrollSensitivity,
                    triggerVibration,
                    Modifier.weight(1f).fillMaxWidth(),
                )
                MouseButtonRow(btManager, Modifier.fillMaxWidth().height(mouseThickness), gap, triggerVibration)
            }
            SlideNavigationColumn(btManager, Modifier.width(navigationWidth).fillMaxHeight(), gap, triggerVibration)
        }
    } else {
        Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(gap)) {
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                SlideNavigationRow(btManager, Modifier.fillMaxWidth().height(mouseThickness), gap, triggerVibration)
                MultimediaTouchpad(
                    btManager,
                    posture,
                    sensitivity,
                    scrollSensitivity,
                    triggerVibration,
                    Modifier.weight(1f).fillMaxWidth(),
                )
            }
            MouseButtonColumn(btManager, Modifier.width(mouseThickness).fillMaxHeight(), gap, triggerVibration)
        }
    }
}

@Composable
private fun MultimediaTouchpad(
    btManager: BluetoothKeyboardManager,
    posture: MultimediaPosture,
    sensitivity: Float,
    scrollSensitivity: Float,
    triggerVibration: (Long) -> Unit,
    modifier: Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(RemoteTouchpadColor)
            .testTag("multimedia_touchpad")
            // Upright content is turned -90 degrees; turn the gesture surface with it so a swipe
            // moves the pointer the way the holder swiped.
            .then(
                if (posture == MultimediaPosture.PORTRAIT_HELD) {
                    Modifier.rotateLayoutQuarterTurn(clockwise = false)
                } else {
                    Modifier
                },
            ),
    ) {
        TouchGestureLayer(
            btManager = btManager,
            sensitivity = sensitivity,
            scrollSensitivity = scrollSensitivity,
            buttonMode = TrackpadButtonMode.CLICKPAD,
            triggerVibration = triggerVibration,
            showNumpadLed = false,
        )
    }
}

@Composable
private fun SlideNavigationColumn(
    btManager: BluetoothKeyboardManager,
    modifier: Modifier,
    gap: Dp,
    triggerVibration: (Long) -> Unit,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
        MultimediaActionButton(
            RemoteAction.Key("Previous slide", Icons.Default.KeyboardArrowUp, KeyboardLayouts.KEY_PAGEUP),
            btManager,
            Modifier.weight(1f).fillMaxWidth(),
            triggerVibration = triggerVibration,
        )
        MultimediaActionButton(
            RemoteAction.Key("Next slide", Icons.Default.KeyboardArrowDown, KeyboardLayouts.KEY_PAGEDOWN),
            btManager,
            Modifier.weight(1f).fillMaxWidth(),
            triggerVibration = triggerVibration,
        )
    }
}

@Composable
private fun SlideNavigationRow(
    btManager: BluetoothKeyboardManager,
    modifier: Modifier,
    gap: Dp,
    triggerVibration: (Long) -> Unit,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(gap)) {
        MultimediaActionButton(
            RemoteAction.Key("Previous slide", Icons.AutoMirrored.Filled.KeyboardArrowLeft, KeyboardLayouts.KEY_PAGEUP),
            btManager,
            Modifier.weight(1f).fillMaxHeight(),
            triggerVibration = triggerVibration,
        )
        MultimediaActionButton(
            RemoteAction.Key("Next slide", Icons.AutoMirrored.Filled.KeyboardArrowRight, KeyboardLayouts.KEY_PAGEDOWN),
            btManager,
            Modifier.weight(1f).fillMaxHeight(),
            triggerVibration = triggerVibration,
        )
    }
}

@Composable
private fun MouseButtonRow(
    btManager: BluetoothKeyboardManager,
    modifier: Modifier,
    gap: Dp,
    triggerVibration: (Long) -> Unit,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(gap)) {
        MouseHoldButton("Left mouse button", 0x01, btManager, Modifier.weight(1f).fillMaxHeight(), triggerVibration)
        MouseHoldButton("Middle mouse button", 0x04, btManager, Modifier.weight(1f).fillMaxHeight(), triggerVibration)
        MouseHoldButton("Right mouse button", 0x02, btManager, Modifier.weight(1f).fillMaxHeight(), triggerVibration)
    }
}

@Composable
private fun MouseButtonColumn(
    btManager: BluetoothKeyboardManager,
    modifier: Modifier,
    gap: Dp,
    triggerVibration: (Long) -> Unit,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
        MouseHoldButton("Left mouse button", 0x01, btManager, Modifier.weight(1f).fillMaxWidth(), triggerVibration)
        MouseHoldButton("Middle mouse button", 0x04, btManager, Modifier.weight(1f).fillMaxWidth(), triggerVibration)
        MouseHoldButton("Right mouse button", 0x02, btManager, Modifier.weight(1f).fillMaxWidth(), triggerVibration)
    }
}

@Composable
private fun MouseHoldButton(
    label: String,
    mask: Int,
    btManager: BluetoothKeyboardManager,
    modifier: Modifier,
    triggerVibration: (Long) -> Unit,
) {
    RemoteHoldButton(
        label = label,
        icon = null,
        modifier = modifier,
        fillHeight = true,
        showLabel = false,
        cornerRadius = 8.dp,
        showBorder = false,
        containerColor = MouseButtonColor,
        contentColor = RemoteContentColor,
        onPressFeedback = { triggerVibration(15) },
        onPressedChange = { pressed ->
            btManager.sendMouseReport(if (pressed) mask.toByte() else 0, 0, 0, 0)
        },
    )
}

@Composable
private fun MultimediaActionButton(
    action: RemoteAction,
    btManager: BluetoothKeyboardManager,
    width: Dp,
    height: Dp,
    contentRotation: Float = 0f,
    triggerVibration: (Long) -> Unit,
) {
    // Icons grow to RemoteIconSize, but stay inside smaller buttons.
    val iconSize = minOf(RemoteIconSize, minOf(width, height) * 0.85f)
    Box(Modifier.width(width).height(height)) {
        MultimediaActionButton(action, btManager, Modifier.fillMaxSize(), contentRotation, triggerVibration, iconSize)
    }
}

@Composable
private fun MultimediaActionButton(
    action: RemoteAction,
    btManager: BluetoothKeyboardManager,
    modifier: Modifier,
    contentRotation: Float = 0f,
    triggerVibration: (Long) -> Unit,
    iconSize: Dp = RemoteIconSize * 0.75f,
) {
    val scope = rememberCoroutineScope()
    var longPressJob by remember { mutableStateOf<Job?>(null) }
    val shownKey = when (action) {
        is RemoteAction.Key -> action
        is RemoteAction.TapOrHold -> action.tap
        is RemoteAction.Consumer -> null
    }
    RemoteHoldButton(
        label = action.label,
        icon = action.icon,
        text = shownKey?.shortLabel ?: action.label,
        hint = (action as? RemoteAction.TapOrHold)?.hold?.shortLabel,
        modifier = modifier,
        fillHeight = true,
        showLabel = action.icon == null,
        contentRotation = contentRotation,
        cornerRadius = 8.dp,
        showBorder = false,
        iconSize = iconSize,
        labelFontSize = RemoteLabelSize,
        containerColor = action.containerColor,
        contentColor = RemoteContentColor,
        onPressFeedback = { triggerVibration(15) },
        onPressedChange = { pressed ->
            when (action) {
                is RemoteAction.Consumer -> {
                    btManager.sendConsumerControl(if (pressed) action.control else null)
                }
                is RemoteAction.Key -> btManager.sendRemoteKey(action, pressed)
                // Sent on release for a tap, or once the press passes the long-press time.
                is RemoteAction.TapOrHold -> if (pressed) {
                    longPressJob = scope.launch {
                        delay(LONG_PRESS_MILLIS)
                        triggerVibration(35)
                        btManager.tapRemoteKey(action.hold)
                    }
                } else {
                    val job = longPressJob
                    longPressJob = null
                    if (job?.isActive == true) {
                        job.cancel()
                        btManager.tapRemoteKey(action.tap)
                    }
                }
            }
        },
    )
}

private fun BluetoothKeyboardManager.sendRemoteKey(key: RemoteAction.Key, pressed: Boolean) {
    if (pressed) {
        if (key.shift) sendKey(KeyboardLayouts.MOD_LSHIFT, true)
        sendKey(key.keyCode, true)
    } else {
        sendKey(key.keyCode, false)
        if (key.shift) sendKey(KeyboardLayouts.MOD_LSHIFT, false)
    }
}

private fun BluetoothKeyboardManager.tapRemoteKey(key: RemoteAction.Key) {
    sendRemoteKey(key, true)
    sendRemoteKey(key, false)
}

// A smaller button tucked into the outer corner of its grid cell, away from the arrows.
@Composable
private fun MultimediaCornerButton(
    action: RemoteAction,
    btManager: BluetoothKeyboardManager,
    width: Dp,
    height: Dp,
    alignment: Alignment,
    contentRotation: Float,
    triggerVibration: (Long) -> Unit,
) {
    Box(Modifier.width(width).height(height), contentAlignment = alignment) {
        MultimediaActionButton(
            action,
            btManager,
            width * CORNER_BUTTON_SCALE,
            height * CORNER_BUTTON_SCALE,
            contentRotation,
            triggerVibration,
        )
    }
}

private sealed interface RemoteAction {
    val label: String
    val icon: ImageVector?

    data class Consumer(
        override val label: String,
        override val icon: ImageVector,
        val control: ConsumerControl,
    ) : RemoteAction

    data class Key(
        override val label: String,
        override val icon: ImageVector?,
        val keyCode: Int,
        val shortLabel: String? = null,
        val shift: Boolean = false,
    ) : RemoteAction

    // Sends [tap] on a tap and [hold] on a long-press.
    data class TapOrHold(val tap: Key, val hold: Key) : RemoteAction {
        override val label: String get() = tap.label
        override val icon: ImageVector? get() = null
    }

}

private val RemoteAction.containerColor: Color
    get() = when (this) {
        is RemoteAction.Consumer -> MediaButtonColor
        is RemoteAction.Key -> when (keyCode) {
            KeyboardLayouts.KEY_UP,
            KeyboardLayouts.KEY_DOWN,
            KeyboardLayouts.KEY_LEFT,
            KeyboardLayouts.KEY_RIGHT,
            -> NavigationButtonColor
            KeyboardLayouts.KEY_ENTER -> ConfirmButtonColor
            KeyboardLayouts.KEY_HOME,
            KeyboardLayouts.KEY_END,
            KeyboardLayouts.KEY_PAGEUP,
            KeyboardLayouts.KEY_PAGEDOWN,
            -> PageNavigationButtonColor
            KeyboardLayouts.KEY_F5,
            KeyboardLayouts.KEY_F,
            KeyboardLayouts.KEY_B,
            KeyboardLayouts.KEY_W,
            KeyboardLayouts.KEY_ESC,
            -> PresentationButtonColor
            KeyboardLayouts.KEY_LBRACKET,
            KeyboardLayouts.KEY_RBRACKET,
            KeyboardLayouts.KEY_COMMA,
            KeyboardLayouts.KEY_PERIOD,
            -> SymbolButtonColor
            else -> SystemButtonColor
        }
        is RemoteAction.TapOrHold -> tap.containerColor
    }

private val muteAction = RemoteAction.Consumer("Mute", Icons.AutoMirrored.Filled.VolumeOff, ConsumerControl.MUTE)
private val volumeUpAction = RemoteAction.Consumer("Volume up", Icons.AutoMirrored.Filled.VolumeUp, ConsumerControl.VOLUME_UP)
private val volumeDownAction = RemoteAction.Consumer("Volume down", Icons.AutoMirrored.Filled.VolumeDown, ConsumerControl.VOLUME_DOWN)
private val previousAction = RemoteAction.Consumer("Previous track", Icons.Default.SkipPrevious, ConsumerControl.PREVIOUS_TRACK)
private val playAction = RemoteAction.Consumer("Play or pause", Icons.Default.PlayArrow, ConsumerControl.PLAY_PAUSE)
private val nextAction = RemoteAction.Consumer("Next track", Icons.Default.SkipNext, ConsumerControl.NEXT_TRACK)
// Upright content is rotated -90 degrees. Use the opposite source glyphs so the rendered
// Previous/Next symbols retain their intended visual direction without swapping HID commands.
private val uprightPreviousAction = RemoteAction.Consumer("Previous track", Icons.Default.SkipNext, ConsumerControl.PREVIOUS_TRACK)
private val uprightNextAction = RemoteAction.Consumer("Next track", Icons.Default.SkipPrevious, ConsumerControl.NEXT_TRACK)
// Esc leaves fullscreen or a slideshow; F toggles fullscreen in YouTube, Netflix and VLC.
private val escapeFullscreenAction = RemoteAction.TapOrHold(
    tap = RemoteAction.Key("Esc", null, KeyboardLayouts.KEY_ESC),
    hold = RemoteAction.Key("Fullscreen", null, KeyboardLayouts.KEY_F, shortLabel = "F"),
)
// Playback speed: < > in YouTube on a tap, [ ] in VLC and mpv on a long-press.
private val slowerAction = RemoteAction.TapOrHold(
    tap = RemoteAction.Key("Slower", null, KeyboardLayouts.KEY_COMMA, shortLabel = "<", shift = true),
    hold = RemoteAction.Key("Slower, VLC", null, KeyboardLayouts.KEY_LBRACKET, shortLabel = "["),
)
private val fasterAction = RemoteAction.TapOrHold(
    tap = RemoteAction.Key("Faster", null, KeyboardLayouts.KEY_PERIOD, shortLabel = ">", shift = true),
    hold = RemoteAction.Key("Faster, VLC", null, KeyboardLayouts.KEY_RBRACKET, shortLabel = "]"),
)
private val commaAction = RemoteAction.Key("Comma", null, KeyboardLayouts.KEY_COMMA, shortLabel = ",")
private val periodAction = RemoteAction.Key("Period", null, KeyboardLayouts.KEY_PERIOD, shortLabel = ".")
private val homeAction = RemoteAction.Key("Home", null, KeyboardLayouts.KEY_HOME)
private val endAction = RemoteAction.Key("End", null, KeyboardLayouts.KEY_END)
private val pageUpAction = RemoteAction.Key("PgUp", null, KeyboardLayouts.KEY_PAGEUP)
private val pageDownAction = RemoteAction.Key("PgDn", null, KeyboardLayouts.KEY_PAGEDOWN)
// B and W blank a running slideshow to black or white in PowerPoint, Impress, Slides and Keynote.
private val blankScreenAction = RemoteAction.TapOrHold(
    tap = RemoteAction.Key("Black screen", null, KeyboardLayouts.KEY_B, shortLabel = "B"),
    hold = RemoteAction.Key("White screen", null, KeyboardLayouts.KEY_W, shortLabel = "W"),
)
private val upArrowAction = RemoteAction.Key("Up", Icons.Default.KeyboardArrowUp, KeyboardLayouts.KEY_UP)
private val downArrowAction = RemoteAction.Key("Down", Icons.Default.KeyboardArrowDown, KeyboardLayouts.KEY_DOWN)
private val leftArrowAction = RemoteAction.Key("Left", Icons.AutoMirrored.Filled.KeyboardArrowLeft, KeyboardLayouts.KEY_LEFT)
private val rightArrowAction = RemoteAction.Key("Right", Icons.AutoMirrored.Filled.KeyboardArrowRight, KeyboardLayouts.KEY_RIGHT)

// Home and PgUp sit over End and PgDn, as on a full-size keyboard.
private val landscapeMediaRows = listOf(
    listOf(muteAction, volumeUpAction, volumeDownAction),
    listOf(previousAction, playAction, nextAction),
    listOf(escapeFullscreenAction, homeAction, pageUpAction),
    listOf(blankScreenAction, endAction, pageDownAction),
)

// Read with the -90 degree upright rotation: the holder sees Home and PgUp over End and PgDn,
// as on a full-size keyboard.
private val portraitHeldMediaRows = listOf(
    listOf(muteAction, volumeUpAction, volumeDownAction),
    listOf(uprightPreviousAction, escapeFullscreenAction, blankScreenAction),
    listOf(playAction, pageUpAction, pageDownAction),
    listOf(uprightNextAction, homeAction, endAction),
)

@Composable
private fun MultimediaUprightBar(
    btManager: BluetoothKeyboardManager,
    onClose: () -> Unit,
    launchMode: Int,
    onModeChange: (Int) -> Unit,
    sharedPrefs: SharedPreferences,
    isConnected: Boolean,
    sensitivity: Float,
    onSensitivityChange: () -> Unit,
    scrollSensitivity: Float,
    onScrollSensitivityChange: () -> Unit,
    isVibrationEnabled: Boolean,
    onToggleVibration: () -> Unit,
    isTrackpadVisible: Boolean,
    onToggleTrackpad: () -> Unit,
    rotateMenu: Boolean,
) {
    Column(
        modifier = Modifier
            .width(42.dp)
            .fillMaxHeight()
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        UprightToolbarButton("Close Multimedia", onClose) {
            Icon(Icons.Default.Close, null, tint = Color.White, modifier = Modifier.size(13.dp))
        }
        InputModeDropdown(
            currentMode = launchMode,
            sharedPrefs = sharedPrefs,
            onModeSelected = onModeChange,
            rotateContent = rotateMenu,
        ) { openMenu ->
            UprightToolbarButton("Switch input mode", openMenu) {
                Icon(InputMode.MEDIA_PRESENTATION.icon, null, tint = Color.White, modifier = Modifier.size(13.dp))
            }
        }
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (isConnected) Color(0xFF39FF14) else Color(0xFFFF9800))
                .semantics {
                    contentDescription = if (isConnected) "Host connected" else "Host offline"
                },
        )
        ReconnectHostButton(btManager, iconOnly = true)
        Spacer(Modifier.weight(1f))
        UprightToolbarButton(
            if (isTrackpadVisible) "Hide trackpad" else "Show trackpad",
            onToggleTrackpad,
        ) {
            Icon(
                Icons.Default.Mouse,
                null,
                tint = if (isTrackpadVisible) Color.White else Color.White.copy(alpha = 0.4f),
                modifier = Modifier.size(13.dp),
            )
        }
        UprightToolbarButton("Pointer sensitivity ${sensitivity}x", onSensitivityChange) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Speed, null, tint = Color.White, modifier = Modifier.size(10.dp))
                Text("${sensitivity}x", color = Color.White, fontSize = 7.sp, fontWeight = FontWeight.Bold)
            }
        }
        UprightToolbarButton("Scroll sensitivity ${scrollSensitivity}x", onScrollSensitivityChange) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.KeyboardArrowUp, null, tint = Color.White, modifier = Modifier.size(10.dp))
                Text("${scrollSensitivity}x", color = Color.White, fontSize = 7.sp, fontWeight = FontWeight.Bold)
            }
        }
        UprightToolbarButton(
            if (isVibrationEnabled) "Disable vibration" else "Enable vibration",
            onToggleVibration,
        ) {
            Icon(
                Icons.Default.Vibration,
                null,
                tint = if (isVibrationEnabled) Color.White else Color.White.copy(alpha = 0.4f),
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

@Composable
private fun UprightToolbarButton(
    description: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .size(34.dp)
            .semantics { contentDescription = description },
        color = Color.White.copy(alpha = 0.15f),
        shape = RoundedCornerShape(7.dp),
    ) {
        Box(
            modifier = Modifier.graphicsLayer { rotationZ = -90f },
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MultimediaTopBar(
    btManager: BluetoothKeyboardManager,
    onClose: () -> Unit,
    launchMode: Int,
    onModeChange: (Int) -> Unit,
    sharedPrefs: SharedPreferences,
    isConnected: Boolean,
    sensitivity: Float,
    onSensitivityChange: () -> Unit,
    scrollSensitivity: Float,
    onScrollSensitivityChange: () -> Unit,
    isVibrationEnabled: Boolean,
    onToggleVibration: () -> Unit,
    isTrackpadVisible: Boolean,
    onToggleTrackpad: () -> Unit,
) {
    val context = LocalContext.current
    val connectedDevice by btManager.connectedDevice.collectAsState()
    val hostLabel = try {
        (connectedDevice ?: btManager.getReconnectTarget())?.name ?: "No Host"
    } catch (_: SecurityException) {
        if (isConnected) "Host" else "No Host"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ToolbarPill(onClick = onClose) {
                Icon(Icons.Default.Close, "Close", tint = Color.White, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
                Text("Close", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            InputModeDropdown(
                currentMode = launchMode,
                sharedPrefs = sharedPrefs,
                onModeSelected = onModeChange,
            ) { openMenu ->
                ToolbarPill(
                    onClick = openMenu,
                    onLongClick = {
                        context.startActivity(Intent(context, QuickCycleActivity::class.java))
                    },
                ) {
                    Icon(InputMode.MEDIA_PRESENTATION.icon, "Switch mode", tint = Color.White, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Multimedia", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Icon(Icons.Default.ArrowDropDown, null, tint = Color.White, modifier = Modifier.size(14.dp))
                }
            }
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (isConnected) Color(0xFF39FF14) else Color(0xFFFF9800))
                    .semantics {
                        contentDescription = if (isConnected) "Host connected" else "Host offline"
                    },
            )
            Text(hostLabel, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            ReconnectHostButton(btManager, iconOnly = true)
            if (!isConnected) {
                Text("[offline]", color = Color.White.copy(alpha = 0.45f), fontSize = 9.sp)
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ToolbarPill(onClick = onToggleTrackpad) {
                Icon(
                    Icons.Default.Mouse,
                    null,
                    tint = if (isTrackpadVisible) Color.White else Color.White.copy(alpha = 0.4f),
                    modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    if (isTrackpadVisible) "Hide trackpad" else "Trackpad",
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            ToolbarPill(onClick = onSensitivityChange) {
                Icon(Icons.Default.Speed, "Sensitivity", tint = Color.White, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
                Text("${sensitivity}x", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
            ToolbarPill(onClick = onScrollSensitivityChange) {
                Icon(Icons.Default.KeyboardArrowUp, "Scroll speed", tint = Color.White, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
                Text("${scrollSensitivity}x", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
            Surface(
                onClick = onToggleVibration,
                modifier = Modifier.size(28.dp),
                shape = RoundedCornerShape(8.dp),
                color = Color.White.copy(alpha = 0.15f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Vibration,
                        if (isVibrationEnabled) "Disable vibration" else "Enable vibration",
                        tint = if (isVibrationEnabled) Color.White else Color.White.copy(alpha = 0.4f),
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
    }
}

private const val KEY_APPLICATION = 0x65
private const val CORNER_BUTTON_SCALE = 0.6f
private const val LONG_PRESS_MILLIS = 450L
internal const val MULTIMEDIA_TRACKPAD_VISIBLE_PREFERENCE = "multimedia_trackpad_visible"
private const val POSTURE_ENTRY_DEGREES = 20
internal const val POSTURE_STABILITY_MILLIS = 500L

private tailrec fun Context.findRemoteVolumeKeyHost(): RemoteVolumeKeyHost? = when (this) {
    is RemoteVolumeKeyHost -> this
    is ContextWrapper -> if (baseContext === this) null else baseContext.findRemoteVolumeKeyHost()
    else -> null
}

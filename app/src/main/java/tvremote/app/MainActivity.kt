package tvremote.app

import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.Surface
import androidx.compose.material3.ripple
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.offset
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import tvremote.Button
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    private lateinit var discovery: Discovery

    override fun onCreate(b: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(b)
        Remote.init(this)
        discovery = Discovery(this)
        setContent { Theme { RemoteScreen() } }
    }

    // Hold the connection only while the remote is on screen.
    override fun onStart() {
        super.onStart()
        discovery.start()
        Remote.connect()
    }

    override fun onStop() {
        discovery.stop()
        Remote.disconnect()
        super.onStop()
    }
}

@Composable
private fun Theme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoteScreen() {
    val connected = Remote.link == Link.Connected
    Scaffold(topBar = { CenterAlignedTopAppBar(title = { TvPicker() }) }) { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Status()
            Spacer(Modifier.weight(1f))
            Column(
                Modifier.alpha(if (connected) 1f else 0.4f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                TouchPad()
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    RoundButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", Button.Menu)
                    // Holding the TV button opens Control Center, as on the Siri Remote.
                    RoundButton(Icons.Default.Tv, "Home (hold for Control Center)", Button.Home, holdable = true)
                    RoundButton(Icons.Default.PlayArrow, "Play/Pause", Button.PlayPause)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    RoundButton(Icons.AutoMirrored.Filled.VolumeDown, "Volume down", Button.VolumeDown)
                    RoundButton(Icons.Default.PowerSettingsNew, "Sleep", Button.Sleep)
                    RoundButton(Icons.AutoMirrored.Filled.VolumeUp, "Volume up", Button.VolumeUp)
                }
            }
            Spacer(Modifier.weight(1f))
        }
    }
    Remote.pinPrompt?.let { PinDialog(it) }
}

/** Springy press: squashes under the finger and overshoots on release. */
private val bounce = spring<Float>(dampingRatio = 0.4f, stiffness = Spring.StiffnessMediumLow)

@Composable
private fun TvPicker() {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text(Remote.selected?.name ?: if (Remote.tvs.isEmpty()) "Looking for Apple TVs…" else "Choose an Apple TV",
                style = MaterialTheme.typography.titleLarge)
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (Remote.tvs.isEmpty()) DropdownMenuItem(text = { Text("Searching this Wi-Fi network…") }, onClick = {}, enabled = false)
            Remote.tvs.forEach { tv ->
                DropdownMenuItem(
                    text = { Text(tv.name) },
                    leadingIcon = { Icon(Icons.Default.Tv, contentDescription = null) },
                    onClick = { open = false; Remote.select(tv) },
                )
            }
        }
    }
}

@Composable
private fun Status() {
    val text = when (val l = Remote.link) {
        Link.Connected -> null
        Link.Connecting -> "Connecting…"
        Link.Pairing -> "Pairing — enter the PIN shown on your TV"
        is Link.Failed -> l.message
        Link.Idle -> if (Remote.selected == null) null else "Not connected. Tap the pad to reconnect."
    }
    Box(Modifier.height(48.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (Remote.link == Link.Connecting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else if (text != null) Text(text, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium,
            color = if (Remote.link is Link.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Siri-Remote-style pad. Tap an edge for an arrow or the middle for Select (hold it for a
 * context menu); swipe in any direction to move, a long swipe repeating about once per
 * finger-width of travel. The pad squashes under the finger and the center disc nudges
 * toward each move.
 */
@Composable
private fun TouchPad() {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val step = with(density) { 64.dp.toPx() }
    val flick = with(density) { 24.dp.toPx() }
    val nudgeBy = with(density) { 14.dp.toPx() }
    val followMax = with(density) { 22.dp.toPx() }
    var touching by remember { mutableStateOf(false) }
    val padScale by animateFloatAsState(if (touching) 0.96f else 1f, bounce, label = "pad")
    val nudge = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val center = remember { Animatable(1f) }

    // Taps animate the disc; during a swipe it follows the finger instead (see below).
    fun send(b: Button, long: Boolean = false, animate: Boolean = true) {
        if (long) Remote.longPress(b) else Remote.press(b)
        if (animate) scope.launch {
            if (b == Button.Select) {
                center.snapTo(0.82f)
                center.animateTo(1f, bounce)
            } else {
                nudge.nudgeTo(b, nudgeBy)
                nudge.animateTo(Offset.Zero, spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessMedium))
            }
        }
    }

    BoxWithConstraints {
        val size = minOf(maxWidth, 320.dp)
        Box(
            Modifier.size(size)
                .graphicsLayer { scaleX = padScale; scaleY = padScale }
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                .pointerInput(Unit) {
                    fun at(pos: Offset): Button {
                        val d = pos - Offset(this.size.width / 2f, this.size.height / 2f)
                        return if (d.getDistance() < this.size.width * 0.19f) Button.Select else direction(d)
                    }
                    detectTapGestures(
                        onPress = {
                            touching = true
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            tryAwaitRelease()
                            touching = false
                        },
                        onTap = { send(at(it)) },
                        onLongPress = {
                            if (at(it) == Button.Select) {
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                send(Button.Select, long = true)
                            }
                        },
                    )
                }
                .pointerInput(Unit) {
                    var travel = Offset.Zero // since the last step press
                    var total = Offset.Zero // since the finger went down; drives the disc
                    var moved = false
                    val settle = {
                        touching = false
                        scope.launch { nudge.animateTo(Offset.Zero, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow)) }
                    }
                    detectDragGestures(
                        onDragStart = { travel = Offset.Zero; total = Offset.Zero; moved = false; touching = true },
                        onDragEnd = {
                            if (!moved && travel.getDistance() > flick) {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                send(direction(travel), animate = false)
                            }
                            settle()
                        },
                        onDragCancel = { settle() },
                    ) { change, delta ->
                        change.consume()
                        travel += delta
                        total += delta
                        val follow = total * 0.25f
                        scope.launch { nudge.snapTo(if (follow.getDistance() > followMax) follow * (followMax / follow.getDistance()) else follow) }
                        while (abs(travel.x) >= step || abs(travel.y) >= step) {
                            val b = direction(travel)
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            send(b, animate = false)
                            moved = true
                            travel -= unit(b) * step
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            val tint = MaterialTheme.colorScheme.onSurfaceVariant
            Icon(Icons.Default.KeyboardArrowUp, null, Modifier.align(Alignment.TopCenter).padding(top = 16.dp).size(32.dp), tint)
            Icon(Icons.Default.KeyboardArrowDown, null, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp).size(32.dp), tint)
            Icon(Icons.Default.KeyboardArrowLeft, null, Modifier.align(Alignment.CenterStart).padding(start = 16.dp).size(32.dp), tint)
            Icon(Icons.Default.KeyboardArrowRight, null, Modifier.align(Alignment.CenterEnd).padding(end = 16.dp).size(32.dp), tint)
            Box(
                Modifier.offset { IntOffset(nudge.value.x.toInt(), nudge.value.y.toInt()) }
                    .graphicsLayer { scaleX = center.value; scaleY = center.value }
                    .size(size * 0.38f)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
            )
        }
    }
}

private suspend fun Animatable<Offset, *>.nudgeTo(direction: Button, by: Float) = snapTo(unit(direction) * by)

private fun unit(b: Button) = when (b) {
    Button.Left -> Offset(-1f, 0f)
    Button.Right -> Offset(1f, 0f)
    Button.Up -> Offset(0f, -1f)
    else -> Offset(0f, 1f)
}

private fun direction(d: Offset) =
    if (abs(d.x) > abs(d.y)) (if (d.x > 0) Button.Right else Button.Left)
    else (if (d.y > 0) Button.Down else Button.Up)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RoundButton(icon: ImageVector, label: String, button: Button, holdable: Boolean = false) {
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.86f else 1f, bounce, label = "button")
    if (pressed) androidx.compose.runtime.LaunchedEffect(Unit) { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.size(64.dp).graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .combinedClickable(
                interactionSource = interaction,
                indication = ripple(bounded = true),
                onClickLabel = label,
                onClick = { Remote.press(button) },
                onLongClick = if (holdable) ({
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    Remote.longPress(button)
                }) else null,
            ),
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = label, modifier = Modifier.size(28.dp)) }
    }
}

@Composable
private fun PinDialog(prompt: PinPrompt) {
    var pin by remember(prompt) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { prompt.reply("") },
        title = { Text("Pair with ${prompt.tv}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Enter the 4-digit code shown on your TV.")
                OutlinedTextField(
                    value = pin,
                    onValueChange = { if (it.length <= 4 && it.all(Char::isDigit)) pin = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
            }
        },
        confirmButton = { TextButton(onClick = { prompt.reply(pin) }, enabled = pin.length == 4) { Text("Pair") } },
        dismissButton = { TextButton(onClick = { prompt.reply("") }) { Text("Cancel") } },
    )
}

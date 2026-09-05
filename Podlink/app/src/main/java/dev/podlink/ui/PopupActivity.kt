package dev.podlink.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.podlink.R
import dev.podlink.service.PodsRepo
import dev.podlink.service.PodsService
import dev.podlink.ui.components.BatteryPill
import dev.podlink.ui.components.PodsArt
import dev.podlink.ui.components.StatusChips
import dev.podlink.ui.theme.PodlinkTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * iOS-style bottom card shown when the case opens or the buds connect.
 * Auto-dismisses after `duration` seconds, on tap outside, on swipe down, or when the lid closes.
 */
class PopupActivity : ComponentActivity() {

    private val dismissReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val duration = intent.getIntExtra("duration", 6).coerceIn(2, 30)
        val lock = intent.getBooleanExtra("lock", true)
        val theme = intent.getStringExtra("theme") ?: "system"
        val dynamic = intent.getBooleanExtra("dynamic", true)
        if (lock && Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        val filter = IntentFilter(PodsService.ACTION_POPUP_DISMISS)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(dismissReceiver, filter, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(dismissReceiver, filter)

        setContent {
            PodlinkTheme(theme = theme, dynamic = dynamic) {
                val s by PodsRepo.state.collectAsState()
                var visible by remember { mutableStateOf(false) }
                var progress by remember { mutableStateOf(1f) }
                val offset = remember { Animatable(0f) }
                val scope = rememberCoroutineScope()
                fun close() { scope.launch { visible = false; delay(220); finish() } }
                // iOS-style: the case appears closed and the lid springs open a beat later.
                var lidAnim by remember { mutableStateOf(false) }
                LaunchedEffect(s.lidOpen) { lidAnim = false; delay(350); lidAnim = s.lidOpen || s.connected }
                LaunchedEffect(Unit) {
                    visible = true
                    val steps = duration * 20
                    for (i in steps downTo 0) { progress = i / steps.toFloat(); delay(50) }
                    close()
                }
                Box(
                    Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { close() },
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    AnimatedVisibility(
                        visible,
                        enter = slideInVertically(spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn(),
                        exit = slideOutVertically(tween(200)) { it } + fadeOut(),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(32.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            tonalElevation = 3.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                                .navigationBarsPadding()
                                .offset { IntOffset(0, offset.value.roundToInt()) }
                                .pointerInput(Unit) {
                                    detectVerticalDragGestures(
                                        onDragEnd = { if (offset.value > 120f) close() else scope.launch { offset.animateTo(0f, spring()) } },
                                        onVerticalDrag = { _, dy -> scope.launch { offset.snapTo((offset.value + dy).coerceAtLeast(0f)) } },
                                    )
                                }
                                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
                        ) {
                            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Box(Modifier.width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).padding(0.dp)) {
                                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.outline) {}
                                }
                                Text(s.deviceName ?: s.model.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Text(
                                    stringResource(if (s.connected) R.string.popup_connected else if (s.lidOpen) R.string.lid_open else R.string.status_not_connected),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                AnimatedVisibility(visible, enter = scaleIn(spring(Spring.DampingRatioMediumBouncy)) + fadeIn()) {
                                    PodsArt(s.model, s.leftInEar || s.isHeadphones, s.rightInEar || s.isHeadphones, lidAnim, 160.dp, charging = s.leftCharging || s.rightCharging || s.caseCharging, shineTrigger = 1)
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
                                    if (s.isHeadphones) Staggered(visible, 0) { BatteryPill(s.single, s.leftCharging, s.model.label, compact = true) }
                                    else {
                                        Staggered(visible, 0) { BatteryPill(s.left, s.leftCharging, stringResource(R.string.left), compact = true) }
                                        Staggered(visible, 1) { BatteryPill(s.case, s.caseCharging, stringResource(R.string.case_), compact = true) }
                                        Staggered(visible, 2) { BatteryPill(s.right, s.rightCharging, stringResource(R.string.right), compact = true) }
                                    }
                                }
                                StatusChips(s)
                                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)))
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(dismissReceiver) }
        super.onDestroy()
    }
}

@Composable
private fun Staggered(visible: Boolean, index: Int, content: @Composable () -> Unit) {
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(visible) { if (visible) { delay(80L * index); show = true } else show = false }
    AnimatedVisibility(show, enter = slideInVertically(spring(Spring.DampingRatioMediumBouncy)) { it / 2 } + fadeIn(), exit = fadeOut()) { content() }
}

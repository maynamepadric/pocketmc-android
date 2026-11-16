package com.pocketmc.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput

// Basınca hafifçe küçülüp yaylanan buton efekti
fun Modifier.pressScale(): Modifier = composed {
    var down by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (down) 0.93f else 1f, spring(dampingRatio = 0.45f, stiffness = 450f), label = "press")
    this.pointerInput(Unit) {
        awaitEachGesture { awaitFirstDown(requireUnconsumed = false); down = true; waitForUpOrCancellation(); down = false }
    }.graphicsLayer { scaleX = s; scaleY = s }
}

// Aynı pakette olduğu için uygulamadaki tüm Button/OutlinedButton/TextButton çağrıları otomatik bu sürümleri kullanır
@Composable fun Button(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    colors: androidx.compose.material3.ButtonColors = androidx.compose.material3.ButtonDefaults.buttonColors(), content: @Composable RowScope.() -> Unit) =
    androidx.compose.material3.Button(onClick, modifier.pressScale(), enabled = enabled, colors = colors, content = content)

@Composable fun OutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) =
    androidx.compose.material3.OutlinedButton(onClick, modifier.pressScale(), enabled = enabled, content = content)

@Composable fun TextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) =
    androidx.compose.material3.TextButton(onClick, modifier.pressScale(), enabled = enabled, content = content)

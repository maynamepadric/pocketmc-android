@file:OptIn(ExperimentalMaterial3Api::class)
package com.pocketmc.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val CardShape = RoundedCornerShape(10.dp)

// Çerçevenin arkasına, bulanıklaştırılmış arka plan resminin ilgili bölümünü çizer (buzlu cam)
fun Modifier.backdrop(): Modifier = composed {
    val blur = Ui.blurred
    if (!Ui.cardBlur || blur == null || Ui.bg == null) this
    else {
        var pos by remember { mutableStateOf(Offset.Zero) }
        this.clip(CardShape).onGloballyPositioned { pos = it.positionInRoot() }.drawBehind {
            drawImage(blur, topLeft = Offset(-pos.x, -pos.y))
            drawRect(Color(0xFF0A100E).copy(alpha = Ui.dim))
        }
    }
}

// Uygulamadaki tüm Card çağrıları bu sürümleri kullanır: 10dp köşe, ince kenarlık
@Composable fun Card(modifier: Modifier = Modifier, colors: CardColors? = null, content: @Composable ColumnScope.() -> Unit) =
    androidx.compose.material3.Card(modifier.backdrop(), shape = CardShape, colors = colors ?: CardDefaults.cardColors(),
        border = BorderStroke(1.dp, Line), content = content)

@Composable fun Card(modifier: Modifier = Modifier, onClick: () -> Unit, colors: CardColors? = null, content: @Composable ColumnScope.() -> Unit) =
    androidx.compose.material3.Card(onClick, modifier.backdrop(), shape = CardShape, colors = colors ?: CardDefaults.cardColors(),
        border = BorderStroke(1.dp, Line), content = content)

package com.nekoplayer.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text as Material3Text

@Composable
internal fun SubtitleCaptionText(
    text: AnnotatedString,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    shadow: Shadow?,
    outlineEnabled: Boolean,
    modifier: Modifier = Modifier,
    foregroundModifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Center,
    onTextLayout: (TextLayoutResult) -> Unit = {},
) {
    val density = LocalDensity.current
    val outlineWidth = with(density) {
        (fontSize * 0.04f).toPx().coerceAtLeast(0.75.dp.toPx())
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        if (outlineEnabled) {
            Material3Text(
                text = text,
                modifier = Modifier.clearAndSetSemantics {},
                color = Color.Black,
                fontSize = fontSize,
                style = MaterialTheme.typography.bodyMedium.copy(
                    shadow = null,
                    drawStyle = Stroke(width = outlineWidth),
                ),
                textAlign = textAlign,
                lineHeight = lineHeight,
            )
        }
        Material3Text(
            text = text,
            modifier = foregroundModifier,
            color = Color.White,
            fontSize = fontSize,
            style = MaterialTheme.typography.bodyMedium.copy(shadow = shadow),
            textAlign = textAlign,
            lineHeight = lineHeight,
            onTextLayout = onTextLayout,
        )
    }
}

package io.embodit.signhands.ui.panels

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.isSpecified
import io.embodit.signhands.platform.SignLog

/**
 * v0.1.3 (profile D13, no text cut off by an ellipsis): text in a fixed slot (word cells, word titles) shrinks in steps
 * down to [minScale] until it fits [maxLines]; drawn once settled. Text that still does not fit logs
 * `OVERFLOW <id>` (QA runs every state in the longest language and greps the log).
 */
@Composable
internal fun FitText(text: String, style: TextStyle, color: Color, id: String, modifier: Modifier = Modifier, maxLines: Int = 1, minScale: Float = 0.7f, align: TextAlign? = null) {
    var scale by remember(text, style, maxLines) { mutableFloatStateOf(1f) }
    var settled by remember(text, style, maxLines) { mutableStateOf(false) }
    val sized =
        if (scale < 1f) style.copy(fontSize = style.fontSize * scale, lineHeight = if (style.lineHeight.isSpecified) style.lineHeight * scale else style.lineHeight)
        else style
    BasicText(
        text = text,
        modifier = modifier.drawWithContent { if (settled) drawContent() },
        style = sized.copy(color = color, textAlign = align ?: sized.textAlign),
        overflow = TextOverflow.Ellipsis,
        maxLines = maxLines,
        onTextLayout = { r ->
            when {
                !r.hasVisualOverflow -> settled = true
                scale > minScale -> scale = maxOf(minScale, scale - 0.06f)
                else -> {
                    settled = true
                    SignLog.i("OVERFLOW $id '$text'")
                }
            }
        },
    )
}

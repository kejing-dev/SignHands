package io.embodit.signhands.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import io.embodit.signhands.domain.LetterBook
import io.embodit.signhands.domain.Quiz
import io.embodit.signhands.domain.Reveal
import io.embodit.signhands.domain.Screen
import io.embodit.signhands.domain.UiState
import io.embodit.signhands.domain.Lang
import io.embodit.signhands.ui.theme.LocalCopy
import io.embodit.signhands.ui.theme.SignColors

/**
 * The hand label (200 x 88 dp, design `panels.handLabel`): the big letter (48 dp) and one line beside the wrist,
 * white with a dark outline on a faint dark pill (readable over passthrough: >= 4.5:1 on the pill). In the quiz a
 * thin 10 s countdown ring sits round the letter. "✓ Right" turns jade.
 */
@Composable
fun HandLabel(state: UiState, book: LetterBook) {
    val c = LocalCopy.current
    val letter = state.letter
    val line: String? =
        when (state.screen) {
            Screen.S02 -> c.raiseShort(state.variant == "b")
            Screen.S06 -> c.hint(state.hint) ?: if (state.sign != null) c.signShort else c.fitHint.take(if (c.en) 30 else 12)
            Screen.S07 -> c.right
            Screen.S08 -> letter?.let { book.letters[it]?.tip(if (c.en) Lang.EN else Lang.ZH) }
            Screen.S09 -> null
            Screen.S10 ->
                when (state.quiz?.reveal) {
                    Reveal.RIGHT -> c.right
                    Reveal.WRONG -> c.missPart(state.quiz.missKey)?.let { c.almostPart(it) } ?: c.likeThis
                    else -> c.compare
                }
            else -> null
        }
    val wordDone = state.wordDone
    val jade = state.screen == Screen.S07 || (state.screen == Screen.S10 && state.quiz?.reveal == Reveal.RIGHT)
    val ink = if (jade) SignColors.Jade else SignColors.LabelInk
    val outline = TextStyle(shadow = Shadow(color = SignColors.LabelShadow, offset = Offset(0f, 0f), blurRadius = 6f))
    Box(modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).background(Color(0xCC14121A)).padding(horizontal = 12.dp, vertical = 6.dp), contentAlignment = Alignment.CenterStart) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val sign = state.sign
            val big = wordDone?.let { "$it ✓" } ?: letter ?: sign?.en ?: ""
            // v0.1.1: no quiz countdown ring (no time pressure, no penalty)
            if (sign != null && wordDone == null) {
                // v0.1.3: a word sign's name is longer than a letter
                Box(modifier = Modifier.width(96.dp).height(64.dp), contentAlignment = Alignment.CenterStart) {
                    FitText(big, TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold).merge(outline), ink, id = "label/word", maxLines = 2, minScale = 0.6f)
                }
            } else {
                Box(modifier = Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                    Text(big, fontSize = if (wordDone != null) 22.sp else 48.sp, fontWeight = FontWeight.SemiBold, color = if (wordDone != null) SignColors.Jade else ink, style = outline, maxLines = 1)
                }
            }
            if (line != null && wordDone == null) {
                Column {
                    Text(line, style = PicoTheme.typography.bodyLarge.merge(outline), fontWeight = FontWeight.SemiBold, color = ink, maxLines = 2)
                }
            }
        }
    }
}

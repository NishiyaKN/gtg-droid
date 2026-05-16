package com.gtg.app.presentation.common

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Texto que reduz o `fontSize` até caber na largura disponível, com piso em
 * [minFontSize]. Útil para countdowns e contadores cujo tamanho do conteúdo
 * varia em runtime (ex: "1:00:00" cabe, "12:34:56" não) e onde o overflow
 * silencioso seria pior que perder hierarquia visual.
 *
 * **Como mede:** usa [rememberTextMeasurer] em busca binária (até 8
 * iterações) entre [minFontSize] e o `fontSize` declarado em [style]. O
 * resultado é cacheado por `remember` chaveado por (texto, largura, style,
 * limites) — uma mudança de texto a cada segundo (countdown) recalcula uma
 * única vez por tick, não a cada frame.
 *
 * **Quando NÃO usar:** texto estático (use [Text] direto), ou conteúdo que
 * pode quebrar em múltiplas linhas (use [AdaptiveText] com `maxLines > 1`).
 *
 * **Pré-requisito:** [style] precisa ter `fontSize` definido (não
 * [TextUnit.Unspecified]). Esse é o teto da busca.
 */
@Composable
fun AutoShrinkText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    minFontSize: TextUnit = 24.sp,
    maxLines: Int = 1,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
) {
    // require: style.fontSize.value retorna NaN para TextUnit.Unspecified. NaN
    // bypassa o guard maxSp<=minSp em shrinkToFit (comparações NaN sempre false),
    // propagando NaN para TextMeasurer — UB. Falha em tempo de composição é
    // melhor que UB silencioso.
    require(style.fontSize != TextUnit.Unspecified) {
        "AutoShrinkText requires style.fontSize to be defined (not Unspecified)"
    }
    val measurer = rememberTextMeasurer()

    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        val maxWidthPx = constraints.maxWidth
        val effectiveStyle = remember(text, maxWidthPx, style, minFontSize, maxLines) {
            shrinkToFit(
                measurer = measurer,
                text = text,
                style = style,
                maxWidthPx = maxWidthPx,
                minFontSize = minFontSize,
                maxLines = maxLines,
            )
        }
        Text(
            text = text,
            style = effectiveStyle,
            color = color,
            fontWeight = fontWeight,
            textAlign = textAlign,
            maxLines = maxLines,
            overflow = TextOverflow.Visible,
            softWrap = false,
        )
    }
}

/**
 * Busca binária pela maior `fontSize` na faixa `[minFontSize, style.fontSize]`
 * tal que `text` caiba em `maxWidthPx` sem `didOverflowWidth`. 8 iterações
 * dão granularidade de ~0.2sp em uma faixa de 56sp — visualmente
 * imperceptível.
 *
 * Se `maxWidthPx` for `Int.MAX_VALUE` (BoxWithConstraints sem pai bounded),
 * retorna `style` inalterado — não há nada a encolher.
 */
private fun shrinkToFit(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    maxWidthPx: Int,
    minFontSize: TextUnit,
    maxLines: Int,
): TextStyle {
    if (maxWidthPx <= 0 || maxWidthPx == Int.MAX_VALUE) return style
    val maxSp = style.fontSize.value
    val minSp = minFontSize.value
    if (maxSp <= minSp) return style.copy(fontSize = minFontSize)

    fun fitsAt(sizeSp: Float): Boolean {
        val layout = measurer.measure(
            text = AnnotatedString(text),
            style = style.copy(fontSize = sizeSp.sp),
            maxLines = maxLines,
            softWrap = false,
        )
        return !layout.didOverflowWidth && layout.size.width <= maxWidthPx
    }

    if (fitsAt(maxSp)) return style

    var low = minSp
    var high = maxSp
    var best = minSp
    repeat(8) {
        val mid = (low + high) / 2f
        if (fitsAt(mid)) {
            best = mid
            low = mid
        } else {
            high = mid
        }
    }
    return style.copy(fontSize = best.sp)
}

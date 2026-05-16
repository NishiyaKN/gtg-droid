package com.gtg.app.presentation.common

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit

/**
 * Wrapper sobre [Text] que aplica defaults defensivos para conteúdo dinâmico
 * em Rows/Cards apertados: [TextOverflow.Ellipsis] sempre e `maxLines=1` por
 * default (sobrescrevível).
 *
 * Use para nomes de exercícios, ringtones, calendários, eventos importados —
 * qualquer string cujo comprimento o app não controla (entrada do usuário ou
 * de fontes externas) e que aparece em layouts horizontais com largura
 * limitada.
 *
 * Para texto que pode crescer em altura mas precisa ainda truncar com "…":
 * passar `maxLines = 2` (ou mais). `softWrap` é derivado: só permite quebra
 * de linha quando `maxLines > 1`.
 */
@Composable
fun AdaptiveText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    maxLines: Int = 1,
    textAlign: TextAlign? = null,
) {
    Text(
        text = text,
        modifier = modifier,
        style = style,
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        softWrap = maxLines > 1,
        textAlign = textAlign,
    )
}

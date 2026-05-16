package com.gtg.app.presentation.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Typography tokens do GtG.
 *
 * Estratégia mínima por escolha: NÃO sobrescrevemos slots padrão do M3
 * (displayLarge, headlineSmall, etc) — isso evitaria regressão visual em
 * Button/TopAppBar/Card defaults. Apenas expomos **extension properties**
 * de [Typography] para os sites com finding de responsividade onde o tamanho
 * precisa ser estável e o lineHeight precisa cobrir font-scale XL do sistema
 * (1.3×–1.5×).
 *
 * Uso típico:
 * ```
 * Text(text = "...", style = MaterialTheme.typography.titleExercise)
 * AutoShrinkText(text = "...", style = MaterialTheme.typography.countdownDisplay)
 * ```
 *
 * Todos os tokens definem `lineHeight ≈ fontSize × 1.2` — sem isso, Texts em
 * escala XL do sistema ficam com baseline cortada.
 */

/** Countdown principal da Home. Monospace para dígitos alinhados. */
val Typography.countdownDisplay: TextStyle
    @Composable get() = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 56.sp,
        lineHeight = 67.sp,
        fontWeight = FontWeight.Bold,
    )

/** Número de reps no AlarmActivity full-screen. */
val Typography.repsDisplay: TextStyle
    @Composable get() = TextStyle(
        fontSize = 72.sp,
        lineHeight = 86.sp,
        fontWeight = FontWeight.Bold,
    )

/** Nome do exercício em AlarmActivity. Aceita 1-2 linhas (passar `maxLines=2`). */
val Typography.titleExercise: TextStyle
    @Composable get() = TextStyle(
        fontSize = 20.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold,
    )

/** Rótulo "Início"/"Fim" sobre o WheelTimePicker. */
val Typography.wheelLabel: TextStyle
    @Composable get() = TextStyle(
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Bold,
    )

package com.gtg.app.presentation.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gtg.app.presentation.theme.wheelLabel

/**
 * Par de [WheelNumberPicker] (hora 0..23 e minuto 0..59) com rótulo.
 * Usado em Settings (janela de atividade) e Schedule (blocos de inatividade).
 *
 * `pickerWidth` controla a largura individual de cada roleta — em telas
 * estreitas o call site pode passar valores menores (~44.dp) para evitar
 * overflow quando 2 TimePickers + separador competem por <250dp.
 */
@Composable
fun WheelTimePicker(
    label: String,
    hour: Int,
    minute: Int,
    modifier: Modifier = Modifier,
    pickerWidth: Dp = 64.dp,
    onChange: (Int, Int) -> Unit,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AdaptiveText(
            text = label,
            style = MaterialTheme.typography.wheelLabel,
            color = Color.White.copy(alpha = 0.5f),
        )
        Spacer(modifier = Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            WheelNumberPicker(
                value = hour,
                max = 23,
                width = pickerWidth,
                onValueChange = { newHour -> onChange(newHour, minute) },
            )
            Text(
                text = ":",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            WheelNumberPicker(
                value = minute,
                max = 59,
                width = pickerWidth,
                onValueChange = { newMinute -> onChange(hour, newMinute) },
            )
        }
    }
}

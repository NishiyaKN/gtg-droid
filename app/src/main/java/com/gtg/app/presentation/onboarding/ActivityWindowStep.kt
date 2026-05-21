package com.gtg.app.presentation.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.gtg.app.R
import com.gtg.app.presentation.common.WheelNumberPicker
import com.gtg.app.presentation.theme.GtgPrimary

/**
 * Step 2 do onboarding — usuário define a primeira [ActivityWindow] como
 * intervalo simples de início e fim em horas inteiras. Refinamento de
 * minutos e dias da semana fica para o ScheduleScreen depois do onboarding.
 *
 * "Continuar" persiste e avança; "Pular" avança sem salvar.
 */
@Composable
internal fun ActivityWindowStep(
    onContinue: () -> Unit,
    onSkip: () -> Unit,
    onSkipAll: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    var startHour by remember { mutableIntStateOf(DEFAULT_START_HOUR) }
    var endHour by remember { mutableIntStateOf(DEFAULT_END_HOUR) }
    val valid = endHour > startHour

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.onboarding_window_title),
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )

        Text(
            text = stringResource(R.string.onboarding_window_body),
            color = Color.White.copy(alpha = 0.75f),
            fontSize = 14.sp,
            lineHeight = 20.sp,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.onboarding_window_start_label),
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 13.sp,
                )
                Spacer(modifier = Modifier.height(8.dp))
                WheelNumberPicker(
                    value = startHour,
                    max = 23,
                    onValueChange = { startHour = it },
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.onboarding_window_end_label),
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 13.sp,
                )
                Spacer(modifier = Modifier.height(8.dp))
                WheelNumberPicker(
                    value = endHour,
                    max = 23,
                    onValueChange = { endHour = it },
                )
            }
        }

        if (!valid) {
            Text(
                text = stringResource(R.string.onboarding_window_error_invalid),
                color = Color.Red.copy(alpha = 0.85f),
                fontSize = 13.sp,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                viewModel.saveActivityWindow(startHour, endHour)
                onContinue()
            },
            enabled = valid,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = GtgPrimary,
                contentColor = Color.White,
            ),
        ) {
            Text(
                text = stringResource(R.string.onboarding_button_continue),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        TextButton(onClick = onSkip) {
            Text(
                text = stringResource(R.string.onboarding_button_skip),
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 14.sp,
            )
        }

        TextButton(onClick = onSkipAll) {
            Text(
                text = stringResource(R.string.onboarding_button_skip_all),
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 13.sp,
            )
        }
    }
}

private const val DEFAULT_START_HOUR = 8
private const val DEFAULT_END_HOUR = 18

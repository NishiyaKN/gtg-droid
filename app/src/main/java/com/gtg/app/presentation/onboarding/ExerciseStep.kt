package com.gtg.app.presentation.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
 * Step 3 (final) do onboarding — primeiro [Exercise] da rotação.
 *
 * Nome obrigatório (não-vazio) + maxReps. targetPercentage usa default
 * conservador (50%) gerenciado pelo [OnboardingViewModel].
 *
 * "Concluir" persiste e finaliza; "Pular" finaliza sem salvar. Ambos
 * disparam o `onFinish` do host — `hasSeenOnboarding=true` + Home.
 */
@Composable
internal fun ExerciseStep(
    onFinish: () -> Unit,
    onSkip: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    var name by remember { mutableStateOf("") }
    var maxReps by remember { mutableIntStateOf(DEFAULT_MAX_REPS) }
    val canSubmit = name.trim().isNotEmpty() && maxReps > 0

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
            text = stringResource(R.string.onboarding_exercise_title),
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )

        Text(
            text = stringResource(R.string.onboarding_exercise_body),
            color = Color.White.copy(alpha = 0.75f),
            fontSize = 14.sp,
            lineHeight = 20.sp,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.onboarding_exercise_name_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = GtgPrimary,
                unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                focusedLabelColor = GtgPrimary,
                unfocusedLabelColor = Color.White.copy(alpha = 0.6f),
                cursorColor = GtgPrimary,
            ),
        )

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.onboarding_exercise_max_reps_label),
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 13.sp,
            )
            Spacer(modifier = Modifier.height(8.dp))
            WheelNumberPicker(
                value = maxReps,
                max = MAX_REPS_UPPER_BOUND,
                onValueChange = { maxReps = it },
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                viewModel.saveExercise(name, maxReps)
                onFinish()
            },
            enabled = canSubmit,
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
                text = stringResource(R.string.onboarding_button_finish),
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
    }
}

private const val DEFAULT_MAX_REPS = 10
private const val MAX_REPS_UPPER_BOUND = 50

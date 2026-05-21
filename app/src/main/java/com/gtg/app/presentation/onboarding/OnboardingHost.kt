package com.gtg.app.presentation.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Fluxo de onboarding de 3 steps disparado no primeiro launch da app.
 *
 * 1. Welcome — explica o que é GtG (texto + 2 botões).
 * 2. ActivityWindow — pede para o usuário criar a primeira janela.
 * 3. Exercise — pede o primeiro exercício para a rotação.
 *
 * "Pular tudo" (em qualquer step) ou "Concluir" (no step final) chamam
 * [onFinish]. O caller (MainActivity / OnboardingGate) marca
 * `hasSeenOnboarding=true` e troca para o GtgNavHost.
 *
 * State machine **interno** com `remember { mutableStateOf(...) }` — sem
 * NavController. Justificativa: 3 steps lineares, sem deep-linking, sem
 * back-stack relevante. NavController adicionaria overhead sem benefício.
 */
@Composable
fun OnboardingHost(onFinish: () -> Unit) {
    var step by remember { mutableStateOf(OnboardingStep.WELCOME) }

    when (step) {
        OnboardingStep.WELCOME -> WelcomeStep(
            onContinue = { step = OnboardingStep.WINDOW },
            onSkipAll = onFinish,
        )
        OnboardingStep.WINDOW -> ActivityWindowStep(
            onContinue = { step = OnboardingStep.EXERCISE },
            onSkip = { step = OnboardingStep.EXERCISE },
            onSkipAll = onFinish,
        )
        OnboardingStep.EXERCISE -> ExerciseStep(
            onFinish = onFinish,
            onSkip = onFinish,
        )
    }
}

private enum class OnboardingStep { WELCOME, WINDOW, EXERCISE }

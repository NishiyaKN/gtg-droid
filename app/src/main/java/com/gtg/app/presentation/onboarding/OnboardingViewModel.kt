package com.gtg.app.presentation.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gtg.app.domain.model.ActivityWindow
import com.gtg.app.domain.model.Exercise
import com.gtg.app.domain.repository.ActivityWindowRepository
import com.gtg.app.domain.repository.ExerciseRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import java.time.LocalTime
import javax.inject.Inject

/**
 * VM minimalista do onboarding — apenas duas operações de persistência
 * (criar primeira ActivityWindow + primeiro Exercise). Não detém step state,
 * que vive em `remember` dentro do [OnboardingHost].
 *
 * Pequena exceção ao "OnboardingHost without ViewModel" do brainstorm KD:
 * concentrar as duas writes num único Hilt VM é mais simples que duas Hilt
 * EntryPoints ou dois ViewModels separados por step, e não recria o
 * "shared multi-step state" que a KD original queria evitar.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val activityWindowRepository: ActivityWindowRepository,
    private val exerciseRepository: ExerciseRepository,
) : ViewModel() {

    fun saveActivityWindow(
        startHour: Int,
        startMinute: Int,
        endHour: Int,
        endMinute: Int,
    ) {
        viewModelScope.launch {
            activityWindowRepository.save(
                ActivityWindow(
                    startTime = LocalTime.of(
                        startHour.coerceIn(0, 23),
                        startMinute.coerceIn(0, 59),
                    ),
                    endTime = LocalTime.of(
                        endHour.coerceIn(0, 23),
                        endMinute.coerceIn(0, 59),
                    ),
                    isActive = true,
                ),
            )
        }
    }

    fun saveExercise(name: String, maxReps: Int) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || maxReps <= 0) return
        viewModelScope.launch {
            exerciseRepository.insert(
                Exercise(
                    name = trimmed,
                    maxReps = maxReps,
                    targetPercentage = DEFAULT_TARGET_PERCENTAGE,
                    isActive = true,
                ),
            )
        }
    }

    companion object {
        /** Default conservador — usuário ajusta depois em Exercises. */
        private const val DEFAULT_TARGET_PERCENTAGE = 50
    }
}

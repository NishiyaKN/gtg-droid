package com.gtg.app.presentation.exercises

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gtg.app.domain.model.Exercise
import com.gtg.app.domain.repository.ExerciseRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ExercisesUiState(
    val exercises: List<Exercise> = emptyList(),
    val showDialog: Boolean = false,
    /** Se não-null, estamos editando; se null, estamos criando. */
    val editingExercise: Exercise? = null,
    val dialogName: String = "",
    val dialogMaxReps: String = "",
    val dialogPercentage: Int = 50,
)

@HiltViewModel
class ExercisesViewModel @Inject constructor(
    private val repository: ExerciseRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ExercisesUiState())
    val state: StateFlow<ExercisesUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeAllExercises().collectLatest { list ->
                _state.update { it.copy(exercises = list) }
            }
        }
    }

    // ── Dialog ───────────────────────────────────────────────────

    fun openCreateDialog() {
        _state.update {
            it.copy(
                showDialog = true,
                editingExercise = null,
                dialogName = "",
                dialogMaxReps = "",
                dialogPercentage = 50,
            )
        }
    }

    fun openEditDialog(exercise: Exercise) {
        _state.update {
            it.copy(
                showDialog = true,
                editingExercise = exercise,
                dialogName = exercise.name,
                dialogMaxReps = exercise.maxReps.toString(),
                dialogPercentage = exercise.targetPercentage,
            )
        }
    }

    fun dismissDialog() {
        _state.update { it.copy(showDialog = false) }
    }

    fun updateDialogName(value: String) {
        _state.update { it.copy(dialogName = value) }
    }

    fun updateDialogMaxReps(value: String) {
        _state.update { it.copy(dialogMaxReps = value) }
    }

    fun updateDialogPercentage(value: Int) {
        _state.update { it.copy(dialogPercentage = value.coerceIn(10, 100)) }
    }

    // ── Persistência ─────────────────────────────────────────────

    fun saveExercise() {
        val s = _state.value
        val name = s.dialogName.trim()
        val maxReps = s.dialogMaxReps.toIntOrNull() ?: return
        if (name.isBlank() || maxReps <= 0) return

        viewModelScope.launch {
            val existing = s.editingExercise
            if (existing != null) {
                repository.update(
                    existing.copy(
                        name = name,
                        maxReps = maxReps,
                        targetPercentage = s.dialogPercentage,
                    ),
                )
            } else {
                repository.insert(
                    Exercise(
                        name = name,
                        maxReps = maxReps,
                        targetPercentage = s.dialogPercentage,
                    ),
                )
            }
            _state.update { it.copy(showDialog = false) }
        }
    }

    fun deleteExercise(exercise: Exercise) {
        viewModelScope.launch { repository.delete(exercise) }
    }

    /**
     * Ativa/desativa o exercício no ciclo de rotina.
     * Inativos permanecem cadastrados mas não entram na seleção de exercícios
     * do agendamento (ver [com.gtg.app.domain.repository.ExerciseRepository.observeActiveExercises]).
     */
    fun setExerciseActive(exercise: Exercise, isActive: Boolean) {
        if (exercise.isActive == isActive) return
        viewModelScope.launch {
            repository.update(exercise.copy(isActive = isActive))
        }
    }
}

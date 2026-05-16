package com.gtg.app.presentation.exercises

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.gtg.app.R
import com.gtg.app.domain.model.Exercise
import com.gtg.app.presentation.theme.GtgError
import com.gtg.app.presentation.theme.GtgPrimary
import com.gtg.app.presentation.theme.GtgSurface
import com.gtg.app.presentation.theme.GtgSurfaceVariant
import kotlin.math.roundToInt

@Composable
fun ExercisesScreen(viewModel: ExercisesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = Color.Transparent,
        floatingActionButton = {
            FloatingActionButton(
                onClick = viewModel::openCreateDialog,
                containerColor = GtgPrimary,
                contentColor = Color.White,
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.exercises_add),
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.exercises_title),
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (state.exercises.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 80.dp),
                ) {
                    items(state.exercises, key = { it.id }) { exercise ->
                        ExerciseCard(
                            exercise = exercise,
                            onEdit = { viewModel.openEditDialog(exercise) },
                            onDelete = { viewModel.deleteExercise(exercise) },
                            onToggleActive = { active ->
                                viewModel.setExerciseActive(exercise, active)
                            },
                        )
                    }
                }
            }
        }
    }

    if (state.showDialog) {
        ExerciseDialog(
            isEditing = state.editingExercise != null,
            name = state.dialogName,
            maxReps = state.dialogMaxReps,
            percentage = state.dialogPercentage,
            onNameChange = viewModel::updateDialogName,
            onMaxRepsChange = viewModel::updateDialogMaxReps,
            onPercentageChange = viewModel::updateDialogPercentage,
            onSave = viewModel::saveExercise,
            onDismiss = viewModel::dismissDialog,
        )
    }
}

@Composable
private fun ExerciseCard(
    exercise: Exercise,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleActive: (Boolean) -> Unit,
) {
    // Inativo: alpha reduzido em toda a card para deixar claro que está fora
    // da rotação. Mantém o conteúdo legível para o usuário identificar.
    val contentAlpha = if (exercise.isActive) 1f else 0.45f
    val accentAlpha = if (exercise.isActive) 1f else 0.4f

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = GtgSurface),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.FitnessCenter,
                    contentDescription = null,
                    tint = GtgPrimary.copy(alpha = accentAlpha),
                    modifier = Modifier.size(28.dp),
                )

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = exercise.name,
                        color = Color.White.copy(alpha = contentAlpha),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(
                            R.string.exercises_card_max_format,
                            exercise.maxReps,
                            exercise.targetPercentage,
                        ),
                        color = Color.White.copy(alpha = 0.5f * contentAlpha),
                        fontSize = 13.sp,
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "${exercise.targetReps}",
                        color = GtgPrimary.copy(alpha = accentAlpha),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.home_reps),
                        color = GtgPrimary.copy(alpha = 0.7f * accentAlpha),
                        fontSize = 11.sp,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── Linha de ações: status + switch + edit/delete ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(
                        if (exercise.isActive) R.string.exercises_active
                        else R.string.exercises_inactive,
                    ),
                    color = if (exercise.isActive) {
                        GtgPrimary
                    } else {
                        Color.White.copy(alpha = 0.4f)
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )

                Spacer(modifier = Modifier.width(8.dp))

                Switch(
                    checked = exercise.isActive,
                    onCheckedChange = onToggleActive,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = GtgPrimary,
                        uncheckedThumbColor = Color.White.copy(alpha = 0.7f),
                        uncheckedTrackColor = GtgSurfaceVariant,
                        uncheckedBorderColor = GtgSurfaceVariant,
                    ),
                )

                Spacer(modifier = Modifier.weight(1f))

                IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Edit, null, tint = Color.White.copy(alpha = 0.5f))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Delete, null, tint = GtgError.copy(alpha = 0.6f))
                }
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.FitnessCenter,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.2f),
            modifier = Modifier.size(64.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.exercises_empty_title),
            color = Color.White.copy(alpha = 0.4f),
            fontSize = 16.sp,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.exercises_empty_hint),
            color = Color.White.copy(alpha = 0.3f),
            fontSize = 14.sp,
        )
    }
}

// ── Dialog de criação/edição ─────────────────────────────────────

@Composable
private fun ExerciseDialog(
    isEditing: Boolean,
    name: String,
    maxReps: String,
    percentage: Int,
    onNameChange: (String) -> Unit,
    onMaxRepsChange: (String) -> Unit,
    onPercentageChange: (Int) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val computedTarget = (maxReps.toIntOrNull() ?: 0) * percentage / 100.0
    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = GtgPrimary,
        unfocusedBorderColor = GtgSurfaceVariant,
        focusedLabelColor = GtgPrimary,
        unfocusedLabelColor = Color.White.copy(alpha = 0.5f),
        cursorColor = GtgPrimary,
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GtgSurface,
        title = {
            Text(
                text = stringResource(
                    if (isEditing) R.string.exercises_dialog_edit
                    else R.string.exercises_dialog_new,
                ),
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text(stringResource(R.string.exercises_dialog_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = textFieldColors,
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = maxReps,
                    onValueChange = { v -> onMaxRepsChange(v.filter { it.isDigit() }) },
                    label = { Text(stringResource(R.string.exercises_dialog_max_reps)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    colors = textFieldColors,
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = stringResource(R.string.exercises_dialog_target_percent, percentage),
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp,
                )

                Slider(
                    value = percentage.toFloat(),
                    onValueChange = { onPercentageChange(it.roundToInt()) },
                    valueRange = 10f..100f,
                    steps = 17, // 10,15,20,...100 → (100-10)/5 - 1 = 17
                    colors = SliderDefaults.colors(
                        thumbColor = GtgPrimary,
                        activeTrackColor = GtgPrimary,
                        inactiveTrackColor = GtgSurfaceVariant,
                    ),
                )

                if (computedTarget > 0) {
                    Text(
                        text = stringResource(
                            R.string.exercises_dialog_computed_target,
                            computedTarget.roundToInt(),
                        ),
                        color = GtgPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave) {
                Text(
                    text = stringResource(R.string.common_save),
                    color = GtgPrimary,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.common_cancel),
                    color = Color.White.copy(alpha = 0.5f),
                )
            }
        },
    )
}

package com.gtg.app.presentation.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Rotas de navegação do app.
 * Cada rota com ícone e label para a BottomNavigationBar.
 */
enum class Route(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    HOME("home", "Home", Icons.Default.Home),
    EXERCISES("exercises", "Exercícios", Icons.Default.FitnessCenter),
    SCHEDULE("schedule", "Agenda", Icons.Default.CalendarMonth),
    STATISTICS("statistics", "Stats", Icons.Default.BarChart),
    SETTINGS("settings", "Configs", Icons.Default.Settings),
}

package com.gtg.app.presentation.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.gtg.app.R

/**
 * Rotas de navegação do app.
 * Cada rota com ícone e label resource para a BottomNavigationBar.
 */
enum class Route(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    HOME("home", R.string.nav_home, Icons.Default.Home),
    EXERCISES("exercises", R.string.nav_exercises, Icons.Default.FitnessCenter),
    SCHEDULE("schedule", R.string.nav_schedule, Icons.Default.CalendarMonth),
    STATISTICS("statistics", R.string.nav_statistics, Icons.Default.BarChart),
    SETTINGS("settings", R.string.nav_settings, Icons.Default.Settings),
}

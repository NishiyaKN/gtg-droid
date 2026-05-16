package com.gtg.app.presentation.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.gtg.app.presentation.exercises.ExercisesScreen
import com.gtg.app.presentation.home.HomeScreen
import com.gtg.app.presentation.schedule.ScheduleScreen
import com.gtg.app.presentation.settings.SettingsScreen
import com.gtg.app.presentation.statistics.StatisticsScreen
import com.gtg.app.presentation.theme.GtgPrimary
import com.gtg.app.presentation.theme.GtgSurface

@Composable
fun GtgNavHost() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(
                containerColor = GtgSurface,
                contentColor = Color.White,
            ) {
                Route.entries.forEach { route ->
                    val selected = currentDestination?.hierarchy?.any {
                        it.route == route.route
                    } == true

                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(route.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = route.icon,
                                contentDescription = route.label,
                            )
                        },
                        label = {
                            Text(
                                text = route.label,
                                fontSize = 11.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = GtgPrimary,
                            selectedTextColor = GtgPrimary,
                            unselectedIconColor = Color.White.copy(alpha = 0.5f),
                            unselectedTextColor = Color.White.copy(alpha = 0.5f),
                            indicatorColor = GtgPrimary.copy(alpha = 0.12f),
                        ),
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Route.HOME.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Route.HOME.route) {
                HomeScreen()
            }
            composable(Route.EXERCISES.route) {
                ExercisesScreen()
            }
            composable(Route.SCHEDULE.route) {
                ScheduleScreen()
            }
            composable(Route.STATISTICS.route) {
                StatisticsScreen()
            }
            composable(Route.SETTINGS.route) {
                SettingsScreen()
            }
        }
    }
}

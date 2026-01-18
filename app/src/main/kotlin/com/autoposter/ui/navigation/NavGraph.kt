package com.autoposter.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.autoposter.ui.screens.history.HistoryScreen
import com.autoposter.ui.screens.home.HomeScreen
import com.autoposter.ui.screens.newpost.NewPostScreen
import com.autoposter.ui.screens.queue.QueueScreen
import com.autoposter.ui.screens.settings.SettingsScreen
import com.autoposter.ui.screens.settings.UnlockSettingsScreen

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object NewPost : Screen("new_post")
    object Queue : Screen("queue")
    object History : Screen("history")
    object Settings : Screen("settings")
    object UnlockSettings : Screen("unlock_settings")
}

@Composable
fun NavGraph(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                onNavigateToNewPost = { navController.navigate(Screen.NewPost.route) },
                onNavigateToQueue = { navController.navigate(Screen.Queue.route) },
                onNavigateToHistory = { navController.navigate(Screen.History.route) },
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) }
            )
        }

        composable(Screen.NewPost.route) {
            NewPostScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Queue.route) {
            QueueScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.History.route) {
            HistoryScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToUnlockSettings = { navController.navigate(Screen.UnlockSettings.route) }
            )
        }

        composable(Screen.UnlockSettings.route) {
            UnlockSettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}

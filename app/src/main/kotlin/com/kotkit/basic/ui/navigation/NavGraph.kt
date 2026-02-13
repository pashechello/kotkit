package com.kotkit.basic.ui.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.kotkit.basic.ui.screens.captionprefs.CaptionPreferencesScreen
import com.kotkit.basic.ui.screens.history.HistoryScreen
import com.kotkit.basic.ui.screens.home.HomeScreen
import com.kotkit.basic.ui.screens.newpost.NewPostScreen
import com.kotkit.basic.ui.screens.queue.QueueScreen
import com.kotkit.basic.ui.screens.settings.SettingsScreen
import com.kotkit.basic.ui.screens.settings.UnlockSettingsScreen
import com.kotkit.basic.ui.screens.splash.SplashScreen
import com.kotkit.basic.ui.screens.worker.AvailableTasksScreen
import com.kotkit.basic.ui.screens.worker.CompletedTasksScreen
import com.kotkit.basic.ui.screens.worker.EarningsScreen
import com.kotkit.basic.ui.screens.worker.WorkerDashboardScreen

sealed class Screen(val route: String) {
    object Splash : Screen("splash")
    object Home : Screen("home")
    object NewPost : Screen("new_post")
    object Queue : Screen("queue")
    object History : Screen("history")
    object Settings : Screen("settings")
    object UnlockSettings : Screen("unlock_settings")
    object CaptionPreferences : Screen("caption_preferences")

    // Worker Mode screens
    object WorkerDashboard : Screen("worker_dashboard")
    object AvailableTasks : Screen("available_tasks")
    object CompletedTasks : Screen("completed_tasks")
    object Earnings : Screen("earnings")
}

@Composable
fun NavGraph(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = Screen.Splash.route,
        enterTransition = {
            fadeIn(animationSpec = tween(300)) +
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Start,
                    animationSpec = tween(300, easing = EaseOutCubic)
                )
        },
        exitTransition = {
            fadeOut(animationSpec = tween(300)) +
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Start,
                    animationSpec = tween(300, easing = EaseInCubic)
                )
        },
        popEnterTransition = {
            fadeIn(animationSpec = tween(300)) +
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.End,
                    animationSpec = tween(300, easing = EaseOutCubic)
                )
        },
        popExitTransition = {
            fadeOut(animationSpec = tween(300)) +
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.End,
                    animationSpec = tween(300, easing = EaseInCubic)
                )
        }
    ) {
        // Splash Screen - no animations, just fade
        composable(
            route = Screen.Splash.route,
            enterTransition = { fadeIn(animationSpec = tween(0)) },
            exitTransition = { fadeOut(animationSpec = tween(400)) }
        ) {
            SplashScreen(
                onSplashFinished = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Splash.route) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = Screen.Home.route,
            enterTransition = { fadeIn(animationSpec = tween(400)) },
            exitTransition = { fadeOut(animationSpec = tween(300)) }
        ) {
            HomeScreen(
                onNavigateToNewPost = { navController.navigate(Screen.NewPost.route) },
                onNavigateToQueue = { navController.navigate(Screen.Queue.route) },
                onNavigateToHistory = { navController.navigate(Screen.History.route) },
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                onNavigateToWorkerDashboard = { navController.navigate(Screen.WorkerDashboard.route) }
            )
        }

        composable(
            route = Screen.NewPost.route,
            enterTransition = {
                fadeIn(animationSpec = tween(300)) +
                    slideIntoContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Up,
                        animationSpec = tween(400, easing = EaseOutCubic),
                        initialOffset = { it / 4 }
                    )
            },
            exitTransition = {
                fadeOut(animationSpec = tween(300)) +
                    slideOutOfContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Down,
                        animationSpec = tween(300, easing = EaseInCubic),
                        targetOffset = { it / 4 }
                    )
            }
        ) {
            NewPostScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Queue.route) {
            QueueScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToNewPost = { navController.navigate(Screen.NewPost.route) }
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
                onNavigateToUnlockSettings = { navController.navigate(Screen.UnlockSettings.route) },
                onNavigateToCaptionSettings = { navController.navigate(Screen.CaptionPreferences.route) }
            )
        }

        composable(Screen.UnlockSettings.route) {
            UnlockSettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.CaptionPreferences.route) {
            CaptionPreferencesScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // Worker Mode screens
        composable(Screen.WorkerDashboard.route) {
            WorkerDashboardScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToAvailableTasks = { navController.navigate(Screen.AvailableTasks.route) },
                onNavigateToActiveTasks = { /* Active tasks shown in dashboard */ },
                onNavigateToEarnings = { navController.navigate(Screen.Earnings.route) },
                onNavigateToCompletedTasks = { navController.navigate(Screen.CompletedTasks.route) },
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) }
            )
        }

        composable(Screen.AvailableTasks.route) {
            AvailableTasksScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.CompletedTasks.route) {
            CompletedTasksScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Earnings.route) {
            EarningsScreen(
                onNavigateBack = { navController.popBackStack() },
                onRequestPayout = { /* TODO: Navigate to payout screen or show dialog */ }
            )
        }
    }
}

private val EaseOutCubic = CubicBezierEasing(0.33f, 1f, 0.68f, 1f)
private val EaseInCubic = CubicBezierEasing(0.32f, 0f, 0.67f, 0f)

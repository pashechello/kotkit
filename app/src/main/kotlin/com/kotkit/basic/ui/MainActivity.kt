package com.kotkit.basic.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.kotkit.basic.R
import com.kotkit.basic.auth.AuthEvent
import com.kotkit.basic.auth.AuthStateManager
import com.kotkit.basic.data.local.preferences.EncryptedPreferences
import com.kotkit.basic.ui.components.AppNotificationBanner
import com.kotkit.basic.ui.components.SnackbarController
import com.kotkit.basic.ui.navigation.NavGraph
import com.kotkit.basic.ui.navigation.Screen
import com.kotkit.basic.ui.theme.KotKitTheme
import com.kotkit.basic.ui.theme.SurfaceBase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    @Inject
    lateinit var encryptedPreferences: EncryptedPreferences

    @Inject
    lateinit var authStateManager: AuthStateManager

    // Observable state for FCM navigate_to — triggers recomposition from both onCreate and onNewIntent
    private val pendingNavTargetState = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Handle deep link on launch
        handleDeepLink(intent)
        handleNavigateTo(intent)

        setContent {
            KotKitTheme {
                // Route auth events through SnackbarController
                LaunchedEffect(Unit) {
                    authStateManager.authEvents.collect { event ->
                        when (event) {
                            is AuthEvent.LoggedIn -> {
                                Timber.tag(TAG).i("User logged in, showing notification")
                                SnackbarController.showSuccess(
                                    message = getString(R.string.auth_login_success)
                                )
                            }
                            is AuthEvent.LoggedOut -> {
                                Timber.tag(TAG).i("User logged out")
                            }
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(SurfaceBase)
                ) {
                    val navController = rememberNavController()

                    // Handle FCM deep link navigation (navigate_to extra)
                    // Uses pendingNavTarget so both onCreate and onNewIntent can trigger it
                    var pendingNavTarget by remember { pendingNavTargetState }
                    LaunchedEffect(pendingNavTarget) {
                        val target = pendingNavTarget ?: return@LaunchedEffect
                        pendingNavTarget = null
                        when (target) {
                            "completed_tasks" -> {
                                // Delay slightly to ensure NavHost is ready
                                delay(500)
                                navController.navigate(Screen.CompletedTasks.route)
                            }
                        }
                    }

                    NavGraph(navController = navController)

                    // Top-positioned notification banner — no overlap with FAB or bottom UI
                    AppNotificationBanner(
                        modifier = Modifier.align(Alignment.TopCenter)
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Handle deep link when app is already running (singleTask)
        handleDeepLink(intent)
        handleNavigateTo(intent)
    }

    /**
     * Handle FCM navigate_to intent extra.
     * Updates pendingNavTargetState which triggers LaunchedEffect in Compose.
     */
    private fun handleNavigateTo(intent: Intent?) {
        val target = intent?.getStringExtra("navigate_to") ?: return
        Timber.tag(TAG).i("Navigate to: $target")
        pendingNavTargetState.value = target
    }

    /**
     * Handle deep link from website OAuth flow.
     * URL format: kotkit://auth?access_token=xxx&refresh_token=xxx
     */
    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.data ?: return

        if (data.scheme == "kotkit" && data.host == "auth") {
            val accessToken = data.getQueryParameter("access_token")
            val refreshToken = data.getQueryParameter("refresh_token")

            if (!accessToken.isNullOrBlank() && !refreshToken.isNullOrBlank()) {
                Timber.tag(TAG).i("Received auth deep link, saving tokens")
                Timber.tag(TAG).d("Access token length: ${accessToken.length}, starts with: ${accessToken.take(20)}...")
                Timber.tag(TAG).d("Refresh token length: ${refreshToken.length}, starts with: ${refreshToken.take(20)}...")

                // Save tokens
                encryptedPreferences.authToken = accessToken
                encryptedPreferences.refreshToken = refreshToken

                // Notify AuthStateManager
                authStateManager.onLogin()
            } else {
                Timber.tag(TAG).w("Deep link missing tokens: access=${accessToken?.length}, refresh=${refreshToken?.length}")
            }
        }
    }
}

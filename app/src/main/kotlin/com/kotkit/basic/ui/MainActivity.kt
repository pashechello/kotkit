package com.kotkit.basic.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import com.kotkit.basic.R
import com.kotkit.basic.auth.AuthEvent
import com.kotkit.basic.auth.AuthStateManager
import com.kotkit.basic.data.local.preferences.EncryptedPreferences
import com.kotkit.basic.ui.navigation.NavGraph
import com.kotkit.basic.ui.navigation.Screen
import com.kotkit.basic.ui.theme.BrandCyan
import com.kotkit.basic.ui.theme.BrandPink
import com.kotkit.basic.ui.theme.KotKitTheme
import com.kotkit.basic.ui.theme.SurfaceBase
import com.kotkit.basic.ui.theme.SurfaceDialog
import com.kotkit.basic.ui.theme.TextPrimary
import dagger.hilt.android.AndroidEntryPoint
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
                val snackbarHostState = remember { SnackbarHostState() }

                // Track last login time to avoid showing TokenExpired right after login
                var lastLoginTime by remember { mutableLongStateOf(0L) }

                // Listen for auth events
                LaunchedEffect(Unit) {
                    authStateManager.authEvents.collect { event ->
                        // Dismiss any existing snackbar and wait for animation to complete
                        snackbarHostState.currentSnackbarData?.let {
                            it.dismiss()
                            kotlinx.coroutines.delay(200) // Wait for dismiss animation
                        }

                        when (event) {
                            is AuthEvent.TokenExpired -> {
                                // Don't show TokenExpired within 10 seconds of login
                                val timeSinceLogin = System.currentTimeMillis() - lastLoginTime
                                if (timeSinceLogin > 10_000) {
                                    Timber.tag(TAG).w("Token expired, showing snackbar")
                                    val result = snackbarHostState.showSnackbar(
                                        message = getString(R.string.auth_session_expired),
                                        actionLabel = getString(R.string.auth_login_via_website),
                                        duration = SnackbarDuration.Long
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        val intent = Intent(Intent.ACTION_VIEW)
                                        intent.data = android.net.Uri.parse("https://kotkit.pro/auth/app")
                                        startActivity(intent)
                                    }
                                } else {
                                    Timber.tag(TAG).w("Token expired ignored (just logged in ${timeSinceLogin}ms ago)")
                                }
                            }
                            is AuthEvent.LoggedIn -> {
                                lastLoginTime = System.currentTimeMillis()
                                Timber.tag(TAG).i("User logged in, showing snackbar")
                                snackbarHostState.showSnackbar(
                                    message = getString(R.string.auth_login_success),
                                    duration = SnackbarDuration.Short
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
                                kotlinx.coroutines.delay(500)
                                navController.navigate(Screen.CompletedTasks.route)
                            }
                        }
                    }

                    NavGraph(navController = navController)

                    // Global auth snackbar at BOTTOM (avoids overlap with GlassTopBar)
                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 16.dp)
                    ) { data ->
                        Snackbar(
                            snackbarData = data,
                            containerColor = SurfaceDialog,
                            contentColor = Color.White,
                            actionColor = BrandCyan,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(
                                    width = 1.dp,
                                    brush = Brush.linearGradient(
                                        colors = listOf(
                                            BrandCyan.copy(alpha = 0.4f),
                                            BrandPink.copy(alpha = 0.4f)
                                        )
                                    ),
                                    shape = RoundedCornerShape(16.dp)
                                )
                        )
                    }
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

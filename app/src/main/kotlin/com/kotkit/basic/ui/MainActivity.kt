package com.kotkit.basic.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.kotkit.basic.data.local.preferences.EncryptedPreferences
import com.kotkit.basic.ui.navigation.NavGraph
import com.kotkit.basic.ui.theme.KotKitTheme
import com.kotkit.basic.ui.theme.SurfaceBase
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject
    lateinit var encryptedPreferences: EncryptedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Handle deep link on launch
        handleDeepLink(intent)

        setContent {
            KotKitTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(SurfaceBase)
                ) {
                    val navController = rememberNavController()
                    NavGraph(navController = navController)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Handle deep link when app is already running (singleTask)
        handleDeepLink(intent)
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
                // Save tokens
                encryptedPreferences.authToken = accessToken
                encryptedPreferences.refreshToken = refreshToken

                Toast.makeText(
                    this,
                    "Вход выполнен успешно!",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}

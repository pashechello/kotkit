package com.autoposter.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Checks for app integrity and potential tampering
 */
@Singleton
class IntegrityChecker @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * Check if the app appears to be running in a legitimate environment
     */
    fun checkIntegrity(): IntegrityResult {
        val issues = mutableListOf<String>()

        // Check for debuggable flag
        if (isDebuggable()) {
            issues.add("App is debuggable")
        }

        // Check for emulator
        if (isEmulator()) {
            issues.add("Running on emulator")
        }

        // Check for root
        if (isRooted()) {
            issues.add("Device may be rooted")
        }

        return if (issues.isEmpty()) {
            IntegrityResult.Passed
        } else {
            IntegrityResult.Issues(issues)
        }
    }

    /**
     * Check if app is debuggable (should be false in release)
     */
    private fun isDebuggable(): Boolean {
        return try {
            val appInfo = context.applicationInfo
            (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Simple emulator detection
     */
    private fun isEmulator(): Boolean {
        return (Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.MANUFACTURER.contains("Genymotion")
                || (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
                || "google_sdk" == Build.PRODUCT)
    }

    /**
     * Basic root detection
     */
    private fun isRooted(): Boolean {
        val rootIndicators = listOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su"
        )

        return rootIndicators.any { File(it).exists() }
    }

    /**
     * Get APK signature hash for verification
     */
    @Suppress("DEPRECATION", "PackageManagerGetSignatures")
    fun getSignatureHash(): String? {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
            } else {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNATURES
                )
            }

            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                packageInfo.signatures
            }

            signatures?.firstOrNull()?.let { signature ->
                val digest = MessageDigest.getInstance("SHA-256")
                val hash = digest.digest(signature.toByteArray())
                hash.joinToString("") { "%02x".format(it) }
            }
        } catch (e: Exception) {
            null
        }
    }
}

sealed class IntegrityResult {
    object Passed : IntegrityResult()
    data class Issues(val issues: List<String>) : IntegrityResult()
}

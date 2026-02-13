package com.kotkit.basic.security

object SecurityConfig {
    // SSL Pinning hosts - Updated 2026-01-29 for Fly.io backend (монолит)
    const val API_HOST = "kotkit-app.fly.dev"

    // Certificate pins (SHA-256) - kotkit-app.fly.dev
    // Generated: 2026-01-29
    val CERTIFICATE_PINS = listOf(
        "sha256/1v+R//jEMn05LRXJ17Cs8GIiV0/In3BIqhadtHKxhn0=", // kotkit-app.fly.dev primary
        "sha256/C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M="  // Let's Encrypt ISRG Root X1 (backup)
    )

    // Allowed TikTok packages
    val ALLOWED_TIKTOK_PACKAGES = setOf(
        "com.zhiliaoapp.musically",    // TikTok
        "com.ss.android.ugc.trill"     // TikTok Lite
    )

    // Timeouts
    const val CONNECT_TIMEOUT_SECONDS = 30L
    const val READ_TIMEOUT_SECONDS = 60L
    const val WRITE_TIMEOUT_SECONDS = 60L

    // Retry configuration
    const val MAX_RETRIES = 3
    const val INITIAL_RETRY_DELAY_MS = 1000L
    const val MAX_RETRY_DELAY_MS = 10000L
}

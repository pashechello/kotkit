package com.kotkit.basic.security

object SecurityConfig {
    // SSL Pinning hosts
    const val API_HOST = "kotkit.pro"

    // Certificate pins (SHA-256) - kotkit.pro Let's Encrypt
    // Generated: 2026-01-21
    val CERTIFICATE_PINS = listOf(
        "sha256/X1AdYvvnwl2Ykm0jWyiYyTOK/nt0NJ2MpbxPdsdcFK8=", // kotkit.pro
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

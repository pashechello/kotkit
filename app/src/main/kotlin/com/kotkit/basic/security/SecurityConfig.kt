package com.kotkit.basic.security

object SecurityConfig {
    // SSL Pinning hosts
    const val API_HOST = "kotkit-app.fly.dev"
    const val API_HOST_PUBLIC = "kotkit.pro"

    // Certificate pins (SHA-256 SPKI) — pinned to CA, not leaf certs.
    // Leaf certs rotate every 90 days; CA pins are stable for years.
    // Both domains use Let's Encrypt, so they share the same CA pins.
    val CA_PINS = listOf(
        "sha256/C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M=", // ISRG Root X1 (Let's Encrypt root, valid until 2035)
        "sha256/y7xVm0TVJNahMr2sZydE2jQH8SquXV9yLF9seROHHHU="  // Let's Encrypt E7 intermediate (current active)
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

package com.autoposter.security

object SecurityConfig {
    // SSL Pinning hosts
    const val API_HOST = "api.autoposter.com"

    // Certificate pins (SHA-256)
    // TODO: Replace with actual certificate pins before production
    val CERTIFICATE_PINS = listOf(
        "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", // Primary
        "sha256/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB="  // Backup
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

package com.autoposter.security

import okhttp3.CertificatePinner

object SSLPinning {

    /**
     * Get certificate pinner for API requests
     * This prevents MITM attacks by verifying server certificates
     */
    fun getCertificatePinner(): CertificatePinner {
        val builder = CertificatePinner.Builder()

        SecurityConfig.CERTIFICATE_PINS.forEach { pin ->
            builder.add(SecurityConfig.API_HOST, pin)
        }

        return builder.build()
    }

    /**
     * Get pins for a specific host
     */
    fun getPinsForHost(host: String): List<String> {
        return when (host) {
            SecurityConfig.API_HOST -> SecurityConfig.CERTIFICATE_PINS
            else -> emptyList()
        }
    }
}

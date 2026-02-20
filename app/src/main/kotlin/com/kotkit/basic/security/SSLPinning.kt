package com.kotkit.basic.security

import okhttp3.CertificatePinner

object SSLPinning {

    /**
     * Get certificate pinner for API requests.
     * Pins both kotkit-app.fly.dev (direct) and kotkit.pro (public domain).
     */
    fun getCertificatePinner(): CertificatePinner {
        val builder = CertificatePinner.Builder()

        // Both domains share CA pins (Let's Encrypt root + intermediate)
        for (host in listOf(SecurityConfig.API_HOST, SecurityConfig.API_HOST_PUBLIC)) {
            SecurityConfig.CA_PINS.forEach { pin ->
                builder.add(host, pin)
            }
        }

        return builder.build()
    }
}

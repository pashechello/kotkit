package com.autoposter.data.remote.api

import com.autoposter.data.local.preferences.EncryptedPreferences
import okhttp3.Interceptor
import okhttp3.Response

class AuthInterceptor(
    private val encryptedPreferences: EncryptedPreferences
) : Interceptor {

    companion object {
        private const val HEADER_AUTHORIZATION = "Authorization"
        private const val BEARER_PREFIX = "Bearer "
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        // Skip auth for login/register endpoints
        val path = originalRequest.url.encodedPath
        if (path.contains("/auth/login") || path.contains("/auth/register")) {
            return chain.proceed(originalRequest)
        }

        val token = encryptedPreferences.authToken
        if (token.isNullOrBlank()) {
            return chain.proceed(originalRequest)
        }

        val authenticatedRequest = originalRequest.newBuilder()
            .header(HEADER_AUTHORIZATION, BEARER_PREFIX + token)
            .build()

        return chain.proceed(authenticatedRequest)
    }
}

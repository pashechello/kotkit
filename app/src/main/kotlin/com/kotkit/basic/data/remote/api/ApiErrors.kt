package com.kotkit.basic.data.remote.api

import retrofit2.HttpException

/**
 * Check if a throwable is a 401 auth error.
 * Auth errors are handled globally by AuthStateManager + MainActivity snackbar,
 * so ViewModels should NOT show local error snackbars for them.
 */
fun Throwable?.isAuthError(): Boolean =
    this is HttpException && this.code() == 401

/**
 * Extract a user-facing error message from a Result, filtering out auth errors
 * (which are handled globally by the auth system).
 * Returns null for auth errors so ViewModels can skip setting uiState.error.
 */
fun <T> Result<T>.userErrorMessage(fallback: String? = null): String? {
    val exception = exceptionOrNull() ?: return null
    if (exception.isAuthError()) return null
    return exception.message ?: fallback
}

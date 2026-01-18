package com.autoposter.agent

import com.autoposter.data.remote.api.models.AnalyzeResponse
import com.autoposter.executor.accessibility.ActionExecutor
import com.autoposter.executor.accessibility.ExecutionResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles mapping of server responses to executor actions
 * Provides additional logic for complex action sequences
 */
@Singleton
class ActionHandler @Inject constructor(
    private val actionExecutor: ActionExecutor
) {
    /**
     * Process an action response from the server
     */
    suspend fun handle(action: AnalyzeResponse): ActionResult {
        return when (val result = actionExecutor.execute(action)) {
            is ExecutionResult.Success -> ActionResult.Success
            is ExecutionResult.Done -> ActionResult.Done(result.message)
            is ExecutionResult.Failed -> ActionResult.Failed(result.reason, recoverable = true)
            is ExecutionResult.Error -> {
                if (result.recoverable) {
                    ActionResult.Failed(result.message ?: "Unknown error", recoverable = true)
                } else {
                    ActionResult.Failed(result.message ?: "Unknown error", recoverable = false)
                }
            }
        }
    }
}

sealed class ActionResult {
    object Success : ActionResult()
    data class Done(val message: String?) : ActionResult()
    data class Failed(val reason: String, val recoverable: Boolean) : ActionResult()
}

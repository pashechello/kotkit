package com.kotkit.basic.agent

import com.kotkit.basic.data.remote.api.models.AnalyzeResponse
import com.kotkit.basic.executor.accessibility.ActionExecutor
import com.kotkit.basic.executor.accessibility.ExecutionResult
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
            is ExecutionResult.PublishTapped -> ActionResult.Success // Publish tapped, continue flow
            is ExecutionResult.Cancelled -> ActionResult.Failed("Операция отменена", recoverable = false)
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

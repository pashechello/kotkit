package com.autoposter.agent

sealed class AgentState {
    object Idle : AgentState()
    object UnlockingScreen : AgentState()
    object OpeningTikTok : AgentState()
    object WaitingForTikTok : AgentState()
    data class ExecutingStep(val step: Int, val action: String) : AgentState()
    data class WaitingForServer(val step: Int) : AgentState()
    data class Completed(val message: String?) : AgentState()
    data class Failed(val reason: String) : AgentState()
    data class NeedUserAction(val message: String) : AgentState()
}

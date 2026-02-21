package com.kotkit.basic.warmup

data class WarmupStats(
    val videosWatched: Int = 0,
    val likesGiven: Int = 0,
    val durationSeconds: Int = 0,
    val vlmRecoveryCalls: Int = 0,
    val popupsDismissed: Int = 0
)

sealed class WarmupResult {
    data class Success(val stats: WarmupStats) : WarmupResult()
    data class Cancelled(val stats: WarmupStats) : WarmupResult()
    data class Failed(val reason: String, val stats: WarmupStats = WarmupStats()) : WarmupResult()
}

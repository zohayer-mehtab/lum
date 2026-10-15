package lumen.core

sealed interface RecoveryAction {
    data class Restart(val backoffMs: Long) : RecoveryAction
    data class Fallback(val id: String) : RecoveryAction
    data object GiveUp : RecoveryAction
}

/**
 * Bounded recovery, per session. First failure of an id -> one restart with backoff.
 * Second failure -> id is unavailable (until manualRetry) and we fall back to the next candidate.
 * No candidates -> GiveUp (recoverable error screen). There are no loops: every failure consumes budget.
 */
class RecoveryPolicy(private val backoffMs: Long = 600) {
    private val failures = HashMap<String, Int>()
    private val unavailable = HashSet<String>()

    @Synchronized fun isUnavailable(id: String) = id in unavailable
    @Synchronized fun unavailableIds(): Set<String> = unavailable.toSet()

    @Synchronized
    fun onFailure(id: String, candidates: List<String>): RecoveryAction {
        val n = (failures[id] ?: 0) + 1
        failures[id] = n
        if (n == 1) return RecoveryAction.Restart(backoffMs)
        unavailable += id
        val next = candidates.firstOrNull { it != id && it !in unavailable }
        return if (next != null) RecoveryAction.Fallback(next) else RecoveryAction.GiveUp
    }

    @Synchronized fun manualRetry(id: String? = null) {
        if (id == null) { failures.clear(); unavailable.clear() } else { failures.remove(id); unavailable.remove(id) }
    }
}

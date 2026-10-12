package io.github.ammarqaisar11a55.relaysms.core.reset

import kotlinx.coroutines.CancellationException

/**
 * Runs a full, ordered wipe of the installation so that the role can be chosen again without
 * leaving forwarding enabled, orphaned pairings or inaccessible keys behind.
 *
 * Steps are registered by the components that own the data. Every step runs even if an earlier
 * one fails; failures are reported by name only.
 */
class ResetCoordinator {
    fun interface Step {
        suspend fun run()
    }

    private data class Named(val order: Int, val name: String, val step: Step)

    private val steps = mutableListOf<Named>()

    /** Lower [order] runs first. Stop forwarding < revoke on server < delete keys < wipe storage. */
    @Synchronized
    fun register(order: Int, name: String, step: Step) {
        steps += Named(order, name, step)
    }

    /** @return names of steps that failed; empty when the reset was complete. */
    suspend fun resetEverything(): List<String> {
        val ordered = synchronized(this) { steps.sortedBy { it.order } }
        val failed = mutableListOf<String>()
        for (s in ordered) {
            try {
                s.step.run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed += s.name
            }
        }
        return failed
    }

    companion object {
        const val ORDER_STOP_ACTIVITY = 0
        const val ORDER_REVOKE_REMOTE = 10
        const val ORDER_DELETE_KEYS = 20
        const val ORDER_WIPE_STORAGE = 30
        const val ORDER_CLEAR_PREFERENCES = 40
    }
}

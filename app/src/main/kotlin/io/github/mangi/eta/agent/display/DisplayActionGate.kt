package io.github.mangi.eta.agent.display

/** Serializes local accessibility submissions with acknowledged pause and task migration. */
internal class DisplayActionGate {
    private val lock = Any()
    fun <T> commit(block: () -> T): T = synchronized(lock, block)
}

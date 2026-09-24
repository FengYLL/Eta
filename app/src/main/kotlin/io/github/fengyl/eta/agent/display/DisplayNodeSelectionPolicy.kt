package io.github.fengyl.eta.agent.display

/** Selects the implicit text target when the model omits an observation index. */
internal object DisplayNodeSelectionPolicy {
    data class Candidate(val index: Int, val editable: Boolean, val focused: Boolean)

    fun select(candidates: List<Candidate>): Int? {
        val editable = candidates.filter { it.editable }
        return editable.singleOrNull { it.focused }?.index ?: editable.singleOrNull()?.index
    }
}

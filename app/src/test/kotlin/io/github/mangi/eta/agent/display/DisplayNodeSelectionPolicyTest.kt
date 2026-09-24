package io.github.mangi.eta.agent.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayNodeSelectionPolicyTest {
    @Test
    fun selectsTheOnlyEditableNodeEvenWhenWorkDisplayDoesNotExposeFocus() {
        val selected = DisplayNodeSelectionPolicy.select(
            listOf(
                DisplayNodeSelectionPolicy.Candidate(2, editable = false, focused = false),
                DisplayNodeSelectionPolicy.Candidate(5, editable = true, focused = false),
            ),
        )

        assertEquals(5, selected)
    }

    @Test
    fun prefersTheOnlyFocusedEditableNode() {
        val selected = DisplayNodeSelectionPolicy.select(
            listOf(
                DisplayNodeSelectionPolicy.Candidate(1, editable = true, focused = false),
                DisplayNodeSelectionPolicy.Candidate(4, editable = true, focused = true),
            ),
        )

        assertEquals(4, selected)
    }

    @Test
    fun requiresExplicitIndexWhenThereAreSeveralEditableNodes() {
        val selected = DisplayNodeSelectionPolicy.select(
            listOf(
                DisplayNodeSelectionPolicy.Candidate(1, editable = true, focused = false),
                DisplayNodeSelectionPolicy.Candidate(4, editable = true, focused = false),
            ),
        )

        assertNull(selected)
    }
}

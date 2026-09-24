package io.github.mangi.eta.agent.display

/** Pure state machine shared by the privileged broker and its tests. Never reuse an action epoch. */
internal class DisplayLease {
    enum class State { READY, RUNNING, PAUSED, RETAINED, HUMAN, CLOSED, LOST }
    @Volatile var state = State.READY
        private set
    @Volatile var epoch = 0L
        private set
    @Volatile var runId = ""
        private set

    @Synchronized fun acquire(run: String): Long {
        require(run.isNotBlank())
        check(state == State.READY || state == State.RETAINED || (state == State.PAUSED && runId == run)) {
            "工作屏正在使用、已接管或已失效"
        }
        runId = run
        state = State.RUNNING
        return ++epoch
    }

    @Synchronized fun pause() {
        if (state == State.RUNNING) { state = State.PAUSED; ++epoch }
    }

    @Synchronized fun retain(run: String) {
        if (runId == run && state in setOf(State.RUNNING, State.PAUSED)) {
            state = State.RETAINED
            ++epoch
        }
    }

    @Synchronized fun takeover() { check(state != State.CLOSED); state = State.HUMAN; ++epoch }
    @Synchronized fun close() { state = State.CLOSED; ++epoch }
    @Synchronized fun lost() { state = State.LOST; ++epoch }
    fun accepts(run: String, expectedEpoch: Long): Boolean =
        state == State.RUNNING && runId == run && epoch == expectedEpoch
    val exclusive: Boolean get() = state == State.RUNNING || state == State.PAUSED
}

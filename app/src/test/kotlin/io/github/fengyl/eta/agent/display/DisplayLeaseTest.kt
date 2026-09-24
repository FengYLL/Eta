package io.github.fengyl.eta.agent.display

import org.junit.Assert.*
import org.junit.Test

class DisplayLeaseTest {
    @Test fun pauseAndResumeNeverAcceptOldCoordinates() {
        val lease = DisplayLease()
        val first = lease.acquire("run")
        assertTrue(lease.accepts("run", first))
        lease.pause()
        assertFalse(lease.accepts("run", first))
        assertTrue(lease.exclusive)
        val resumed = lease.acquire("run")
        assertTrue(resumed > first)
        assertFalse(lease.accepts("run", first))
        assertTrue(lease.accepts("run", resumed))
    }
    @Test fun completionRetainsPageButRevokesInputAndExclusivity() {
        val lease = DisplayLease()
        val epoch = lease.acquire("one")
        lease.retain("one")
        assertEquals(DisplayLease.State.RETAINED, lease.state)
        assertFalse(lease.exclusive)
        assertFalse(lease.accepts("one", epoch))
        val next = lease.acquire("two")
        lease.retain("one") // A late finally block must not stop a newer run.
        assertTrue(lease.accepts("two", next))
    }
    @Test fun takeoverAndLossCannotResumeAnOldRun() {
        for (lose in listOf(false, true)) {
            val lease = DisplayLease()
            val epoch = lease.acquire("run")
            if (lose) lease.lost() else lease.takeover()
            assertFalse(lease.accepts("run", epoch))
            assertThrows(IllegalStateException::class.java) { lease.acquire("run") }
        }
    }
    @Test fun anotherRunCannotStealAPausedSession() {
        val lease = DisplayLease()
        lease.acquire("one"); lease.pause()
        assertThrows(IllegalStateException::class.java) { lease.acquire("two") }
        lease.close()
        assertThrows(IllegalStateException::class.java) { lease.acquire("one") }
    }
    @Test fun isolationAllowlistDeniesGlobalAndUnknownExtensions() {
        listOf("terminal", "run_command", "write_file", "open_system_panel", "set_clipboard", "browser_use", "new_future_tool", "mcp__device").forEach {
            assertFalse(it, DisplayToolPolicy.allows(it))
        }
        listOf("observe_screen", "replace_text", "launch_app", "conversation_history").forEach {
            assertTrue(it, DisplayToolPolicy.allows(it))
        }
    }
}

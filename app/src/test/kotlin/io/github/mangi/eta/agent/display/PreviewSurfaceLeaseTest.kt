package io.github.mangi.eta.agent.display

import org.junit.Assert.*
import org.junit.Test

class PreviewSurfaceLeaseTest {
    private val events = mutableListOf<String>()
    private val lease = PreviewSurfaceLease("background", { events += "output:$it" }, { events += "release:$it" })

    @Test fun leavingViewerRestoresBackgroundBeforeReleasingForeground() {
        lease.attach(1, "viewer", 0)
        lease.detach(1)
        assertEquals(listOf("output:viewer", "output:background", "release:viewer"), events)
        lease.detach(1)
        assertEquals(3, events.size)
    }

    @Test fun lateOldActivityDetachCannotDisconnectNewViewer() {
        lease.attach(1, "old", 0)
        lease.attach(2, "new", 1)
        lease.detach(1)
        assertTrue(lease.renew(2, 2))
        assertEquals(listOf("output:old", "output:new", "release:old"), events)
    }

    @Test fun detachBeforeAttachRevokesDelayedRequest() {
        lease.detach(3)
        assertThrows(IllegalStateException::class.java) { lease.attach(3, "stale", 0) }
        assertEquals(listOf("release:stale"), events)
        lease.attach(4, "new", 1)
        assertTrue(lease.renew(4, 2))
    }

    @Test fun heartbeatLossRestoresBackgroundWithoutClosingAutomationLease() {
        val automation = DisplayLease()
        val epoch = automation.acquire("run")
        lease.attach(1, "viewer", 100)
        assertTrue(lease.renew(1, 4000))
        lease.expire(8999)
        assertEquals(listOf("output:viewer"), events)
        lease.expire(9000)
        assertEquals("output:background", events[1])
        assertFalse(lease.renew(1, 9001))
        assertTrue(automation.accepts("run", epoch))
    }

    @Test fun olderAttachCannotStealNewViewer() {
        lease.attach(2, "current", 0)
        assertThrows(IllegalStateException::class.java) { lease.attach(1, "stale", 1) }
        assertTrue(lease.renew(2, 2))
        assertFalse(lease.renew(1, 3))
        assertEquals(listOf("output:current", "release:stale"), events)
    }

    @Test fun failedAttachmentReleasesNewSurfaceAndPreservesPreviousOwner() {
        val failing = PreviewSurfaceLease("background", { value: String ->
            if (value == "bad") error("surface abandoned")
            events += "output:$value"
        }, { events += "release:$it" })
        failing.attach(1, "old", 0)
        assertThrows(IllegalStateException::class.java) { failing.attach(2, "bad", 1) }
        assertTrue(failing.renew(1, 2))
        failing.detach(1)
        assertEquals(listOf("output:old", "release:bad", "output:background", "release:old"), events)
    }

    @Test fun failedBackgroundSwitchKeepsConsumerUntilRetry() {
        var fail = true
        val failing = PreviewSurfaceLease("background", { value: String ->
            if (value == "background" && fail) error("transient error")
            events += "output:$value"
        }, { events += "release:$it" })
        failing.attach(1, "viewer", 0)
        assertThrows(IllegalStateException::class.java) { failing.detach(1) }
        assertEquals(listOf("output:viewer"), events)
        fail = false
        failing.detach(1)
        assertEquals(listOf("output:viewer", "output:background", "release:viewer"), events)
    }

    @Test fun closingDisplayReleasesOnlyItsOwnedViewerOnce() {
        lease.attach(1, "viewer", 0)
        lease.close()
        lease.close()
        lease.expire(10000)
        assertFalse(lease.renew(1, 10000))
        assertThrows(IllegalStateException::class.java) { lease.attach(2, "late", 10001) }
        assertEquals(listOf("output:viewer", "release:viewer", "release:late"), events)
    }
}

package io.github.mangi.eta.agent.display

import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import org.junit.Assert.*
import org.junit.Test

class DisplayWindowAwaiterTest {
    private class Clock {
        var now = 0L
        val delays = mutableListOf<Long>()
        val awaiter = DisplayWindowAwaiter({ now }) { delay ->
            delays += delay
            now += delay
        }
    }

    @Test fun startupWaitsForMissingWindowThenMissingRoot() {
        val clock = Clock()
        var reads = 0
        val result = clock.awaiter.await(5000) {
            when (++reads) {
                1 -> throw DisplayWindowNotReadyException("window not reported")
                2 -> throw DisplayWindowNotReadyException("root not ready")
                else -> "com.tencent.mm"
            }
        }
        assertEquals("com.tencent.mm", result)
        assertEquals(3, reads)
        assertEquals(400L, clock.now)
    }

    @Test fun packageAndTextWaitsKeepPollingThroughTemporaryWindowLoss() {
        val clock = Clock()
        var reads = 0
        val result = clock.awaiter.await(1000) {
            when (++reads) {
                1, 3 -> null // A readable window, but the requested condition is absent.
                2 -> throw DisplayWindowNotReadyException("migration in progress")
                else -> "matched"
            }
        }
        assertEquals("matched", result)
        assertEquals(600L, clock.now)
    }

    @Test fun inaccessibleDisplayTimesOutWithDiagnosticsInsteadOfSuggestingRelaunch() {
        val clock = Clock()
        val failure = assertThrows(DisplayWindowNotReadyException::class.java) {
            clock.awaiter.await<String>(500) {
                throw DisplayWindowNotReadyException("display=7，上报显示器=[0]")
            }
        }
        assertEquals(listOf(200L, 200L, 100L), clock.delays)
        assertEquals(500L, clock.now)
        assertTrue(failure.message!!.contains("500ms"))
        assertTrue(failure.message!!.contains("display=7"))
        assertTrue(failure.message!!.contains("已有画面"))
        assertFalse(failure.message!!.contains("launch_app"))
    }

    @Test fun readableWindowWithUnmatchedConditionReturnsOrdinaryTimeout() {
        val clock = Clock()
        assertNull(clock.awaiter.await<String>(500) { null })
        assertEquals(500L, clock.now)
    }

    @Test fun recoveredWindowDoesNotReportStaleAccessibilityFailureAtTimeout() {
        val clock = Clock()
        var reads = 0
        assertNull(clock.awaiter.await<String>(200) {
            if (++reads == 1) throw DisplayWindowNotReadyException("transient")
            null
        })
    }

    @Test fun zeroTimeoutStillChecksOnceWithoutSleeping() {
        val clock = Clock()
        var reads = 0
        assertNull(clock.awaiter.await<String>(0) { reads++; null })
        assertEquals(1, reads)
        assertTrue(clock.delays.isEmpty())
        assertEquals("ready", clock.awaiter.await(0) { "ready" })
    }

    @Test fun securityAndSessionFailuresAreNeverRetried() {
        listOf(SecurityException("blocked"), IllegalStateException("lease changed")).forEach { failure ->
            val clock = Clock()
            val actual = assertThrows(failure.javaClass) {
                clock.awaiter.await<String>(5000) { throw failure }
            }
            assertSame(failure, actual)
            assertTrue(clock.delays.isEmpty())
        }
    }

    @Test fun cancellationStopsBeforeAnotherRead() {
        val controller = AgentRunController()
        var reads = 0
        val awaiter = DisplayWindowAwaiter({ 0L }) { delay ->
            controller.cancel()
            controller.awaitRetryDelay(delay)
        }
        assertThrows(AgentRunCancelledException::class.java) {
            awaiter.await<String>(5000) {
                controller.throwIfCancelled()
                reads++
                throw DisplayWindowNotReadyException("loading")
            }
        }
        assertEquals(1, reads)
    }

    @Test fun pauseAndResumeInvalidateTheOriginalLeaseBeforeAnotherWindowRead() {
        val lease = DisplayLease()
        val epoch = lease.acquire("run")
        val controller = AgentRunController()
        val binding = controller.observePause { if (it) lease.pause() else lease.acquire("run") }
        var reads = 0
        val awaiter = DisplayWindowAwaiter({ 0L }) {
            controller.pause()
            controller.resume()
        }
        try {
            val failure = assertThrows(IllegalStateException::class.java) {
                awaiter.await<String>(5000) {
                    controller.throwIfCancelled()
                    check(lease.accepts("run", epoch)) { "lease changed" }
                    reads++
                    throw DisplayWindowNotReadyException("loading")
                }
            }
            assertEquals("lease changed", failure.message)
            assertEquals(1, reads)
        } finally { binding.close() }
    }
}

package io.github.mangi.eta.agent.display

import io.github.mangi.eta.agent.runtime.AgentRunController
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class DisplayPauseTest {
    @Test fun pauseRevokesLeaseBeforeReturningAndResumeRenewsBeforeLoopWakes() {
        val lease = DisplayLease()
        val first = lease.acquire("run")
        val controller = AgentRunController()
        val binding = controller.observePause { if (it) lease.pause() else lease.acquire("run") }
        controller.pause()
        assertFalse(lease.accepts("run", first))
        val woke = CountDownLatch(1)
        val task = thread { controller.throwIfCancelled(); assertEquals(DisplayLease.State.RUNNING, lease.state); woke.countDown() }
        assertFalse(woke.await(50, TimeUnit.MILLISECONDS))
        controller.resume()
        assertTrue(woke.await(2, TimeUnit.SECONDS))
        task.join(2000); binding.close()
    }
    @Test fun failedRenewalKeepsLoopPaused() {
        val controller = AgentRunController()
        controller.observePause { if (!it) error("broker unavailable") }
        controller.pause()
        assertThrows(IllegalStateException::class.java) { controller.resume() }
        val woke = CountDownLatch(1)
        val task = thread {
            runCatching { controller.throwIfCancelled() }
            woke.countDown()
        }
        assertFalse(woke.await(50, TimeUnit.MILLISECONDS))
        controller.cancel()
        assertTrue(woke.await(2, TimeUnit.SECONDS)); task.join(2000)
    }
}

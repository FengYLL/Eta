package io.github.mangi.eta.agent.display

import io.github.mangi.eta.agent.runtime.AgentRunController
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class DisplayPauseTest {
    @Test fun pauseAcknowledgementWaitsForNodeSubmission() {
        val gate = DisplayActionGate()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val paused = CountDownLatch(1)
        val node = thread { gate.commit { entered.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS)) } }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val pause = thread { gate.commit { paused.countDown() } }
        assertFalse(paused.await(50, TimeUnit.MILLISECONDS))
        release.countDown()
        assertTrue(paused.await(2, TimeUnit.SECONDS))
        node.join(2000); pause.join(2000)
    }
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

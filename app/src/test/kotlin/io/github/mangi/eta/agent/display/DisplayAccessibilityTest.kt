package io.github.mangi.eta.agent.display

import android.app.Application
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@Suppress("DEPRECATION")
class DisplayAccessibilityTest {
    private lateinit var service: AgentAccessibilityService

    @Before fun connect() {
        service = Robolectric.buildService(AgentAccessibilityService::class.java).create().get()
        ReflectionHelpers.callInstanceMethod<Unit>(service, "onServiceConnected")
    }

    @After fun disconnect() { service.onDestroy() }

    private fun window(id: Int, packageName: String? = "com.tencent.mm", layer: Int = 1): AccessibilityWindowInfo =
        AccessibilityWindowInfo.obtain().also { window ->
            shadowOf(window).apply {
                setId(id)
                setType(AccessibilityWindowInfo.TYPE_APPLICATION)
                setLayer(layer)
                if (packageName != null) setRoot(AccessibilityNodeInfo.obtain().apply {
                    this.packageName = packageName
                    isVisibleToUser = true
                    setBoundsInScreen(Rect(0, 0, 900, 1600))
                })
            }
        }

    @Test fun mainDisplayWindowCannotSatisfyMissingWorkDisplay() {
        shadowOf(service).setWindowsOnDisplay(0, listOf(window(10, "main.app")))
        val failure = assertThrows(DisplayWindowNotReadyException::class.java) { DisplayAccessibility.tree(7) }
        assertTrue(failure.message!!.contains("display=7"))
        assertTrue(failure.message!!.contains("上报显示器=[0]"))
        assertFalse(failure.message!!.contains("launch_app"))
    }

    @Test fun startupRecoversWhenSystemReportsWorkDisplayLater() {
        shadowOf(service).setWindowsOnDisplay(0, listOf(window(10, "main.app")))
        var now = 0L
        var retries = 0
        val awaiter = DisplayWindowAwaiter({ now }) { delay ->
            now += delay
            retries++
            shadowOf(service).setWindowsOnDisplay(7, listOf(window(20)))
        }
        checkNotNull(awaiter.await(5000) { DisplayAccessibility.tree(7) }).use { tree ->
            assertEquals("com.tencent.mm", tree.packageName)
            assertEquals(20, tree.window)
        }
        assertEquals(1, retries)
    }

    @Test fun missingRootIsRetriedUntilItBecomesReadable() {
        shadowOf(service).setWindowsOnDisplay(7, listOf(window(20, packageName = null)))
        var now = 0L
        val awaiter = DisplayWindowAwaiter({ now }) { delay ->
            now += delay
            shadowOf(service).setWindowsOnDisplay(7, listOf(window(20)))
        }
        checkNotNull(awaiter.await(5000) { DisplayAccessibility.tree(7) }).use { tree ->
            assertEquals("com.tencent.mm", tree.packageName)
        }
        assertEquals(200L, now)
    }

    @Test fun unreadableTopWindowNeverFallsThroughToUnderlyingApplication() {
        shadowOf(service).setWindowsOnDisplay(7, listOf(window(20), window(21, packageName = null, layer = 2)))
        assertThrows(DisplayWindowNotReadyException::class.java) { DisplayAccessibility.tree(7) }
    }

    @Test fun systemOverlayIsRejectedWithoutReadinessRetries() {
        val overlay = window(21, layer = 2)
        shadowOf(overlay).setType(AccessibilityWindowInfo.TYPE_SYSTEM)
        shadowOf(service).setWindowsOnDisplay(7, listOf(window(20), overlay))
        val awaiter = DisplayWindowAwaiter({ 0L }) { fail("System overlay must not be retried") }
        val failure = assertThrows(IllegalStateException::class.java) {
            awaiter.await(5000) { DisplayAccessibility.tree(7) }
        }
        assertFalse(failure is DisplayWindowNotReadyException)
        assertTrue(failure.message!!.contains("系统窗口遮挡"))
    }
}

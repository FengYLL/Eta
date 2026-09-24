package io.github.fengyl.eta

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppProcessPolicyTest {
    @Test
    fun `仅主进程初始化完整 Runtime 依赖`() {
        assertTrue(AppProcessPolicy.shouldInitializeFullRuntime("io.github.fengyl.eta", "io.github.fengyl.eta"))
        assertFalse(AppProcessPolicy.shouldInitializeFullRuntime("io.github.fengyl.eta:voice", "io.github.fengyl.eta"))
        assertFalse(AppProcessPolicy.shouldInitializeFullRuntime("io.github.fengyl.eta:voice_session", "io.github.fengyl.eta"))
    }
}

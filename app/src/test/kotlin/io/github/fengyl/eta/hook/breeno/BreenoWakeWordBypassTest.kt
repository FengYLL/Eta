package io.github.fengyl.eta.hook.breeno

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.fengyl.eta.config.Prefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class BreenoWakeWordBypassTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        Prefs.attachRemote(null)
    }

    @After
    fun tearDown() {
        Prefs.attachRemote(null)
    }

    @Test
    fun defaultSettingsBypassWakewordPrefix() {
        // 默认情况下，AGENT_CUSTOM_MODEL=true, AGENT_REQUIRE_PREFIX=false, AGENT_BYPASS_WAKEWORD_PREFIX=true
        assertNull(BreenoHooks.resolveCustomModelPrompt("小布小布，今天天气"))
        assertNull(BreenoHooks.resolveCustomModelPrompt("小布小布"))
        assertNull(BreenoHooks.resolveCustomModelPrompt("  小布小布，帮我定个闹钟"))

        // 常规输入正常接管
        assertEquals("今天天气", BreenoHooks.resolveCustomModelPrompt("今天天气"))

        // /agent 具有最高优先级，强制接管
        assertEquals("小布小布，今天天气", BreenoHooks.resolveCustomModelPrompt("/agent 小布小布，今天天气"))
        assertEquals("小布小布，今天天气", BreenoHooks.resolveCustomModelPrompt("/agent%20小布小布，今天天气"))
    }

    @Test
    fun disabledBypassSettingAllowsWakewordTakeover() {
        val sharedPrefs = context.getSharedPreferences("test_breeno_prefs", Context.MODE_PRIVATE)
        sharedPrefs.edit()
            .putBoolean(Prefs.Keys.AGENT_CUSTOM_MODEL, true)
            .putBoolean(Prefs.Keys.AGENT_REQUIRE_PREFIX, false)
            .putBoolean(Prefs.Keys.AGENT_BYPASS_WAKEWORD_PREFIX, false)
            .commit()

        Prefs.attachRemote(sharedPrefs)

        // 开关关闭后，小布小布开头仍被自定义模型接管
        assertEquals("小布小布，今天天气", BreenoHooks.resolveCustomModelPrompt("小布小布，今天天气"))
    }

    @Test
    fun customModelDisabledStillAllowsExplicitAgentPrefix() {
        val sharedPrefs = context.getSharedPreferences("test_breeno_prefs", Context.MODE_PRIVATE)
        sharedPrefs.edit()
            .putBoolean(Prefs.Keys.AGENT_CUSTOM_MODEL, false)
            .commit()

        Prefs.attachRemote(sharedPrefs)

        assertNull(BreenoHooks.resolveCustomModelPrompt("今天天气"))
        // The explicit experimental prefix is a force-entry independent of the automatic switch.
        assertEquals("今天天气", BreenoHooks.resolveCustomModelPrompt("/agent 今天天气"))
    }
}

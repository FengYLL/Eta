package io.github.mangi.eta.agent.display

import io.github.mangi.eta.agent.model.AgentToolCatalog
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DisplayToolPolicyTest {
    private fun catalog(isolated: Boolean) = AgentToolCatalog.build(
        terminalTools = true, browserTools = true, deviceDirectTools = true,
        deviceSensitiveReadTools = true, deviceSensitiveActionTools = true,
        skillGitHubDiscovery = true, skillGitHubInstall = true, memoryTools = true,
        capabilities = AgentToolCapabilities(rootAvailable = true, isolatedDisplay = isolated),
    )

    private fun functions(tools: JSONArray): List<JSONObject> =
        (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function") }

    @Test fun isolatedCatalogCannotExposeGlobalOrUnregisteredTools() {
        val names = functions(catalog(true)).map { it.getString("name") }.toSet()
        assertTrue(names.containsAll(DisplayToolPolicy.gui))
        assertTrue(names.all(DisplayToolPolicy::allows))
        assertFalse("terminal" in names)
        assertFalse("browser_use" in names)
        assertFalse("set_clipboard" in names)
        assertEquals("DISPLAY_SCOPE_REQUIRED", AgentToolCapabilities(rootAvailable = true, isolatedDisplay = true)
            .unavailableCode("future_tool"))
    }

    @Test fun workScreenNarrowsKeyAndTextActionsWithoutChangingOrdinaryCatalog() {
        fun props(isolated: Boolean, name: String) = functions(catalog(isolated)).single { it.getString("name") == name }
            .getJSONObject("parameters").getJSONObject("properties")
        assertEquals("[\"BACK\"]", props(true, "press_key").getJSONObject("button").getJSONArray("enum").toString())
        assertEquals("[\"append\",\"replace\"]", props(true, "input_text").getJSONObject("mode").getJSONArray("enum").toString())
        assertTrue(props(false, "press_key").getJSONObject("button").getJSONArray("enum").length() > 1)
        val normal = functions(catalog(false)).map { it.getString("name") }
        assertTrue("terminal" in normal)
        assertTrue("browser_use" in normal)
    }
}

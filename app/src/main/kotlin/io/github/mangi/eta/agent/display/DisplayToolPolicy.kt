package io.github.mangi.eta.agent.display

import org.json.JSONArray
import org.json.JSONObject

/** An allowlist is intentional: newly introduced tools must not silently escape the display. */
internal object DisplayToolPolicy {
    val gui = setOf("observe_screen", "launch_app", "open_uri", "tap", "tap_area", "tap_element",
        "long_press", "long_press_element", "swipe", "scroll", "scroll_element", "input_text",
        "replace_text", "clear_text", "press_key", "wait", "wait_for_text", "wait_for_package")
    private val passive = setOf("search_apps", "memory_get", "memory_write", "skills_list", "skills_read",
        "skills_read_resource", "conversation_history")
    fun allows(name: String): Boolean = name in gui || name in passive

    fun project(schema: JSONObject): JSONObject {
        val copy = JSONObject(schema.toString())
        val function = copy.getJSONObject("function")
        val name = function.getString("name")
        val properties = function.optJSONObject("parameters")?.optJSONObject("properties")
        when (name) {
            "press_key" -> properties?.optJSONObject("button")?.put("enum", JSONArray(listOf("back")))
            "input_text" -> properties?.optJSONObject("mode")?.put("enum", JSONArray(listOf("append", "replace")))
            "open_uri" -> function.put("description", "在工作屏打开 http/https 网页；其他协议需要用户主动接管。")
        }
        if (name in gui) function.put("description", function.optString("description") +
            " 当前为独立工作屏：动作后、暂停恢复后必须重新 observe_screen；不支持的操作会暂停等待用户。")
        return copy
    }
}

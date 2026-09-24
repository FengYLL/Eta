package io.github.fengyl.eta.agent.display

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
            "press_key" -> {
                properties?.optJSONObject("button")?.put("enum", JSONArray(listOf("BACK")))
                function.put("description", "只向工作屏发送 BACK；不执行主屏全局动作。")
            }
            "input_text" -> {
                properties?.optJSONObject("mode")
                    ?.put("enum", JSONArray(listOf("append", "replace")))
                    ?.put("description", "append 在目标节点光标处插入或替换选区；replace 替换完整值。禁止共享剪贴板。")
                function.put(
                    "description",
                    function.optString("description") +
                        " 工作屏没有普通输入法：如果观察结果只有一个 editable 节点，即使 focused=false 也可省略 index；多个 editable 节点时才提供 index 和 observation_id。",
                )
            }
            "replace_text", "clear_text" -> function.put(
                "description",
                function.optString("description") +
                    " 工作屏没有普通输入法：如果观察结果只有一个 editable 节点，即使 focused=false 也可省略 index；多个 editable 节点时才提供 index 和 observation_id。",
            )
            "wait_for_text" -> properties?.optJSONObject("match")?.put("enum", JSONArray(listOf("contains", "exact", "prefix")))
            "open_uri" -> function.put("description", "在工作屏打开 http/https 网页；其他协议需要用户主动接管。")
        }
        if (name in gui) function.put("description", function.optString("description") +
            " 当前为独立工作屏：动作后、暂停恢复后必须重新 observe_screen；不支持的操作会暂停等待用户。")
        return copy
    }
}

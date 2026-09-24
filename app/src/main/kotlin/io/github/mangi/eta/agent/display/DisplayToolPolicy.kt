package io.github.mangi.eta.agent.display

/** An allowlist is intentional: newly introduced tools must not silently escape the display. */
internal object DisplayToolPolicy {
    val gui = setOf("observe_screen", "launch_app", "open_uri", "tap", "tap_area", "tap_element",
        "long_press", "long_press_element", "swipe", "scroll", "scroll_element", "input_text",
        "replace_text", "clear_text", "press_key", "wait", "wait_for_text", "wait_for_package")
    private val passive = setOf("search_apps", "memory_get", "memory_write", "skills_list", "skills_read",
        "skills_read_resource", "conversation_history")
    fun allows(name: String): Boolean = name in gui || name in passive
}

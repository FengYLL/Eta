package io.github.mangi.eta.agent.display

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import io.github.mangi.eta.agent.media.AgentImageCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentRunController
import java.util.UUID
import org.json.JSONObject

/** One run, one display. All GUI tools are handled here, with no route to RootShellDeviceController. */
internal class DisplayLocalTools(
    private val context: Context,
    private val run: String,
    private val controller: AgentRunController,
) : AutoCloseable {
    private var observation: Observation? = null
    private var pauseBinding: AgentRunController.ResourceBinding? = null
    private data class Observation(val id: String, val session: String, val epoch: Long, val tree: DisplayAccessibility.Tree)

    init {
        DisplaySessionStore.acquire(context, run, controller)
        pauseBinding = controller.observePause { DisplaySessionStore.onRunPause(run, it) }
    }

    override fun close() {
        pauseBinding?.close()
        clearObservation()
        DisplaySessionStore.retain(run)
    }
    private fun clearObservation() { observation?.tree?.close(); observation = null }

    fun execute(call: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        return try {
            check(DisplayToolPolicy.allows(call.name)) { "此工具无法隔离到副屏，需要暂停并主动接管" }
            val args = JSONObject(call.argumentsJson.ifBlank { "{}" })
            when (call.name) {
                "observe_screen" -> observe(args)
                "launch_app" -> {
                    val packageName = args.optString("package_name").ifBlank {
                        val name = args.optString("app_name")
                        val matches = context.packageManager.getInstalledApplications(0).filter {
                            context.packageManager.getApplicationLabel(it).toString().equals(name, true)
                        }
                        check(matches.size == 1) { "应用名不明确，请 search_apps 后提供 package_name" }
                        matches.single().packageName
                    }
                    val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: error("应用不可启动")
                    launch(intent)
                }
                "open_uri" -> {
                    val uri = Uri.parse(args.getString("uri"))
                    require(uri.scheme in setOf("https", "http")) { "副屏仅允许明确的网页 URI，其他协议请主动接管" }
                    launch(Intent(Intent.ACTION_VIEW, uri))
                }
                "wait" -> {
                    val end = SystemClock.uptimeMillis() + args.optLong("duration_ms", 1000).coerceIn(0, 10000)
                    while (SystemClock.uptimeMillis() < end) { controller.throwIfCancelled(); SystemClock.sleep(50) }
                    success()
                }
                "wait_for_text", "wait_for_package" -> waitFor(call.name, args)
                "press_key" -> {
                    check(args.optString("button").lowercase() == "back") { "副屏只允许定向 BACK；Home、最近任务等需要主动接管" }
                    val obs = requireObservation(args)
                    DisplaySessionStore.action("back", run, obs.epoch)
                    clearObservation(); success()
                }
                "tap", "tap_area", "long_press", "swipe", "scroll" -> gesture(call.name, args)
                "tap_element", "long_press_element", "scroll_element", "input_text", "replace_text", "clear_text" -> nodeAction(call.name, args)
                else -> error("副屏不支持此操作")
            }
        } catch (failure: Exception) {
            clearObservation()
            DisplaySessionStore.pause()
            AgentModelClient.ToolResult(JSONObject().put("ok", false).put("code", "WORK_DISPLAY_PAUSED")
                .put("message", "${failure.cause?.message ?: failure.message}。已暂停；请查看工作屏，继续前重新观察，或主动接管。已提交动作不会重放。")
                .toString())
        }
    }

    private fun launch(intent: Intent): AgentModelClient.ToolResult {
        val state = DisplaySessionStore.refresh()
        DisplaySessionStore.action("launch", run, state.epoch, Bundle().apply { putParcelable("intent", intent) })
        clearObservation()
        return success("应用已定向启动，请重新观察副屏")
    }

    private fun observe(args: JSONObject): AgentModelClient.ToolResult {
        clearObservation()
        val state = DisplaySessionStore.refresh()
        DisplaySessionStore.action("validate", run, state.epoch)
        val tree = DisplayAccessibility.tree(state.display, args.optInt("max_nodes", 120))
        try {
            val image = if (args.optBoolean("include_screenshot", true)) {
                val bitmap = DisplayAccessibility.screenshot(state.display)
                try {
                    check(bitmap.width == DisplayProtocol.WIDTH && bitmap.height == DisplayProtocol.HEIGHT) {
                        "副屏几何已改变，坐标映射不可用"
                    }
                    AgentImageCodec.fromScreenBitmap(bitmap, "work_display")
                } finally { bitmap.recycle() }
            } else null
            DisplaySessionStore.action("validate", run, state.epoch)
            DisplayAccessibility.tree(state.display, args.optInt("max_nodes", 120)).use { after ->
                check(after.window == tree.window && after.signature == tree.signature) { "截图期间窗口内容发生变化，请继续后重新观察" }
            }
            val id = UUID.randomUUID().toString()
            observation = Observation(id, state.session, state.epoch, tree)
            val json = JSONObject().put("ok", true).put("tool", "observe_screen").put("display_mode", "isolated")
                .put("observation_id", id).put("window_id", tree.window)
                .put("focus", JSONObject().put("package", tree.packageName))
                .put("screen", JSONObject().put("width", DisplayProtocol.WIDTH).put("height", DisplayProtocol.HEIGHT))
                .put("ui_nodes", tree.json()).put("coordinate_contract", "原始截图像素 = 工作屏坐标；动作后必须重新观察")
                .put("note", "普通输入法不可用；文字通过节点 setText。授权/支付/不兼容页面需要暂停并主动接管")
            return AgentModelClient.ToolResult(json.toString(), listOfNotNull(image))
        } catch (failure: Exception) { tree.close(); throw failure }
    }

    private fun requireObservation(args: JSONObject): Observation {
        val obs = observation ?: error("请先 observe_screen；动作、暂停或接管后的旧坐标不能使用")
        if (args.has("observation_id")) check(args.getString("observation_id") == obs.id) { "观察 ID 已过期" }
        val current = DisplaySessionStore.refresh()
        check(current.session == obs.session && current.epoch == obs.epoch) { "显示会话或操作代次已改变，请重新观察" }
        DisplaySessionStore.action("validate", run, obs.epoch)
        DisplayAccessibility.tree(current.display, obs.tree.nodes.size.coerceAtLeast(1)).use { tree ->
            check(tree.window == obs.tree.window && tree.signature == obs.tree.signature) { "页面已变化，旧观察不可操作" }
        }
        return obs
    }

    private fun gesture(name: String, args: JSONObject): AgentModelClient.ToolResult {
        val obs = requireObservation(args)
        val data = Bundle()
        when (name) {
            "scroll" -> {
                val direction = args.getString("direction")
                val x = DisplayProtocol.WIDTH / 2; val y = DisplayProtocol.HEIGHT / 2
                val dx = when (direction) { "left" -> 250; "right" -> -250; "up", "down" -> 0; else -> error("方向无效") }
                val dy = when (direction) { "up" -> 450; "down" -> -450; else -> 0 }
                data.putInt("x1", x - dx / 2); data.putInt("y1", y - dy / 2)
                data.putInt("x2", x + dx / 2); data.putInt("y2", y + dy / 2); data.putInt("duration", 400)
            }
            "swipe" -> {
                listOf("x1", "y1", "x2", "y2").forEach { data.putInt(it, args.getInt(it)) }
                data.putInt("duration", args.optInt("duration_ms", 400))
            }
            "tap_area" -> {
                data.putInt("x1", ((args.getInt("x1").toLong() + args.getInt("x2")) / 2).toInt())
                data.putInt("y1", ((args.getInt("y1").toLong() + args.getInt("y2")) / 2).toInt())
            }
            else -> {
                data.putInt("x1", args.getInt("x")); data.putInt("y1", args.getInt("y"))
                if (name == "long_press") data.putInt("duration", args.optInt("duration_ms", 650))
            }
        }
        DisplaySessionStore.action("touch", run, obs.epoch, data)
        clearObservation()
        return success()
    }

    private fun nodeAction(name: String, args: JSONObject): AgentModelClient.ToolResult {
        val obs = requireObservation(args)
        val selected = if (args.has("index")) obs.tree.nodes.getOrNull(args.getInt("index"))
            else obs.tree.nodes.singleOrNull { it.value.isEditable && it.value.isFocused }
        val node = selected ?: error("没有唯一的目标节点，请提供 index 和 observation_id")
        check(node.value.refresh() && DisplayAccessibility.identity(node.value) == node.identity) { "节点已失效" }
        check(node.value.isEnabled) { "目标节点不可用" }
        val text = when (name) {
            "input_text" -> when (args.optString("mode", "append")) {
                "replace" -> args.getString("text")
                "append" -> node.value.text.orEmpty().toString() + args.getString("text")
                else -> error("副屏不支持共享剪贴板粘贴，请使用 replace_text")
            }
            "replace_text" -> args.getString("text")
            "clear_text" -> ""
            else -> null
        }
        val action = when (name) {
            "tap_element" -> AccessibilityNodeInfo.ACTION_CLICK
            "long_press_element" -> AccessibilityNodeInfo.ACTION_LONG_CLICK
            "scroll_element" -> when (args.getString("direction")) {
                "down" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
                "up" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
                "left" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
                "right" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
                else -> error("方向无效")
            }
            else -> {
                check(node.value.isEditable && text!!.length <= 10000) { "节点不可编辑或文字过长" }
                check(name != "input_text" || !node.value.isPassword) { "密码节点不能安全追加文字，请使用 replace_text 或主动接管" }
                AccessibilityNodeInfo.ACTION_SET_TEXT
            }
        }
        DisplaySessionStore.action("validate", run, obs.epoch)
        check(node.value.performAction(action, text?.let { Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, it) } })) {
            "应用拒绝节点操作，可能依赖 IME，请主动接管"
        }
        if (text != null && !node.value.isPassword) {
            check(node.value.refresh() && node.value.text.orEmpty().toString() == text) { "文字提交结果无法确认，不自动重试" }
        }
        clearObservation()
        return success()
    }

    private fun waitFor(name: String, args: JSONObject): AgentModelClient.ToolResult {
        val expected = args.getString(if (name == "wait_for_text") "text" else "package_name")
        val deadline = SystemClock.uptimeMillis() + args.optLong("timeout_ms", 5000).coerceIn(0, 15000)
        do {
            controller.throwIfCancelled()
            val state = DisplaySessionStore.refresh()
            DisplaySessionStore.action("validate", run, state.epoch)
            DisplayAccessibility.tree(state.display).use { tree ->
                if (if (name == "wait_for_package") tree.packageName == expected else tree.nodes.any {
                        !it.value.isPassword && (it.value.text?.contains(expected) == true || it.value.contentDescription?.contains(expected) == true)
                    }) return success("条件已出现，请重新观察后操作")
            }
            SystemClock.sleep(250)
        } while (SystemClock.uptimeMillis() < deadline)
        return AgentModelClient.ToolResult(JSONObject().put("ok", false).put("code", "TIMEOUT").toString())
    }

    private fun success(message: String = "动作已提交，请重新观察；不自动重放") =
        AgentModelClient.ToolResult(JSONObject().put("ok", true).put("message", message).toString())
}

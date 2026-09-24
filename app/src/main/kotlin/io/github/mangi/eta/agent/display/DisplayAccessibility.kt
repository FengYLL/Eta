package io.github.mangi.eta.agent.display

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/** Never consults rootInActiveWindow or the default display. */
internal object DisplayAccessibility {
    data class Node(val index: Int, val value: AccessibilityNodeInfo, val bounds: Rect, val identity: String)
    class Tree(val window: Int, val packageName: String, val nodes: List<Node>) : AutoCloseable {
        val signature = nodes.joinToString("|") { it.identity }
        override fun close() { nodes.forEach { @Suppress("DEPRECATION") it.value.recycle() } }
        fun json(): JSONArray = JSONArray().also { array ->
            nodes.forEach { n ->
                val v = n.value
                array.put(JSONObject().put("index", n.index).put("text", if (v.isPassword) "" else v.text?.toString().orEmpty())
                    .put("desc", v.contentDescription?.toString().orEmpty()).put("class", v.className?.toString().orEmpty())
                    .put("package", v.packageName?.toString().orEmpty()).put("view_id", v.viewIdResourceName.orEmpty())
                    .put("clickable", v.isClickable).put("editable", v.isEditable).put("focused", v.isFocused)
                    .put("scrollable", v.isScrollable).put("enabled", v.isEnabled).put("password", v.isPassword)
                    .put("bounds", JSONArray(listOf(n.bounds.left, n.bounds.top, n.bounds.right, n.bounds.bottom)))
                    .put("center", JSONObject().put("x", n.bounds.centerX()).put("y", n.bounds.centerY())))
            }
        }
    }

    fun tree(displayId: Int, maxNodes: Int = 120): Tree {
        require(displayId > 0)
        val service = AgentAccessibilityService.current() ?: error("请先启用 Eta 无障碍服务")
        val allWindows = service.windowsOnAllDisplays
        val windows = allWindows[displayId].orEmpty()
        fun notReady(reason: String): Nothing {
            val reportedDisplays = (0 until allWindows.size()).joinToString(",") { allWindows.keyAt(it).toString() }
            val windowTypes = windows.joinToString(",") { "${it.id}:${it.type}" }
            // Migration can leave an empty window list or a stale root in the service cache.
            // The next bounded attempt must query the system again, never the main display.
            service.clearCache()
            throw DisplayWindowNotReadyException(
                "$reason（display=$displayId，上报显示器=[$reportedDisplays]，窗口id:type=[$windowTypes]）",
            )
        }
        val window = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedByDescending { it.layer }
            .firstOrNull() ?: notReady("副屏应用窗口尚未就绪或尚未上报")
        check(windows.none { it.layer > window.layer && it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }) {
            "副屏被系统窗口遮挡，请主动接管；不操作下层页面"
        }
        val root = window.root ?: notReady("副屏应用窗口的无障碍节点尚不可读取")
        val packageName = root.packageName?.toString().orEmpty()
        val nodes = mutableListOf<Node>()
        val pending = java.util.ArrayDeque<AccessibilityNodeInfo>()
        pending.add(root)
        var visited = 0
        try {
            while (pending.isNotEmpty() && nodes.size < maxNodes.coerceIn(1, 120) && visited++ < 600) {
                val node = pending.removeFirst()
                for (i in 0 until node.childCount.coerceAtMost(120)) node.getChild(i)?.let(pending::add)
                val bounds = Rect().also(node::getBoundsInScreen)
                if (node.isVisibleToUser && !bounds.isEmpty) nodes += Node(nodes.size, node, bounds, identity(node, bounds))
                else @Suppress("DEPRECATION") node.recycle()
            }
            return Tree(window.id, packageName, nodes)
        } catch (failure: Exception) {
            nodes.forEach { @Suppress("DEPRECATION") it.value.recycle() }
            throw failure
        } finally {
            pending.forEach { @Suppress("DEPRECATION") it.recycle() }
        }
    }

    fun identity(node: AccessibilityNodeInfo, bounds: Rect = Rect().also(node::getBoundsInScreen)): String =
        listOf(node.windowId, node.uniqueId, node.packageName, node.viewIdResourceName, node.className,
            if (node.isPassword) "password" else node.text, node.contentDescription, bounds.toShortString(), node.isEditable,
            node.isFocused, node.textSelectionStart, node.textSelectionEnd, node.isEnabled).joinToString("\u0001")

    fun screenshot(displayId: Int): Bitmap {
        require(displayId > 0)
        val service = AgentAccessibilityService.current() ?: error("请先启用 Eta 无障碍服务")
        val result = CompletableFuture<Bitmap>()
        service.takeScreenshot(displayId, service.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                try {
                    val bitmap = screenshot.hardwareBuffer.use { buffer ->
                        val hardware = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace) ?: error("副屏截图不可读")
                        try { hardware.copy(Bitmap.Config.ARGB_8888, false) ?: error("副屏截图转换失败") }
                        finally { hardware.recycle() }
                    }
                    if (!result.complete(bitmap)) bitmap.recycle()
                } catch (failure: Exception) { result.completeExceptionally(failure) }
            }
            override fun onFailure(errorCode: Int) {
                result.completeExceptionally(IllegalStateException("副屏截图失败 ($errorCode)，安全窗口或 ROM 可能不支持；不回退主屏"))
            }
        })
        return try { result.get(4, TimeUnit.SECONDS) }
        catch (failure: Exception) {
            if (!result.cancel(false) && !result.isCompletedExceptionally) result.getNow(null)?.recycle()
            throw failure
        }
    }
}

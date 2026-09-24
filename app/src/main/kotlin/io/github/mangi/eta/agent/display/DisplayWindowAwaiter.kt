package io.github.mangi.eta.agent.display

/** Only missing windows/roots are transient; routing, security and lease errors must propagate. */
internal class DisplayWindowNotReadyException(message: String) : IllegalStateException(message)

/** Polls observations only. Callers must never put a launch or another input action in [read]. */
internal class DisplayWindowAwaiter(
    private val uptimeMillis: () -> Long,
    private val waitForRetry: (Long) -> Unit,
) {
    fun <T : Any> await(timeoutMs: Long, read: () -> T?): T? {
        require(timeoutMs >= 0)
        val started = uptimeMillis()
        while (true) {
            var unavailable: DisplayWindowNotReadyException? = null
            try {
                read()?.let { return it }
            } catch (failure: DisplayWindowNotReadyException) {
                unavailable = failure
            }
            val remaining = timeoutMs - (uptimeMillis() - started)
            if (remaining <= 0) {
                if (unavailable != null) throw DisplayWindowNotReadyException(
                    "等待副屏窗口就绪超时（${timeoutMs}ms）：${unavailable.message}。" +
                        "若工作屏已有画面，可能是系统未向无障碍服务上报副屏窗口；请检查无障碍服务或主动接管。",
                )
                return null
            }
            waitForRetry(minOf(200L, remaining))
        }
    }
}

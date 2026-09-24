package io.github.mangi.eta.agent.display

/** Viewer ownership is separate from the automation lease. All times use uptimeMillis. */
internal class PreviewSurfaceLease<T : Any>(
    private val background: T,
    private val output: (T) -> Unit,
    private val release: (T) -> Unit,
    private val timeoutMs: Long = 5_000,
) {
    private var latestOwner = 0L
    private var owner = 0L
    private var surface: T? = null
    private var deadline = 0L
    private var closed = false

    /** Takes ownership of [next], including rejected or failed attachments. */
    @Synchronized fun attach(id: Long, next: T, now: Long) {
        try {
            check(!closed && id > latestOwner) { "观看页面已更换，请重新连接" }
            output(next)
        } catch (failure: Throwable) {
            release(next)
            throw failure
        }
        val previous = surface
        latestOwner = id
        owner = id
        surface = next
        deadline = now + timeoutMs
        previous?.let(release)
    }

    @Synchronized fun renew(id: Long, now: Long): Boolean {
        if (closed || surface == null || owner != id) return false
        deadline = now + timeoutMs
        return true
    }

    @Synchronized fun detach(id: Long) {
        // Also revoke an attachment which has not reached the broker yet.
        latestOwner = maxOf(latestOwner, id)
        if (surface != null && owner <= id) toBackground()
    }

    @Synchronized fun expire(now: Long) {
        if (surface != null && now >= deadline) toBackground()
    }

    private fun toBackground() {
        // Never set null: it would turn the virtual display off.
        output(background)
        val previous = surface
        surface = null
        owner = 0
        previous?.let(release)
    }

    /** Called after the VirtualDisplay is released; does not own the background reader. */
    @Synchronized fun close() {
        closed = true
        val previous = surface
        surface = null
        owner = 0
        previous?.let(release)
    }
}

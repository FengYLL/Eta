package io.github.mangi.eta.agent.display

import android.app.Activity
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.mangi.eta.R
import java.util.concurrent.Executors

/** A viewer of the service-owned display. Leaving this Activity only detaches the viewer. */
class WorkDisplayActivity : Activity(), TextureView.SurfaceTextureListener {
    private lateinit var status: TextView
    private lateinit var preview: TextureView
    private lateinit var placeholder: TextView
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var resumed = false
    @Volatile private var visibilityGeneration = 0L
    @Volatile private var output: Output? = null
    // Only accessed on the shared worker. It outlives Activity surface destruction callbacks.
    private var attached: Attachment? = null
    private class Output(val texture: SurfaceTexture, val surface: Surface)
    private data class Attachment(val output: Output, val session: String, val viewer: Long)

    private val polling = object : Runnable {
        override fun run() {
            if (!resumed || isDestroyed) return
            val generation = visibilityGeneration
            worker.execute {
                val error = runCatching {
                    if (DisplaySessionStore.state.value.session.isNotEmpty()) DisplaySessionStore.refresh()
                    syncPreview(generation)
                }.exceptionOrNull()
                main.post {
                    if (resumed && !isDestroyed && generation == visibilityGeneration) {
                        showStatus(error?.let { it.cause?.message ?: it.message })
                        main.postDelayed(this, 1000)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (12 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom)
                insets
            }
        }
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        controls.addView(TextView(this).apply { setText(R.string.work_display_title); textSize = 22f })
        controls.addView(TextView(this).apply { setText(R.string.work_display_description) })
        controls.addView(CheckBox(this).apply {
            setText(R.string.work_display_enable)
            isChecked = DisplaySessionStore.enabled(this@WorkDisplayActivity)
            setOnCheckedChangeListener { _, checked -> DisplaySessionStore.setEnabled(this@WorkDisplayActivity, checked) }
        })
        status = TextView(this).apply { setPadding(0, pad, 0, 0) }
        controls.addView(status)
        fun row(vararg buttons: Pair<Int, () -> Unit>) {
            val row = LinearLayout(this)
            buttons.forEach { (label, action) ->
                row.addView(Button(this).apply {
                    setText(label)
                    setOnClickListener { submit(action) }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            controls.addView(row)
        }
        row(R.string.work_display_create to { DisplaySessionStore.create(applicationContext) },
            R.string.work_display_pause to { DisplaySessionStore.pause() },
            R.string.work_display_resume to { DisplaySessionStore.resume() })
        row(R.string.work_display_stop to { DisplaySessionStore.stop() },
            R.string.work_display_takeover to { detachPreview(); DisplaySessionStore.takeover() },
            R.string.work_display_close to { detachPreview(); DisplaySessionStore.close() })
        controls.addView(TextView(this).apply { setText(R.string.work_display_snapshot_note) })
        // Controls remain reachable with large fonts and in landscape.
        val scroll = ScrollView(this).apply { addView(controls) }
        layout.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        preview = TextureView(this).apply {
            surfaceTextureListener = this@WorkDisplayActivity
            contentDescription = getString(R.string.work_display_preview)
            isOpaque = true
        }
        placeholder = TextView(this).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            setTextColor(Color.WHITE)
            setText(R.string.work_display_preview_waiting)
        }
        val frame = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(preview, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
            addView(placeholder, FrameLayout.LayoutParams(-1, -1))
            addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
                val width = right - left
                val height = bottom - top
                val scale = minOf(width.toFloat() / DisplayProtocol.WIDTH, height.toFloat() / DisplayProtocol.HEIGHT)
                if (scale > 0) {
                    val params = preview.layoutParams as FrameLayout.LayoutParams
                    val w = (DisplayProtocol.WIDTH * scale).toInt().coerceAtLeast(1)
                    val h = (DisplayProtocol.HEIGHT * scale).toInt().coerceAtLeast(1)
                    if (params.width != w || params.height != h) {
                        params.width = w; params.height = h; preview.layoutParams = params
                    }
                }
            }
        }
        layout.addView(frame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        layout.addView(Button(this).apply {
            setText(R.string.work_display_background)
            setOnClickListener { finish() }
        })
        setContentView(layout)
        showStatus()
    }

    private fun submit(action: () -> Unit) {
        status.setText(R.string.work_display_busy)
        val generation = visibilityGeneration
        worker.execute {
            val error = runCatching { action(); syncPreview(generation) }.exceptionOrNull()
            main.post { if (!isDestroyed) showStatus(error?.let { it.cause?.message ?: it.message }) }
        }
    }

    private fun viewable(snapshot: DisplaySessionStore.Snapshot): Boolean =
        snapshot.session.isNotEmpty() && snapshot.state in setOf("READY", "RUNNING", "PAUSED", "RETAINED")

    private fun syncPreview(generation: Long) {
        val snapshot = DisplaySessionStore.state.value
        val next = output
        if (!resumed || generation != visibilityGeneration || next == null || !viewable(snapshot)) {
            detachPreview()
            return
        }
        val current = attached
        if (current?.output === next && current.session == snapshot.session) {
            try { DisplaySessionStore.renewPreview(current.session, current.viewer) }
            catch (failure: Exception) { detachPreview(); throw failure }
        } else {
            detachPreview()
            val target = Attachment(next, snapshot.session, DisplaySessionStore.nextViewerId())
            // Record before IPC so even an uncertain attachment will be revoked on departure.
            attached = target
            try { DisplaySessionStore.attachPreview(target.session, target.viewer, next.surface) }
            catch (failure: Exception) { detachPreview(); throw failure }
        }
    }

    private fun detachPreview() {
        val old = attached ?: return
        DisplaySessionStore.detachPreview(old.session, old.viewer)
        attached = null
    }

    private fun showStatus(error: String? = null) {
        val snapshot = DisplaySessionStore.state.value
        val label = when (snapshot.state) {
            "RUNNING" -> R.string.work_display_running
            "PAUSED" -> R.string.work_display_paused
            "RETAINED" -> R.string.work_display_retained
            "HUMAN" -> R.string.work_display_human
            "READY" -> R.string.work_display_ready
            "LOST" -> R.string.work_display_lost
            else -> R.string.work_display_closed
        }
        status.text = error ?: (getString(label) + if (snapshot.reason.isBlank()) "" else "\n${snapshot.reason}")
        if (!viewable(snapshot) || error != null) {
            placeholder.visibility = View.VISIBLE
            placeholder.text = error ?: getString(label)
        }
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        texture.setDefaultBufferSize(DisplayProtocol.WIDTH, DisplayProtocol.HEIGHT)
        output = Output(texture, Surface(texture))
    }
    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        // Viewer dimensions must never resize the agent's coordinate space.
        texture.setDefaultBufferSize(DisplayProtocol.WIDTH, DisplayProtocol.HEIGHT)
    }
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
        if (resumed && output?.texture === texture && viewable(DisplaySessionStore.state.value)) {
            placeholder.visibility = View.GONE
        }
    }
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        val old = output?.takeIf { it.texture === texture }
        if (old != null) output = null
        worker.execute {
            val detached = runCatching { if (attached?.output === old) detachPreview() }.isSuccess
            val release = Runnable { old?.surface?.release(); texture.release() }
            // Keep the consumer alive through detach. On IPC failure the broker watchdog
            // first restores its permanent ImageReader, even if the Activity disappeared.
            if (detached) release.run() else main.postDelayed(release, 6000)
        }
        return false
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        visibilityGeneration++
        main.post(polling)
    }
    override fun onPause() {
        resumed = false
        visibilityGeneration++
        main.removeCallbacks(polling)
        worker.execute { runCatching { detachPreview() } }
        super.onPause()
    }
    override fun onDestroy() {
        main.removeCallbacks(polling)
        super.onDestroy()
    }

    companion object {
        // Serializes old/new Activity viewers without blocking the UI or the agent loop.
        private val worker = Executors.newSingleThreadExecutor { Thread(it, "work-display-viewer") }
    }
}

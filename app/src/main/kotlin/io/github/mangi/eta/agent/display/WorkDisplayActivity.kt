package io.github.mangi.eta.agent.display

import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.mangi.eta.R
import java.util.concurrent.Executors

/** User-owned control surface; preview never replaces the display's producer Surface. */
class WorkDisplayActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var preview: ImageView
    private var bitmap: Bitmap? = null
    private var previewSession = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        layout.addView(TextView(this).apply { text = getString(R.string.work_display_title); textSize = 24f })
        layout.addView(TextView(this).apply { text = getString(R.string.work_display_description) })
        layout.addView(CheckBox(this).apply {
            text = getString(R.string.work_display_enable)
            isChecked = DisplaySessionStore.enabled(this@WorkDisplayActivity)
            setOnCheckedChangeListener { _, checked -> DisplaySessionStore.setEnabled(this@WorkDisplayActivity, checked) }
        })
        status = TextView(this).apply { setPadding(0, pad, 0, pad) }
        layout.addView(status)
        fun row(vararg buttons: Pair<Int, () -> Unit>) {
            val row = LinearLayout(this)
            buttons.forEach { (label, action) ->
                row.addView(Button(this).apply {
                    setText(label)
                    setOnClickListener { submit(action) }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            layout.addView(row)
        }
        row(R.string.work_display_create to { DisplaySessionStore.create(this) },
            R.string.work_display_preview to {
                val snapshot = DisplaySessionStore.refresh()
                check(snapshot.state !in setOf("HUMAN", "LOST", "CLOSED")) { getString(R.string.work_display_preview_unavailable) }
                val next = DisplayAccessibility.screenshot(snapshot.display)
                val after = DisplaySessionStore.refresh()
                if (after.session != snapshot.session || after.epoch != snapshot.epoch) { next.recycle(); error(getString(R.string.work_display_preview_unavailable)) }
                runOnUiThread {
                    if (isDestroyed) next.recycle() else {
                        preview.setImageBitmap(next); bitmap?.recycle(); bitmap = next
                        previewSession = snapshot.session
                    }
                }
            })
        row(R.string.work_display_pause to { DisplaySessionStore.pause() },
            R.string.work_display_resume to { DisplaySessionStore.resume() })
        row(R.string.work_display_takeover to { DisplaySessionStore.takeover() },
            R.string.work_display_close to { DisplaySessionStore.close() })
        preview = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = getString(R.string.work_display_preview) }
        layout.addView(preview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        layout.addView(TextView(this).apply { text = getString(R.string.work_display_snapshot_note) })
        setContentView(layout)
        showStatus()
    }

    private fun submit(action: () -> Unit) {
        status.setText(R.string.work_display_busy)
        worker.execute {
            val error = runCatching(action).exceptionOrNull()
            runOnUiThread {
                if (!isDestroyed) {
                    showStatus()
                    if (error != null) status.text = error.cause?.message ?: error.message
                }
            }
        }
    }

    private fun showStatus() {
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
        status.text = getString(label) + if (snapshot.reason.isBlank()) "" else "\n${snapshot.reason}"
        if (snapshot.session != previewSession || snapshot.state in setOf("HUMAN", "LOST", "CLOSED")) {
            preview.setImageDrawable(null); bitmap?.recycle(); bitmap = null
        }
    }

    override fun onDestroy() {
        worker.shutdown()
        preview.setImageDrawable(null); bitmap?.recycle(); bitmap = null
        super.onDestroy()
    }
}

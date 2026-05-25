package com.carnet.app

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Series catalog manager. The user creates one series per investigative protocol
 * (e.g. "Quit Smoking Spring 2026") and pins the Bios metrics that every take in
 * the series will render as intro slides. Selecting a series makes it the active
 * one for the next recording; only one series can be active at a time because a
 * single take can't honour two competing protocols.
 *
 * UI is deliberately flat: list + inline edit dialog. There will rarely be more
 * than a handful of series per device, so a RecyclerView is overkill.
 */
class SeriesActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private lateinit var emptyState: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        val t0 = SystemClock.elapsedRealtime()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_series)
        title = getString(R.string.series_title)

        container = findViewById(R.id.series_list)
        emptyState = findViewById(R.id.series_empty)
        findViewById<View>(R.id.series_add).setOnClickListener { editDialog(existing = null) }
        Log.i(TAG, "onCreate took ${SystemClock.elapsedRealtime() - t0}ms")
    }

    override fun onResume() {
        val t0 = SystemClock.elapsedRealtime()
        super.onResume()
        render()
        Log.i(TAG, "onResume+render took ${SystemClock.elapsedRealtime() - t0}ms")
    }

    private fun render() {
        container.removeAllViews()
        val series = SeriesPreferences.list(this)
        emptyState.visibility = if (series.isEmpty()) View.VISIBLE else View.GONE
        val selectedId = SeriesPreferences.selectedId(this)
        for (s in series) {
            container.addView(rowFor(s, isActive = s.id == selectedId))
        }
    }

    private fun rowFor(s: Series, isActive: Boolean): View {
        val row = layoutInflater.inflate(R.layout.row_series, container, false)
        row.findViewById<RadioButton>(R.id.row_series_active).apply {
            isChecked = isActive
            setOnClickListener {
                SeriesPreferences.setSelected(this@SeriesActivity, s.id)
                render()
            }
        }
        row.findViewById<TextView>(R.id.row_series_name).text = s.name
        row.findViewById<TextView>(R.id.row_series_meta).text =
            getString(R.string.series_metric_count, s.metricKeys.size)
        row.findViewById<View>(R.id.row_series_edit).setOnClickListener { editDialog(existing = s) }
        row.findViewById<View>(R.id.row_series_delete).setOnClickListener { deleteConfirm(s) }
        return row
    }

    private fun editDialog(existing: Series?) {
        val t0 = SystemClock.elapsedRealtime()
        val view = layoutInflater.inflate(R.layout.dialog_series_edit, null)
        val nameInput = view.findViewById<EditText>(R.id.dialog_series_name)
        val metricsContainer = view.findViewById<LinearLayout>(R.id.dialog_series_metrics)

        nameInput.setText(existing?.name.orEmpty())
        val initialKeys = existing?.metricKeys.orEmpty().toMutableSet()
        val textColor = ContextCompat.getColor(this, R.color.role_text_primary)
        val minHeightPx = (48 * resources.displayMetrics.density).toInt()
        // Plain widget CheckBox (not Material) — Material themed checkboxes can each pay a
        // heavy first-inflate cost, which on cold-launch summed to a ~6s main-thread block
        // visible as an ANR.
        for (spec in MetricSpec.CATALOG) {
            val cb = CheckBox(this).apply {
                text = "${spec.label}  (${spec.unit})"
                textSize = 14f
                setTextColor(textColor)
                isChecked = spec.key in initialKeys
                minHeight = minHeightPx
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                tag = spec.key
            }
            metricsContainer.addView(cb)
        }

        // MaterialAlertDialogBuilder cooperates with Material themes more cheaply than the
        // plain AlertDialog.Builder, which on Material-themed activities sometimes triggers
        // a second theme-inflation pass.
        MaterialAlertDialogBuilder(this)
            .setTitle(
                if (existing == null) R.string.series_dialog_title_new
                else R.string.series_dialog_title_edit
            )
            .setView(view)
            .setPositiveButton(R.string.series_dialog_save) { _, _ ->
                val name = nameInput.text.toString().trim()
                if (name.isBlank()) {
                    Toast.makeText(this, R.string.series_name_required, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val keys = (0 until metricsContainer.childCount).mapNotNull { idx ->
                    val cb = metricsContainer.getChildAt(idx) as CheckBox
                    if (cb.isChecked) cb.tag as String else null
                }
                val saved = existing?.copy(name = name, metricKeys = keys)
                    ?: Series(name = name, metricKeys = keys)
                SeriesPreferences.upsert(this, saved)
                if (SeriesPreferences.selectedId(this) == null) {
                    SeriesPreferences.setSelected(this, saved.id)
                }
                render()
            }
            .setNegativeButton(R.string.series_dialog_cancel, null)
            .show()
        Log.i(TAG, "editDialog built+shown in ${SystemClock.elapsedRealtime() - t0}ms")
    }

    private fun deleteConfirm(s: Series) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.series_delete_title)
            .setMessage(getString(R.string.series_delete_message, s.name))
            .setPositiveButton(R.string.series_delete_confirm) { _, _ ->
                SeriesPreferences.delete(this, s.id)
                render()
            }
            .setNegativeButton(R.string.series_dialog_cancel, null)
            .show()
    }

    companion object {
        private const val TAG = "Carnet/Series"
        fun intent(context: Context) =
            android.content.Intent(context, SeriesActivity::class.java)
    }
}

package com.carnet.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A series is a protocol-bound run of takes — all takes in the same series share the
 * intro metric set so longitudinal comparison stays valid. Scientific frame: the
 * series sits between a free-text Experiment ("Quit Smoking") and individual Takes
 * ("DAY-0"); changing measurements between takes would invalidate the trend, so
 * intro metrics are pinned at the series level rather than per take.
 */
data class Series(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val metricKeys: List<String>,
) {
    fun specs(): List<MetricSpec> = metricKeys.mapNotNull(MetricSpec::byKey)
}

/**
 * JSON-in-SharedPreferences catalog of series + the currently-selected one. Room is
 * overkill for v1; there will typically be a handful of series per user and the
 * intro-metric list per series is tiny.
 */
object SeriesPreferences {

    private const val PREFS = "carnet_series"
    private const val KEY_LIST = "series_list"
    private const val KEY_SELECTED = "selected_series_id"

    fun list(context: Context): List<Series> {
        val raw = prefs(context).getString(KEY_LIST, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i -> arr.getJSONObject(i).toSeries() }
        }.getOrElse { emptyList() }
    }

    fun selectedId(context: Context): String? =
        prefs(context).getString(KEY_SELECTED, null)?.takeIf { it.isNotBlank() }

    fun selected(context: Context): Series? {
        val id = selectedId(context) ?: return null
        return list(context).firstOrNull { it.id == id }
    }

    fun setSelected(context: Context, id: String?) {
        prefs(context).edit().apply {
            if (id.isNullOrBlank()) remove(KEY_SELECTED) else putString(KEY_SELECTED, id)
        }.apply()
    }

    /** Insert-or-update by id; preserves list order, appends new entries at the end. */
    fun upsert(context: Context, series: Series) {
        val current = list(context).toMutableList()
        val idx = current.indexOfFirst { it.id == series.id }
        if (idx >= 0) current[idx] = series else current += series
        save(context, current)
    }

    fun delete(context: Context, id: String) {
        val current = list(context).filterNot { it.id == id }
        save(context, current)
        if (selectedId(context) == id) setSelected(context, null)
    }

    private fun save(context: Context, list: List<Series>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs(context).edit().putString(KEY_LIST, arr.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun Series.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("metric_keys", JSONArray().apply { metricKeys.forEach { put(it) } })
    }

    private fun JSONObject.toSeries(): Series = Series(
        id = optString("id").ifBlank { UUID.randomUUID().toString() },
        name = optString("name"),
        metricKeys = optJSONArray("metric_keys")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        } ?: emptyList(),
    )
}

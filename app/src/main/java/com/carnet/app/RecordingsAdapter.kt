package com.carnet.app

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

class RecordingsAdapter(
    private val items: List<Recording>,
    private val onClick: (Recording) -> Unit,
) : RecyclerView.Adapter<RecordingsAdapter.VH>() {

    class VH(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        val primary: TextView = itemView.findViewById(R.id.row_primary)
        val secondary: TextView = itemView.findViewById(R.id.row_secondary)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recording, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val rec = items[position]
        val sessionTag = rec.sessionNumber?.let { "V${it.toString().padStart(2, '0')}" } ?: "V--"
        holder.primary.text = "$sessionTag · ${rec.sessionLabel}"
        holder.secondary.text = String.format(
            Locale.US,
            "%s · %s · %s",
            rec.date,
            formatDuration(rec.durationMs),
            formatSize(rec.sizeBytes),
        )
        holder.itemView.setOnClickListener { onClick(rec) }
    }

    override fun getItemCount(): Int = items.size

    private fun formatDuration(ms: Long): String {
        if (ms <= 0) return "--:--"
        val totalSec = ms / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return String.format(Locale.US, "%d:%02d", m, s)
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "--"
        val mb = bytes / 1024.0 / 1024.0
        return String.format(Locale.US, "%.1f MB", mb)
    }
}

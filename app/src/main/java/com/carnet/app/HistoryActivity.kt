package com.carnet.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.carnet.app.databinding.ActivityHistoryBinding

/**
 * Lists past Carnet recordings under Movies/Carnet/ so the local-first archive is browsable
 * inside the app — opens the system video player on tap. v0.2 baseline: text rows only, no
 * thumbnails or inline sidecar viewer yet.
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.history_title)

        binding.list.layoutManager = LinearLayoutManager(this)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun refresh() {
        val items = Recording.loadAll(this, CARNET_RELATIVE_PATH)
        binding.list.adapter = RecordingsAdapter(items, ::onItemClick)
        binding.empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun onItemClick(rec: Recording) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(rec.contentUri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }
    }

    companion object {
        const val CARNET_RELATIVE_PATH = "Movies/Carnet/"
    }
}

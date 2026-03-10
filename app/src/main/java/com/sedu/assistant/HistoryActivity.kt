package com.sedu.assistant

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sedu.assistant.memory.SeduMemory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private lateinit var memory: SeduMemory
    private lateinit var recycler: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var adapter: HistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        memory = SeduMemory.getInstance(this)
        recycler = findViewById(R.id.historyRecycler)
        emptyText = findViewById(R.id.emptyText)
        val clearButton = findViewById<Button>(R.id.clearHistoryButton)

        adapter = HistoryAdapter(memory.getAllConversations().reversed())
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        updateEmptyState()

        clearButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear History")
                .setMessage("Are you sure you want to delete all chat history? This cannot be undone.")
                .setPositiveButton("Clear") { _, _ ->
                    memory.clear()
                    adapter.updateData(emptyList())
                    updateEmptyState()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun updateEmptyState() {
        if (adapter.itemCount == 0) {
            emptyText.visibility = View.VISIBLE
            recycler.visibility = View.GONE
        } else {
            emptyText.visibility = View.GONE
            recycler.visibility = View.VISIBLE
        }
    }

    // ========== RecyclerView Adapter ==========

    private class HistoryAdapter(
        private var items: List<SeduMemory.ConversationTurn>
    ) : RecyclerView.Adapter<HistoryAdapter.VH>() {

        fun updateData(newItems: List<SeduMemory.ConversationTurn>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val turn = items[position]
            val dateFormat = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
            holder.timeText.text = dateFormat.format(Date(turn.timestamp))
            holder.userText.text = "You: ${turn.userText}"
            holder.replyText.text = "SEDU: ${turn.aiReply}"
        }

        override fun getItemCount() = items.size

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val timeText: TextView = view.findViewById(R.id.timeText)
            val userText: TextView = view.findViewById(R.id.userText)
            val replyText: TextView = view.findViewById(R.id.replyText)
        }
    }
}

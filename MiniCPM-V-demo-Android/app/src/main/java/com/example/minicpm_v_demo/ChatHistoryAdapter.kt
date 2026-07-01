package com.example.minicpm_v_demo

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatHistoryAdapter(
    private var items: List<ChatHistoryStore.Summary>,
    private val onOpen: (ChatHistoryStore.Summary) -> Unit,
    private val onRename: (ChatHistoryStore.Summary) -> Unit,
    private val onDelete: (ChatHistoryStore.Summary) -> Unit
) : RecyclerView.Adapter<ChatHistoryAdapter.ViewHolder>() {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val title: TextView = itemView.findViewById(R.id.tv_history_title)
        val meta: TextView = itemView.findViewById(R.id.tv_history_meta)
        val preview: TextView = itemView.findViewById(R.id.tv_history_preview)
        val open: MaterialButton = itemView.findViewById(R.id.btn_history_open)
        val rename: MaterialButton = itemView.findViewById(R.id.btn_history_rename)
        val delete: MaterialButton = itemView.findViewById(R.id.btn_history_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val context = holder.itemView.context
        val time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(item.updatedAt))

        holder.title.text = item.title
        holder.meta.text = context.getString(R.string.history_meta_format, time, item.messageCount)
        holder.preview.text = item.preview.ifBlank { context.getString(R.string.history_no_preview) }
        holder.itemView.setOnClickListener { onOpen(item) }
        holder.open.setOnClickListener { onOpen(item) }
        holder.rename.setOnClickListener { onRename(item) }
        holder.delete.setOnClickListener { onDelete(item) }
    }

    override fun getItemCount(): Int = items.size

    fun submitList(next: List<ChatHistoryStore.Summary>) {
        items = next
        notifyDataSetChanged()
    }
}

package com.example.minicpm_v_demo

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class QuickModelAdapter(
    private val models: List<ModelInfo>,
    private val selectedModelId: String?,
    private val loadedModelId: String?,
    private val onModelSelected: (ModelInfo) -> Unit
) : RecyclerView.Adapter<QuickModelAdapter.ViewHolder>() {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val icon: TextView = itemView.findViewById(R.id.tv_quick_model_icon)
        val name: TextView = itemView.findViewById(R.id.tv_quick_model_name)
        val desc: TextView = itemView.findViewById(R.id.tv_quick_model_desc)
        val status: TextView = itemView.findViewById(R.id.tv_quick_model_status)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_quick_model, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val model = models[position]
        val context = holder.itemView.context
        holder.icon.text = if (model.isTextOnly) context.getString(R.string.quick_model_icon_text)
            else context.getString(R.string.quick_model_icon_vision)
        holder.name.text = model.displayName
        holder.desc.text = model.getDescription(context)
        holder.status.text = if (model.id == selectedModelId && model.id == loadedModelId) {
            context.getString(R.string.quick_model_loaded_badge)
        } else {
            context.getString(R.string.quick_model_ready_badge)
        }
        holder.itemView.setOnClickListener { onModelSelected(model) }
    }

    override fun getItemCount(): Int = models.size
}

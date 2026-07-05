package com.eve.agent

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

/**
 * RecyclerView adapter for the History tab.
 * Uses [ListAdapter] + [DiffUtil] for efficient, animated updates.
 */
class HistoryAdapter : ListAdapter<HistoryItem, HistoryAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val timeText: TextView   = view.findViewById(R.id.historyTime)
        private val actionText: TextView = view.findViewById(R.id.historyAction)
        private val resultText: TextView = view.findViewById(R.id.historyResult)

        fun bind(item: HistoryItem) {
            timeText.text   = item.formattedTime
            actionText.text = item.action
            resultText.text = item.result

            // Colour-code failures
            val colorRes = if (item.failed) R.color.status_error else R.color.status_ok
            actionText.setTextColor(
                ContextCompat.getColor(itemView.context, colorRes)
            )
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<HistoryItem>() {
            override fun areItemsTheSame(a: HistoryItem, b: HistoryItem) =
                a.taskId == b.taskId
            override fun areContentsTheSame(a: HistoryItem, b: HistoryItem) =
                a == b
        }
    }
}

package com.eve.agent

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * HistoryFragment displays completed and failed tasks in a scrollable list,
 * newest first.  Backed by [EveViewModel.taskHistory].
 */
class HistoryFragment : Fragment() {

    private val viewModel: EveViewModel by activityViewModels()

    private lateinit var recycler: RecyclerView
    private lateinit var emptyText: TextView
    private val adapter = HistoryAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_history, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recycler  = view.findViewById(R.id.historyList)
        emptyText = view.findViewById(R.id.emptyText)

        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        viewModel.taskHistory.observe(viewLifecycleOwner) { items ->
            adapter.submitList(items)
            emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            recycler.visibility  = if (items.isEmpty()) View.GONE   else View.VISIBLE
        }
    }
}

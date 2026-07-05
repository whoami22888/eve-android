package com.eve.agent

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment

/**
 * HistoryFragment shows the log of completed tasks and their outcomes.
 *
 * TODO: Bind to a Room-backed repository (or simple file log) and render
 *       task history in a RecyclerView sorted by recency.
 */
class HistoryFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_history, container, false)
}

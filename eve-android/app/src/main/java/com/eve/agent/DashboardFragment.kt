package com.eve.agent

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels

/**
 * DashboardFragment shows the live status of the EVE orchestrator.
 *
 * Observes [EveViewModel] (scoped to the Activity) which collects events from
 * [EveEventBus].  The Python orchestrator pushes events via [EveKotlinBridge].
 */
class DashboardFragment : Fragment() {

    private val viewModel: EveViewModel by activityViewModels()

    private lateinit var statusText: TextView
    private lateinit var agentStatusText: TextView
    private lateinit var logText: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_dashboard, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        statusText      = view.findViewById(R.id.statusText)
        agentStatusText = view.findViewById(R.id.agentStatusText)
        logText         = view.findViewById(R.id.logText)

        viewModel.status.observe(viewLifecycleOwner) { status ->
            statusText.text = status
        }

        viewModel.agentStatus.observe(viewLifecycleOwner) { agentStatus ->
            agentStatusText.text = agentStatus
        }

        viewModel.logLines.observe(viewLifecycleOwner) { lines ->
            // Show the last 20 lines in the dashboard log preview
            logText.text = lines.takeLast(20).joinToString("\n")
        }
    }
}

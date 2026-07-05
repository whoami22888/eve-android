package com.eve.agent

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * DashboardFragment shows the live status of the EVE orchestrator.
 *
 * TODO: Bind to EveService via the activity's ServiceConnection and
 *       observe a LiveData/Flow that the service publishes for log lines,
 *       agent status, and task counts.
 */
class DashboardFragment : Fragment() {

    private lateinit var statusText: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_dashboard, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        statusText = view.findViewById(R.id.statusText)
        // TODO: observe LiveData from bound EveService
    }
}

package com.eve.agent

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.viewpager2.widget.ViewPager2

/**
 * Live EVE dashboard. System settings are never opened automatically; users
 * navigate to in-app setup intentionally from these controls.
 */
class DashboardFragment : Fragment() {

    private val viewModel: EveViewModel by activityViewModels()

    private lateinit var statusText: TextView
    private lateinit var agentStatusText: TextView
    private lateinit var runtimeText: TextView
    private lateinit var hermesText: TextView
    private lateinit var providerText: TextView
    private lateinit var accessibilityText: TextView
    private lateinit var detailText: TextView
    private lateinit var logText: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_dashboard, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        statusText = view.findViewById(R.id.statusText)
        agentStatusText = view.findViewById(R.id.agentStatusText)
        runtimeText = view.findViewById(R.id.runtimeText)
        hermesText = view.findViewById(R.id.hermesText)
        providerText = view.findViewById(R.id.providerText)
        accessibilityText = view.findViewById(R.id.accessibilityText)
        detailText = view.findViewById(R.id.detailText)
        logText = view.findViewById(R.id.logText)

        viewModel.status.observe(viewLifecycleOwner) { status -> statusText.text = status }
        viewModel.agentStatus.observe(viewLifecycleOwner) { agentStatus -> agentStatusText.text = agentStatus }
        viewModel.runtimeSnapshot.observe(viewLifecycleOwner) { snapshot ->
            runtimeText.text = "Runtime: ${snapshot.runtime}"
            hermesText.text = "Hermes: ${snapshot.hermes}"
            providerText.text = "AI: ${snapshot.provider}"
            accessibilityText.text = "Accessibility: ${snapshot.accessibility}"
            detailText.text = snapshot.detail.ifBlank { "No runtime errors reported." }
        }
        viewModel.logLines.observe(viewLifecycleOwner) { lines ->
            logText.text = lines.takeLast(20).joinToString("\n")
        }

        view.findViewById<Button>(R.id.refreshStatusButton).setOnClickListener {
            MainActivity.currentService?.publishRuntimeStatus("Dashboard refreshed")
                ?: run { detailText.text = "EVE service is reconnecting. Try refresh again in a moment." }
        }
        view.findViewById<Button>(R.id.aiModelsButton).setOnClickListener { navigateToTab(2) }
        view.findViewById<Button>(R.id.setupButton).setOnClickListener { navigateToTab(6) }
    }

    private fun navigateToTab(index: Int) {
        activity?.findViewById<ViewPager2>(R.id.viewPager)?.currentItem = index
    }
}

package com.eve.agent

import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * AgentComputerFragment shows the last screenshot captured by
 * VirtualComputer and exposes manual controls for testing.
 *
 * TODO: Poll (or subscribe via Flow) to VirtualComputer screenshots
 *       and display them; add a gesture-capture overlay so the user
 *       can see what the agent sees.
 */
class AgentComputerFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_agent_computer, container, false)
}

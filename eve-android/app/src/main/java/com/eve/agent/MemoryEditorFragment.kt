package com.eve.agent

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment

/**
 * MemoryEditorFragment lets the user view and edit the agent's persistent
 * memory store.
 *
 * Planned memory format: key-value JSON stored in internal storage under
 * filesDir/memory.json, exposed to Python agents via the android_computer
 * adapter as read/write helpers.
 *
 * TODO: Load memory.json and display in a RecyclerView; support add/delete/edit.
 */
class MemoryEditorFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_memory_editor, container, false)
}

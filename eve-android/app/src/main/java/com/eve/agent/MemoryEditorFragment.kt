package com.eve.agent

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.json.JSONObject
import java.io.File

/**
 * MemoryEditorFragment lets the user view and edit the agent's persistent
 * memory store (key-value pairs in filesDir/memory.json).
 *
 * The same file is read and written by the Python `eve.memory` module so
 * agents can persist state across sessions.
 */
class MemoryEditorFragment : Fragment() {

    private lateinit var recycler: RecyclerView
    private lateinit var fab: FloatingActionButton
    private val entries = mutableListOf<Pair<String, String>>()
    private lateinit var memoryFile: File

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_memory_editor, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        memoryFile = File(requireContext().filesDir, "memory.json")
        recycler   = view.findViewById(R.id.memoryList)
        fab        = view.findViewById(R.id.addMemoryFab)

        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = MemoryAdapter(entries) { key -> deleteEntry(key) }

        fab.setOnClickListener { showAddDialog() }

        loadMemory()
    }

    // ── Persistence ────────────────────────────────────────────────────────────

    private fun loadMemory() {
        entries.clear()
        if (memoryFile.exists()) {
            try {
                val json = JSONObject(memoryFile.readText())
                json.keys().forEach { key -> entries.add(key to json.getString(key)) }
            } catch (_: Exception) { }
        }
        recycler.adapter?.notifyDataSetChanged()
    }

    private fun saveMemory() {
        val json = JSONObject()
        entries.forEach { (k, v) -> json.put(k, v) }
        memoryFile.writeText(json.toString(2))
    }

    private fun deleteEntry(key: String) {
        entries.removeAll { it.first == key }
        saveMemory()
        recycler.adapter?.notifyDataSetChanged()
    }

    // ── Add dialog ─────────────────────────────────────────────────────────────

    private fun showAddDialog() {
        val layout = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_add_memory, null)
        val keyEdit   = layout.findViewById<EditText>(R.id.memoryKeyInput)
        val valueEdit = layout.findViewById<EditText>(R.id.memoryValueInput)

        AlertDialog.Builder(requireContext())
            .setTitle("Add memory entry")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val key   = keyEdit.text.toString().trim()
                val value = valueEdit.text.toString().trim()
                if (key.isBlank()) {
                    Toast.makeText(requireContext(), "Key cannot be empty", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                // Overwrite if exists
                entries.removeAll { it.first == key }
                entries.add(key to value)
                saveMemory()
                recycler.adapter?.notifyDataSetChanged()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

// ── Inline adapter (simple, no DiffUtil needed for small memory stores) ───────

private class MemoryAdapter(
    private val items: List<Pair<String, String>>,
    private val onDelete: (String) -> Unit
) : RecyclerView.Adapter<MemoryAdapter.VH>() {

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_memory, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) =
        holder.bind(items[position], onDelete)

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val keyText: android.widget.TextView   = view.findViewById(R.id.memoryKey)
        private val valueText: android.widget.TextView = view.findViewById(R.id.memoryValue)
        private val deleteBtn: android.widget.ImageButton = view.findViewById(R.id.memoryDeleteBtn)

        fun bind(entry: Pair<String, String>, onDelete: (String) -> Unit) {
            keyText.text   = entry.first
            valueText.text = entry.second
            deleteBtn.setOnClickListener { onDelete(entry.first) }
        }
    }
}

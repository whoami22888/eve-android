package com.eve.agent

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.eve.agent.R
import com.google.android.material.switchmaterial.SwitchMaterial

class ModelSettingsFragment : Fragment() {
    private lateinit var store: ModelProviderStore
    private lateinit var providerDropdown: AutoCompleteTextView
    private lateinit var modelDropdown: AutoCompleteTextView
    private lateinit var localMode: SwitchMaterial
    private lateinit var baseUrl: EditText
    private lateinit var apiKey: EditText
    private lateinit var timeout: EditText
    private lateinit var status: TextView
    private var selectedPreset: ModelProviderPresets.Preset? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_model_settings, container, false)
        store = ModelProviderStore(requireContext())
        providerDropdown = view.findViewById(R.id.providerDropdown)
        modelDropdown = view.findViewById(R.id.modelDropdown)
        localMode = view.findViewById(R.id.localMode)
        baseUrl = view.findViewById(R.id.baseUrl)
        apiKey = view.findViewById(R.id.apiKey)
        timeout = view.findViewById(R.id.timeout)
        status = view.findViewById(R.id.status)

        val saved = store.load()
        localMode.isChecked = ModelProviderPresets.find(saved.provider)?.local == true || saved.provider.equals("ollama", true)
        refreshProviders(saved.provider)
        applySavedModel(saved)
        timeout.setText(saved.timeoutSeconds.toString())
        apiKey.hint = if (saved.apiKey.isBlank()) "API key" else "API key (stored; enter to replace)"

        localMode.setOnCheckedChangeListener { _, checked ->
            refreshProviders(if (checked) "ollama" else "openai")
        }
        providerDropdown.setOnItemClickListener { _, _, position, _ ->
            val presets = currentPresets()
            if (position < presets.size) selectPreset(presets[position])
        }
        modelDropdown.setOnItemClickListener { _, _, _, _ ->
            // Model text is read directly on save.
        }

        view.findViewById<Button>(R.id.saveModel).setOnClickListener { save() }
        view.findViewById<Button>(R.id.testModel).setOnClickListener { testConnection() }
        view.findViewById<Button>(R.id.clearKey).setOnClickListener {
            store.clearApiKey()
            apiKey.text?.clear()
            apiKey.hint = "API key"
            status.text = "Stored API key cleared."
        }
        return view
    }

    private fun currentPresets(): List<ModelProviderPresets.Preset> =
        if (localMode.isChecked) ModelProviderPresets.LOCAL else ModelProviderPresets.CLOUD

    private fun refreshProviders(preferredId: String?) {
        val presets = currentPresets()
        providerDropdown.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, presets.map { it.name }))
        val preset = presets.firstOrNull { it.id.equals(preferredId, true) } ?: presets.first()
        selectPreset(preset)
    }

    private fun selectPreset(preset: ModelProviderPresets.Preset) {
        selectedPreset = preset
        providerDropdown.setText(preset.name, false)
        baseUrl.setText(preset.baseUrl)
        modelDropdown.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, preset.models))
        val currentModel = modelDropdown.text?.toString().orEmpty()
        if (currentModel.isBlank() || currentModel !in preset.models) modelDropdown.setText(preset.models.firstOrNull().orEmpty(), false)
        apiKey.isEnabled = preset.apiKeyRequired
        if (!preset.apiKeyRequired) apiKey.text?.clear()
    }

    private fun applySavedModel(saved: ModelProviderStore.Config) {
        val preset = ModelProviderPresets.find(saved.provider) ?: return
        if (preset.local != localMode.isChecked) return
        selectPreset(preset)
        if (saved.model.isNotBlank()) modelDropdown.setText(saved.model, false)
        if (saved.baseUrl.isNotBlank()) baseUrl.setText(saved.baseUrl)
    }

    private fun save() {
        val current = store.load()
        val preset = selectedPreset
        val enteredKey = apiKey.text?.toString().orEmpty()
        val timeoutValue = timeout.text?.toString()?.toIntOrNull()?.coerceIn(10, 600) ?: 120
        val providerId = preset?.id ?: providerDropdown.text.toString().ifBlank { "openai" }
        store.save(ModelProviderStore.Config(
            providerId,
            baseUrl.text.toString().trim(),
            modelDropdown.text.toString().trim(),
            if (preset?.apiKeyRequired == false) "" else enteredKey.ifBlank { current.apiKey },
            timeoutValue
        ))
        status.text = "Saved. EVE will use ${preset?.name ?: providerId} for the Agent Hub."
    }

    private fun testConnection() {
        save()
        val service = MainActivity.currentService
        if (service == null) {
            status.text = "EVE runtime is not connected."
            return
        }
        status.text = "Testing ${selectedPreset?.name ?: "provider"}…"
        service.testModelProvider { message -> requireActivity().runOnUiThread { status.text = message } }
    }
}

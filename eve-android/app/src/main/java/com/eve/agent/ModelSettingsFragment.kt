package com.eve.agent

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.eve.agent.R

class ModelSettingsFragment : Fragment() {
    private lateinit var store: ModelProviderStore
    private lateinit var provider: EditText
    private lateinit var baseUrl: EditText
    private lateinit var model: EditText
    private lateinit var apiKey: EditText
    private lateinit var timeout: EditText
    private lateinit var status: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_model_settings, container, false)
        store = ModelProviderStore(requireContext())
        provider = view.findViewById(R.id.provider)
        baseUrl = view.findViewById(R.id.baseUrl)
        model = view.findViewById(R.id.model)
        apiKey = view.findViewById(R.id.apiKey)
        timeout = view.findViewById(R.id.timeout)
        status = view.findViewById(R.id.status)

        val saved = store.load()
        provider.setText(saved.provider)
        baseUrl.setText(saved.baseUrl)
        model.setText(saved.model)
        timeout.setText(saved.timeoutSeconds.toString())
        apiKey.hint = if (saved.apiKey.isBlank()) "API key" else "API key (stored; enter to replace)"

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

    private fun save() {
        val current = store.load()
        val enteredKey = apiKey.text?.toString().orEmpty()
        val timeoutValue = timeout.text?.toString()?.toIntOrNull()?.coerceIn(10, 600) ?: 120
        store.save(ModelProviderStore.Config(
            provider.text.toString().ifBlank { "OpenAI-compatible" },
            baseUrl.text.toString(),
            model.text.toString(),
            enteredKey.ifBlank { current.apiKey },
            timeoutValue
        ))
        status.text = "Saved securely. Restarting EVE runtime will apply provider settings."
    }

    private fun testConnection() {
        save()
        val service = MainActivity.currentService
        if (service == null) {
            status.text = "EVE runtime is not connected."
            return
        }
        service.testModelProvider { message ->
            requireActivity().runOnUiThread { status.text = message }
        }
    }
}

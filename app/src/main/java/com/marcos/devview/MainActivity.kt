package com.marcos.devview

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.marcos.devview.adapter.ProcessAdapter
import com.marcos.devview.databinding.ActivityMainBinding
import com.marcos.devview.telemetry.ProcessTelemetry
import com.marcos.devview.telemetry.TelemetryEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var adapter: ProcessAdapter? = null
    
    // In-memory list of processes scanned
    private var fullProcessList = ArrayList<ProcessTelemetry>()
    private var filteredProcessList = ArrayList<ProcessTelemetry>()
    
    private var telemetryJob: Job? = null
    private var currentSearchQuery = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
    }

    private fun setupUI() {
        // Setup Info / About Dialog
        binding.btnAbout.setOnClickListener {
            showAboutDialog()
        }

        // Setup Permission Grant Button
        binding.btnGrantPermission.setOnClickListener {
            TelemetryEngine.openPermissionSettings(this)
        }

        // Setup Recycler View
        binding.rvProcesses.layoutManager = LinearLayoutManager(this)
        adapter = ProcessAdapter(filteredProcessList)
        binding.rvProcesses.adapter = adapter

        // Setup Search Filter
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentSearchQuery = s?.toString() ?: ""
                filterProcesses()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    override fun onResume() {
        super.onResume()
        checkPermissionAndStartTelemetry()
    }

    override fun onPause() {
        super.onPause()
        stopTelemetryLoop()
    }

    private fun checkPermissionAndStartTelemetry() {
        val hasPermission = TelemetryEngine.hasUsageAccessPermission(this)
        
        // Show banner only if permission is missing, but never block the list
        binding.permissionPanel.visibility = if (hasPermission) View.GONE else View.VISIBLE
        binding.rvProcesses.visibility = View.VISIBLE
        binding.etSearch.visibility = View.VISIBLE
        
        startTelemetryLoop()
    }

    private fun startTelemetryLoop() {
        if (telemetryJob != null) return // Already running

        binding.progressBar.visibility = View.VISIBLE

        telemetryJob = lifecycleScope.launch {
            try {
                // First load: scan all running packages
                if (fullProcessList.isEmpty()) {
                    val scanned = TelemetryEngine.getRunningProcesses(this@MainActivity)
                    fullProcessList.clear()
                    fullProcessList.addAll(scanned)
                    filterProcesses() // Display instantly
                    binding.progressBar.visibility = View.GONE
                }

                // Real-time telemetry update loop (every 2 seconds)
                while (true) {
                    TelemetryEngine.updateTelemetry(this@MainActivity, fullProcessList)
                    filterProcesses()
                    delay(2000)
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Error in telemetry loop", e)
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun stopTelemetryLoop() {
        telemetryJob?.cancel()
        telemetryJob = null
    }

    private fun filterProcesses() {
        filteredProcessList.clear()
        if (currentSearchQuery.isEmpty()) {
            filteredProcessList.addAll(fullProcessList)
        } else {
            val query = currentSearchQuery.lowercase(Locale.getDefault())
            for (proc in fullProcessList) {
                if (proc.appName.lowercase(Locale.getDefault()).contains(query) ||
                    proc.packageName.lowercase(Locale.getDefault()).contains(query)) {
                    filteredProcessList.add(proc)
                }
            }
        }
        adapter?.updateData(filteredProcessList)
    }

    private fun showAboutDialog() {
        MaterialAlertDialogBuilder(this, R.style.Theme_DevView)
            .setTitle(getString(R.string.about_dialog_title))
            .setMessage(getString(R.string.about_dialog_message))
            .setPositiveButton(getString(R.string.btn_close)) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }
}

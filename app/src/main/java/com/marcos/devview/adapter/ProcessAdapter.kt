package com.marcos.devview.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.marcos.devview.R
import com.marcos.devview.databinding.ItemProcessBinding
import com.marcos.devview.telemetry.ProcessTelemetry
import java.util.Locale

class ProcessAdapter(
    private var processes: List<ProcessTelemetry>
) : RecyclerView.Adapter<ProcessAdapter.ProcessViewHolder>() {

    fun updateData(newProcesses: List<ProcessTelemetry>) {
        // We sync the expanded state of old items to the new items
        val expandedPackages = processes.filter { it.isExpanded }.map { it.packageName }.toSet()
        for (item in newProcesses) {
            if (expandedPackages.contains(item.packageName)) {
                item.isExpanded = true
            }
        }
        this.processes = newProcesses
        notifyDataSetChanged() // Quick refresh for real-time telemetry changes
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProcessViewHolder {
        val binding = ItemProcessBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ProcessViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ProcessViewHolder, position: Int) {
        holder.bind(processes[position])
    }

    override fun getItemCount(): Int = processes.size

    inner class ProcessViewHolder(
        private val binding: ItemProcessBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(process: ProcessTelemetry) {
            val context = binding.root.context

            // Bind basic details
            binding.txtAppName.text = process.appName
            binding.txtPackageName.text = process.packageName
            
            if (process.appIcon != null) {
                binding.imgAppIcon.setImageDrawable(process.appIcon)
            } else {
                binding.imgAppIcon.setImageResource(android.R.drawable.sym_def_app_icon)
            }

            // Foreground/Background Status Chip
            if (process.isForeground) {
                binding.txtStatus.text = context.getString(R.string.status_foreground)
                binding.txtStatus.setTextColor(ContextCompat.getColor(context, R.color.green_neon))
                binding.txtStatus.setBackgroundResource(R.drawable.bg_status_foreground)
            } else {
                binding.txtStatus.text = context.getString(R.string.status_background)
                binding.txtStatus.setTextColor(ContextCompat.getColor(context, R.color.purple_neon))
                binding.txtStatus.setBackgroundResource(R.drawable.bg_status_background)
            }

            // Setup Expandable Container
            binding.layoutDetails.visibility = if (process.isExpanded) View.VISIBLE else View.GONE
            binding.imgChevron.rotation = if (process.isExpanded) 180f else 0f

            // Bind real-time telemetry values
            binding.txtCpuValue.text = "${process.cpuUsage}%"
            binding.progressCpu.progress = process.cpuUsage

            binding.txtRamValue.text = "${process.ramUsageMb} MB"
            // Let's cap RAM progress bar at 512MB for visualization scaling
            val ramProgress = ((process.ramUsageMb.toFloat() / 512f) * 100).toInt().coerceIn(1, 100)
            binding.progressRam.progress = ramProgress

            // Network speeds
            val rxSpeed = formatSpeed(process.networkRxBps)
            val txSpeed = formatSpeed(process.networkTxBps)
            binding.txtNetworkValue.text = "↑ $txSpeed\n↓ $rxSpeed"

            // Storage space
            binding.txtStorageValue.text = String.format(Locale.getDefault(), "%.2f MB", process.storageSizeMb)

            // Telemetry source label
            if (process.isRealTelemetry) {
                binding.txtTelemetrySource.text = context.getString(R.string.telemetry_actual)
                binding.txtTelemetrySource.setTextColor(ContextCompat.getColor(context, R.color.cyan_neon))
            } else {
                binding.txtTelemetrySource.text = context.getString(R.string.telemetry_simulated)
                binding.txtTelemetrySource.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            }

            // Click listener to toggle expanded state
            binding.layoutHeader.setOnClickListener {
                process.isExpanded = !process.isExpanded
                binding.layoutDetails.visibility = if (process.isExpanded) View.VISIBLE else View.GONE
                binding.imgChevron.animate().rotation(if (process.isExpanded) 180f else 0f).setDuration(200).start()
            }
        }

        private fun formatSpeed(bytesPerSecond: Long): String {
            if (bytesPerSecond <= 0) return "0 B/s"
            if (bytesPerSecond < 1024) return "$bytesPerSecond B/s"
            val kb = bytesPerSecond / 1024.0
            if (kb < 1024) return String.format(Locale.US, "%.1f KB/s", kb)
            val mb = kb / 1024.0
            return String.format(Locale.US, "%.1f MB/s", mb)
        }
    }
}

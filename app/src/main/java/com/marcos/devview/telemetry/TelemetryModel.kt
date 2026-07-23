package com.marcos.devview.telemetry

import android.graphics.drawable.Drawable

data class ProcessTelemetry(
    val packageName: String,
    val appName: String,
    val appIcon: Drawable?,
    val isForeground: Boolean,
    val uid: Int,
    var cpuUsage: Int,          // CPU percentage (0 - 100)
    var ramUsageMb: Int,        // RAM usage in MB
    var networkRxBps: Long,      // Received bytes per second
    var networkTxBps: Long,      // Transmitted bytes per second
    val storageSizeMb: Double,  // Application APK size in MB
    val isRealTelemetry: Boolean, // True if actual (local DevView), False if simulated
    var isExpanded: Boolean = false
)

package com.marcos.devview.telemetry

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import java.io.File
import kotlin.math.abs
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

object TelemetryEngine {

    private const val TAG = "TelemetryEngine"
    
    // Caches cumulative network bytes per UID: UID -> Pair(RxBytes, TxBytes)
    private val networkBytesCache = HashMap<Int, Pair<Long, Long>>()
    private var lastNetworkQueryTime = SystemClock.elapsedRealtime()

    // Cache base memory size for each package so it doesn't jump completely randomly
    private val baseMemoryCache = HashMap<String, Int>()

    // For calculating DevView's actual CPU usage
    private var lastDevViewCpuTime = Process.getElapsedCpuTime()
    private var lastDevViewRealTime = SystemClock.elapsedRealtime()

    fun hasUsageAccessPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun openPermissionSettings(context: Context) {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    suspend fun getRunningProcesses(context: Context): List<ProcessTelemetry> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        
        val endTime = System.currentTimeMillis()
        // Query last 1 hour of usage to find active applications
        val startTime = endTime - 1000 * 3600

        val usageStatsList = try {
            usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                startTime,
                endTime
            ) ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query usage stats: ${e.message}")
            emptyList()
        }

        // Filter and map to unique package names that were active
        // A package is active if it was used or has time in foreground in the interval
        val activePackages = if (usageStatsList.isNotEmpty()) {
            usageStatsList
                .filter { it.totalTimeInForeground > 0 || (endTime - it.lastTimeUsed) < 1000 * 300 }
                .associateBy { it.packageName }
        } else {
            emptyMap()
        }

        val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val processList = ArrayList<ProcessTelemetry>()

        for (appInfo in installedApps) {
            // Filter out system apps that haven't been active to avoid cluttering, 
            // but keep all active apps or user-installed apps
            val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isActive = activePackages.containsKey(appInfo.packageName)
            
            // We show the app if it's active or if it's a non-system user app
            if (isActive || !isSystemApp) {
                val packageName = appInfo.packageName
                val appLabel = pm.getApplicationLabel(appInfo).toString()
                
                // Get app icon safely
                val icon = try {
                    pm.getApplicationIcon(appInfo)
                } catch (e: Exception) {
                    null
                }

                // Determine if in foreground: active in the last 15 seconds
                val stats = activePackages[packageName]
                val isForeground = stats?.let {
                    (endTime - it.lastTimeUsed) < 15000
                } ?: false

                // Get APK storage size
                val apkFile = File(appInfo.publicSourceDir)
                val sizeBytes = if (apkFile.exists()) apkFile.length() else 0L
                val sizeMb = sizeBytes.toDouble() / (1024.0 * 1024.0)

                val isDevView = packageName == context.packageName

                // Setup base simulated memory if not cached
                if (!baseMemoryCache.containsKey(packageName)) {
                    val hash = abs(packageName.hashCode())
                    val baseRam = 45 + (hash % 180) // 45MB to 225MB base RAM
                    baseMemoryCache[packageName] = baseRam
                }

                processList.add(
                    ProcessTelemetry(
                        packageName = packageName,
                        appName = appLabel,
                        appIcon = icon,
                        isForeground = isForeground,
                        uid = appInfo.uid,
                        cpuUsage = 0,
                        ramUsageMb = baseMemoryCache[packageName] ?: 80,
                        networkRxBps = 0L,
                        networkTxBps = 0L,
                        storageSizeMb = sizeMb,
                        isRealTelemetry = isDevView
                    )
                )
            }
        }

        // Sort: DevView first, then foreground apps, then active, then alphabetical
        processList.sortedWith(
            compareByDescending<ProcessTelemetry> { it.packageName == context.packageName }
                .thenByDescending { it.isForeground }
                .thenBy { it.appName }
        )
    }

    fun isShizukuActive(): Boolean {
        return try {
            if (Shizuku.pingBinder()) {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } else {
                false
            }
        } catch (e: Throwable) {
            false
        }
    }

    private fun executeTopViaShizuku(): List<String> {
        val lines = ArrayList<String>()
        var process: java.lang.Process? = null
        try {
            process = Shizuku.newProcess(arrayOf("top", "-n", "1", "-b"), null, null)
            val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                line?.let { lines.add(it) }
            }
            process.waitFor()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to run top via Shizuku: ${e.message}")
        } finally {
            process?.destroy()
        }
        return lines
    }

    private fun parseTopOutput(lines: List<String>, processes: List<ProcessTelemetry>) {
        var cpuIndex = -1
        var rssIndex = -1
        var nameIndex = -1
        
        val procMap = processes.associateBy { it.packageName }
        
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("PID")) {
                val headers = trimmed.split("\\s+".toRegex())
                cpuIndex = headers.indexOf("CPU%")
                if (cpuIndex == -1) cpuIndex = headers.indexOf("%CPU")
                rssIndex = headers.indexOf("RSS")
                if (rssIndex == -1) rssIndex = headers.indexOf("RES")
                nameIndex = headers.indexOf("Name")
                if (nameIndex == -1) nameIndex = headers.indexOf("ARGS")
                continue
            }
            
            if (cpuIndex != -1 && rssIndex != -1 && nameIndex != -1) {
                val tokens = trimmed.split("\\s+".toRegex())
                if (tokens.size > nameIndex && tokens.size > cpuIndex && tokens.size > rssIndex) {
                    val pkgName = tokens[nameIndex]
                    val proc = procMap[pkgName]
                    if (proc != null && !proc.isRealTelemetry) { // Only update non-local apps
                        // Parse CPU
                        val cpuStr = tokens[cpuIndex].replace("%", "")
                        val cpuVal = cpuStr.toFloatOrNull()?.toInt() ?: 0
                        proc.cpuUsage = cpuVal.coerceIn(0, 100)
                        
                        // Parse RAM (RSS)
                        val rssStr = tokens[rssIndex]
                        var ramMb = 0
                        if (rssStr.endsWith("M", ignoreCase = true)) {
                            ramMb = rssStr.substring(0, rssStr.length - 1).toFloatOrNull()?.toInt() ?: 0
                        } else if (rssStr.endsWith("K", ignoreCase = true)) {
                            val kb = rssStr.substring(0, rssStr.length - 1).toFloatOrNull() ?: 0f
                            ramMb = (kb / 1024).toInt()
                        } else if (rssStr.endsWith("G", ignoreCase = true)) {
                            val gb = rssStr.substring(0, rssStr.length - 1).toFloatOrNull() ?: 0f
                            ramMb = (gb * 1024).toInt()
                        } else {
                            val kb = rssStr.toFloatOrNull() ?: 0f
                            ramMb = (kb / 1024).toInt()
                        }
                        proc.ramUsageMb = ramMb.coerceAtLeast(15)
                    }
                }
            }
        }
    }

    suspend fun updateTelemetry(context: Context, processes: List<ProcessTelemetry>): List<ProcessTelemetry> = withContext(Dispatchers.IO) {
        val now = SystemClock.elapsedRealtime()
        val timeDeltaMs = now - lastNetworkQueryTime
        val timeDeltaSec = if (timeDeltaMs > 0) timeDeltaMs / 1000.0 else 1.0
        
        lastNetworkQueryTime = now

        val networkStatsManager = context.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
        val hasUsagePermission = hasUsageAccessPermission(context)
        val shizukuActive = isShizukuActive()

        // 1. If Shizuku is active, run top to fetch actual CPU and RAM metrics
        if (shizukuActive) {
            val topLines = executeTopViaShizuku()
            parseTopOutput(topLines, processes)
        } else {
            // Hide CPU/RAM values of non-local apps by setting them to 0 (hidden in UI)
            for (proc in processes) {
                if (!proc.isRealTelemetry) {
                    proc.cpuUsage = 0
                    proc.ramUsageMb = 0
                }
            }
        }

        // Query time window for network bytes
        val endTime = System.currentTimeMillis()
        val startTime = endTime - 1000 * 60 // last 60 seconds

        for (proc in processes) {
            if (proc.isRealTelemetry) {
                // Actual DevView Telemetry (Local RAM/CPU)
                
                // RAM
                val runtime = Runtime.getRuntime()
                val usedMem = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
                proc.ramUsageMb = usedMem.toInt()

                // CPU
                val currentCpuTime = Process.getElapsedCpuTime()
                val currentRealTime = SystemClock.elapsedRealtime()
                val cpuDelta = currentCpuTime - lastDevViewCpuTime
                val realDelta = currentRealTime - lastDevViewRealTime
                
                lastDevViewCpuTime = currentCpuTime
                lastDevViewRealTime = currentRealTime

                if (realDelta > 0) {
                    val numCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
                    val cpuPercent = ((cpuDelta.toDouble() / realDelta.toDouble()) * 100.0 / numCores).toInt()
                    proc.cpuUsage = cpuPercent.coerceIn(1, 100)
                } else {
                    proc.cpuUsage = 2
                }
            }

            // Network Stats (Real telemetry per UID if permission is granted and card is expanded)
            if (hasUsagePermission && (proc.isExpanded || proc.isRealTelemetry)) {
                try {
                    val wifiRxTx = getUidNetworkBytes(networkStatsManager, ConnectivityManager.TYPE_WIFI, proc.uid, startTime, endTime)
                    val mobileRxTx = getUidNetworkBytes(networkStatsManager, ConnectivityManager.TYPE_MOBILE, proc.uid, startTime, endTime)

                    val currentRx = wifiRxTx.first + mobileRxTx.first
                    val currentTx = wifiRxTx.second + mobileRxTx.second

                    val cachedBytes = networkBytesCache[proc.uid]
                    if (cachedBytes != null) {
                        val rxSpeed = ((currentRx - cachedBytes.first) / timeDeltaSec).toLong().coerceAtLeast(0L)
                        val txSpeed = ((currentTx - cachedBytes.second) / timeDeltaSec).toLong().coerceAtLeast(0L)
                        
                        proc.networkRxBps = rxSpeed
                        proc.networkTxBps = txSpeed
                    } else {
                        proc.networkRxBps = 0L
                        proc.networkTxBps = 0L
                    }

                    networkBytesCache[proc.uid] = Pair(currentRx, currentTx)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to query network for UID ${proc.uid}: ${e.message}")
                    proc.networkRxBps = 0L
                    proc.networkTxBps = 0L
                }
            } else {
                proc.networkRxBps = 0L
                proc.networkTxBps = 0L
            }
        }

        processes
    }

    private fun getUidNetworkBytes(
        nsm: NetworkStatsManager,
        networkType: Int,
        uid: Int,
        startTime: Long,
        endTime: Long
    ): Pair<Long, Long> {
        var rx = 0L
        var tx = 0L
        try {
            val stats = nsm.queryDetailsForUid(networkType, null, startTime, endTime, uid)
            val bucket = NetworkStats.Bucket()
            while (stats.hasNextBucket()) {
                stats.getNextBucket(bucket)
                rx += bucket.rxBytes
                tx += bucket.txBytes
            }
            stats.close()
        } catch (e: Exception) {
            // Ignore type-specific errors (e.g. mobile type not available on Wi-Fi only tablets)
        }
        return Pair(rx, tx)
    }
}

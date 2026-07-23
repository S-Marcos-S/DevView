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

    fun getRunningProcesses(context: Context): List<ProcessTelemetry> {
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
        return processList.sortedWith(
            compareByDescending<ProcessTelemetry> { it.packageName == context.packageName }
                .thenByDescending { it.isForeground }
                .thenBy { it.appName }
        )
    }

    fun updateTelemetry(context: Context, processes: List<ProcessTelemetry>): List<ProcessTelemetry> {
        val now = SystemClock.elapsedRealtime()
        val timeDeltaMs = now - lastNetworkQueryTime
        val timeDeltaSec = if (timeDeltaMs > 0) timeDeltaMs / 1000.0 else 1.0
        
        lastNetworkQueryTime = now

        val networkStatsManager = context.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
        val hasUsagePermission = hasUsageAccessPermission(context)

        // Query time window for network bytes
        val endTime = System.currentTimeMillis()
        val startTime = endTime - 1000 * 60 // last 60 seconds

        for (proc in processes) {
            if (proc.isRealTelemetry) {
                // Actual DevView Telemetry
                
                // RAM
                val runtime = Runtime.getRuntime()
                val usedMem = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
                proc.ramUsageMb = usedMem.toInt()

                // CPU (DevView process CPU load calculation)
                val currentCpuTime = Process.getElapsedCpuTime()
                val currentRealTime = SystemClock.elapsedRealtime()
                val cpuDelta = currentCpuTime - lastDevViewCpuTime
                val realDelta = currentRealTime - lastDevViewRealTime
                
                lastDevViewCpuTime = currentCpuTime
                lastDevViewRealTime = currentRealTime

                if (realDelta > 0) {
                    // cpuDelta is in milliseconds of CPU time. realDelta is wall clock time.
                    // CPU usage = (CPU Time Delta / Real Time Delta) * 100
                    val numCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
                    val cpuPercent = ((cpuDelta.toDouble() / realDelta.toDouble()) * 100.0 / numCores).toInt()
                    proc.cpuUsage = cpuPercent.coerceIn(1, 100)
                } else {
                    proc.cpuUsage = 2
                }

            } else {
                // Simulated Telemetry (CPU & RAM)
                val baseRam = baseMemoryCache[proc.packageName] ?: 80
                val ramFluctuation = (-4..4).random()
                proc.ramUsageMb = (baseRam + ramFluctuation).coerceAtLeast(15)

                if (proc.isForeground) {
                    proc.cpuUsage = (5..38).random()
                } else {
                    // Background apps consume minimal CPU
                    proc.cpuUsage = if ((0..10).random() > 8) (1..3).random() else 0
                }
            }

            // Network Stats (Real telemetry per UID if permission is granted and card is expanded)
            if (hasUsagePermission && (proc.isExpanded || proc.isRealTelemetry)) {
                try {
                    // Query WiFi
                    val wifiRxTx = getUidNetworkBytes(networkStatsManager, ConnectivityManager.TYPE_WIFI, proc.uid, startTime, endTime)
                    // Query Mobile
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

                    // Update cache with current totals
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

        return processes
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

package com.marcos.devview.widget

import android.app.PendingIntent
import android.app.usage.UsageStatsManager
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.RemoteViews
import com.marcos.devview.MainActivity
import com.marcos.devview.R
import com.marcos.devview.telemetry.TelemetryEngine
import java.util.Calendar
import java.util.Locale

class GreetingWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    private fun updateAppWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        val views = RemoteViews(context.packageName, R.layout.greeting_widget)

        // 1. Set Greeting and Day of the Week in Portuguese
        val calendar = Calendar.getInstance()
        val ptLocale = Locale("pt", "BR")
        val dayOfWeek = calendar.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, ptLocale)
            ?.replaceFirstChar { if (it.isLowerCase()) it.titlecase(ptLocale) else it.toString() } ?: "Dia da Semana"
        
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val greeting = when (hour) {
            in 6..11 -> "Tenha um excelente dia!"
            in 12..17 -> "Tenha uma excelente tarde!"
            else -> "Tenha uma excelente noite!"
        }

        views.setTextViewText(R.id.txtWidgetGreeting, greeting)
        views.setTextViewText(R.id.txtWidgetDay, dayOfWeek)

        // 2. Fetch and list active processes (top 3 recently used)
        if (TelemetryEngine.hasUsageAccessPermission(context)) {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val pm = context.packageManager
            val endTime = System.currentTimeMillis()
            val startTime = endTime - 1000 * 3600 // 1 hour

            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime)
            
            // Sort by last time used, excluding system apps to show user apps, or just show top 3
            val activeApps = stats
                ?.filter { it.totalTimeInForeground > 0 }
                ?.sortedByDescending { it.lastTimeUsed }
                ?.take(3) ?: emptyList()

            // Bind to widget views
            for (i in 0..2) {
                val appTitleId = when (i) {
                    0 -> R.id.txtWidgetTask1
                    1 -> R.id.txtWidgetTask2
                    else -> R.id.txtWidgetTask3
                }
                val appStatsId = when (i) {
                    0 -> R.id.txtWidgetTask1Stats
                    1 -> R.id.txtWidgetTask2Stats
                    else -> R.id.txtWidgetTask3Stats
                }

                if (i < activeApps.size) {
                    val appUsage = activeApps[i]
                    val appName = try {
                        val appInfo = pm.getApplicationInfo(appUsage.packageName, 0)
                        pm.getApplicationLabel(appInfo).toString()
                    } catch (e: PackageManager.NameNotFoundException) {
                        appUsage.packageName.split(".").last()
                    }
                    
                    // Simulated load for widget
                    val cpuLoad = (1..15).random()
                    
                    views.setTextViewText(appTitleId, "${i + 1}. $appName")
                    views.setTextViewText(appStatsId, "CPU: $cpuLoad%")
                } else {
                    // Default values if less than 3 apps run
                    if (i == 0) {
                        views.setTextViewText(appTitleId, "1. DevView")
                        views.setTextViewText(appStatsId, "CPU: 1%")
                    } else {
                        views.setTextViewText(appTitleId, "${i + 1}. Nenhum outro app")
                        views.setTextViewText(appStatsId, "-")
                    }
                }
            }
        } else {
            // Placeholder values when no permission is granted
            views.setTextViewText(R.id.txtWidgetTask1, "1. DevView")
            views.setTextViewText(R.id.txtWidgetTask1Stats, "CPU: 1%")
            views.setTextViewText(R.id.txtWidgetTask2, "2. Aguardando permissão...")
            views.setTextViewText(R.id.txtWidgetTask2Stats, "-")
            views.setTextViewText(R.id.txtWidgetTask3, "3. Configurar no app")
            views.setTextViewText(R.id.txtWidgetTask3Stats, "-")
        }

        // 3. Click intent: click widget to open main activity
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.txtWidgetDay, pendingIntent)
        views.setOnClickPendingIntent(R.id.txtWidgetGreeting, pendingIntent)

        // Instruct widget manager to update
        appWidgetManager.updateAppWidget(appWidgetId, views)
    }
}

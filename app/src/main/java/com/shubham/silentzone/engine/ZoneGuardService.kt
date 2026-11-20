package com.shubham.silentzone.engine

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.shubham.silentzone.MainActivity
import com.shubham.silentzone.R
import com.shubham.silentzone.data.ZoneRepository

/**
 * Keeps SilentZone alive for as long as at least one zone toggle is ON (screen on OR off), so the OS
 * does not freeze/kill the app and Wi-Fi / time / location changes are always noticed.
 */
class ZoneGuardService : Service() {

    companion object {
        private const val CHANNEL_ID = "silentzone_guard_channel"
        private const val NOTIF_ID = 1001

        @Volatile
        private var running = false

        private fun hasEnabledRules(context: Context): Boolean =
            ZoneRepository(context).getRules().any { it.isEnabled }

        fun syncServiceState(context: Context) {
            val appCtx = context.applicationContext
            val shouldRunGuard = hasEnabledRules(appCtx)
            val intent = Intent(appCtx, ZoneGuardService::class.java)
            runCatching {
                if (shouldRunGuard) {
                    if (running) {
                        // Already alive: just refresh what the notification says
                        refreshNotification(appCtx)
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        appCtx.startForegroundService(intent)
                    } else {
                        appCtx.startService(intent)
                    }
                } else {
                    appCtx.stopService(intent)
                    val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.cancel(NOTIF_ID)
                }
            }
        }

        private fun refreshNotification(context: Context) {
            runCatching {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIF_ID, buildNotification(context))
            }
        }

        private fun buildNotification(context: Context): Notification {
            val repo = ZoneRepository(context)
            val status = repo.getStatus()
            val enabledCount = repo.getRules().count { it.isEnabled }
            val title: String
            val text: String
            if (status.isMutedByApp) {
                title = "Active Zone: ${status.activeZoneName ?: "Muted"}"
                text = status.matchedReason
            } else {
                title = "SilentZone is watching"
                text = "$enabledCount zone(s) ON - will silence automatically"
            }
            val openAppIntent = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_silentzone)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openAppIntent)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build()
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var screenReceiver: android.content.BroadcastReceiver? = null

    private val periodicCheck = object : Runnable {
        override fun run() {
            evaluateAndRefresh()
            handler.postDelayed(this, 60_000L)
        }
    }

    /** Evaluates on a background thread, then (on the main thread) refreshes the notification or stops. */
    private fun evaluateAndRefresh() {
        ZoneEvaluatorEngine.evaluateNow(applicationContext) {
            if (!hasEnabledRules(applicationContext)) {
                stopGuardCleanly()
            } else {
                updateNotification()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notif = buildNotification(applicationContext)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        running = true
        registerScreenReceiver()
        // Live Wi-Fi callback inside this always-alive process
        ZoneEvaluatorEngine.registerWifiWatcher(applicationContext)
        handler.postDelayed(periodicCheck, 60_000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!hasEnabledRules(applicationContext)) {
            stopGuardCleanly()
            return START_NOT_STICKY
        }
        updateNotification()
        return START_STICKY
    }

    private fun stopGuardCleanly() {
        handler.removeCallbacks(periodicCheck)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIF_ID)
        }
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // User swiped the app away from recents: bring the guard back right away
        if (hasEnabledRules(applicationContext)) {
            val restartIntent = Intent(applicationContext, ZoneGuardService::class.java)
            val pi = PendingIntent.getService(
                applicationContext,
                1002,
                restartIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 1000L, pi)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(periodicCheck)
        unregisterScreenReceiver()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIF_ID)
        }
        super.onDestroy()
    }

    private fun registerScreenReceiver() {
        runCatching {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            val rx = object : android.content.BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    handler.postDelayed({ evaluateAndRefresh() }, 300L)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(rx, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(rx, filter)
            }
            screenReceiver = rx
        }
    }

    private fun unregisterScreenReceiver() {
        runCatching {
            screenReceiver?.let { unregisterReceiver(it) }
            screenReceiver = null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SilentZone Background Guard",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Keeps SilentZone running while at least one zone is turned ON"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun updateNotification() {
        refreshNotification(applicationContext)
    }
}

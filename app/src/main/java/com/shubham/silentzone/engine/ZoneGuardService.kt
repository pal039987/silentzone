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

        private fun isZoneCurrentlyActive(context: Context): Boolean {
            val repo = ZoneRepository(context)
            return repo.getStatus().isMutedByApp && repo.getRules().any { it.isEnabled }
        }

        fun syncServiceState(context: Context) {
            val appCtx = context.applicationContext
            val shouldRunGuard = isZoneCurrentlyActive(appCtx)
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
            if (!isZoneCurrentlyActive(applicationContext)) {
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
            val hasLocPerm = androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val fgType = if (hasLocPerm) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            }
            startForeground(NOTIF_ID, notif, fgType)
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
        if (!isZoneCurrentlyActive(applicationContext)) {
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
        if (isZoneCurrentlyActive(applicationContext)) {
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
                addAction(android.location.LocationManager.PROVIDERS_CHANGED_ACTION)
                addAction(android.net.wifi.WifiManager.WIFI_STATE_CHANGED_ACTION)
            }
            val rx = object : android.content.BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    val act = intent?.action
                    if (act == android.location.LocationManager.PROVIDERS_CHANGED_ACTION ||
                        act == android.net.wifi.WifiManager.WIFI_STATE_CHANGED_ACTION
                    ) {
                        evaluateAndRefresh()
                    } else {
                        handler.postDelayed({ evaluateAndRefresh() }, 3500L)
                    }
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

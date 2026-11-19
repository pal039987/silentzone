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

class ZoneGuardService : Service() {

    companion object {
        private const val CHANNEL_ID = "silentzone_guard_channel"
        private const val NOTIF_ID = 1001

        fun syncServiceState(context: Context) {
            val repo = ZoneRepository(context)
            val hasEnabledRules = repo.getRules().any { it.isEnabled }
            val status = repo.getStatus()
            val shouldRunGuard = hasEnabledRules && status.isMutedByApp
            val intent = Intent(context, ZoneGuardService::class.java)
            runCatching {
                if (shouldRunGuard) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                } else {
                    context.stopService(intent)
                    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.cancel(NOTIF_ID)
                }
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var screenReceiver: android.content.BroadcastReceiver? = null

    private val periodicCheck = object : Runnable {
        override fun run() {
            ZoneEvaluatorEngine.evaluateNow(applicationContext)
            val repo = ZoneRepository(applicationContext)
            val stillActive = repo.getRules().any { it.isEnabled } && repo.getStatus().isMutedByApp
            if (!stillActive) {
                stopGuardCleanly()
                return
            }
            updateNotification()
            handler.postDelayed(this, 60_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        registerScreenReceiver()
        handler.postDelayed(periodicCheck, 60_000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val repo = ZoneRepository(applicationContext)
        val shouldRun = repo.getRules().any { it.isEnabled } && repo.getStatus().isMutedByApp
        if (!shouldRun) {
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
        val repo = ZoneRepository(applicationContext)
        val shouldRestart = repo.getRules().any { it.isEnabled } && repo.getStatus().isMutedByApp
        if (shouldRestart) {
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
                addAction(Intent.ACTION_USER_PRESENT)
            }
            val rx = object : android.content.BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    handler.postDelayed({
                        ZoneEvaluatorEngine.evaluateNow(applicationContext)
                        val repo = ZoneRepository(applicationContext)
                        val stillActive = repo.getRules().any { it.isEnabled } && repo.getStatus().isMutedByApp
                        if (!stillActive) {
                            stopGuardCleanly()
                        } else {
                            updateNotification()
                        }
                    }, 300L)
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
                description = "Active only while inside a SilentZone"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val status = ZoneRepository(applicationContext).getStatus()
        val title = "Active Zone: ${status.activeZoneName ?: "Muted"}"
        val text = status.matchedReason
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun updateNotification() {
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification())
        }
    }
}

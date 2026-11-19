package com.shubham.silentzone.engine

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
            val status = repo.getStatus()
            val shouldRunGuard = status.isMutedByApp
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
                }
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var screenReceiver: android.content.BroadcastReceiver? = null

    private val periodicCheck = object : Runnable {
        override fun run() {
            ZoneEvaluatorEngine.evaluateNow(applicationContext)
            if (!ZoneRepository(applicationContext).getStatus().isMutedByApp) {
                stopSelf()
                return
            }
            updateNotification()
            handler.postDelayed(this, 45_000L)
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
        registerWifiCallback()
        registerScreenReceiver()
        handler.post(periodicCheck)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ZoneEvaluatorEngine.evaluateNow(applicationContext)
        if (!ZoneRepository(applicationContext).getStatus().isMutedByApp) {
            stopSelf()
            return START_NOT_STICKY
        }
        updateNotification()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Immediately schedule self-restart when user swipes app away from Recents
        val restartIntent = Intent(applicationContext, ZoneGuardService::class.java)
        val pi = PendingIntent.getService(
            applicationContext,
            1002,
            restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 1000L, pi)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(periodicCheck)
        unregisterWifiCallback()
        unregisterScreenReceiver()
        super.onDestroy()
    }

    private fun registerScreenReceiver() {
        runCatching {
            val filter = android.content.IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            val rx = object : android.content.BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    handler.postDelayed({
                        ZoneEvaluatorEngine.evaluateNow(applicationContext)
                        if (!ZoneRepository(applicationContext).getStatus().isMutedByApp) {
                            stopSelf()
                        } else {
                            updateNotification()
                        }
                    }, 400L)
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

    private fun registerWifiCallback() {
        runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    handler.postDelayed({
                        ZoneEvaluatorEngine.evaluateNow(applicationContext)
                        updateNotification()
                    }, 500L)
                }

                override fun onLost(network: Network) {
                    handler.postDelayed({
                        ZoneEvaluatorEngine.evaluateNow(applicationContext)
                        updateNotification()
                    }, 500L)
                }
            }
            cm.registerNetworkCallback(req, cb)
            networkCallback = cb
        }
    }

    private fun unregisterWifiCallback() {
        runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            networkCallback?.let { cm.unregisterNetworkCallback(it) }
            networkCallback = null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SilentZone Background Guard",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Keeps auto-silent zones active even after app is closed"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val status = ZoneRepository(applicationContext).getStatus()
        val title = if (status.isMutedByApp) {
            "Active Zone: ${status.activeZoneName ?: "Muted"}"
        } else {
            "SilentZone Guard Monitoring"
        }
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

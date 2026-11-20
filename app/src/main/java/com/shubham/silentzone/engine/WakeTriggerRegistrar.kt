package com.shubham.silentzone.engine

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.shubham.silentzone.data.ZoneRepository

object WakeTriggerRegistrar {

    private const val PROXIMITY_ACTION = "com.shubham.silentzone.ACTION_PROXIMITY_ALERT"

    @SuppressLint("MissingPermission")
    fun syncOsWakeTriggers(context: Context) {
        val repo = ZoneRepository(context)
        val enabledRules = repo.getRules().filter { it.isEnabled }

        // 1. OS-level Wi-Fi connect wakeup (only schedules if not already pending)
        WifiWakeJobService.scheduleWifiWake(context)

        // 2. OS-level 40m Micro-Location Proximity Alert
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val hasFine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val locRules = if (hasFine) {
            enabledRules.filter {
                it.isLocationTriggerActive() && it.latitude != null && it.longitude != null
            }.take(15)
        } else {
            emptyList()
        }

        // Re-registering an alert while the phone is already inside the fence makes Android fire it again
        // immediately -> evaluate -> re-register -> ... So only touch the alerts when something changed.
        // Alerts are wiped on reboot, hence the boot count is part of the signature.
        val bootCount = runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)
        }.getOrDefault(0)
        val signature = "boot=$bootCount;" + locRules.joinToString("|") {
            "${it.id}:${it.latitude}:${it.longitude}:${it.radiusMeters}"
        }
        val prefs = context.getSharedPreferences("silentzone_wake_prefs", Context.MODE_PRIVATE)
        if (prefs.getString("proximity_sig", null) == signature) return

        // Clear old proximity alerts for up to 15 rules
        for (i in 0 until 15) {
            val pi = buildProximityPendingIntent(context, 8000 + i, PendingIntent.FLAG_NO_CREATE)
            if (pi != null) {
                runCatching { lm.removeProximityAlert(pi) }
                pi.cancel()
            }
        }

        locRules.forEachIndexed { idx, rule ->
            val pi = buildProximityPendingIntent(context, 8000 + idx, PendingIntent.FLAG_UPDATE_CURRENT)
            if (pi != null) {
                runCatching {
                    lm.addProximityAlert(
                        rule.latitude!!,
                        rule.longitude!!,
                        rule.radiusMeters.coerceAtLeast(40f),
                        -1L,
                        pi
                    )
                }
            }
        }

        prefs.edit().putString("proximity_sig", signature).apply()
    }

    private fun buildProximityPendingIntent(
        context: Context,
        requestCode: Int,
        extraFlag: Int
    ): PendingIntent? {
        val intent = Intent(context, ZoneTriggerReceiver::class.java).apply {
            action = PROXIMITY_ACTION
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            extraFlag or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

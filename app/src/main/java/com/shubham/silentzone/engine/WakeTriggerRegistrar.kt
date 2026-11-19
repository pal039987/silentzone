package com.shubham.silentzone.engine

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.shubham.silentzone.data.ZoneRepository

object WakeTriggerRegistrar {

    private const val PROXIMITY_ACTION = "com.shubham.silentzone.ACTION_PROXIMITY_ALERT"

    @SuppressLint("MissingPermission")
    fun syncOsWakeTriggers(context: Context) {
        val repo = ZoneRepository(context)
        val enabledRules = repo.getRules().filter { it.isEnabled }

        // 1. OS-level Wi-Fi connect wakeup (zero CPU while disconnected)
        WifiWakeJobService.scheduleWifiWake(context)

        // 2. OS-level 40m Micro-Location Proximity Alert
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val hasFine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        // Clear old proximity alerts for up to 15 rules
        for (i in 0 until 15) {
            val pi = buildProximityPendingIntent(context, 8000 + i, PendingIntent.FLAG_NO_CREATE)
            if (pi != null) {
                runCatching { lm.removeProximityAlert(pi) }
                pi.cancel()
            }
        }

        if (hasFine) {
            val locRules = enabledRules.filter {
                it.isLocationTriggerActive() && it.latitude != null && it.longitude != null
            }.take(15)

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
        }
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

package com.shubham.silentzone.engine

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.shubham.silentzone.data.ActiveZoneStatus
import com.shubham.silentzone.data.SilenceProfile
import com.shubham.silentzone.data.SilentZoneRule
import com.shubham.silentzone.data.TriggerType
import com.shubham.silentzone.data.ZoneRepository
import com.shubham.silentzone.util.RadioFingerprintScanner
import com.shubham.silentzone.util.RadioSnapshot
import java.util.Calendar

object ZoneEvaluatorEngine {

    const val ACTION_EVALUATE = "com.shubham.silentzone.ACTION_EVALUATE_ZONES"
    private var networkCallbackRegistered = false
    private var pendingIntentCallbackRegistered = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioHandler = Handler(Looper.getMainLooper())

    fun registerWifiWatcher(context: Context) {
        runCatching {
            val appCtx = context.applicationContext
            val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()

            // 1. OS-level PendingIntent callback wakes app even if killed in background (register ONCE to avoid 100-callback limit crash!)
            if (!pendingIntentCallbackRegistered) {
                val intent = Intent(appCtx, ZoneTriggerReceiver::class.java).apply {
                    action = ACTION_EVALUATE
                }
                val pi = PendingIntent.getBroadcast(
                    appCtx,
                    4042,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                cm.registerNetworkCallback(request, pi)
                pendingIntentCallbackRegistered = true
            }

            // 2. Live process callback with 6-second anti-flicker grace on disconnect
            if (!networkCallbackRegistered) {
                cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        audioHandler.postDelayed({ evaluateNow(appCtx) }, 500L)
                    }

                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                            evaluateNow(appCtx)
                        }
                    }

                    override fun onLost(network: Network) {
                        // 6-second grace window so weak fringe Wi-Fi or mesh handoff doesn't unmute prematurely
                        audioHandler.postDelayed({ evaluateNow(appCtx) }, 6000L)
                    }
                })
                networkCallbackRegistered = true
            }
        }
    }

    private const val ZEN_RULE_PREF_ID = "silentzone_zen_rule_id"

    private fun setOsOwnedDndState(context: Context, nm: NotificationManager, active: Boolean, zoneName: String?) {
        if (!nm.isNotificationPolicyAccessGranted) return
        val prefs = context.getSharedPreferences("silentzone_zen_prefs", Context.MODE_PRIVATE)
        runCatching {
            val existingId = prefs.getString(ZEN_RULE_PREF_ID, null)
            val ruleName = "SilentZone Guard"
            val ownerComponent = android.content.ComponentName(context, SilentZoneConditionService::class.java)
            val configActivity = android.content.ComponentName(context, com.shubham.silentzone.MainActivity::class.java)
            val conditionId = android.net.Uri.Builder()
                .scheme("condition")
                .authority(context.packageName)
                .appendPath("active_zone")
                .build()

            var ruleId = existingId
            if (ruleId == null || nm.getAutomaticZenRule(ruleId) == null) {
                val zenRule = android.app.AutomaticZenRule(
                    ruleName,
                    ownerComponent,
                    configActivity,
                    conditionId,
                    null,
                    NotificationManager.INTERRUPTION_FILTER_NONE,
                    true
                )
                ruleId = nm.addAutomaticZenRule(zenRule)
                prefs.edit().putString(ZEN_RULE_PREF_ID, ruleId).apply()
            } else if (active && !zoneName.isNullOrBlank()) {
                val currentRule = nm.getAutomaticZenRule(ruleId)
                if (currentRule != null) {
                    currentRule.name = "SilentZone: $zoneName"
                    currentRule.interruptionFilter = NotificationManager.INTERRUPTION_FILTER_NONE
                    currentRule.isEnabled = true
                    nm.updateAutomaticZenRule(ruleId, currentRule)
                }
            }

            if (ruleId != null) {
                val state = if (active) android.service.notification.Condition.STATE_TRUE else android.service.notification.Condition.STATE_FALSE
                val summary = if (active) "Active: ${zoneName ?: "SilentZone"}" else "Standby"
                val condition = android.service.notification.Condition(conditionId, summary, state)
                nm.setAutomaticZenRuleState(ruleId, condition)
            }
        }
    }

    fun evaluateNow(context: Context) {
        registerWifiWatcher(context)
        val repo = ZoneRepository(context)
        val rules = repo.getRules().filter { it.isEnabled }
        val currentStatus = repo.getStatus()
        val snapshot = RadioFingerprintScanner.captureCurrentEnvironment(context)

        val matchedRule = rules.firstOrNull { rule -> matchesRule(rule, snapshot) }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val notifManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (matchedRule != null) {
            val prevMode = if (currentStatus.isMutedByApp) {
                currentStatus.previousRingerMode
            } else {
                AudioManager.RINGER_MODE_NORMAL
            }

            val appliedSuccessfully = applySilenceProfile(context, audioManager, notifManager, matchedRule.profile, matchedRule.name)
            val matchedTriggers = mutableListOf<String>()
            if (matchedRule.isWifiTriggerActive() && matchesWifiOnly(matchedRule, snapshot)) {
                matchedTriggers.add("Wi-Fi (${matchedRule.wifiSsid ?: "Locked"})")
            }
            if (matchedRule.isTimeTriggerActive() && matchesTimeWindow(matchedRule)) {
                matchedTriggers.add("Time")
            }
            if (matchedRule.isLocationTriggerActive() && matchesLocationOnly(matchedRule, snapshot)) {
                matchedTriggers.add("Location")
            }
            val reason = if (!appliedSuccessfully) {
                "DND Permission Needed to Mute"
            } else if (matchedTriggers.isNotEmpty()) {
                "Matched: ${matchedTriggers.joinToString(" + ")}"
            } else {
                "Zone Active"
            }

            repo.saveStatus(
                ActiveZoneStatus(
                    isMutedByApp = appliedSuccessfully,
                    activeZoneName = if (appliedSuccessfully) matchedRule.name else null,
                    matchedReason = reason,
                    lastEvaluatedAt = System.currentTimeMillis(),
                    previousRingerMode = prevMode
                )
            )
        } else if (currentStatus.isMutedByApp) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            val isScreenOn = pm.isInteractive
            val activeRule = rules.firstOrNull { it.name == currentStatus.activeZoneName }

            // If rule was deleted or disabled by user, unmute immediately!
            if (activeRule == null) {
                restoreNormalMode(context, audioManager, notifManager)
                repo.saveStatus(
                    ActiveZoneStatus(
                        isMutedByApp = false,
                        activeZoneName = null,
                        matchedReason = "Normal Ring Mode",
                        lastEvaluatedAt = System.currentTimeMillis(),
                        previousRingerMode = AudioManager.RINGER_MODE_NORMAL
                    )
                )
                scheduleNextBoundaryAlarm(context, rules)
                WakeTriggerRegistrar.syncOsWakeTriggers(context)
                ZoneGuardService.syncServiceState(context)
                return
            }

            // If Time trigger is active and its window has expired (and neither Wi-Fi nor Location matches), unmute cleanly
            val timeExpired = activeRule.isTimeTriggerActive() && !matchesTimeWindow(activeRule)
            val wifiExplicitlyDisconnected = activeRule.isWifiTriggerActive() && !snapshot.isWifiConnected
            val locationExplicitlyExited = activeRule.isLocationTriggerActive() &&
                    activeRule.latitude != null && activeRule.longitude != null &&
                    snapshot.latitude != null && snapshot.longitude != null &&
                    !matchesLocationOnly(activeRule, snapshot)

            // Keep zone LOCKED if screen is OFF (unless time expired) OR if Wi-Fi/GPS is still waking up
            val keepLocked = (!isScreenOn && !timeExpired) ||
                    (!timeExpired && !wifiExplicitlyDisconnected && !locationExplicitlyExited)

            if (keepLocked) {
                applySilenceProfile(context, audioManager, notifManager, activeRule.profile, activeRule.name)
                scheduleNextBoundaryAlarm(context, rules)
                WakeTriggerRegistrar.syncOsWakeTriggers(context)
                ZoneGuardService.syncServiceState(context)
                return
            }

            restoreNormalMode(context, audioManager, notifManager)
            repo.saveStatus(
                ActiveZoneStatus(
                    isMutedByApp = false,
                    activeZoneName = null,
                    matchedReason = "Normal Ring Mode",
                    lastEvaluatedAt = System.currentTimeMillis(),
                    previousRingerMode = AudioManager.RINGER_MODE_NORMAL
                )
            )
        }

        scheduleNextBoundaryAlarm(context, rules)
        WakeTriggerRegistrar.syncOsWakeTriggers(context)
        ZoneGuardService.syncServiceState(context)
    }

    private fun matchesRule(rule: SilentZoneRule, snapshot: RadioSnapshot): Boolean {
        val wifiHit = rule.isWifiTriggerActive() && matchesWifiOnly(rule, snapshot)
        val timeHit = rule.isTimeTriggerActive() && matchesTimeWindow(rule)
        val locationHit = rule.isLocationTriggerActive() && matchesLocationOnly(rule, snapshot)
        return wifiHit || timeHit || locationHit
    }

    private fun matchesWifiOnly(rule: SilentZoneRule, snapshot: RadioSnapshot): Boolean {
        if (!snapshot.isWifiConnected) return false
        val hasFingerprint = !rule.wifiBssid.isNullOrBlank() || !rule.wifiSsid.isNullOrBlank()
        if (!hasFingerprint) return false

        val sigMatch = !rule.wifiBssid.isNullOrBlank() &&
                !snapshot.wifiNetworkSignature.isNullOrBlank() &&
                rule.wifiBssid == snapshot.wifiNetworkSignature

        val bssidMatch = !rule.wifiBssid.isNullOrBlank() &&
                !snapshot.wifiBssid.isNullOrBlank() &&
                rule.wifiBssid.equals(snapshot.wifiBssid, ignoreCase = true)

        val ssidMatch = !rule.wifiSsid.isNullOrBlank() &&
                !snapshot.wifiSsid.isNullOrBlank() &&
                rule.wifiSsid.equals(snapshot.wifiSsid, ignoreCase = true)

        return sigMatch || bssidMatch || ssidMatch
    }

    private fun matchesLocationOnly(rule: SilentZoneRule, snapshot: RadioSnapshot): Boolean {
        if (!snapshot.isSystemLocationEnabled) return false
        if (rule.latitude != null && rule.longitude != null &&
            snapshot.latitude != null && snapshot.longitude != null
        ) {
            val results = FloatArray(1)
            android.location.Location.distanceBetween(
                rule.latitude, rule.longitude,
                snapshot.latitude, snapshot.longitude,
                results
            )
            return results[0] <= rule.radiusMeters
        }
        return rule.cellSignatures.isNotEmpty() &&
                rule.cellSignatures.any { saved -> snapshot.cellSignatures.contains(saved) }
    }

    private fun matchesTimeWindow(rule: SilentZoneRule): Boolean {
        val cal = Calendar.getInstance()
        val today = cal.get(Calendar.DAY_OF_WEEK)
        if (!rule.activeDays.contains(today)) return false

        val nowMins = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val startMins = rule.startHour * 60 + rule.startMinute
        val endMins = rule.endHour * 60 + rule.endMinute

        return if (startMins <= endMins) {
            nowMins in startMins until endMins
        } else {
            nowMins >= startMins || nowMins < endMins
        }
    }

    private fun applySilenceProfile(
        context: Context,
        audioManager: AudioManager,
        notifManager: NotificationManager,
        profile: SilenceProfile,
        zoneName: String? = null
    ): Boolean {
        return runCatching {
            if (!notifManager.isNotificationPolicyAccessGranted) {
                return false
            }
            audioHandler.removeCallbacksAndMessages(null)

            when (profile) {
                SilenceProfile.TOTAL_SILENT -> {
                    // Use OS-owned AutomaticZenRule so Android OS automatically restores Normal Ring if app is uninstalled!
                    setOsOwnedDndState(context, notifManager, true, zoneName)
                    audioHandler.postDelayed({
                        runCatching {
                            setOsOwnedDndState(context, notifManager, true, zoneName)
                        }
                    }, 200L)
                }
                SilenceProfile.VIBRATE_ONLY -> {
                    // Keep DND OFF (INTERRUPTION_FILTER_ALL) so ColorOS/Realme never flickers DND icon
                    if (notifManager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) {
                        notifManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                    }
                    if (audioManager.ringerMode != AudioManager.RINGER_MODE_VIBRATE) {
                        audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                    }
                    audioHandler.postDelayed({
                        runCatching {
                            if (notifManager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) {
                                notifManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                            }
                            if (audioManager.ringerMode != AudioManager.RINGER_MODE_VIBRATE) {
                                audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                            }
                        }
                    }, 200L)
                }
            }
            true
        }.getOrDefault(false)
    }

    private fun restoreNormalMode(
        context: Context,
        audioManager: AudioManager,
        notifManager: NotificationManager
    ) {
        audioHandler.removeCallbacksAndMessages(null)
        runCatching {
            // Step 1: Deactivate OS-owned AutomaticZenRule & ensure system DND is OFF
            setOsOwnedDndState(context, notifManager, false, null)
            if (notifManager.isNotificationPolicyAccessGranted &&
                notifManager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
            ) {
                notifManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            }
            // Step 2: Immediately set Normal Ring + unmute streams
            audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
            audioManager.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_UNMUTE, 0)
            audioManager.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_UNMUTE, 0)

            // Step 3: Re-apply after 300ms so Realme/ColorOS doesn't leave phone stuck in Vibrate/Silent
            audioHandler.postDelayed({
                runCatching {
                    audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
                    audioManager.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_UNMUTE, 0)
                    audioManager.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_UNMUTE, 0)
                    val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_RING)
                    if (currentVol == 0) {
                        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
                        audioManager.setStreamVolume(AudioManager.STREAM_RING, (maxVol * 0.7).toInt().coerceAtLeast(1), 0)
                    }
                }
            }, 300L)
        }
    }

    fun handleIncomingCallBypass(context: Context, incomingNumber: String?) {
        val repo = ZoneRepository(context)
        val status = repo.getStatus()
        if (!status.isMutedByApp) return

        val vipConfig = repo.getVipConfig()
        if ((!vipConfig.enabled && !vipConfig.repeatCallerBypass) || incomingNumber.isNullOrBlank()) return

        val normalized = normalizePhone(incomingNumber)
        if (normalized.length < 7) return

        val isVipContact = vipConfig.enabled && vipConfig.contacts.any { normalizePhone(it.phoneNumber) == normalized }
        val callCount = repo.recordIncomingCall(normalized)
        val requiredCalls = vipConfig.repeatCallThreshold.coerceAtLeast(5)
        val isRepeatEmergency = vipConfig.repeatCallerBypass && callCount >= requiredCalls

        if (isVipContact || isRepeatEmergency) {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val notifManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            restoreNormalMode(context, audioManager, notifManager)
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
            audioManager.setStreamVolume(AudioManager.STREAM_RING, maxVol, 0)
        }
    }

    fun normalizePhone(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return if (digits.length > 10) digits.takeLast(10) else digits
    }

    fun scheduleNextBoundaryAlarm(context: Context, rules: List<SilentZoneRule>) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ZoneTriggerReceiver::class.java).apply {
            action = ACTION_EVALUATE
        }
        val pi = PendingIntent.getBroadcast(
            context,
            4041,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (rules.isEmpty()) {
            runCatching { alarmManager.cancel(pi) }
            return
        }

        val now = Calendar.getInstance()
        var earliestNextMillis: Long? = null

        for (rule in rules.filter { it.isTimeTriggerActive() }) {
            val boundaries = listOf(rule.startHour to rule.startMinute, rule.endHour to rule.endMinute)
            for ((h, m) in boundaries) {
                val candidate = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, h)
                    set(Calendar.MINUTE, m)
                    set(Calendar.SECOND, 2)
                    set(Calendar.MILLISECOND, 0)
                    if (timeInMillis <= now.timeInMillis) add(Calendar.DAY_OF_YEAR, 1)
                }
                if (earliestNextMillis == null || candidate.timeInMillis < earliestNextMillis) {
                    earliestNextMillis = candidate.timeInMillis
                }
            }
        }

        val twoMinsFromNow = System.currentTimeMillis() + 2 * 60 * 1000L
        val triggerAt = if (earliestNextMillis != null && earliestNextMillis < twoMinsFromNow) earliestNextMillis else twoMinsFromNow
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        }
    }
}

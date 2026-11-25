package com.shubham.silentzone.engine

import android.app.AlarmManager
import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.service.notification.Condition
import android.service.notification.ZenPolicy
import com.shubham.silentzone.MainActivity
import com.shubham.silentzone.data.ActiveZoneStatus
import com.shubham.silentzone.data.SilenceProfile
import com.shubham.silentzone.data.SilentZoneRule
import com.shubham.silentzone.data.ZoneRepository
import com.shubham.silentzone.util.RadioFingerprintScanner
import com.shubham.silentzone.util.RadioSnapshot
import java.util.Calendar
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object ZoneEvaluatorEngine {

    const val ACTION_EVALUATE = "com.shubham.silentzone.ACTION_EVALUATE_ZONES"

    /**
     * With the screen OFF many phones (ColorOS/Realme etc.) put Wi-Fi to sleep. So when the screen is off we
     * only treat "zone Wi-Fi gone" as "left the zone" after it has been gone this long.
     * Screen ON = immediate (plus the 6s anti-flicker in the network callback).
     */
    private const val SCREEN_OFF_EXIT_GRACE_MS = 5 * 60 * 1000L

    /** How long a VIP / repeat-caller bypass keeps the phone un-muted so the zone doesn't re-mute mid-ring. */
    private const val VIP_BYPASS_WINDOW_MS = 60 * 1000L

    private var networkCallbackRegistered = false
    private var pendingIntentCallbackRegistered = false

    // Handler only for delayed re-applies of audio state (cancelled whenever a new profile is applied)
    private val audioHandler = Handler(Looper.getMainLooper())

    // Handler only for delayed evaluations triggered by network callbacks (never cancelled by audio code)
    private val evalHandler = Handler(Looper.getMainLooper())

    // ---- Background evaluation plumbing (keeps the UI thread free -> no scroll lag) ----
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "silentzone-eval").apply { isDaemon = true }
    }
    private val queued = AtomicBoolean(false)
    private val doneCallbacks = ConcurrentLinkedQueue<() -> Unit>()
    private val uiHandler = Handler(Looper.getMainLooper())

    @Synchronized
    fun registerWifiWatcher(context: Context) {
        runCatching {
            val appCtx = context.applicationContext
            val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()

            // 1. OS-level PendingIntent callback wakes app even if the process was killed
            //    (register ONCE per process to avoid the 100-callback limit crash!)
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
                    private var lastCapsEvalAt = 0L

                    override fun onAvailable(network: Network) {
                        evalHandler.postDelayed({ evaluateNow(appCtx) }, 500L)
                    }

                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                        // Fires on every signal-strength change -> throttle hard
                        val t = SystemClock.elapsedRealtime()
                        if (t - lastCapsEvalAt > 3_000L && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                            lastCapsEvalAt = t
                            evaluateNow(appCtx)
                        }
                    }

                    override fun onLost(network: Network) {
                        // 6-second grace window so weak fringe Wi-Fi or mesh handoff doesn't unmute prematurely
                        evalHandler.postDelayed({ evaluateNow(appCtx) }, 6000L)
                    }
                })
                networkCallbackRegistered = true
            }
        }
    }

    private const val ZEN_RULE_PREF_ID = "silentzone_zen_rule_id"

    /**
     * Policy used while a "Total Silent" zone is active:
     * ring + notification sounds are blocked, but ALARMS and MEDIA (videos/music) keep working,
     * so the user can still watch videos and raise media volume inside the zone.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun buildZenPolicy(): ZenPolicy {
        return ZenPolicy.Builder()
            .allowAlarms(true)
            .allowMedia(true)
            .allowSystem(false)
            .allowReminders(false)
            .allowEvents(false)
            .allowCalls(ZenPolicy.PEOPLE_TYPE_NONE)
            .allowMessages(ZenPolicy.PEOPLE_TYPE_NONE)
            .allowRepeatCallers(false)
            .showAllVisualEffects()
            .build()
    }

    private fun newZenRule(
        name: String,
        owner: ComponentName,
        configActivity: ComponentName,
        conditionId: Uri
    ): AutomaticZenRule {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            AutomaticZenRule(
                name,
                owner,
                configActivity,
                conditionId,
                buildZenPolicy(),
                NotificationManager.INTERRUPTION_FILTER_PRIORITY,
                true
            )
        } else {
            // API 26-28: the 7-arg constructor does not exist there
            AutomaticZenRule(
                name,
                owner,
                conditionId,
                NotificationManager.INTERRUPTION_FILTER_PRIORITY,
                true
            )
        }
    }

    private fun setOsOwnedDndState(context: Context, nm: NotificationManager, active: Boolean, zoneName: String?) {
        if (!nm.isNotificationPolicyAccessGranted) return
        val prefs = context.getSharedPreferences("silentzone_zen_prefs", Context.MODE_PRIVATE)
        runCatching {
            val existingId = prefs.getString(ZEN_RULE_PREF_ID, null)
            val ruleName = if (active && !zoneName.isNullOrBlank()) "SilentZone: $zoneName" else "SilentZone Guard"
            val ownerComponent = ComponentName(context, SilentZoneConditionService::class.java)
            val configActivity = ComponentName(context, MainActivity::class.java)
            val conditionId = Uri.Builder()
                .scheme("condition")
                .authority(context.packageName)
                .appendPath("active_zone")
                .build()

            var ruleId = existingId
            val existingRule = if (ruleId != null) nm.getAutomaticZenRule(ruleId) else null

            if (existingRule == null) {
                if (!active) return@runCatching // nothing to switch off
                ruleId = nm.addAutomaticZenRule(newZenRule(ruleName, ownerComponent, configActivity, conditionId))
                prefs.edit().putString(ZEN_RULE_PREF_ID, ruleId).apply()
            } else if (active && ruleId != null) {
                // Also upgrades rules created by older versions (they used "total silence" = media+alarms muted)
                existingRule.name = ruleName
                existingRule.interruptionFilter = NotificationManager.INTERRUPTION_FILTER_PRIORITY
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    existingRule.zenPolicy = buildZenPolicy()
                }
                existingRule.isEnabled = true
                nm.updateAutomaticZenRule(ruleId, existingRule)
            }

            if (ruleId != null) {
                val state = if (active) Condition.STATE_TRUE else Condition.STATE_FALSE
                val summary = if (active) "Active: ${zoneName ?: "SilentZone"}" else "Standby"
                val condition = Condition(conditionId, summary, state)
                nm.setAutomaticZenRuleState(ruleId, condition)
            }
        }
    }

    /**
     * Re-evaluates all zones on a background thread. Safe to call from anywhere, as often as you like:
     * calls are coalesced. [onDone] (optional) runs on the main thread after the evaluation finished.
     */
    fun evaluateNow(context: Context, onDone: (() -> Unit)? = null) {
        val appCtx = context.applicationContext
        if (onDone != null) doneCallbacks.add(onDone)
        if (!queued.compareAndSet(false, true)) return // a run is already queued and will cover this request

        worker.execute {
            queued.set(false) // requests arriving from now on schedule a fresh run
            val callbacks = generateSequence { doneCallbacks.poll() }.toList()
            try {
                registerWifiWatcher(appCtx)
                evaluateBlocking(appCtx)
            } catch (_: Throwable) {
                // never let a background evaluation crash the app
            }
            if (callbacks.isNotEmpty()) {
                uiHandler.post { callbacks.forEach { it() } }
            }
        }
    }

    /**
     * "Quick Unmute Now": restores the normal ring state and pauses all zones for [snoozeMs]
     * (otherwise the very next evaluation would simply mute the phone again).
     * [onDone] runs on the main thread afterwards.
     */
    fun quickUnmute(context: Context, snoozeMs: Long, onDone: (() -> Unit)? = null) {
        val appCtx = context.applicationContext
        worker.execute {
            try {
                val repo = ZoneRepository(appCtx)
                repo.setSnoozeUntil(System.currentTimeMillis() + snoozeMs)
                val audioManager = appCtx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val notifManager = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                unmuteAndSaveStatus(appCtx, repo, audioManager, notifManager)
                finishEvaluation(appCtx, repo.getRules().filter { it.isEnabled })
            } catch (_: Throwable) {
            }
            if (onDone != null) uiHandler.post { onDone() }
        }
    }

    private fun finishEvaluation(context: Context, rules: List<SilentZoneRule>) {
        scheduleNextBoundaryAlarm(context, rules)
        WakeTriggerRegistrar.syncOsWakeTriggers(context)
        ZoneGuardService.syncServiceState(context)
    }

    private fun evaluateBlocking(context: Context) {
        val repo = ZoneRepository(context)
        val rules = repo.getRules().filter { it.isEnabled }
        val currentStatus = repo.getStatus()
        val needsLocation = rules.any { it.isLocationTriggerActive() }
        val snapshot = RadioFingerprintScanner.captureCurrentEnvironment(context, includeLocation = needsLocation)
        if (needsLocation && snapshot.isSystemLocationEnabled && snapshot.latitude == null) {
            uiHandler.post {
                RadioFingerprintScanner.captureFreshEnvironmentAsync(context) {
                    evaluateNow(context)
                }
            }
        }
        val now = System.currentTimeMillis()

        val matchedRule = rules.firstOrNull { rule -> matchesRule(rule, snapshot) }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val notifManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // User pressed "Quick Unmute": zones are paused for a while, don't mute again
        if (now < repo.getSnoozeUntil()) {
            finishEvaluation(context, rules)
            return
        }

        if (matchedRule != null) {
            repo.setWifiLostSince(0L)
            val prevMode = if (currentStatus.isMutedByApp) {
                currentStatus.previousRingerMode
            } else {
                AudioManager.RINGER_MODE_NORMAL
            }

            // A VIP call is ringing right now -> don't re-mute until it ends
            val bypassActive = now < repo.getBypassUntil()
            val appliedSuccessfully = if (bypassActive) {
                notifManager.isNotificationPolicyAccessGranted
            } else {
                applySilenceProfile(context, audioManager, notifManager, matchedRule.profile, matchedRule.name)
            }

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
                    lastEvaluatedAt = now,
                    previousRingerMode = prevMode
                )
            )
        } else if (currentStatus.isMutedByApp) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val isScreenOn = pm.isInteractive
            val activeRule = rules.firstOrNull { it.name == currentStatus.activeZoneName }

            // If rule was deleted or disabled by user, unmute immediately!
            if (activeRule == null) {
                unmuteAndSaveStatus(context, repo, audioManager, notifManager)
                finishEvaluation(context, rules)
                return
            }

            // ---- Has the user really left the zone? Every ACTIVE trigger of the rule must have "exited" ----
            val exits = mutableListOf<Boolean>()

            if (activeRule.isTimeTriggerActive()) {
                // We only get here when no trigger matched, so the time window is over
                exits.add(!matchesTimeWindow(activeRule))
            }

            if (activeRule.isWifiTriggerActive()) {
                val wifiMgr = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                val isWifiRadioEnabled = runCatching { wifiMgr.isWifiEnabled }.getOrDefault(true)
                val wifiGone = !snapshot.isWifiConnected ||
                        (!snapshot.wifiNetworkSignature.isNullOrBlank() && !matchesWifiOnly(activeRule, snapshot))
                if (isScreenOn && !isWifiRadioEnabled) {
                    // User turned Wi-Fi toggle OFF -> exit immediately
                    repo.setWifiLostSince(0L)
                    exits.add(true)
                } else if (!isScreenOn) {
                    // Screen is OFF: ColorOS/Realme puts Wi-Fi to sleep, so NEVER unmute due to Wi-Fi sleep off-screen
                    repo.setWifiLostSince(0L)
                    exits.add(false)
                } else if (wifiGone) {
                    // Screen is ON & Wi-Fi toggle is ON: give radio 12s to wake from sleep & reconnect
                    if (repo.getWifiLostSince() == 0L) {
                        repo.setWifiLostSince(now)
                        evalHandler.postDelayed({ evaluateNow(context) }, 12_500L)
                    }
                    val lostFor = now - repo.getWifiLostSince()
                    exits.add(lostFor >= 12_000L)
                } else {
                    repo.setWifiLostSince(0L)
                    exits.add(false)
                }
            }

            if (activeRule.isLocationTriggerActive()) {
                if (!snapshot.isSystemLocationEnabled) {
                    // User turned phone Location OFF -> exit immediately
                    exits.add(true)
                } else {
                    exits.add(
                        activeRule.latitude != null && activeRule.longitude != null &&
                                snapshot.latitude != null && snapshot.longitude != null &&
                                !matchesLocationOnly(activeRule, snapshot)
                    )
                }
            }

            val keepLocked = exits.isNotEmpty() && !exits.all { it }

            if (keepLocked) {
                if (now >= repo.getBypassUntil()) {
                    applySilenceProfile(context, audioManager, notifManager, activeRule.profile, activeRule.name)
                }
                finishEvaluation(context, rules)
                return
            }

            unmuteAndSaveStatus(context, repo, audioManager, notifManager)
        }

        finishEvaluation(context, rules)
    }

    private fun unmuteAndSaveStatus(
        context: Context,
        repo: ZoneRepository,
        audioManager: AudioManager,
        notifManager: NotificationManager
    ) {
        restoreNormalMode(context, audioManager, notifManager)
        repo.setWifiLostSince(0L)
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
                    // OS-owned AutomaticZenRule (Android auto-restores Normal Ring if the app is uninstalled).
                    // Policy = ring/notification silent, media + alarms still audible.
                    setOsOwnedDndState(context, notifManager, true, zoneName)
                    if (audioManager.ringerMode != AudioManager.RINGER_MODE_SILENT) { audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT }
                    audioHandler.postDelayed({
                        runCatching {
                            setOsOwnedDndState(context, notifManager, true, zoneName)
                        }
                    }, 200L)
                }
                SilenceProfile.VIBRATE_ONLY -> {
                    // Make sure a previous Total-Silent rule is switched off, otherwise it would keep DND on
                    setOsOwnedDndState(context, notifManager, false, null)
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

    /** Called from the receiver (main thread) the moment a call starts ringing. Must stay fast. */
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
            // Stop background re-evaluations from re-muting the phone while this call rings
            repo.setBypassUntil(System.currentTimeMillis() + VIP_BYPASS_WINDOW_MS)
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val notifManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            restoreNormalMode(context, audioManager, notifManager)
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
            audioManager.setStreamVolume(AudioManager.STREAM_RING, maxVol, 0)
        }
    }

    /** Called when the phone goes back to idle (call ended / missed) so the zone can mute again. */
    fun clearCallBypass(context: Context) {
        ZoneRepository(context).setBypassUntil(0L)
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

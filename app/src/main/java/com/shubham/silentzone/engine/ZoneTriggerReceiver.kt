package com.shubham.silentzone.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager

class ZoneTriggerReceiver : BroadcastReceiver() {

    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val appCtx = context.applicationContext

        if (action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            when (state) {
                TelephonyManager.EXTRA_STATE_RINGING -> {
                    // Must be immediate (before the ringtone starts) and is cheap -> stays synchronous
                    ZoneEvaluatorEngine.handleIncomingCallBypass(appCtx, incomingNumber)
                }
                TelephonyManager.EXTRA_STATE_IDLE -> {
                    // Call finished: allow the zone to mute again
                    val pending = goAsync()
                    ZoneEvaluatorEngine.clearCallBypass(appCtx)
                    Handler(Looper.getMainLooper()).postDelayed({
                        ZoneEvaluatorEngine.evaluateNow(appCtx) { pending?.finish() }
                    }, 2500L)
                }
            }
        } else {
            // Evaluation runs on a background thread; goAsync keeps this process alive until it is done
            val pending = goAsync()
            ZoneEvaluatorEngine.evaluateNow(appCtx) { pending?.finish() }
        }
    }
}

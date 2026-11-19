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
                    ZoneEvaluatorEngine.handleIncomingCallBypass(appCtx, incomingNumber)
                }
                TelephonyManager.EXTRA_STATE_IDLE -> {
                    Handler(Looper.getMainLooper()).postDelayed({
                        ZoneEvaluatorEngine.evaluateNow(appCtx)
                    }, 2500L)
                }
            }
        } else {
            ZoneEvaluatorEngine.evaluateNow(appCtx)
        }
    }
}

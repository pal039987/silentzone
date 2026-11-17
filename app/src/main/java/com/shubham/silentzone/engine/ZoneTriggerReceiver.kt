package com.shubham.silentzone.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager

class ZoneTriggerReceiver : BroadcastReceiver() {

    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return

        if (action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            when (state) {
                TelephonyManager.EXTRA_STATE_RINGING -> {
                    ZoneEvaluatorEngine.handleIncomingCallBypass(context, incomingNumber)
                }
                TelephonyManager.EXTRA_STATE_IDLE -> {
                    ZoneEvaluatorEngine.evaluateNow(context)
                }
            }
        } else {
            ZoneEvaluatorEngine.evaluateNow(context)
        }
    }
}

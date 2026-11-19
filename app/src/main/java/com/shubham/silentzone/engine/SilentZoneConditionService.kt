package com.shubham.silentzone.engine

import android.net.Uri
import android.service.notification.ConditionProviderService

class SilentZoneConditionService : ConditionProviderService() {
    override fun onConnected() {}
    override fun onSubscribe(conditionId: Uri?) {}
    override fun onUnsubscribe(conditionId: Uri?) {}
}

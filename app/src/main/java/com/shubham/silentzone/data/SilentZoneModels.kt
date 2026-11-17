package com.shubham.silentzone.data

import java.util.UUID

enum class TriggerType {
    WIFI_ANY,     // Zero-Location: Any Wi-Fi connected
    SCHEDULE,     // Zero-Location: Exact Time & Day window
    TOWER_WIFI    // Optional: Specific Cell-ID / Wi-Fi BSSID (needs Location toggle ON)
}

enum class SilenceProfile {
    VIBRATE_ONLY,
    TOTAL_SILENT
}

data class SilentZoneRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val category: String = "College",
    val triggerType: TriggerType = TriggerType.WIFI_ANY,
    val cellSignatures: List<String> = emptyList(),
    val wifiBssid: String? = null,
    val wifiSsid: String? = null,
    val startHour: Int = 9,
    val startMinute: Int = 0,
    val endHour: Int = 17,
    val endMinute: Int = 0,
    val activeDays: List<Int> = listOf(2, 3, 4, 5, 6),
    val profile: SilenceProfile = SilenceProfile.VIBRATE_ONLY,
    val isEnabled: Boolean = true
)

data class VipContact(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val phoneNumber: String
)

data class VipGuardConfig(
    val enabled: Boolean = true,
    val repeatCallerBypass: Boolean = true,
    val repeatCallThreshold: Int = 5, // >4 calls (5th call triggers ring)
    val contacts: List<VipContact> = emptyList()
)

data class ActiveZoneStatus(
    val isMutedByApp: Boolean = false,
    val activeZoneName: String? = null,
    val matchedReason: String = "Normal Ring Mode",
    val lastEvaluatedAt: Long = System.currentTimeMillis(),
    val previousRingerMode: Int = 2
)

package com.shubham.silentzone.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class ZoneRepository(context: Context) {

    private val prefs = context.getSharedPreferences("silentzone_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    private fun normalizePlaceKey(name: String): String {
        return name.trim().lowercase()
    }

    fun getRules(): List<SilentZoneRule> {
        val json = prefs.getString("rules_json", null) ?: return emptyList()
        val type = object : TypeToken<List<SilentZoneRule>>() {}.type
        return runCatching { gson.fromJson<List<SilentZoneRule>>(json, type) }.getOrDefault(emptyList())
    }

    fun saveRules(rules: List<SilentZoneRule>) {
        prefs.edit().putString("rules_json", gson.toJson(rules)).apply()
    }

    fun addOrUpdateRule(rule: SilentZoneRule) {
        val targetKey = normalizePlaceKey(rule.name)
        val current = getRules().map { existing ->
            if (rule.isEnabled && existing.id != rule.id && normalizePlaceKey(existing.name) == targetKey) {
                existing.copy(isEnabled = false)
            } else {
                existing
            }
        }.toMutableList()

        val idx = current.indexOfFirst { it.id == rule.id }
        if (idx >= 0) current[idx] = rule else current.add(rule)
        saveRules(current)
    }

    fun toggleRule(id: String, enabled: Boolean): String? {
        val current = getRules()
        val targetRule = current.find { it.id == id } ?: return null
        val targetKey = normalizePlaceKey(targetRule.name)
        var disabledOtherName: String? = null

        val updated = current.map { rule ->
            when {
                rule.id == id -> rule.copy(isEnabled = enabled)
                enabled && normalizePlaceKey(rule.name) == targetKey && rule.isEnabled -> {
                    disabledOtherName = "${rule.name} (${if (rule.profile == SilenceProfile.VIBRATE_ONLY) "Vibrate" else "Total Silent"})"
                    rule.copy(isEnabled = false)
                }
                else -> rule
            }
        }
        saveRules(updated)
        return disabledOtherName
    }

    fun deleteRule(id: String) {
        saveRules(getRules().filterNot { it.id == id })
    }

    fun getVipConfig(): VipGuardConfig {
        val json = prefs.getString("vip_json", null) ?: return VipGuardConfig()
        return runCatching { gson.fromJson(json, VipGuardConfig::class.java) }.getOrDefault(VipGuardConfig())
    }

    fun saveVipConfig(config: VipGuardConfig) {
        prefs.edit().putString("vip_json", gson.toJson(config)).apply()
    }

    fun getStatus(): ActiveZoneStatus {
        val json = prefs.getString("status_json", null) ?: return ActiveZoneStatus()
        return runCatching { gson.fromJson(json, ActiveZoneStatus::class.java) }.getOrDefault(ActiveZoneStatus())
    }

    fun saveStatus(status: ActiveZoneStatus) {
        prefs.edit().putString("status_json", gson.toJson(status)).apply()
    }

    fun recordIncomingCall(normalizedNumber: String): Int {
        val now = System.currentTimeMillis()
        val windowMs = 3 * 60 * 1000L
        val lastNumber = prefs.getString("last_call_num", "")
        val lastTime = prefs.getLong("last_call_time", 0L)
        val prevCount = prefs.getInt("last_call_count", 0)

        val newCount = if (lastNumber == normalizedNumber && (now - lastTime) <= windowMs) {
            prevCount + 1
        } else {
            1
        }
        prefs.edit()
            .putString("last_call_num", normalizedNumber)
            .putLong("last_call_time", now)
            .putInt("last_call_count", newCount)
            .apply()
        return newCount
    }
}

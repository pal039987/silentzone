package com.shubham.silentzone.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.CellIdentityGsm
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellIdentityWcdma
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

data class RadioSnapshot(
    val isWifiConnected: Boolean,
    val cellSignatures: List<String>,
    val wifiBssid: String?,
    val wifiSsid: String?,
    val isSystemLocationEnabled: Boolean = false
)

object RadioFingerprintScanner {

    fun isLocationEnabled(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }

    fun isConnectedToWifi(context: Context): Boolean {
        return runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }.getOrDefault(false)
    }

    fun captureCurrentEnvironment(context: Context): RadioSnapshot {
        val wifiConnected = isConnectedToWifi(context)
        val locOn = isLocationEnabled(context)
        val cells = if (locOn) getActiveCellSignatures(context) else emptyList()
        val (bssid, ssid) = if (locOn) getConnectedWifiInfo(context) else Pair(null, null)
        return RadioSnapshot(wifiConnected, cells, bssid, ssid, locOn)
    }

    fun captureFreshEnvironmentAsync(context: Context, onResult: (RadioSnapshot) -> Unit) {
        val wifiConnected = isConnectedToWifi(context)
        val locOn = isLocationEnabled(context)
        val (bssid, ssid) = getConnectedWifiInfo(context)
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            onResult(RadioSnapshot(wifiConnected, emptyList(), bssid, ssid, locOn))
            return
        }

        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                tm.requestCellInfoUpdate(
                    ContextCompat.getMainExecutor(context),
                    object : TelephonyManager.CellInfoCallback() {
                        override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                            val parsed = parseCellInfoList(cellInfo).ifEmpty {
                                getActiveCellSignatures(context)
                            }
                            onResult(RadioSnapshot(wifiConnected, parsed, bssid, ssid, locOn))
                        }

                        override fun onError(errorCode: Int, detail: Throwable?) {
                            onResult(RadioSnapshot(wifiConnected, getActiveCellSignatures(context), bssid, ssid, locOn))
                        }
                    }
                )
            }.onFailure {
                onResult(RadioSnapshot(wifiConnected, getActiveCellSignatures(context), bssid, ssid, locOn))
            }
        } else {
            onResult(RadioSnapshot(wifiConnected, getActiveCellSignatures(context), bssid, ssid, locOn))
        }
    }

    @Suppress("DEPRECATION")
    fun getActiveCellSignatures(context: Context): List<String> {
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) return emptyList()

        return runCatching {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            parseCellInfoList(tm.allCellInfo ?: emptyList())
        }.getOrDefault(emptyList())
    }

    private fun parseCellInfoList(allCells: List<CellInfo>): List<String> {
        val signatures = linkedSetOf<String>()
        for (info in allCells) {
            when (info) {
                is CellInfoLte -> {
                    val id: CellIdentityLte = info.cellIdentity
                    if (id.ci != Int.MAX_VALUE && id.ci > 0) {
                        signatures.add("LTE:${id.mccString}-${id.mncString}-${id.tac}-${id.ci}")
                    }
                }
                is CellInfoWcdma -> {
                    val id: CellIdentityWcdma = info.cellIdentity
                    if (id.cid != Int.MAX_VALUE && id.cid > 0) {
                        signatures.add("WCDMA:${id.mccString}-${id.mncString}-${id.lac}-${id.cid}")
                    }
                }
                is CellInfoGsm -> {
                    val id: CellIdentityGsm = info.cellIdentity
                    if (id.cid != Int.MAX_VALUE && id.cid > 0) {
                        signatures.add("GSM:${id.mccString}-${id.mncString}-${id.lac}-${id.cid}")
                    }
                }
                else -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info is CellInfoNr) {
                        val id = info.cellIdentity as? CellIdentityNr
                        if (id != null && id.nci != Long.MAX_VALUE && id.nci > 0L) {
                            signatures.add("NR:${id.mccString}-${id.mncString}-${id.tac}-${id.nci}")
                        }
                    }
                }
            }
        }
        return signatures.take(4).toList()
    }

    @Suppress("DEPRECATION")
    fun getConnectedWifiInfo(context: Context): Pair<String?, String?> {
        return runCatching {
            var wifiInfo: WifiInfo? = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                wifiInfo = caps?.transportInfo as? WifiInfo
            }
            if (wifiInfo == null) {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiInfo = wm.connectionInfo
            }
            val bssid = wifiInfo?.bssid?.takeIf { it.isNotBlank() && it != "02:00:00:00:00:00" }
            val rawSsid = wifiInfo?.ssid?.removePrefix("\"")?.removeSuffix("\"")
            val ssid = rawSsid?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
            Pair(bssid, ssid)
        }.getOrDefault(Pair(null, null))
    }
}

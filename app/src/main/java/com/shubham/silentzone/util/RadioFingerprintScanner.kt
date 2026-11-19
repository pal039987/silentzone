package com.shubham.silentzone.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.LinkProperties
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
    val isSystemLocationEnabled: Boolean = false,
    val wifiNetworkSignature: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null
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

    @Suppress("DEPRECATION")
    private fun findConnectedWifiNetwork(cm: ConnectivityManager): android.net.Network? {
        val active = cm.activeNetwork
        if (active != null && cm.getNetworkCapabilities(active)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
            return active
        }
        return cm.allNetworks.firstOrNull { net ->
            cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    fun isConnectedToWifi(context: Context): Boolean {
        return runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            findConnectedWifiNetwork(cm) != null
        }.getOrDefault(false)
    }


    @Suppress("DEPRECATION")
    fun getBestLastKnownLocation(context: Context): Pair<Double, Double>? {
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse || !isLocationEnabled(context)) return null
        return runCatching {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            var best: Location? = null
            for (p in providers) {
                if (lm.isProviderEnabled(p)) {
                    val loc = lm.getLastKnownLocation(p) ?: continue
                    if (best == null || loc.accuracy < best.accuracy) {
                        best = loc
                    }
                }
            }
            best?.let { Pair(it.latitude, it.longitude) }
        }.getOrNull()
    }

    fun captureCurrentEnvironment(context: Context): RadioSnapshot {
        val wifiConnected = isConnectedToWifi(context)
        val locOn = isLocationEnabled(context)
        val netSig = if (wifiConnected) getWifiNetworkSignature(context) else null
        val cells = if (locOn) getActiveCellSignatures(context) else emptyList()
        val (bssid, ssid) = if (locOn) getConnectedWifiInfo(context) else Pair(null, null)
        val coords = if (locOn) getBestLastKnownLocation(context) else null
        return RadioSnapshot(wifiConnected, cells, bssid, ssid, locOn, netSig, coords?.first, coords?.second)
    }

    fun getWifiNetworkSignature(context: Context): String? {
        return runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = findConnectedWifiNetwork(cm) ?: return null
            val caps = cm.getNetworkCapabilities(network) ?: return null
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
            val lp: LinkProperties = cm.getLinkProperties(network) ?: return null

            val gateways = lp.routes
                .filter { it.isDefaultRoute && it.gateway != null }
                .mapNotNull { it.gateway?.hostAddress }
                .sorted()
                .joinToString(",")

            val subnets = lp.linkAddresses
                .mapNotNull { la ->
                    val addr = la.address?.hostAddress ?: return@mapNotNull null
                    if (addr.contains(":")) null else "$addr/${la.prefixLength}"
                }
                .map { cidr ->
                    val parts = cidr.split("/")
                    val octets = parts[0].split(".")
                    if (octets.size == 4) "${octets[0]}.${octets[1]}.${octets[2]}.0/${parts[1]}" else cidr
                }
                .distinct()
                .sorted()
                .joinToString(",")

            val dnsList = lp.dnsServers
                .mapNotNull { it.hostAddress }
                .filter { !it.contains(":") }
                .sorted()
                .joinToString(",")

            val raw = "GW=$gateways|SUB=$subnets|DNS=$dnsList"
            if (gateways.isBlank() && subnets.isBlank()) null else raw
        }.getOrNull()
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
                            val c = getBestLastKnownLocation(context); onResult(RadioSnapshot(wifiConnected, parsed, bssid, ssid, locOn, getWifiNetworkSignature(context), c?.first, c?.second))
                        }

                        override fun onError(errorCode: Int, detail: Throwable?) {
                            val c = getBestLastKnownLocation(context); onResult(RadioSnapshot(wifiConnected, getActiveCellSignatures(context), bssid, ssid, locOn, getWifiNetworkSignature(context), c?.first, c?.second))
                        }
                    }
                )
            }.onFailure {
                val c = getBestLastKnownLocation(context); onResult(RadioSnapshot(wifiConnected, getActiveCellSignatures(context), bssid, ssid, locOn, getWifiNetworkSignature(context), c?.first, c?.second))
            }
        } else {
            val c = getBestLastKnownLocation(context); onResult(RadioSnapshot(wifiConnected, getActiveCellSignatures(context), bssid, ssid, locOn, getWifiNetworkSignature(context), c?.first, c?.second))
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
                val wifiNet = findConnectedWifiNetwork(cm)
                val caps = if (wifiNet != null) cm.getNetworkCapabilities(wifiNet) else null
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

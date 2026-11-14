package app.matterplug.cellular

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.telephony.CellInfo
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.NetworkRegistrationInfo
import android.telephony.ServiceState
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import app.matterplug.cellular.logic.PassiveReading
import app.matterplug.cellular.logic.SignalDetail

/**
 * Reads the passive layers (SIM, registration, signal) of the default SMS subscription and parses them into a
 * [PassiveReading]. The only place Cellular Check touches the telephony APIs; everything after it is pure.
 */
object CellularReader {
    private const val TAG = "Cellular"

    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    fun read(ctx: Context): PassiveReading {
        if (!hasPermission(ctx)) return unavailable("no phone permission")
        val base = ctx.getSystemService(TelephonyManager::class.java) ?: return unavailable("no telephony")
        // In airplane mode the phone reports no active subscription (seen on the SM-G780F): read the default slot then,
        // so radio-off shows as a registration failure, not as a SIM fault.
        val subId = SubscriptionManager.getDefaultSmsSubscriptionId()
        val tm = if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) base else base.createForSubscriptionId(subId)
        val airplane = Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1

        val simState = try { tm.simState } catch (e: SecurityException) { TelephonyManager.SIM_STATE_UNKNOWN }
        if (simState != TelephonyManager.SIM_STATE_READY) return unavailable(simText(simState))

        val service: ServiceState? = try { tm.serviceState } catch (e: SecurityException) {
            Log.w(TAG, "service state not readable", e); null
        }
        val (registered, roaming) = registration(service)
        val rat = ratName(try { tm.dataNetworkType } catch (e: SecurityException) { TelephonyManager.NETWORK_TYPE_UNKNOWN })

        val strength = tm.signalStrength
        val level = strength?.level
        val detail = signalDetail(strength, level)

        return PassiveReading(
            simReady = true, simDetail = "ready",
            registered = registered, roaming = roaming,
            registrationDetail = when {
                registered -> rat
                airplane -> "airplane mode"
                else -> serviceText(service)
            },
            signalLevel = level, signalDetail = detail,
        )
    }

    /**
     * Registered for SMS: in service in the voice domain (CS), which on VoLTE networks also covers IMS. Data
     * registration is not judged. VERIFY on the SM-G780F that a VoLTE-only network reports the CS domain registered.
     */
    private fun registration(s: ServiceState?): Pair<Boolean, Boolean> {
        if (s == null) return false to false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val cs = s.networkRegistrationInfoList.firstOrNull {
                it.domain and NetworkRegistrationInfo.DOMAIN_CS != 0 &&
                    it.transportType == android.telephony.AccessNetworkConstants.TRANSPORT_TYPE_WWAN
            }
            if (cs != null) return cs.isRegistered to cs.isRoaming
        }
        return (s.state == ServiceState.STATE_IN_SERVICE) to s.roaming
    }

    private fun signalDetail(strength: android.telephony.SignalStrength?, level: Int?): String {
        if (strength == null) return "unknown"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            for (c in strength.cellSignalStrengths) {
                when (c) {
                    is CellSignalStrengthNr -> return SignalDetail.format("NR", v(c.ssRsrp), v(c.ssRsrq), v(c.ssSinr), level)
                    is CellSignalStrengthLte -> return SignalDetail.format("LTE", v(c.rsrp), v(c.rsrq), v(c.rssnr), level)
                }
            }
        }
        return SignalDetail.format(null, null, null, null, level)
    }

    /** CellInfo.UNAVAILABLE means Android has no value. */
    private fun v(x: Int): Int? = if (x == CellInfo.UNAVAILABLE) null else x

    private fun unavailable(reason: String) = PassiveReading(
        simReady = false, simDetail = reason, registered = false, roaming = false,
        registrationDetail = reason, signalLevel = null, signalDetail = "unknown",
    )

    private fun simText(state: Int): String = when (state) {
        TelephonyManager.SIM_STATE_ABSENT -> "SIM absent"
        TelephonyManager.SIM_STATE_PIN_REQUIRED -> "SIM locked (PIN)"
        TelephonyManager.SIM_STATE_PUK_REQUIRED -> "SIM locked (PUK)"
        TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "SIM network-locked"
        TelephonyManager.SIM_STATE_NOT_READY -> "SIM not ready"
        TelephonyManager.SIM_STATE_PERM_DISABLED -> "SIM disabled"
        TelephonyManager.SIM_STATE_CARD_IO_ERROR -> "SIM card error"
        TelephonyManager.SIM_STATE_CARD_RESTRICTED -> "SIM restricted"
        else -> "SIM state unknown"
    }

    private fun serviceText(s: ServiceState?): String = when (s?.state) {
        ServiceState.STATE_OUT_OF_SERVICE -> "out of service"
        ServiceState.STATE_EMERGENCY_ONLY -> "emergency calls only"
        ServiceState.STATE_POWER_OFF -> "radio off"
        null -> "service state unknown"
        else -> "not registered"
    }

    private fun ratName(type: Int): String = when (type) {
        TelephonyManager.NETWORK_TYPE_NR -> "5G"
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
        TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA, TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
        TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GPRS, TelephonyManager.NETWORK_TYPE_GSM -> "2G"
        else -> "registered"
    }
}

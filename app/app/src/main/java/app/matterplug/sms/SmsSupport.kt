package app.matterplug.sms

import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager

/** What Cellular Check's loopback and Fixed IP's alerts share: which SmsManager to use, and what an SMS result code is called. */
object SmsSupport {
    /** The default SMS subscription's SmsManager, or the default one when there is none. */
    @Suppress("DEPRECATION")
    fun smsManager(ctx: Context): SmsManager {
        val subId = SubscriptionManager.getDefaultSmsSubscriptionId()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val base = ctx.getSystemService(SmsManager::class.java)
            if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) base.createForSubscriptionId(subId) else base
        } else {
            if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) SmsManager.getSmsManagerForSubscriptionId(subId) else SmsManager.getDefault()
        }
    }

    /** The name of the SmsManager RESULT_* constant with this value, so an unusual failure is readable; null if none. */
    fun resultName(code: Int): String? = try {
        SmsManager::class.java.fields.firstOrNull {
            it.name.startsWith("RESULT_") && it.type == Int::class.javaPrimitiveType &&
                java.lang.reflect.Modifier.isStatic(it.modifiers) && it.getInt(null) == code
        }?.name
    } catch (e: Exception) {
        null
    }
}

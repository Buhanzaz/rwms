package dev.buhanzaz.rwms.client.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri

/** Outcome of opening the dialer, not of placing or completing a telephone call. */
internal enum class CustomerSupportDialResult {
    OPENED,
    PHONE_UNAVAILABLE,
    DIALER_UNAVAILABLE,
}

/**
 * Accepts a supplied telephone number, not an arbitrary URI or a dial command. Formatting may be
 * removed, but no country prefix, fallback contact, extension, or USSD sequence is invented.
 */
internal fun customerSupportDialNumber(phone: String?): String? {
    val supplied = phone?.trim()?.takeIf { it.length <= 64 } ?: return null
    if (!supplied.matches(Regex("[+0-9() .-]+"))) return null
    return supplied.filter { it == '+' || it in '0'..'9' }
        .takeIf { it.matches(Regex("\\+?[0-9]{7,15}")) }
}

/**
 * Opens ACTION_DIAL only after an explicit user action. It never requests CALL_PHONE, starts an
 * automatic call, preflights package visibility, or reports an unavailable dialer as success.
 */
internal fun openCustomerSupportDialer(context: Context, phone: String?): CustomerSupportDialResult {
    val number = customerSupportDialNumber(phone) ?: return CustomerSupportDialResult.PHONE_UNAVAILABLE
    val intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))
    if (!context.hasActivityContext()) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        CustomerSupportDialResult.OPENED
    } catch (_: ActivityNotFoundException) {
        CustomerSupportDialResult.DIALER_UNAVAILABLE
    } catch (_: SecurityException) {
        CustomerSupportDialResult.DIALER_UNAVAILABLE
    }
}

private tailrec fun Context.hasActivityContext(): Boolean = when (this) {
    is Activity -> true
    is ContextWrapper -> if (baseContext === this) false else baseContext.hasActivityContext()
    else -> false
}

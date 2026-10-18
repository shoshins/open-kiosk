package org.openkiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Brings the kiosk up after a reboot. Android 10+ blocks activity starts
 * from the background, so on newer devices set Open Kiosk as the Home app
 * instead; this receiver covers Android 8.1 and 9.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (Settings(context).startUrl.isNullOrEmpty()) return
        context.startActivity(
            Intent(context, KioskActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

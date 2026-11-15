package app.absentmotion.ui.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Re-arms the periodic release check after a reboot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            UpdateWorker.schedule(context)
        }
    }
}

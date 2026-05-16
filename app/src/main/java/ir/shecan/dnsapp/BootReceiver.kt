package ir.shecan.dnsapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("shecan_prefs", Context.MODE_PRIVATE)
            val autoStart = prefs.getBoolean("auto_start", false)
            if (autoStart) {
                val vpnIntent = Intent(context, DnsVpnService::class.java)
                vpnIntent.action = DnsVpnService.ACTION_START
                context.startForegroundService(vpnIntent)
            }
        }
    }
}

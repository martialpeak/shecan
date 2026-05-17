package ir.shecan.dnsapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ir.shecan.dnsapp.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val client = OkHttpClient()

    companion object {
        const val VPN_REQUEST_CODE = 100
        val DDNS_URL get() = "https://ddns.shecan.ir/update?password=${BuildConfig.DDNS_PASSWORD}"
        val DNS_SERVERS = listOf("178.22.122.101", "185.51.200.1")
    }

    private val vpnStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == DnsVpnService.ACTION_VPN_STATE_CHANGED) {
                updateServiceStatus()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        updateServiceStatus()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(DnsVpnService.ACTION_VPN_STATE_CHANGED)
        registerReceiver(vpnStateReceiver, filter)
        updateServiceStatus()
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(vpnStateReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupUI() {
        binding.btnConnect.setOnClickListener {
            if (DnsVpnService.isRunning) {
                // اگر وصل است، فقط قطع کن
                stopVpn()
            } else {
                // اگر قطع است، اول آپدیت بعد وصل کن
                startFullConnectionFlow()
            }
        }
    }

    // تابع اصلی که همه کارها رو پشت سر هم انجام میده
    private fun startFullConnectionFlow() {
        lifecycleScope.launch {
            // مرحله اول: آپدیت DDNS
            setLoadingState(LoadingState.DDNS_UPDATING)
            log("در حال به‌روزرسانی اجباری DDNS...")
            
            val success = performDdnsUpdate()
            
            if (success) {
                // مرحله دوم: اتصال VPN
                log("✅ DDNS به‌روز شد. در حال اتصال VPN...")
                setLoadingState(LoadingState.CONNECTING)
                delay(500) // یک مکث کوتاه برای خواندن متن توسط کاربر
                requestVpnPermission()
            } else {
                // اگر آپدیت خطا خورد، وصل نشو
                log("❌ خطا در به‌روزرسانی DDNS. اتصال لغو شد.")
                setLoadingState(LoadingState.IDLE)
            }
        }
    }

    private suspend fun performDdnsUpdate(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(DDNS_URL)
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            withContext(Dispatchers.Main) {
                log("پاسخ سرور: $body")
            }
            response.isSuccessful
        } catch (e: IOException) {
            withContext(Dispatchers.Main) {
                log("خطا: ${e.message}")
            }
            false
        }
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, VPN_REQUEST_CODE)
        } else {
            startVpn()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE && resultCode == RESULT_OK) {
            startVpn()
        } else {
            log("❌ دسترسی VPN رد شد")
            setLoadingState(LoadingState.IDLE)
        }
    }

    private fun startVpn() {
        val intent = Intent(this, DnsVpnService::class.java)
        intent.action = DnsVpnService.ACTION_START
        startForegroundService(intent)
    }

    private fun stopVpn() {
        val intent = Intent(this, DnsVpnService::class.java)
        intent.action = DnsVpnService.ACTION_STOP
        startService(intent)
        log("🔌 VPN قطع شد")
    }

    private fun updateServiceStatus() {
        if (DnsVpnService.isRunning) {
            binding.btnConnect.text = "قطع اتصال"
            binding.statusIndicator.setBackgroundResource(R.drawable.status_connected)
            binding.tvStatus.text = "متصل به DNS شکن"
            binding.tvDns1.text = DNS_SERVERS[0]
            binding.tvDns2.text = DNS_SERVERS[1]
        } else {
            binding.btnConnect.text = "اتصال VPN"
            binding.statusIndicator.setBackgroundResource(R.drawable.status_disconnected)
            binding.tvStatus.text = "آماده اتصال"
            binding.tvDns1.text = "---"
            binding.tvDns2.text = "---"
        }
        setLoadingState(LoadingState.IDLE)
    }

    private fun log(message: String) {
        val current = binding.tvLog.text.toString()
        val newLog = "$message\n$current"
        val lines = newLog.split("\n").take(20)
        binding.tvLog.text = lines.joinToString("\n")
    }

    private fun setLoadingState(state: LoadingState) {
        when (state) {
            LoadingState.IDLE -> {
                binding.progressBar.visibility = View.GONE
                binding.btnConnect.isEnabled = true
            }
            LoadingState.CONNECTING -> {
                binding.progressBar.visibility = View.VISIBLE
                binding.btnConnect.isEnabled = false
                binding.btnConnect.text = "در حال اتصال..."
            }
            LoadingState.DDNS_UPDATING -> {
                binding.progressBar.visibility = View.VISIBLE
                binding.btnConnect.isEnabled = false
                binding.btnConnect.text = "در حال آپدیت DDNS..."
            }
        }
    }
}

enum class LoadingState {
    IDLE,
    CONNECTING,
    DDNS_UPDATING
}

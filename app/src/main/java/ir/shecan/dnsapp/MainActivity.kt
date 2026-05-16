package ir.shecan.dnsapp

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ir.shecan.dnsapp.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
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
        const val DDNS_URL = "https://ddns.shecan.ir/update?password=be36b57e172d0ecb"
        val DNS_SERVERS = listOf("178.22.122.101", "185.51.200.1")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        updateServiceStatus()
    }

    private fun setupUI() {
        binding.btnConnect.setOnClickListener {
            if (DnsVpnService.isRunning) {
                stopVpn()
            } else {
                startVpnFlow()
            }
        }

        binding.btnUpdateDdns.setOnClickListener {
            updateDdns()
        }

        binding.btnUpdateAndConnect.setOnClickListener {
            updateDdnsThenConnect()
        }
    }

    private fun updateDdnsThenConnect() {
        lifecycleScope.launch {
            setLoading(true)
            log("در حال به‌روزرسانی DDNS...")
            val success = performDdnsUpdate()
            if (success) {
                log("DDNS به‌روز شد. در حال اتصال VPN...")
                requestVpnPermission()
            } else {
                log("خطا در به‌روزرسانی DDNS. لطفاً دوباره امتحان کنید.")
                setLoading(false)
            }
        }
    }

    private fun updateDdns() {
        lifecycleScope.launch {
            setLoading(true)
            log("در حال به‌روزرسانی DDNS...")
            val success = performDdnsUpdate()
            if (success) {
                log("✅ DDNS با موفقیت به‌روز شد!")
            } else {
                log("❌ خطا در به‌روزرسانی DDNS")
            }
            setLoading(false)
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

    private fun startVpnFlow() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, VPN_REQUEST_CODE)
        } else {
            startVpn()
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE && resultCode == RESULT_OK) {
            startVpn()
        } else {
            log("❌ دسترسی VPN رد شد")
            setLoading(false)
        }
    }

    private fun startVpn() {
        val intent = Intent(this, DnsVpnService::class.java)
        intent.action = DnsVpnService.ACTION_START
        startService(intent)
        log("🔗 در حال اتصال به DNS شکن...")
        updateServiceStatus()
        setLoading(false)
    }

    private fun stopVpn() {
        val intent = Intent(this, DnsVpnService::class.java)
        intent.action = DnsVpnService.ACTION_STOP
        startService(intent)
        log("🔌 VPN قطع شد")
        updateServiceStatus()
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
            binding.tvStatus.text = "قطع"
            binding.tvDns1.text = "---"
            binding.tvDns2.text = "---"
        }
    }

    private fun log(message: String) {
        val current = binding.tvLog.text.toString()
        val newLog = if (current.isEmpty()) message else "$message\n$current"
        binding.tvLog.text = newLog
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnConnect.isEnabled = !loading
        binding.btnUpdateDdns.isEnabled = !loading
        binding.btnUpdateAndConnect.isEnabled = !loading
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
    }
}

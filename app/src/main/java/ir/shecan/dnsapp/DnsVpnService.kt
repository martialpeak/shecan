package ir.shecan.dnsapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class DnsVpnService : VpnService() {

    companion object {
        const val TAG = "DnsVpnService"
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"
        const val CHANNEL_ID = "shecan_vpn_channel"
        const val NOTIFICATION_ID = 1

        val PRIMARY_DNS = "178.22.122.101"
        val SECONDARY_DNS = "185.51.200.1"

        @Volatile
        var isRunning = false
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val running = AtomicBoolean(false)
    private var vpnThread: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_START -> {
                startVpn()
                START_STICKY
            }
            ACTION_STOP -> {
                stopVpn()
                START_NOT_STICKY
            }
            else -> START_NOT_STICKY
        }
    }

    private fun startVpn() {
        if (running.get()) return

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        try {
            val builder = Builder()
                .setSession("ShecanDNS")
                .addAddress("10.0.0.2", 32)
                .addDnsServer(PRIMARY_DNS)
                .addDnsServer(SECONDARY_DNS)
                .setMtu(1500)
                .setBlocking(true)

            vpnInterface = builder.establish()

            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface")
                stopSelf()
                return
            }

            running.set(true)
            isRunning = true

            vpnThread = Thread {
                runVpnLoop()
            }.also { it.start() }

            Log.d(TAG, "VPN started with DNS: $PRIMARY_DNS, $SECONDARY_DNS")

        } catch (e: Exception) {
            Log.e(TAG, "Error starting VPN: ${e.message}")
            stopVpn()
        }
    }

    private fun runVpnLoop() {
        val vpnFd = vpnInterface ?: return
        val inputStream = FileInputStream(vpnFd.fileDescriptor)
        val outputStream = FileOutputStream(vpnFd.fileDescriptor)
        val buffer = ByteBuffer.allocate(32767)

        Log.d(TAG, "VPN loop started")

        while (running.get()) {
            try {
                buffer.clear()
                val length = inputStream.read(buffer.array())
                if (length <= 0) continue

                buffer.limit(length)

                if (isDnsPacket(buffer, length)) {
                    forwardDnsPacket(buffer, length, outputStream)
                } else {
                    outputStream.write(buffer.array(), 0, length)
                }

            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                if (running.get()) {
                    Log.e(TAG, "VPN loop error: ${e.message}")
                }
            }
        }

        Log.d(TAG, "VPN loop stopped")
    }

    private fun isDnsPacket(buffer: ByteBuffer, length: Int): Boolean {
        if (length < 28) return false
        val protocol = buffer.get(9).toInt() and 0xFF
        if (protocol != 17) return false
        val destPort = ((buffer.get(22).toInt() and 0xFF) shl 8) or (buffer.get(23).toInt() and 0xFF)
        return destPort == 53
    }

    private fun forwardDnsPacket(buffer: ByteBuffer, length: Int, outputStream: FileOutputStream) {
        try {
            val dnsPayloadOffset = 28
            val dnsPayloadLength = length - dnsPayloadOffset
            if (dnsPayloadLength <= 0) return

            val dnsPayload = ByteArray(dnsPayloadLength)
            System.arraycopy(buffer.array(), dnsPayloadOffset, dnsPayload, 0, dnsPayloadLength)

            val sourcePort = extractSourcePort(buffer)

            DatagramSocket().use { dnsSocket ->
                protect(dnsSocket)

                val dnsServer = InetAddress.getByName(PRIMARY_DNS)
                val sendPacket = DatagramPacket(dnsPayload, dnsPayloadLength, dnsServer, 53)
                dnsSocket.send(sendPacket)

                val responseBuffer = ByteArray(512)
                val receivePacket = DatagramPacket(responseBuffer, responseBuffer.size)
                dnsSocket.soTimeout = 3000
                dnsSocket.receive(receivePacket)

                val responseIpPacket = buildIpUdpPacket(
                    srcIp = PRIMARY_DNS,
                    dstIp = "10.0.0.2",
                    srcPort = 53,
                    dstPort = sourcePort,
                    payload = receivePacket.data.copyOf(receivePacket.length)
                )
                outputStream.write(responseIpPacket)
            }

        } catch (e: Exception) {
            Log.w(TAG, "DNS forward error: ${e.message}")
        }
    }

    private fun extractSourcePort(buffer: ByteBuffer): Int {
        return ((buffer.get(20).toInt() and 0xFF) shl 8) or (buffer.get(21).toInt() and 0xFF)
    }

    private fun buildIpUdpPacket(
        srcIp: String, dstIp: String,
        srcPort: Int, dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val udpLength = 8 + payload.size
        val ipLength = 20 + udpLength
        val packet = ByteArray(ipLength)

        packet[0] = 0x45.toByte()
        packet[1] = 0x00
        packet[2] = (ipLength shr 8).toByte()
        packet[3] = (ipLength and 0xFF).toByte()
        packet[4] = 0x00; packet[5] = 0x01
        packet[6] = 0x00; packet[7] = 0x00
        packet[8] = 0x40
        packet[9] = 0x11
        packet[10] = 0x00; packet[11] = 0x00

        val src = InetAddress.getByName(srcIp).address
        val dst = InetAddress.getByName(dstIp).address
        System.arraycopy(src, 0, packet, 12, 4)
        System.arraycopy(dst, 0, packet, 16, 4)

        packet[20] = (srcPort shr 8).toByte()
        packet[21] = (srcPort and 0xFF).toByte()
        packet[22] = (dstPort shr 8).toByte()
        packet[23] = (dstPort and 0xFF).toByte()
        packet[24] = (udpLength shr 8).toByte()
        packet[25] = (udpLength and 0xFF).toByte()
        packet[26] = 0x00; packet[27] = 0x00

        System.arraycopy(payload, 0, packet, 28, payload.size)

        val checksum = computeChecksum(packet, 0, 20)
        packet[10] = (checksum shr 8).toByte()
        packet[11] = (checksum and 0xFF).toByte()

        return packet
    }

    private fun computeChecksum(buf: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        while (i < offset + length - 1) {
            sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            i += 2
        }
        if ((length and 1) != 0) {
            sum += (buf[offset + length - 1].toInt() and 0xFF) shl 8
        }
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }

    private fun stopVpn() {
        running.set(false)
        isRunning = false
        vpnThread?.interrupt()
        vpnInterface?.close()
        vpnInterface = null
        stopForeground(true)
        stopSelf()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "شکن DNS",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "سرویس DNS شکن در حال اجرا است"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        // اضافه کردن دکمه قطع اتصال به نوتیفیکیشن
        val stopIntent = Intent(this, DnsVpnService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("شکن DNS فعال است")
            .setContentText("DNS: $PRIMARY_DNS")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "قطع اتصال", stopPendingIntent)
            .build()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}

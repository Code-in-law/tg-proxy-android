package com.example.tgproxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

class TgVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val wsBridges = mutableMapOf<String, WsBridge>()

    companion object {
        private const val TAG = "TgVpnService"

        const val CHANNEL_ID = "tg_vpn_channel"
        const val NOTIF_ID   = 2

        const val ACTION_START = "com.example.tgproxy.ACTION_START"
        const val ACTION_STOP  = "com.example.tgproxy.ACTION_STOP"

        @Volatile var isRunning        = false
        @Volatile var connectionCount  = 0
        @Volatile var wsCount          = 0
        @Volatile var tcpFallbackCount = 0

        val TG_DC_IPS = TgConstants.DC_IPS

        /**
         * По IP строим WS-домен.
         * ИСПРАВЛЕНИЕ: медиа-серверы (isMedia=true) используют домен с суффиксом "-1",
         * но базовый номер DC берём из основного (не медиа) DC через DC_OVERRIDES.
         */
        fun getDomainForIp(ip: String): String? {
            val dcInfo = TgConstants.IP_TO_DC[ip] ?: run {
                val dcId = TgConstants.DC_IPS.entries
                    .firstOrNull { it.value == ip }?.key
                    ?: return null
                Pair(dcId, false)
            }
            val dc      = kotlin.math.abs(dcInfo.first)
            val isMedia = dcInfo.second
            val d       = TgConstants.DC_OVERRIDES.getOrDefault(dc, dc)
            // Медиа-серверы используют тот же WS-домен что и основной DC
            // kws{d}.web.telegram.org умеет обслуживать и медиа трафик
            return "kws${d}.web.telegram.org"
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }

        // Если уже запущен — не инициализируем заново
        if (isRunning) return START_STICKY

        isRunning        = true
        connectionCount  = 0
        wsCount          = 0
        tcpFallbackCount = 0

        startForeground(NOTIF_ID, buildNotification())
        setupVpn()
        return START_STICKY
    }

    override fun onRevoke() {
        // Вызывается системой при смене аккаунта, отзыве разрешения VPN и т.д.
        // ВАЖНО: не останавливаем сервис принудительно — пробуем переподключиться
        Log.w(TAG, "onRevoke called — trying to re-establish VPN")
        isRunning = false

        // Закрываем старый интерфейс
        vpnInterface?.close()
        vpnInterface = null
        serviceScope.coroutineContext.cancelChildren()
        wsBridges.values.forEach { runCatching { it.close() } }
        wsBridges.clear()

        // Пробуем поднять VPN заново через небольшую паузу
        serviceScope.launch {
            delay(1000)
            withContext(Dispatchers.Main) {
                if (!isRunning) {
                    Log.i(TAG, "Re-establishing VPN after onRevoke")
                    isRunning = true
                    setupVpn()
                }
            }
        }

        super.onRevoke()
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        isRunning = false
        serviceScope.cancel()
        wsBridges.values.forEach { runCatching { it.close() } }
        wsBridges.clear()
        vpnInterface?.close()
        vpnInterface = null
        super.onDestroy()
    }

    private fun stopVpn() {
        Log.i(TAG, "stopVpn")
        isRunning = false
        serviceScope.coroutineContext.cancelChildren()
        wsBridges.values.forEach { runCatching { it.close() } }
        wsBridges.clear()
        vpnInterface?.close()
        vpnInterface = null
        stopForeground(true)
        stopSelf()
    }

    // -------------------------------------------------------------------------
    // VPN setup
    // -------------------------------------------------------------------------

    private fun setupVpn() {
        try {
            val builder = Builder()
                .setSession("TG Proxy")
                .addAddress("10.0.0.1", 32)
                .addDnsServer("8.8.8.8")

            val allTgIps = buildSet {
                addAll(TgConstants.DC_IPS.values)
                addAll(TgConstants.IP_TO_DC.keys)
            }
            for (ip in allTgIps) {
                try {
                    builder.addRoute(ip, 32)
                } catch (e: Exception) {
                    Log.w(TAG, "addRoute failed for $ip: ${e.message}")
                }
            }

            builder.addDisallowedApplication(packageName)

            val pfd = builder.establish()
            if (pfd == null) {
                Log.e(TAG, "establish() returned null")
                // При onRevoke разрешение могло быть сброшено —
                // останавливаемся, пользователь должен дать разрешение снова
                isRunning = false
                stopForeground(true)
                stopSelf()
                return
            }

            vpnInterface = pfd
            startPacketLoop()
            Log.i(TAG, "VPN established, packet loop started")
        } catch (e: Exception) {
            Log.e(TAG, "setupVpn failed: ${e.message}")
            isRunning = false
            stopForeground(true)
            stopSelf()
        }
    }

    // -------------------------------------------------------------------------
    // Packet loop
    // -------------------------------------------------------------------------

    private fun startPacketLoop() {
        val pfd    = vpnInterface ?: return
        val input  = FileInputStream(pfd.fileDescriptor)
        val output = FileOutputStream(pfd.fileDescriptor)

        serviceScope.launch {
            val buffer = ByteBuffer.allocate(32767)
            while (isActive && isRunning) {
                buffer.clear()
                val length = try {
                    input.read(buffer.array())
                } catch (e: Exception) {
                    if (isRunning) Log.e(TAG, "TUN read error: ${e.message}")
                    break
                }
                if (length <= 0) continue

                buffer.limit(length)
                handlePacket(buffer, output)
            }
            Log.i(TAG, "Packet loop exited")
        }
    }

    private suspend fun handlePacket(packet: ByteBuffer, output: FileOutputStream) {
        val ipVersion = (packet.get(0).toInt() shr 4) and 0xF
        if (ipVersion != 4) return

        val protocol = packet.get(9).toInt() and 0xFF
        if (protocol != 6) return  // только TCP

        val ipHeaderLen = (packet.get(0).toInt() and 0xF) * 4

        val dstIp = buildString {
            for (i in 16..19) {
                if (i > 16) append(".")
                append(packet.get(i).toInt() and 0xFF)
            }
        }

        val srcPort = ((packet.get(ipHeaderLen    ).toInt() and 0xFF) shl 8) or
                (packet.get(ipHeaderLen + 1).toInt() and 0xFF)
        val dstPort = ((packet.get(ipHeaderLen + 2).toInt() and 0xFF) shl 8) or
                (packet.get(ipHeaderLen + 3).toInt() and 0xFF)

        val connKey = "$dstIp:$srcPort:$dstPort"

        val bridge = wsBridges.getOrPut(connKey) {
            connectionCount++
            wsCount++

            val domain = getDomainForIp(dstIp) ?: run {
                Log.w(TAG, "Unknown TG IP: $dstIp — skipping")
                tcpFallbackCount++
                wsCount--
                return
            }

            Log.d(TAG, "New conn $connKey → $domain")

            WsBridge(
                targetIp   = dstIp,
                domain     = domain,
                vpnService = this@TgVpnService,
                onData     = { responseData ->
                    writeResponseToTun(responseData, output)
                }
            ).also { wb ->
                serviceScope.launch {
                    try {
                        wb.connect()
                        Log.d(TAG, "WS connected: $connKey")
                    } catch (e: Exception) {
                        Log.e(TAG, "WS connect failed for $connKey: ${e.message}")
                        wsBridges.remove(connKey)
                        connectionCount--
                        wsCount--
                    }
                }
            }
        }

        val tcpHeaderLen  = ((packet.get(ipHeaderLen + 12).toInt() shr 4) and 0xF) * 4
        val payloadOffset = ipHeaderLen + tcpHeaderLen
        val payloadLen    = packet.limit() - payloadOffset

        if (payloadLen > 0) {
            val payload = ByteArray(payloadLen)
            packet.position(payloadOffset)
            packet.get(payload)
            try {
                bridge.send(payload)
            } catch (e: Exception) {
                Log.w(TAG, "send failed for $connKey: ${e.message}")
                wsBridges.remove(connKey)
            }
        }
    }

    private fun writeResponseToTun(data: ByteArray, output: FileOutputStream) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                output.write(data)
                output.flush()
            } catch (e: Exception) {
                Log.e(TAG, "TUN write error: ${e.message}")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "TG Proxy VPN",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, TgVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("TG Proxy активен")
            .setContentText("Трафик Telegram туннелируется")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Стоп", stopPi)
            .setOngoing(true)
            .build()
    }
}
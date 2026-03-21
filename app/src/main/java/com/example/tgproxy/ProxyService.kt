package com.example.tgproxy

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.net.ServerSocket
import kotlin.coroutines.coroutineContext

class ProxyService : Service() {

    companion object {
        const val TAG = "TGProxy"
        const val CHANNEL_ID = "tg_proxy_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.example.tgproxy.STOP"

        @Volatile
        var isRunning = false
            private set

        @Volatile
        var connectionCount = 0

        @Volatile
        var wsCount = 0

        @Volatile
        var tcpFallbackCount = 0
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var proxyJob: Job? = null
    private var serverSocket: ServerSocket? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val port = intent?.getIntExtra("port", 1080) ?: 1080
        val notification = buildNotification("Запуск на порту $port...")

        // ИЗМЕНЕНИЕ: Используем TYPE_DATA_SYNC вместо SPECIAL_USE
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }

        isRunning = true
        connectionCount = 0
        wsCount = 0
        tcpFallbackCount = 0

        // Отменяем старую задачу если была
        proxyJob?.cancel()
        proxyJob = scope.launch {
            runProxy(port)
        }

        return START_STICKY
    }

    private suspend fun runProxy(port: Int) {
        try {
            serverSocket = ServerSocket(port).apply {
                reuseAddress = true
            }
            Log.i(TAG, "SOCKS5 proxy listening on 127.0.0.1:$port")
            updateNotification("Работает на порту $port | 0 соединений")

            while (coroutineContext.isActive && serverSocket?.isClosed == false) {
                try {
                    val client = serverSocket!!.accept()
                    connectionCount++

                    scope.launch {
                        try {
                            Socks5Engine.handleClient(client)
                        } catch (e: Exception) {
                            Log.e(TAG, "Client error: ${e.message}")
                        }
                        updateNotification(
                            "Порт $port | Соед: $connectionCount | WS: $wsCount | TCP: $tcpFallbackCount"
                        )
                    }
                } catch (_: java.net.SocketException) {
                    break // socket closed during accept
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Proxy server error: ${e.message}", e)
            // Если порт занят, останавливаем сервис
            stopSelf()
        }
    }

    private fun updateNotification(text: String) {
        if (!isRunning) return
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification(text))
        } catch (_: Exception) {
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, ProxyService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPi = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(this, MainActivity::class.java)
        val openPi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TG Proxy")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Стоп", stopPi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TG Proxy Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Telegram WebSocket Bridge Proxy"
            }
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        isRunning = false
        proxyJob?.cancel()
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
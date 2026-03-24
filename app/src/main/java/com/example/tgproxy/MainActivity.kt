package com.example.tgproxy

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var btnToggle: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvStats: TextView
    private lateinit var tvInstructions: TextView

    private val handler = Handler(Looper.getMainLooper())

    private val statsUpdater = object : Runnable {
        override fun run() {
            updateUI()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnToggle      = findViewById(R.id.btnToggle)
        tvStatus       = findViewById(R.id.tvStatus)
        tvStats        = findViewById(R.id.tvStats)
        tvInstructions = findViewById(R.id.tvInstructions)

        // Разрешение на уведомления (Android 13+)
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATION_PERMISSION
                )
            }
        }

        btnToggle.setOnClickListener { toggleProxy() }

        tvInstructions.text = buildString {
            appendLine("📋 Как пользоваться:")
            appendLine("")
            appendLine("1. Нажмите «▶ Запустить прокси»")
            appendLine("2. Откройте Telegram → Настройки →")
            appendLine("   Данные и память → Прокси")
            appendLine("3. Добавить прокси → SOCKS5:")
            appendLine("   Сервер:  127.0.0.1")
            appendLine("   Порт:    1080")
            appendLine("   (логин и пароль — пустые)")
            appendLine("4. Нажмите Подключить ✓")
        }

        findViewById<View>(R.id.creditApp).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Code-in-law")))
        }
        findViewById<View>(R.id.creditProxy).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Flowseal")))
        }

        handler.post(statsUpdater)
    }

    override fun onDestroy() {
        handler.removeCallbacks(statsUpdater)
        super.onDestroy()
    }

    private fun toggleProxy() {
        if (ProxyService.isRunning) {
            stopProxyService()
        } else {
            startProxyService()
        }
        handler.postDelayed({ updateUI() }, 500)
    }

    private fun startProxyService() {
        val intent = Intent(this, ProxyService::class.java)
        intent.putExtra("port", 1080)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopProxyService() {
        val intent = Intent(this, ProxyService::class.java)
        intent.action = ProxyService.ACTION_STOP
        startService(intent)
    }

    private fun updateUI() {
        if (ProxyService.isRunning) {
            btnToggle.text = "⏹  Остановить прокси"
            btnToggle.backgroundTintList =
                android.content.res.ColorStateList.valueOf(0xFFDC2626.toInt())
            tvStatus.text = "🟢 SOCKS5 прокси активен · порт 1080"
            tvStatus.setTextColor(0xFF4ADE80.toInt())
            tvStats.visibility = View.VISIBLE
            tvStats.text = buildString {
                appendLine("📊 Статистика:")
                appendLine("   Соединений:   ${ProxyService.connectionCount}")
                appendLine("   WebSocket:    ${ProxyService.wsCount}")
                appendLine("   TCP fallback: ${ProxyService.tcpFallbackCount}")
            }
        } else {
            btnToggle.text = "▶  Запустить прокси"
            btnToggle.backgroundTintList =
                android.content.res.ColorStateList.valueOf(0xFF4F46E5.toInt())
            tvStatus.text = "🔴 Прокси остановлен"
            tvStatus.setTextColor(0xFFEF4444.toInt())
            tvStats.visibility = View.GONE
        }
    }

    companion object {
        private const val REQUEST_NOTIFICATION_PERMISSION = 1
    }
}
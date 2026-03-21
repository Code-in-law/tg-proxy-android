package com.example.tgproxy

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
    private val PORT = 1080

    private val statsUpdater = object : Runnable {
        override fun run() {
            updateUI()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnToggle = findViewById(R.id.btnToggle)
        tvStatus = findViewById(R.id.tvStatus)
        tvStats = findViewById(R.id.tvStats)
        tvInstructions = findViewById(R.id.tvInstructions)

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    1
                )
            }
        }

        btnToggle.setOnClickListener { toggleProxy() }

        tvInstructions.text = buildString {
            appendLine("📋 Настройка Telegram:")
            appendLine("")
            appendLine("1. Откройте Telegram → Настройки")
            appendLine("2. Данные и память → Прокси")
            appendLine("3. Добавить прокси → SOCKS5")
            appendLine("4. Сервер: 127.0.0.1")
            appendLine("5. Порт: $PORT")
            appendLine("6. Без логина и пароля")
            appendLine("")
            appendLine("💡 Прокси работает только для Telegram.")
            appendLine("   Весь остальной трафик идёт напрямую.")
        }

        handler.post(statsUpdater)

        findViewById<View>(R.id.creditApp).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW,
                android.net.Uri.parse("https://github.com/Code-in-law")))
        }
        findViewById<View>(R.id.creditProxy).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW,
                android.net.Uri.parse("https://github.com/Flowseal")))
        }
    }

    private fun toggleProxy() {
        if (ProxyService.isRunning) {
            val intent = Intent(this, ProxyService::class.java).apply {
                action = ProxyService.ACTION_STOP
            }
            startService(intent)
        } else {
            val intent = Intent(this, ProxyService::class.java).apply {
                putExtra("port", PORT)
            }
            ContextCompat.startForegroundService(this, intent)
        }
        handler.postDelayed({ updateUI() }, 500)
    }

    private fun updateUI() {
        if (ProxyService.isRunning) {
            btnToggle.text = "⏹  Остановить прокси"
            btnToggle.backgroundTintList =
                android.content.res.ColorStateList.valueOf(0xFFDC2626.toInt())
            tvStatus.text = "🟢 Прокси работает на 127.0.0.1:$PORT"
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

    override fun onDestroy() {
        handler.removeCallbacks(statsUpdater)
        super.onDestroy()
    }
}
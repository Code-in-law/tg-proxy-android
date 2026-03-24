package com.example.tgproxy

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    // -------------------------------------------------------------------------
    // VPN permission launcher
    // Запускается системным диалогом "Разрешить VPN?"
    // -------------------------------------------------------------------------
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnService()
        } else {
            Toast.makeText(
                this,
                "Разрешение VPN необходимо для работы прокси",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // -------------------------------------------------------------------------
    // Views
    // -------------------------------------------------------------------------
    private lateinit var btnToggle: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvStats: TextView
    private lateinit var tvInstructions: TextView

    private val handler = Handler(Looper.getMainLooper())

    // -------------------------------------------------------------------------
    // Stats updater
    // -------------------------------------------------------------------------
    private val statsUpdater = object : Runnable {
        override fun run() {
            updateUI()
            handler.postDelayed(this, 1000)
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnToggle = findViewById(R.id.btnToggle)
        tvStatus  = findViewById(R.id.tvStatus)
        tvStats   = findViewById(R.id.tvStats)
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

        // Инструкции теперь отражают VPN-режим: настраивать прокси в Telegram НЕ НУЖНО
        tvInstructions.text = buildString {
            appendLine("📋 Как пользоваться:")
            appendLine("")
            appendLine("1. Нажмите «▶ Запустить прокси»")
            appendLine("2. Разрешите VPN в системном диалоге")
            appendLine("3. Запустите Telegram — он заработает")
            appendLine("   автоматически, без настроек прокси.")
            appendLine("")
            appendLine("ℹ️  Приложение перехватывает только трафик")
            appendLine("   к серверам Telegram. Остальной интернет")
            appendLine("   работает напрямую, как обычно.")
        }

        // Ссылки на авторов
        findViewById<View>(R.id.creditApp).setOnClickListener {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Code-in-law"))
            )
        }
        findViewById<View>(R.id.creditProxy).setOnClickListener {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Flowseal"))
            )
        }

        handler.post(statsUpdater)
    }

    override fun onDestroy() {
        handler.removeCallbacks(statsUpdater)
        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // Управление прокси
    // -------------------------------------------------------------------------

    private fun toggleProxy() {
        if (TgVpnService.isRunning) {
            stopVpnService()
        } else {
            requestVpnPermissionAndStart()
        }
        // небольшая задержка, чтобы сервис успел стартовать/остановиться
        handler.postDelayed({ updateUI() }, 500)
    }

    /**
     * Запрашивает разрешение VPN (если ещё не выдано),
     * после чего стартует TgVpnService.
     */
    private fun requestVpnPermissionAndStart() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            // Нужно показать системный диалог
            vpnPermissionLauncher.launch(intent)
        } else {
            // Разрешение уже есть — сразу стартуем
            startVpnService()
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, TgVpnService::class.java).apply {
            action = TgVpnService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopVpnService() {
        val intent = Intent(this, TgVpnService::class.java).apply {
            action = TgVpnService.ACTION_STOP
        }
        startService(intent)
    }

    // -------------------------------------------------------------------------
    // UI
    // -------------------------------------------------------------------------

    private fun updateUI() {
        if (TgVpnService.isRunning) {
            btnToggle.text = "⏹  Остановить прокси"
            btnToggle.backgroundTintList =
                android.content.res.ColorStateList.valueOf(0xFFDC2626.toInt())
            tvStatus.text = "🟢 VPN-прокси активен"
            tvStatus.setTextColor(0xFF4ADE80.toInt())
            tvStats.visibility = View.VISIBLE
            tvStats.text = buildString {
                appendLine("📊 Статистика:")
                appendLine("   Соединений:   ${TgVpnService.connectionCount}")
                appendLine("   WebSocket:    ${TgVpnService.wsCount}")
                appendLine("   TCP fallback: ${TgVpnService.tcpFallbackCount}")
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
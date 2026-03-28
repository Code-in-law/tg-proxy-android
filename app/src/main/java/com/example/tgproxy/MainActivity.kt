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
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.util.Log

class MainActivity : AppCompatActivity() {

    private lateinit var btnToggle: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvStats: TextView
    private lateinit var tvInstructions: TextView

    // ПУНКТ 4: кнопка авто-добавления прокси в Telegram
    private lateinit var btnAddToTelegram: Button

    // ПУНКТ 3: кнопка добавления пользовательских DC
    private lateinit var btnAddDc: Button
    private lateinit var tvCustomDcs: TextView

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


        // ПУНКТ 3: элементы управления пользовательскими DC
        btnAddDc    = findViewById(R.id.btnAddDc)
        tvCustomDcs = findViewById(R.id.tvCustomDcs)

        // ПУНКТ 3: загрузка пользовательских DC из SharedPreferences при старте
        TgConstants.loadCustomDcs(this)

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


        // =====================================================================
        // ПУНКТ 3 — Кнопка добавления пользовательского DC:
        //
        // Открывает AlertDialog с полями ввода:
        //   - IP-адрес (IPv4 или IPv6)
        //   - Номер DC (1-5, 203)
        //   - Чекбокс "Медиа-сервер"
        //   - Чекбокс "Основной IP для DC"
        // =====================================================================
        btnAddDc.setOnClickListener {
            showAddDcDialog()
        }

        tvInstructions.text = buildString {
            appendLine("📋 Как пользоваться:")
            appendLine("")
            appendLine("1. Нажмите «▶ Запустить прокси»")
            appendLine("2. Telegram → Настройки →")
            appendLine("   Данные и память → Прокси")
            appendLine("   Сервер: 127.0.0.1 · Порт: 1080")
            appendLine("3. Нажмите Подключить ✓")
        }

        findViewById<View>(R.id.creditApp).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Code-in-law")))
        }
        findViewById<View>(R.id.creditProxy).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Flowseal")))
        }

        // Проверка обновлений
        VersionChecker.checkForUpdates(this) { updateFound ->
            if (updateFound) {
                Log.d("MainActivity", "Найдено обновление")
            }
        }

        handler.post(statsUpdater)
        updateCustomDcsList()
    }



    override fun onDestroy() {
        handler.removeCallbacks(statsUpdater)
        super.onDestroy()
    }



    // =========================================================================
    // ПУНКТ 3 — Диалог добавления пользовательского DC
    // =========================================================================

    private fun showAddDcDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

        val etIp = EditText(this).apply {
            hint = "IP-адрес (напр. 149.154.175.55)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        layout.addView(etIp)

        val etDc = EditText(this).apply {
            hint = "Номер DC (1-999)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        layout.addView(etDc)

        val cbMedia = CheckBox(this).apply {
            text = "Медиа-сервер (для загрузки файлов)"
        }
        layout.addView(cbMedia)

        val cbPrimary = CheckBox(this).apply {
            text = "Основной IP для этого DC"
        }
        layout.addView(cbPrimary)

        AlertDialog.Builder(this)
            .setTitle("Добавить адрес DC")
            .setView(layout)
            .setPositiveButton("Добавить") { _, _ ->
                val ip = etIp.text.toString().trim()
                val dcStr = etDc.text.toString().trim()
                val isMedia = cbMedia.isChecked
                val isPrimary = cbPrimary.isChecked

                // Валидация IP
                if (ip.isEmpty()) {
                    Toast.makeText(this, "Введите IP-адрес", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                // Валидация номера DC
                val dcId = dcStr.toIntOrNull()
                if (dcId == null || dcId !in 1..999) {
                    Toast.makeText(this, "DC должен быть от 1 до 999", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                // Проверка валидности IP
                try {
                    java.net.InetAddress.getByName(ip)
                } catch (e: Exception) {
                    Toast.makeText(this, "Некорректный IP-адрес", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                // Проверяем, не занят ли IP другим DC
                val existingDcForIp = TgConstants.IP_TO_DC[ip]?.first
                if (existingDcForIp != null && existingDcForIp != dcId) {
                    // IP принадлежит другому DC - спрашиваем подтверждение
                    AlertDialog.Builder(this)
                        .setTitle("Переместить IP")
                        .setMessage("IP $ip уже принадлежит DC$existingDcForIp\n\nПереместить на DC$dcId?")
                        .setPositiveButton("Переместить") { _, _ ->
                            val result = TgConstants.addCustomDc(this, ip, dcId, isMedia, isPrimary)
                            handleAddResult(result, ip, dcId, existingDcForIp)
                        }
                        .setNegativeButton("Отмена", null)
                        .show()
                    return@setPositiveButton
                }

                // Проверяем, не пытаемся ли мы добавить тот же IP к тому же DC
                val existingEntry = TgConstants.IP_TO_DC[ip]
                if (existingEntry != null && existingEntry.first == dcId) {
                    AlertDialog.Builder(this)
                        .setTitle("Обновить настройки")
                        .setMessage("IP $ip уже принадлежит DC$dcId\n\nОбновить параметры (media/primary)?")
                        .setPositiveButton("Обновить") { _, _ ->
                            val result = TgConstants.addCustomDc(this, ip, dcId, isMedia, isPrimary)
                            handleAddResult(result, ip, dcId, null)
                        }
                        .setNegativeButton("Отмена", null)
                        .show()
                    return@setPositiveButton
                }

                // Новый IP для DC
                val result = TgConstants.addCustomDc(this, ip, dcId, isMedia, isPrimary)
                handleAddResult(result, ip, dcId, null)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun handleAddResult(result: TgConstants.AddDcResult, ip: String, dcId: Int, oldDcId: Int?) {
        when (result) {
            TgConstants.AddDcResult.SUCCESS_ADDED -> {
                Toast.makeText(this, "✅ Добавлен DC$dcId: $ip", Toast.LENGTH_SHORT).show()
                updateCustomDcsList()
            }
            TgConstants.AddDcResult.SUCCESS_UPDATED -> {
                Toast.makeText(this, "🔄 IP $ip перемещен с DC$oldDcId на DC$dcId", Toast.LENGTH_SHORT).show()
                updateCustomDcsList()
            }
            TgConstants.AddDcResult.IP_ALREADY_EXISTS -> {
                Toast.makeText(this, "📝 Обновлены параметры для $ip (DC$dcId)", Toast.LENGTH_SHORT).show()
                updateCustomDcsList()
            }

            else -> {
                Toast.makeText(this, "Error", Toast.LENGTH_SHORT).show()
                updateCustomDcsList()
            }
            }
    }

    /**
     * ПУНКТ 3: обновляет текстовое поле со списком пользовательских DC.
     * Каждый элемент показывает IP, номер DC, тип (media/main) и кнопку удаления.
     */
    private fun updateCustomDcsList() {
        val entries = TgConstants.getCustomDcs(this)
        if (entries.isEmpty()) {
            tvCustomDcs.visibility = View.GONE
            return
        }
        tvCustomDcs.visibility = View.VISIBLE
        tvCustomDcs.text = buildString {
            appendLine("Пользовательские DC:\n")

            // Группируем по DC для лучшего отображения
            val grouped = entries.groupBy { it.dcId }
            for ((dcId, ips) in grouped) {
                appendLine("  DC$dcId:")
                for (entry in ips) {
                    val flags = buildString {
                        if (entry.isMedia) append(" media")
                        if (entry.isPrimary) append(" ⭐")
                        if (isEmpty()) append("")
                    }
                    appendLine("    • ${entry.ip}$flags")
                }
            }
            appendLine("")
            appendLine("(Долгое нажатие для удаления)")
        }

        tvCustomDcs.setOnLongClickListener {
            showRemoveDcDialog(entries)
            true
        }
    }

    private fun showRemoveDcDialog(entries: List<TgConstants.CustomDcEntry>) {
        val items = entries.map { "DC${it.dcId}: ${it.ip}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Удалить адрес DC")
            .setItems(items) { _, which ->
                val entry = entries[which]
                TgConstants.removeCustomDc(this, entry.ip)
                Toast.makeText(this, "${entry.ip} удалён", Toast.LENGTH_SHORT).show()
                updateCustomDcsList()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // =========================================================================
    // Основная логика (без изменений, кроме мелких)
    // =========================================================================

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
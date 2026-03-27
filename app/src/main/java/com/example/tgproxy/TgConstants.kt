package com.example.tgproxy

import android.content.Context
import android.util.Log
import java.math.BigInteger
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.ByteBuffer

object TgConstants {

    private const val TAG = "TgConstants"
    private const val PREFS_NAME = "tg_proxy_custom_dc"

    // =========================================================================
    // ПУНКТ 3 — Ручное добавление адресов DC:
    //
    // Зачем это нужно:
    //   Telegram использует фиксированные IP для своих дата-центров, но:
    //   1) У некоторых провайдеров DNS возвращает подменённые адреса (спуфинг)
    //   2) Некоторые подсети маршрутизируются быстрее у конкретного провайдера
    //   3) Telegram может добавить новые IP, а приложение ещё не обновлено
    //
    // Как реализовано:
    //   - DC_IPS и IP_TO_DC стали mutableMapOf (изменяемые)
    //   - Методы addCustomDc() / removeCustomDc() / loadCustomDcs()
    //   - Данные сохраняются в SharedPreferences (переживают перезапуск)
    //   - При добавлении нового DC обновляются ОБЕ карты одновременно
    // =========================================================================

    /** DC target IPs for WebSocket relay — теперь mutable для пользовательских DC */
    val DC_IPS: MutableMap<Int, String> = mutableMapOf(
        1 to "149.154.175.50",
        2 to "149.154.167.220",
        3 to "149.154.175.100",
        4 to "149.154.167.91",
        5 to "91.108.56.100",
        203 to "91.105.192.100"
    )

    /** DC overrides (e.g., DC203 uses DC2 WS relay) */
    val DC_OVERRIDES: Map<Int, Int> = mapOf(
        203 to 2
    )

    /** IP -> (dc_id, is_media) — теперь mutable */
    val IP_TO_DC: MutableMap<String, Pair<Int, Boolean>> = mutableMapOf(
        // DC1
        "149.154.175.50" to Pair(1, false),
        "149.154.175.51" to Pair(1, false),
        "149.154.175.53" to Pair(1, false),
        "149.154.175.54" to Pair(1, false),
        "149.154.175.52" to Pair(1, true),
        // DC2
        "149.154.167.41" to Pair(2, false),
        "149.154.167.50" to Pair(2, false),
        "149.154.167.51" to Pair(2, false),
        "149.154.167.220" to Pair(2, false),
        "95.161.76.100" to Pair(2, false),
        "149.154.167.151" to Pair(2, true),
        "149.154.167.222" to Pair(2, true),
        "149.154.167.223" to Pair(2, true),
        "149.154.162.123" to Pair(2, true),
        // DC3
        "149.154.175.100" to Pair(3, false),
        "149.154.175.101" to Pair(3, false),
        "149.154.175.102" to Pair(3, true),
        // DC4
        "149.154.167.91" to Pair(4, false),
        "149.154.167.92" to Pair(4, false),
        "149.154.164.250" to Pair(4, true),
        "149.154.166.120" to Pair(4, true),
        "149.154.166.121" to Pair(4, true),
        "149.154.167.118" to Pair(4, true),
        "149.154.165.111" to Pair(4, true),
        // DC5
        "91.108.56.100" to Pair(5, false),
        "91.108.56.101" to Pair(5, false),
        "91.108.56.116" to Pair(5, false),
        "91.108.56.126" to Pair(5, false),
        "149.154.171.5" to Pair(5, false),
        "91.108.56.102" to Pair(5, true),
        "91.108.56.128" to Pair(5, true),
        "91.108.56.151" to Pair(5, true),
        // DC203
        "91.105.192.100" to Pair(203, false)
    )

    // =========================================================================
    // ПУНКТ 2 — IPv6 диапазоны Telegram:
    //
    // Telegram владеет двумя IPv6-префиксами:
    //   2001:67c:4e8::/48    — основной диапазон
    //   2001:b28:f23d::/48   — дополнительный
    //
    // Для IPv6 мы используем BigInteger вместо Long, потому что
    // IPv6-адрес = 128 бит, а Long = 64 бит (не хватит).
    // =========================================================================

    /** Telegram IPv4 ranges */
    private val TG_RANGES_V4: List<Pair<Long, Long>> = listOf(
        ipv4ToLong("185.76.151.0") to ipv4ToLong("185.76.151.255"),
        ipv4ToLong("149.154.160.0") to ipv4ToLong("149.154.175.255"),
        ipv4ToLong("91.105.192.0") to ipv4ToLong("91.105.193.255"),
        ipv4ToLong("91.108.0.0") to ipv4ToLong("91.108.255.255")
    )

    /** ПУНКТ 2: Telegram IPv6 ranges */
    private val TG_RANGES_V6: List<Pair<BigInteger, BigInteger>> = listOf(
        ipv6ToBigInt("2001:67c:4e8::") to ipv6ToBigInt("2001:67c:4e8:ffff:ffff:ffff:ffff:ffff"),
        ipv6ToBigInt("2001:b28:f23d::") to ipv6ToBigInt("2001:b28:f23d:ffff:ffff:ffff:ffff:ffff")
    )

    /**
     * Проверяет, принадлежит ли IP-адрес Telegram.
     * Теперь работает и с IPv4, и с IPv6.
     */
    fun isTelegramIp(ip: String): Boolean {
        return try {
            val addr = InetAddress.getByName(ip)
            when (addr) {
                is Inet6Address -> {
                    // ПУНКТ 2: проверка IPv6
                    val n = BigInteger(1, addr.address)
                    TG_RANGES_V6.any { (lo, hi) -> n in lo..hi }
                }
                is Inet4Address -> {
                    val n = ipv4ToLong(ip)
                    TG_RANGES_V4.any { (lo, hi) -> n in lo..hi }
                }
                else -> false
            }
        } catch (_: Exception) {
            false
        }
    }

    // =========================================================================
    // ПУНКТ 3 — Методы для ручного управления DC адресами
    // =========================================================================

    /**
     * Добавляет пользовательский DC-адрес.
     *
     * @param context   — нужен для SharedPreferences (сохранение на диск)
     * @param ip        — IP-адрес (IPv4 или IPv6)
     * @param dcId      — номер дата-центра (1-5, 203)
     * @param isMedia   — это медиа-сервер? (для скачивания файлов)
     * @param isPrimary — сделать этот IP основным для DC (обновит DC_IPS)
     *
     * Пример: addCustomDc(ctx, "149.154.175.55", 1, false, false)
     * Это добавит IP в IP_TO_DC как не-медиа адрес DC1.
     */
    fun addCustomDc(
        context: Context,
        ip: String,
        dcId: Int,
        isMedia: Boolean,
        isPrimary: Boolean
    ) {
        IP_TO_DC[ip] = Pair(dcId, isMedia)
        if (isPrimary) {
            DC_IPS[dcId] = ip
        }
        saveCustomDcs(context)
        Log.i(TAG, "Added custom DC: $ip → DC$dcId (media=$isMedia, primary=$isPrimary)")
    }

    /**
     * Удаляет пользовательский DC-адрес.
     * Встроенные (hardcoded) адреса НЕ удаляются — только пользовательские.
     */
    fun removeCustomDc(context: Context, ip: String) {
        IP_TO_DC.remove(ip)
        saveCustomDcs(context)
        Log.i(TAG, "Removed custom DC: $ip")
    }

    /** Возвращает список пользовательских DC (для отображения в UI) */
    fun getCustomDcs(context: Context): List<CustomDcEntry> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val entries = mutableListOf<CustomDcEntry>()
        val count = prefs.getInt("count", 0)
        for (i in 0 until count) {
            val ip = prefs.getString("ip_$i", null) ?: continue
            val dc = prefs.getInt("dc_$i", 0)
            val media = prefs.getBoolean("media_$i", false)
            val primary = prefs.getBoolean("primary_$i", false)
            entries.add(CustomDcEntry(ip, dc, media, primary))
        }
        return entries
    }

    /**
     * Загружает пользовательские DC из SharedPreferences при старте приложения.
     * Вызывается из MainActivity.onCreate() или Application.onCreate().
     */
    fun loadCustomDcs(context: Context) {
        val entries = getCustomDcs(context)
        for (entry in entries) {
            IP_TO_DC[entry.ip] = Pair(entry.dcId, entry.isMedia)
            if (entry.isPrimary) {
                DC_IPS[entry.dcId] = entry.ip
            }
        }
        if (entries.isNotEmpty()) {
            Log.i(TAG, "Loaded ${entries.size} custom DC entries")
        }
    }

    private fun saveCustomDcs(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.clear()

        // Собираем только пользовательские (не встроенные) адреса
        val builtinIps = setOf(
            "149.154.175.50", "149.154.175.51", "149.154.175.53", "149.154.175.54",
            "149.154.175.52", "149.154.167.41", "149.154.167.50", "149.154.167.51",
            "149.154.167.220", "95.161.76.100", "149.154.167.151", "149.154.167.222",
            "149.154.167.223", "149.154.162.123", "149.154.175.100", "149.154.175.101",
            "149.154.175.102", "149.154.167.91", "149.154.167.92", "149.154.164.250",
            "149.154.166.120", "149.154.166.121", "149.154.167.118", "149.154.165.111",
            "91.108.56.100", "91.108.56.101", "91.108.56.116", "91.108.56.126",
            "149.154.171.5", "91.108.56.102", "91.108.56.128", "91.108.56.151",
            "91.105.192.100"
        )

        val customEntries = IP_TO_DC.filter { it.key !in builtinIps }
        editor.putInt("count", customEntries.size)
        var i = 0
        for ((ip, dcInfo) in customEntries) {
            editor.putString("ip_$i", ip)
            editor.putInt("dc_$i", dcInfo.first)
            editor.putBoolean("media_$i", dcInfo.second)
            editor.putBoolean("primary_$i", DC_IPS[dcInfo.first] == ip)
            i++
        }
        editor.apply()
    }

    // =========================================================================
    // Вспомогательные функции
    // =========================================================================

    private fun ipv4ToLong(ip: String): Long {
        val bytes = InetAddress.getByName(ip).address
        return ByteBuffer.wrap(bytes).int.toLong() and 0xFFFFFFFFL
    }

    /** ПУНКТ 2: конвертация IPv6 в BigInteger для сравнения диапазонов */
    private fun ipv6ToBigInt(ip: String): BigInteger {
        val bytes = InetAddress.getByName(ip).address
        return BigInteger(1, bytes)
    }

    /** Модель для хранения пользовательского DC */
    data class CustomDcEntry(
        val ip: String,
        val dcId: Int,
        val isMedia: Boolean,
        val isPrimary: Boolean
    )
}
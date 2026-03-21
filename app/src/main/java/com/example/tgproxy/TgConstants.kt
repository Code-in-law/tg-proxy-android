package com.example.tgproxy

import java.net.InetAddress
import java.nio.ByteBuffer

object TgConstants {

    /** DC target IPs for WebSocket relay */
    val DC_IPS: Map<Int, String> = mapOf(
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

    /** IP -> (dc_id, is_media) */
    val IP_TO_DC: Map<String, Pair<Int, Boolean>> = mapOf(
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

    /** Telegram IP ranges */
    private val TG_RANGES: List<Pair<Long, Long>> = listOf(
        ipToLong("185.76.151.0") to ipToLong("185.76.151.255"),
        ipToLong("149.154.160.0") to ipToLong("149.154.175.255"),
        ipToLong("91.105.192.0") to ipToLong("91.105.193.255"),
        ipToLong("91.108.0.0") to ipToLong("91.108.255.255")
    )

    fun isTelegramIp(ip: String): Boolean {
        return try {
            val n = ipToLong(ip)
            TG_RANGES.any { (lo, hi) -> n in lo..hi }
        } catch (_: Exception) {
            false
        }
    }

    private fun ipToLong(ip: String): Long {
        val bytes = InetAddress.getByName(ip).address
        return ByteBuffer.wrap(bytes).int.toLong() and 0xFFFFFFFFL
    }
}
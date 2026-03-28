package com.example.tgproxy

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object Socks5Engine {

    private const val TAG = "Socks5"

    fun handleClient(client: Socket) {
        client.soTimeout = 15_000
        client.tcpNoDelay = true

        val inp = client.getInputStream()
        val out = client.getOutputStream()

        try {
            // --- SOCKS5 greeting ---
            val ver = inp.read()
            if (ver != 5) {
                client.close()
                return
            }
            val nMethods = inp.read()
            inp.readNBytes2(nMethods)
            out.write(byteArrayOf(0x05, 0x00))
            out.flush()

            // --- CONNECT request ---
            val req = inp.readNBytes2(4)
            val cmd = req[1].toInt() and 0xFF
            val atyp = req[3].toInt() and 0xFF

            if (cmd != 1) {
                out.write(socks5Reply(0x07))
                out.flush()
                client.close()
                return
            }

            val dst: String
            when (atyp) {
                1 -> {
                    val raw = inp.readNBytes2(4)
                    dst = InetAddress.getByAddress(raw).hostAddress ?: "0.0.0.0"
                }
                3 -> {
                    val dlen = inp.read()
                    val raw = inp.readNBytes2(dlen)
                    dst = String(raw)
                }
                4 -> {
                    // IPv6: читаем 16 байт адреса и обрабатываем как обычно
                    val raw = inp.readNBytes2(16)
                    dst = InetAddress.getByAddress(raw).hostAddress ?: "::0"
                }
                else -> {
                    out.write(socks5Reply(0x08))
                    out.flush()
                    client.close()
                    return
                }
            }

            val portBytes = inp.readNBytes2(2)
            val port = ((portBytes[0].toInt() and 0xFF) shl 8) or
                    (portBytes[1].toInt() and 0xFF)

            // --- Non-Telegram → passthrough ---
            if (!TgConstants.isTelegramIp(dst)) {
                try {
                    val remote = Socket()
                    remote.connect(InetSocketAddress(dst, port), 10_000)
                    remote.tcpNoDelay = true
                    out.write(socks5Reply(0x00))
                    out.flush()
                    bridgeTcp(client, remote)
                } catch (e: Exception) {
                    Log.w(TAG, "Passthrough to $dst:$port failed: ${e.message}")
                    out.write(socks5Reply(0x05))
                    out.flush()
                    client.close()
                }
                return
            }

            // --- Telegram IP: accept, read 64-byte init ---
            out.write(socks5Reply(0x00))
            out.flush()

            client.soTimeout = 15_000
            val init = inp.readNBytes2(64)
            if (init.size < 64) {
                client.close()
                return
            }

            if (isHttpTransport(init)) {
                Log.d(TAG, "HTTP transport to $dst:$port rejected")
                client.close()
                return
            }

            var dc = dcFromInit(init)
            var isMedia = false
            var initData = init

            if (dc == null) {
                val dcInfo = TgConstants.IP_TO_DC[dst]
                if (dcInfo != null) {
                    dc = dcInfo.first
                    isMedia = dcInfo.second
                    initData = patchInitDc(init, if (isMedia) -dc else dc)
                }
            } else {
                isMedia = dc < 0
                dc = kotlin.math.abs(dc)
            }

            if (dc == null || dc !in TgConstants.DC_IPS) {
                Log.w(TAG, "Unknown DC for $dst:$port → TCP fallback")
                tcpFallback(client, dst, port, initData)
                return
            }

            // --- Try WebSocket ---
            val targetIp = TgConstants.DC_IPS[dc] ?: dst
            val domains = wsDomains(dc, isMedia)
            var wsOk = false

            for (domain in domains) {
                try {
                    Log.i(TAG, "DC$dc${if (isMedia) "m" else ""} → WS via $domain ($targetIp)")

                    val bridge = WsBridge(targetIp, domain, "/")
                    val ws = bridge.connect()
                    ProxyService.wsCount++

                    ws.send(initData)
                    bridge.bridge(client, ws)
                    wsOk = true
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "WS to $domain failed: ${e.message}")
                }
            }

            if (!wsOk) {
                Log.i(TAG, "DC$dc WS failed → TCP fallback to $dst:$port")
                tcpFallback(client, dst, port, initData)
            }

        } catch (e: Exception) {
            Log.e(TAG, "handleClient error: ${e.message}")
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun tcpFallback(client: Socket, dst: String, port: Int, init: ByteArray) {
        try {
            val remote = Socket()
            remote.connect(InetSocketAddress(dst, port), 10_000)
            remote.tcpNoDelay = true
            remote.getOutputStream().write(init)
            remote.getOutputStream().flush()
            ProxyService.tcpFallbackCount++
            bridgeTcp(client, remote)
        } catch (e: Exception) {
            Log.w(TAG, "TCP fallback to $dst:$port failed: ${e.message}")
        }
    }

    private fun bridgeTcp(a: Socket, b: Socket) {
        val t1 = Thread {
            try { pipe(a.getInputStream(), b.getOutputStream()) }
            catch (_: Exception) {}
            finally {
                try { a.close() } catch (_: Exception) {}
                try { b.close() } catch (_: Exception) {}
            }
        }
        val t2 = Thread {
            try { pipe(b.getInputStream(), a.getOutputStream()) }
            catch (_: Exception) {}
            finally {
                try { a.close() } catch (_: Exception) {}
                try { b.close() } catch (_: Exception) {}
            }
        }
        t1.isDaemon = true
        t2.isDaemon = true
        t1.start()
        t2.start()
        t1.join()
        t2.join()
    }

    /**
     * Оптимизация пинга: flush() только когда входной поток пуст.
     * Это склеивает мелкие порции в один TCP-пакет.
     */
    private fun pipe(inp: InputStream, out: OutputStream) {
        val buf = ByteArray(65536)
        while (true) {
            val n = inp.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
            if (inp.available() == 0) {
                out.flush()
            }
        }
        out.flush()
    }

    private fun socks5Reply(status: Int): ByteArray {
        return byteArrayOf(
            0x05, status.toByte(), 0x00, 0x01,
            0, 0, 0, 0, 0, 0
        )
    }

    private fun isHttpTransport(data: ByteArray): Boolean {
        if (data.size < 5) return false
        val s = String(data, 0, 8, Charsets.US_ASCII)
        return s.startsWith("POST ")    || s.startsWith("GET ") ||
                s.startsWith("HEAD ")   || s.startsWith("OPTIONS ")
    }

    private fun dcFromInit(data: ByteArray): Int? {
        try {
            val key = data.copyOfRange(8, 40)
            val iv  = data.copyOfRange(40, 56)
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, "AES"),
                IvParameterSpec(iv)
            )
            val keystream = cipher.update(ByteArray(64))
            val plain = ByteArray(8)
            for (i in 0..7) {
                plain[i] = (data[56 + i].toInt() xor keystream[56 + i].toInt()).toByte()
            }
            val proto = ByteBuffer.wrap(plain, 0, 4)
                .order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            val dcRaw = ByteBuffer.wrap(plain, 4, 2)
                .order(ByteOrder.LITTLE_ENDIAN).short.toInt()

            if (proto == 0xEFEFEFEFL ||
                proto == 0xEEEEEEEEL ||
                proto == 0xDDDDDDDDL
            ) {
                val dcAbs = kotlin.math.abs(dcRaw)
                if (dcAbs in 1..5 || dcAbs == 203) {
                    return dcRaw
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "DC extraction failed: ${e.message}")
        }
        return null
    }

    private fun patchInitDc(data: ByteArray, dc: Int): ByteArray {
        if (data.size < 64) return data
        try {
            val key = data.copyOfRange(8, 40)
            val iv  = data.copyOfRange(40, 56)
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, "AES"),
                IvParameterSpec(iv)
            )
            val ks = cipher.update(ByteArray(64))
            val patched = data.copyOf()
            val dcBytes = ByteBuffer.allocate(2)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort(dc.toShort())
                .array()
            patched[60] = (ks[60].toInt() xor dcBytes[0].toInt()).toByte()
            patched[61] = (ks[61].toInt() xor dcBytes[1].toInt()).toByte()
            return patched
        } catch (_: Exception) {
            return data
        }
    }

    private fun wsDomains(dc: Int, isMedia: Boolean): List<String> {
        val d = TgConstants.DC_OVERRIDES.getOrDefault(dc, dc)
        return if (isMedia) {
            listOf("kws${d}-1.web.telegram.org", "kws${d}.web.telegram.org")
        } else {
            listOf("kws${d}.web.telegram.org", "kws${d}-1.web.telegram.org")
        }
    }

    private fun InputStream.readNBytes2(n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = this.read(buf, off, n - off)
            if (r <= 0) break
            off += r
        }
        return if (off == n) buf else buf.copyOf(off)
    }
}
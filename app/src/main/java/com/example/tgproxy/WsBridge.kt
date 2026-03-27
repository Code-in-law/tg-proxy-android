package com.example.tgproxy

import android.net.VpnService
import android.util.Log
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

/**
 * WebSocket bridge using OkHttp.
 * Class (not object) — каждое соединение имеет свой экземпляр.
 *
 * Два режима:
 *  - VPN-режим:    передать onData + vpnService → protect() на SSL-сокете
 *  - SOCKS5-режим: onData = null, vpnService = null → recvQueue
 */
class WsBridge(
    private val targetIp: String,
    private val domain: String,
    private val path: String = "/apiws",
    private val onData: ((ByteArray) -> Unit)? = null,
    private val vpnService: VpnService? = null
) {

    companion object {
        private const val TAG = "WsBridge"
        private val CLOSED = ByteArray(0)

        private val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })

        private val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, trustAllCerts, java.security.SecureRandom())
        }
    }

    @Volatile private var wsConnection: WsConnection? = null
    private val pendingQueue = LinkedBlockingQueue<ByteArray>(256)

    // -------------------------------------------------------------------------
    // Публичный API
    // -------------------------------------------------------------------------

    fun connect(): WsConnection {
        val client = buildClient()
        val url = "wss://$domain$path"

        val request = Request.Builder()
            .url(url)
            .header("Origin", "https://web.telegram.org")
            .header("Sec-WebSocket-Protocol", "binary")
            .header("User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
            .build()

        val latch = CountDownLatch(1)
        var connection: WsConnection? = null
        var connectError: Exception? = null

        client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                connection = WsConnection(webSocket)
                wsConnection = connection
                // Сбрасываем пакеты, накопленные до открытия соединения
                while (pendingQueue.isNotEmpty()) {
                    val pending = pendingQueue.poll() ?: break
                    try { connection?.send(pending) }
                    catch (e: Exception) { Log.w(TAG, "flush pending failed: ${e.message}") }
                }
                latch.countDown()
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val data = bytes.toByteArray()
                if (onData != null) {
                    onData.invoke(data)          // VPN-режим
                } else {
                    connection?.onMessage(data)  // SOCKS5-режим
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) { /* ignore */ }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WS closing $domain: $code $reason")
                connection?.onClosed()
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WS closed $domain: $code $reason")
                connection?.onClosed()
                wsConnection = null
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WS failure to $domain: ${t.message}")
                if (connection == null) {
                    connectError = Exception("WS connect failed: ${t.message}")
                    latch.countDown()
                } else {
                    connection?.onClosed()
                    wsConnection = null
                }
            }
        })

        if (!latch.await(10, TimeUnit.SECONDS)) {
            throw Exception("WS connect timeout to $domain")
        }
        connectError?.let { throw it }
        return connection!!
    }

    fun send(data: ByteArray) {
        val conn = wsConnection
        if (conn != null && !conn.closed) {
            try { conn.send(data) }
            catch (e: Exception) { Log.w(TAG, "send() error: ${e.message}") }
        } else {
            if (!pendingQueue.offer(data)) {
                Log.w(TAG, "pendingQueue full, dropping packet for $domain")
            }
        }
    }

    fun close() {
        pendingQueue.clear()
        wsConnection?.close()
        wsConnection = null
    }

    // -------------------------------------------------------------------------
    // SOCKS5-режим
    // -------------------------------------------------------------------------

    fun bridge(tcpSocket: Socket, ws: WsConnection) {
        val inp = tcpSocket.getInputStream()
        val out = tcpSocket.getOutputStream()

        val t1 = Thread({
            try {
                val buf = ByteArray(65536)
                while (!ws.closed && !tcpSocket.isClosed) {
                    val n = inp.read(buf)
                    if (n <= 0) break
                    ws.send(buf.copyOf(n))
                }
            } catch (_: Exception) {
            } finally {
                ws.close()
                try { tcpSocket.close() } catch (_: Exception) {}
            }
        }, "tcp2ws-$targetIp")

        val t2 = Thread({
            try {
                while (!ws.closed && !tcpSocket.isClosed) {
                    val data = ws.recv(60_000) ?: break
                    out.write(data)
                    out.flush()
                }
            } catch (_: Exception) {
            } finally {
                ws.close()
                try { tcpSocket.close() } catch (_: Exception) {}
            }
        }, "ws2tcp-$targetIp")

        t1.isDaemon = true; t2.isDaemon = true
        t1.start();         t2.start()
        t1.join();          t2.join()
    }

    // -------------------------------------------------------------------------
    // Построение OkHttpClient с protect() на уровне SSLSocketFactory
    // -------------------------------------------------------------------------

    private fun buildClient(): OkHttpClient {
        val protectedSslFactory = if (vpnService != null) {
            ProtectedSslSocketFactory(sslContext.socketFactory, vpnService)
        } else {
            sslContext.socketFactory
        }

        return OkHttpClient.Builder()
            .sslSocketFactory(protectedSslFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    if (hostname == domain) listOf(InetAddress.getByName(targetIp))
                    else Dns.SYSTEM.lookup(hostname)
            })
            .build()
    }

    /**
     * SSLSocketFactory-обёртка.
     * При создании каждого нового сокета вызывает vpnService.protect(),
     * чтобы тот шёл напрямую через физический интерфейс, минуя TUN.
     */
    private class ProtectedSslSocketFactory(
        private val delegate: SSLSocketFactory,
        private val vpn: VpnService
    ) : SSLSocketFactory() {

        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

        override fun createSocket(
            socket: Socket,
            host: String,
            port: Int,
            autoClose: Boolean
        ): Socket {
            vpn.protect(socket)
            return delegate.createSocket(socket, host, port, autoClose)
        }

        override fun createSocket(): Socket {
            val s = Socket()
            vpn.protect(s)
            return s
        }

        override fun createSocket(host: String, port: Int): Socket {
            val s = Socket()
            vpn.protect(s)
            return delegate.createSocket(s, host, port, true)
        }

        override fun createSocket(
            host: String, port: Int,
            localHost: InetAddress, localPort: Int
        ): Socket {
            val s = Socket()
            vpn.protect(s)
            return delegate.createSocket(s, host, port, true)
        }

        override fun createSocket(address: InetAddress, port: Int): Socket {
            val s = Socket()
            vpn.protect(s)
            return delegate.createSocket(s, address.hostAddress, port, true)
        }

        override fun createSocket(
            address: InetAddress, port: Int,
            localAddress: InetAddress, localPort: Int
        ): Socket {
            val s = Socket()
            vpn.protect(s)
            return delegate.createSocket(s, address.hostAddress, port, true)
        }
    }

    // -------------------------------------------------------------------------
    // WsConnection
    // -------------------------------------------------------------------------

    inner class WsConnection(
        private val ws: WebSocket,
        val recvQueue: LinkedBlockingQueue<ByteArray> = LinkedBlockingQueue()
    ) {
        @Volatile var closed = false
            private set

        fun send(data: ByteArray) {
            if (closed) throw IOException("WebSocket closed")
            ws.send(data.toByteString(0, data.size))
        }

        fun recv(timeoutMs: Long = 60_000): ByteArray? {
            val data = recvQueue.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: return null
            if (data === CLOSED) { closed = true; return null }
            return data
        }

        fun close() {
            if (closed) return
            closed = true
            try { ws.close(1000, null) } catch (_: Exception) {}
        }

        internal fun onMessage(data: ByteArray) { recvQueue.offer(data) }
        internal fun onClosed() { closed = true; recvQueue.offer(CLOSED) }
    }
}
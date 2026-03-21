package com.example.tgproxy

import android.util.Log
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

/**
 * WebSocket bridge using OkHttp.
 * Connects directly to Telegram's WS relay via TLS.
 */
object WsBridge {

    private const val TAG = "WsBridge"

    // Poison pill to signal WS close
    private val CLOSED = ByteArray(0)

    private val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    })

    private val sslContext = SSLContext.getInstance("TLS").apply {
        init(null, trustAllCerts, java.security.SecureRandom())
    }

    private val okClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // no read timeout for WS
            .writeTimeout(10, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            // Force connection to specific IP via DNS override
            .build()
    }

    private fun buildClient(targetIp: String, domain: String): OkHttpClient {
        return okClient.newBuilder()
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    // Resolve the domain to our target IP
                    return if (hostname == domain) {
                        listOf(InetAddress.getByName(targetIp))
                    } else {
                        Dns.SYSTEM.lookup(hostname)
                    }
                }
            })
            .build()
    }

    class WsConnection(
        private val ws: WebSocket,
        val recvQueue: LinkedBlockingQueue<ByteArray> = LinkedBlockingQueue()
    ) {
        @Volatile
        var closed = false
            private set

        fun send(data: ByteArray) {
            if (closed) throw java.io.IOException("WebSocket closed")
            ws.send(data.toByteString(0, data.size))
        }

        fun recv(timeoutMs: Long = 60_000): ByteArray? {
            val data = recvQueue.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: return null
            if (data === CLOSED) {
                closed = true
                return null
            }
            return data
        }

        fun close() {
            closed = true
            try { ws.close(1000, null) } catch (_: Exception) {}
        }

        internal fun onMessage(data: ByteArray) {
            recvQueue.offer(data)
        }

        internal fun onClosed() {
            closed = true
            recvQueue.offer(CLOSED)
        }
    }

    /**
     * Connect to Telegram WS relay.
     * @param targetIp  The DC IP to connect to
     * @param domain    The domain for TLS SNI (e.g. kws2.web.telegram.org)
     */
    fun connect(targetIp: String, domain: String, path: String = "/apiws"): WsConnection {
        val client = buildClient(targetIp, domain)
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

        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connection = WsConnection(webSocket)
                latch.countDown()
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                connection?.onMessage(bytes.toByteArray())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // text frames — ignore for binary protocol
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                connection?.onClosed()
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connection?.onClosed()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (connection == null) {
                    connectError = Exception("WS connect failed: ${t.message}")
                    latch.countDown()
                } else {
                    connection?.onClosed()
                }
            }
        })

        if (!latch.await(10, TimeUnit.SECONDS)) {
            ws.cancel()
            throw Exception("WS connect timeout to $domain")
        }

        if (connectError != null) {
            throw connectError!!
        }

        return connection!!
    }

    /**
     * Bridge TCP socket <-> WebSocket bidirectionally.
     */
    fun bridge(tcpSocket: Socket, ws: WsConnection) {
        val inp = tcpSocket.getInputStream()
        val out = tcpSocket.getOutputStream()

        // TCP -> WS
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
        }, "tcp2ws")

        // WS -> TCP
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
        }, "ws2tcp")

        t1.isDaemon = true
        t2.isDaemon = true
        t1.start()
        t2.start()
        t1.join()
        t2.join()
    }
}
package com.woorizip.radio

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.HandlerThread
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * NAS 서버와의 WebSocket 연결.
 * - 항상 집 Wi-Fi 네트워크에 묶어서 연결함 (인터넷이 끊겨 폰이 모바일 데이터로 넘어가도 집 안 통신 유지)
 * - Wi-Fi가 없으면(집 밖) 연결하지 않고 기다렸다가, 집 Wi-Fi에 다시 붙으면 자동 재연결
 */
class RadioClient(private val ctx: Context, private val listener: Listener) {

    interface Listener {
        fun onText(msg: JSONObject)
        fun onAudio(data: ByteArray)
        fun onConnectionChanged(connected: Boolean, detail: String)
    }

    private val cm = ctx.getSystemService(ConnectivityManager::class.java)
    private val thread = HandlerThread("radio-net").apply { start() }
    private val handler = Handler(thread.looper)

    @Volatile private var ws: WebSocket? = null
    @Volatile private var running = false
    @Volatile private var wifi: Network? = null
    @Volatile var connected = false
        private set
    private var backoff = 2000L
    private var lastError: String? = null

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            wifi = network
            reconnectNow()
        }

        override fun onLost(network: Network) {
            if (wifi == network) {
                wifi = null
                handler.post {
                    ws?.cancel()
                    ws = null
                    setConnected(false, "집 Wi-Fi 대기 중")
                }
            }
        }
    }

    fun start() {
        if (running) return
        running = true
        val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        try {
            cm.registerNetworkCallback(req, wifiCallback)
        } catch (_: Exception) {
        }
        handler.post { connect() }
    }

    fun stop() {
        running = false
        try {
            cm.unregisterNetworkCallback(wifiCallback)
        } catch (_: Exception) {
        }
        handler.removeCallbacksAndMessages(null)
        handler.post {
            ws?.close(1000, "bye")
            ws = null
            setConnected(false, "수신 대기 꺼짐")
        }
    }

    /** 설정 변경이나 Wi-Fi 재연결 시 즉시 다시 연결 */
    fun reconnectNow() {
        if (!running) return
        handler.removeCallbacksAndMessages(null)
        handler.post {
            backoff = 2000L
            ws?.cancel()
            ws = null
            connect()
        }
    }

    private fun connect() {
        if (!running || ws != null) return
        val p = Prefs(ctx)
        if (!p.isConfigured) {
            setConnected(false, "설정에서 NAS 주소·이름·암호를 입력하세요")
            return
        }
        val net = wifi ?: findWifi()
        if (net == null) {
            setConnected(false, "집 Wi-Fi 대기 중")
            return  // Wi-Fi가 연결되면 wifiCallback이 다시 부름
        }
        wifi = net
        setConnected(false, "연결 중…")
        lastError = null
        val http = OkHttpClient.Builder()
            .socketFactory(net.socketFactory)        // 집 Wi-Fi로만 통신
            .dns(okhttp3.Dns { h -> net.getAllByName(h).toList() })
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()
        val req = Request.Builder().url("ws://${p.host}:${p.port}/").build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val device = "android-" + android.provider.Settings.Secure.getString(
                    ctx.contentResolver, android.provider.Settings.Secure.ANDROID_ID
                )
                val hello = JSONObject().put("type", "hello").put("name", p.name).put("key", p.key).put("device", device)
                webSocket.send(hello.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (ws !== webSocket) return
                val msg = try { JSONObject(text) } catch (_: Exception) { return }
                when (msg.optString("type")) {
                    "welcome" -> {
                        backoff = 2000L
                        setConnected(true, "연결됨")
                    }
                    "error" -> lastError = when (msg.optString("reason")) {
                        "auth" -> "가족 암호가 서버와 달라요"
                        "unknown_member" -> "이름이 서버의 가족 목록에 없어요"
                        else -> "서버 오류"
                    }
                }
                listener.onText(msg)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (ws === webSocket) listener.onAudio(bytes.toByteArray())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                dropped(webSocket, "연결 끊김")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                dropped(webSocket, "NAS 서버에 연결할 수 없어요")
            }
        })
    }

    private fun dropped(w: WebSocket, why: String) {
        handler.post {
            if (ws !== w) return@post
            ws = null
            setConnected(false, lastError ?: why)
            if (running) {
                handler.postDelayed({ connect() }, backoff)
                backoff = (backoff * 2).coerceAtMost(15000L)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun findWifi(): Network? = cm.allNetworks.firstOrNull {
        cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    private fun setConnected(c: Boolean, detail: String) {
        connected = c
        listener.onConnectionChanged(c, detail)
    }

    fun sendJson(o: JSONObject): Boolean = ws?.send(o.toString()) ?: false

    fun sendAudio(b: ByteArray, len: Int): Boolean = ws?.send(b.toByteString(0, len)) ?: false
}

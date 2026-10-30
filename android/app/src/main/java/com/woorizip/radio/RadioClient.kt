package com.woorizip.radio

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * NAS 서버와의 WebSocket 연결.
 * - 집 Wi-Fi가 있으면 "집 주소"로 Wi-Fi를 통해 연결 → 집(home) 모드
 * - 집 주소로 안 되거나 Wi-Fi가 없으면 "밖 주소"(Tailscale/DDNS)로 아무 망이나 통해 연결 → 밖(out) 모드
 * - 밖 모드일 때 Wi-Fi가 생기면 집 주소를 다시 시도해서 집 모드로 돌아감
 */
class RadioClient(private val ctx: Context, private val listener: Listener) {

    interface Listener {
        fun onText(msg: JSONObject)
        fun onAudio(data: ByteArray)
        fun onConnectionChanged(connected: Boolean, where: String?, detail: String)
    }

    private val cm = ctx.getSystemService(ConnectivityManager::class.java)
    private val thread = HandlerThread("radio-net").apply { start() }
    private val handler = Handler(thread.looper)

    @Volatile private var ws: WebSocket? = null
    @Volatile private var running = false
    @Volatile private var wifi: Network? = null
    @Volatile var connected = false; private set
    @Volatile var where: String? = null; private set   // "home" | "out" | null
    @Volatile var httpBase: String? = null; private set  // 지금 붙은 서버의 http 주소 (업데이트 파일 받기용)
    private var backoff = 2000L
    private var lastError: String? = null
    private var skipHome = false

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            wifi = network
            if (where != "home") reconnectNow()   // 밖 모드였거나 끊겨 있으면 집 주소부터 다시
        }

        override fun onLost(network: Network) {
            if (wifi == network) {
                wifi = null
                if (where == "home") reconnectNow()   // 집 연결이 끊김 → 밖 주소 시도
            }
        }
    }

    private val anyCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (!connected) reconnectNow()
        }
    }

    fun start() {
        if (running) return
        running = true
        try {
            cm.registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), wifiCallback
            )
            cm.registerNetworkCallback(
                NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), anyCallback
            )
        } catch (_: Exception) {
        }
        handler.post { connect() }
    }

    fun stop() {
        running = false
        try { cm.unregisterNetworkCallback(wifiCallback) } catch (_: Exception) {}
        try { cm.unregisterNetworkCallback(anyCallback) } catch (_: Exception) {}
        handler.removeCallbacksAndMessages(null)
        handler.post {
            ws?.close(1000, "bye")
            ws = null
            setState(false, null, "수신 대기 꺼짐")
        }
    }

    /** 설정 변경이나 망 변경 시 즉시 다시 연결 (집 주소부터) */
    fun reconnectNow() {
        if (!running) return
        handler.removeCallbacksAndMessages(null)
        handler.post {
            backoff = 2000L
            skipHome = false
            ws?.cancel()
            ws = null
            connect()
        }
    }

    private fun connect() {
        if (!running || ws != null) return
        val p = Prefs(ctx)
        if (!p.isConfigured) {
            setState(false, null, "설정에서 NAS 주소·이름·암호를 입력하세요")
            return
        }
        val net = wifi ?: findWifi()
        wifi = net
        val useHome = net != null && !skipHome
        val outAddr = p.outHost
        if (!useHome && outAddr.isBlank()) {
            if (net == null) {
                setState(false, null, "집 Wi-Fi 대기 중 (밖에서도 쓰려면 설정에 밖 주소 입력)")
                return
            }
            // Wi-Fi는 있는데 집 주소가 실패한 경우: 잠시 후 다시 집 주소로
            skipHome = false
            handler.postDelayed({ connect() }, backoff)
            backoff = (backoff * 2).coerceAtMost(20000L)
            return
        }
        val targetWhere = if (useHome) "home" else "out"
        val url = if (useHome) Prefs.toWsUrl(p.host) else Prefs.toWsUrl(outAddr)
        httpBase = url.replaceFirst("ws", "http").trimEnd('/')
        setState(false, null, if (useHome) "집 주소로 연결 중…" else "밖 주소로 연결 중…")
        lastError = null
        val builder = OkHttpClient.Builder()
            .connectTimeout(if (useHome) 4 else 8, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            // 연결 유지 신호: 집(Wi-Fi)은 30초, 밖(데이터)은 2분 — 밖에서 배터리 아끼기
            .pingInterval(if (useHome) 30 else 120, TimeUnit.SECONDS)
        if (useHome && net != null) {
            // 집 주소는 반드시 Wi-Fi로만 (폰이 모바일 데이터를 기본으로 써도 집 안 통신 유지)
            builder.socketFactory(net.socketFactory)
            builder.dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = net.getAllByName(hostname).toList()
            })
        }
        val req = try {
            Request.Builder().url(url).build()
        } catch (_: IllegalArgumentException) {
            setState(false, null, if (useHome) "집 주소 형식이 잘못됐어요" else "밖 주소 형식이 잘못됐어요")
            return
        }
        var gotWelcome = false
        ws = builder.build().newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val device = "android-" + Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
                val hello = JSONObject().put("type", "hello").put("name", p.name).put("key", p.key)
                    .put("device", device).put("where", targetWhere)
                webSocket.send(hello.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (ws !== webSocket) return
                val msg = try { JSONObject(text) } catch (_: Exception) { return }
                when (msg.optString("type")) {
                    "welcome" -> {
                        gotWelcome = true
                        backoff = 2000L
                        skipHome = false
                        setState(true, targetWhere, if (useHome) "집에서 연결됨" else "밖에서 연결됨")
                        if (!useHome) scheduleHomeProbe()
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
                dropped(webSocket, useHome && !gotWelcome, "연결 끊김")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                dropped(webSocket, useHome && !gotWelcome, if (useHome) "집 주소로 연결 안 됨" else "밖 주소로 연결 안 됨")
            }
        })
    }

    /** 밖 주소로 붙어 있는 동안 Wi-Fi가 있으면 1분마다 집 주소를 다시 시도 (집에 돌아왔을 때 집 모드로 복귀) */
    private fun scheduleHomeProbe() {
        handler.postDelayed({
            if (!running || where != "out") return@postDelayed
            if (wifi != null || findWifi() != null) reconnectNow() else scheduleHomeProbe()
        }, 60_000L)
    }

    /** homeFailed: 집 주소로 아예 연결이 안 된 경우(연결됐다가 끊긴 게 아니라) → 바로 밖 주소 시도 */
    private fun dropped(w: WebSocket, homeFailed: Boolean, why: String) {
        handler.post {
            if (ws !== w) return@post
            ws = null
            val hadError = lastError != null
            setState(false, null, lastError ?: why)
            if (!running) return@post
            if (homeFailed && !hadError && Prefs(ctx).outHost.isNotBlank()) {
                skipHome = true          // 집 주소 실패 → 바로 밖 주소로
                connect()
            } else {
                skipHome = false         // 다음엔 다시 집 주소부터
                handler.postDelayed({ connect() }, backoff)
                backoff = (backoff * 2).coerceAtMost(20000L)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun findWifi(): Network? = cm.allNetworks.firstOrNull {
        cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    private fun setState(c: Boolean, w: String?, detail: String) {
        connected = c
        where = w
        listener.onConnectionChanged(c, w, detail)
    }

    fun sendJson(o: JSONObject): Boolean = ws?.send(o.toString()) ?: false

    fun sendAudio(b: ByteArray, len: Int): Boolean = ws?.send(b.toByteString(0, len)) ?: false
}

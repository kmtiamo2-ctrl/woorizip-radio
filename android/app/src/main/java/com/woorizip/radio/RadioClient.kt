package com.woorizip.radio

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * NAS 서버와의 WebSocket 연결.
 * - 집 Wi-Fi가 있으면 "집 주소"로 Wi-Fi를 통해 연결 → 집(home) 모드
 * - 집 주소로 안 되거나 Wi-Fi가 없으면 "밖 주소"(Tailscale/DDNS)로 아무 망이나 통해 연결 → 밖(out) 모드
 * - 밖 모드일 때 Wi-Fi가 있으면 지금 연결은 그대로 둔 채 옆에서 집 주소를 시험해 보고, 성공했을 때만 갈아탐
 *   (시험 간격은 1분 → 2분 → 5분 → 최대 10분, Wi-Fi가 새로 잡히면 바로)
 * - Wi-Fi에 묶어서 붙는 게 곧바로 막히면(VPN 설정 등) 기본 경로로 집 주소를 한 번 더 시도
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
    @Volatile var currentNetwork: Network? = null; private set  // 집 주소로 붙었을 때 쓰는 Wi-Fi 망 (업데이트도 같은 망으로)
    /** 집 주소 마지막 시도 결과 — 설정 화면에 보여줘서 왜 밖 경로인지 알 수 있게 */
    @Volatile var homeNote: String = ""; private set
    private var backoff = 2000L
    private var lastError: String? = null
    private var skipHome = false
    private var homeUnbound = false        // 이번 집 주소 시도가 Wi-Fi에 묶지 않은(기본 경로) 시도인지
    private var triedUnbound = false       // 이번 연결 사이클에서 기본 경로 시도를 이미 했는지
    private var probeWs: WebSocket? = null // 밖 모드에서 옆으로 열어 보는 집 주소 시험 연결
    private var probeDelay = 60_000L
    private val probeRunnable = Runnable { probeHome() }
    private val hhmm = SimpleDateFormat("HH:mm", Locale.KOREA)

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            wifi = network
            handler.post {
                probeDelay = 60_000L
                if (connected && where == "out") { handler.removeCallbacks(probeRunnable); probeHome() }   // 밖 모드면 끊지 않고 옆에서 집 주소 시험
                else if (!connected || where != "home") reconnectNow()
            }
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
            probeWs?.cancel(); probeWs = null
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
            triedUnbound = false
            homeUnbound = false
            probeWs?.cancel(); probeWs = null
            ws?.cancel()
            ws = null
            connect()
        }
    }

    private fun buildClient(net: Network?, home: Boolean): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(if (home) 4 else 8, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            // 연결 유지 신호: 집(Wi-Fi)은 30초, 밖(데이터)은 2분 — 밖에서 배터리 아끼기
            .pingInterval(if (home) 30 else 120, TimeUnit.SECONDS)
        if (net != null) {
            // 집 주소는 Wi-Fi로만 (폰이 모바일 데이터를 기본으로 써도 집 안 통신 유지)
            b.socketFactory(net.socketFactory)
            b.dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = net.getAllByName(hostname).toList()
            })
        }
        return b.build()
    }

    private fun hello(p: Prefs, targetWhere: String): String {
        val device = "android-" + Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
        return JSONObject().put("type", "hello").put("name", p.name).put("key", p.key).put("device", device).put("where", targetWhere).toString()
    }

    private fun reason(t: Throwable?): String {
        val m = (t?.message ?: t?.javaClass?.simpleName ?: "").lowercase()
        return when {
            m.contains("eperm") || m.contains("permission") -> "차단됨(EPERM)"
            m.contains("enetunreach") || m.contains("network is unreachable") -> "네트워크 도달 불가"
            m.contains("ehostunreach") || m.contains("no route") -> "호스트 도달 불가"
            m.contains("econnrefused") || m.contains("refused") -> "연결 거부(서버 꺼짐?)"
            m.contains("timeout") || m.contains("timed out") -> "시간 초과"
            m.contains("unexpected end") || m.contains("eof") -> "연결 중 끊김"
            m.isBlank() -> "실패"
            else -> m.take(40)
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
            triedUnbound = false
            handler.postDelayed({ connect() }, backoff)
            backoff = (backoff * 2).coerceAtMost(20000L)
            return
        }
        val targetWhere = if (useHome) "home" else "out"
        val bindNet = if (useHome && !homeUnbound) net else null
        val url = if (useHome) Prefs.toWsUrl(p.host) else Prefs.toWsUrl(outAddr)
        httpBase = url.replaceFirst("ws", "http").trimEnd('/')
        currentNetwork = bindNet
        setState(false, null, if (useHome) (if (homeUnbound) "집 주소로 연결 중(기본 경로)…" else "집 주소로 연결 중…") else "밖 주소로 연결 중…")
        lastError = null
        val req = try {
            Request.Builder().url(url).build()
        } catch (_: IllegalArgumentException) {
            setState(false, null, if (useHome) "집 주소 형식이 잘못됐어요" else "밖 주소 형식이 잘못됐어요")
            return
        }
        val started = SystemClock.elapsedRealtime()
        val unbound = homeUnbound
        var gotWelcome = false
        ws = buildClient(bindNet, useHome).newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(hello(p, targetWhere))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (ws !== webSocket) return
                val msg = try { JSONObject(text) } catch (_: Exception) { return }
                when (msg.optString("type")) {
                    "welcome" -> {
                        gotWelcome = true
                        backoff = 2000L
                        skipHome = false
                        triedUnbound = false
                        homeUnbound = false
                        if (useHome) homeNote = "${hhmm.format(Date())} 집 주소 성공" + (if (unbound) " (기본 경로)" else " (Wi-Fi)")
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
                dropped(webSocket, useHome && !gotWelcome, "연결 끊김", null, SystemClock.elapsedRealtime() - started)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                dropped(webSocket, useHome && !gotWelcome, if (useHome) "집 주소로 연결 안 됨" else "밖 주소로 연결 안 됨", t, SystemClock.elapsedRealtime() - started)
            }
        })
    }

    /** homeFailed: 집 주소로 아예 연결이 안 된 경우(연결됐다가 끊긴 게 아니라) → 기본 경로 한 번 더, 그 다음 밖 주소 */
    private fun dropped(w: WebSocket, homeFailed: Boolean, why: String, t: Throwable?, elapsedMs: Long) {
        handler.post {
            if (ws !== w) return@post
            ws = null
            val hadError = lastError != null
            if (homeFailed) homeNote = "${hhmm.format(Date())} 집 주소 ${reason(t)}" + (if (homeUnbound) " (기본 경로)" else " (Wi-Fi)") + " · ${elapsedMs / 1000.0}초"
            setState(false, null, lastError ?: why)
            if (!running) return@post
            if (homeFailed && !hadError && !homeUnbound && !triedUnbound && elapsedMs < 1500) {
                // Wi-Fi에 묶은 연결이 곧바로 거절됨(VPN·망 정책 등) → 기본 경로로 집 주소 한 번 더
                triedUnbound = true
                homeUnbound = true
                connect()
                return@post
            }
            homeUnbound = false
            if (homeFailed && !hadError && Prefs(ctx).outHost.isNotBlank()) {
                skipHome = true          // 집 주소 실패 → 바로 밖 주소로
                connect()
            } else {
                skipHome = false         // 다음엔 다시 집 주소부터
                triedUnbound = false
                handler.postDelayed({ connect() }, backoff)
                backoff = (backoff * 2).coerceAtMost(20000L)
            }
        }
    }

    // ---- 밖 모드에서의 집 주소 시험 (지금 연결은 그대로 두고 옆에서) ----

    private fun scheduleHomeProbe() {
        handler.removeCallbacks(probeRunnable)
        handler.postDelayed(probeRunnable, probeDelay)
    }

    private fun probeHome() {
        if (!running || !connected || where != "out" || probeWs != null) return
        val net = wifi ?: findWifi()
        wifi = net
        if (net == null) { scheduleHomeProbe(); return }   // Wi-Fi가 없으면 다음에
        tryHome(net, bound = true) { ok, note ->
            handler.post {
                if (ok) return@post   // 갈아탐 (tryHome 안에서 처리)
                // Wi-Fi 묶음이 곧바로 실패했으면 기본 경로로 한 번 더
                if (note.contains("차단") || note.contains("도달 불가")) {
                    tryHome(net, bound = false) { ok2, _ -> handler.post { if (!ok2) { probeDelay = nextProbeDelay(); scheduleHomeProbe() } } }
                } else {
                    probeDelay = nextProbeDelay(); scheduleHomeProbe()
                }
            }
        }
    }

    private fun nextProbeDelay(): Long = when {
        probeDelay < 120_000L -> 120_000L
        probeDelay < 300_000L -> 300_000L
        else -> 600_000L
    }

    /** 집 주소로 시험 연결. welcome까지 받으면 그 연결로 갈아타고(옛 연결은 닫음) done(true), 아니면 done(false, 이유) */
    private fun tryHome(net: Network, bound: Boolean, done: (Boolean, String) -> Unit) {
        val p = Prefs(ctx)
        val url = Prefs.toWsUrl(p.host)
        val req = try { Request.Builder().url(url).build() } catch (_: IllegalArgumentException) { done(false, "주소 형식"); return }
        val started = SystemClock.elapsedRealtime()
        var promoted = false
        var promoting = false
        var finished = false
        val queue = ArrayList<JSONObject>()   // welcome 뒤 갈아타기가 끝날 때까지 온 메시지(기록·접속 현황)를 잃지 않게 모아 둠
        val tag = if (bound) " (Wi-Fi)" else " (기본 경로)"
        fun finish(ok: Boolean, note: String) { if (!finished) { finished = true; homeNote = "${hhmm.format(Date())} 집 주소 $note$tag"; done(ok, note) } }
        probeWs = buildClient(if (bound) net else null, true).newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(hello(p, "home")) }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = try { JSONObject(text) } catch (_: Exception) { return }
                synchronized(queue) {
                    if (!promoted) {
                        queue.add(msg)
                        if (msg.optString("type") == "welcome" && !promoting) {
                            promoting = true
                            handler.post {
                                if (probeWs !== webSocket || !running) { webSocket.cancel(); return@post }
                                probeWs = null
                                val old = ws
                                ws = webSocket
                                currentNetwork = if (bound) net else null
                                httpBase = url.replaceFirst("ws", "http").trimEnd('/')
                                backoff = 2000L; skipHome = false; triedUnbound = false; homeUnbound = false; probeDelay = 60_000L
                                old?.close(1000, "home")
                                setState(true, "home", "집에서 연결됨")
                                finish(true, "성공 → 집 모드로 전환")
                                val held: List<JSONObject>
                                synchronized(queue) { promoted = true; held = ArrayList(queue); queue.clear() }
                                held.forEach { listener.onText(it) }
                            }
                        }
                        return
                    }
                }
                if (ws === webSocket) listener.onText(msg)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (promoted && ws === webSocket) listener.onAudio(bytes.toByteArray())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (promoted) dropped(webSocket, false, "연결 끊김", null, SystemClock.elapsedRealtime() - started)
                else handler.post { if (probeWs === webSocket) probeWs = null; finish(false, "연결 끊김") }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (promoted) dropped(webSocket, false, "집 연결 끊김", t, SystemClock.elapsedRealtime() - started)
                else handler.post { if (probeWs === webSocket) probeWs = null; finish(false, reason(t) + " · ${(SystemClock.elapsedRealtime() - started) / 1000.0}초") }
            }
        })
    }

    /** 설정의 [집 주소 연결 시험]: Wi-Fi 묶음·기본 경로 두 가지로 집 주소에 붙어 보고 결과 문장을 돌려줌 (지금 연결은 건드리지 않음) */
    fun testHome(done: (String) -> Unit) {
        Thread {
            val p = Prefs(ctx)
            val net = wifi ?: findWifi()
            val lines = ArrayList<String>()
            lines.add("집 주소: ${p.host}")
            lines.add("Wi-Fi: " + (if (net == null) "없음" else "있음"))
            if (net != null) {
                try {
                    val lp = cm.getLinkProperties(net)
                    val ips = lp?.linkAddresses?.map { it.address.hostAddress ?: "" }?.filter { it.contains('.') } ?: emptyList()
                    if (ips.isNotEmpty()) lines.add("폰 IP: " + ips.joinToString(", "))
                } catch (_: Exception) {}
            }
            fun attempt(bindNet: Network?): String {
                val t0 = SystemClock.elapsedRealtime()
                val latch = java.util.concurrent.CountDownLatch(1)
                var result = "응답 없음"
                val url = try { Prefs.toWsUrl(p.host) } catch (_: Exception) { return "주소 형식 오류" }
                val wsock = try {
                    buildClient(bindNet, true).newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(hello(p, "home")) }
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val type = try { JSONObject(text).optString("type") } catch (_: Exception) { "" }
                            if (type == "welcome") { result = "성공 (${(SystemClock.elapsedRealtime() - t0) / 1000.0}초)"; latch.countDown(); webSocket.close(1000, "test") }
                            else if (type == "error") { result = "서버 거절: " + JSONObject(text).optString("reason"); latch.countDown(); webSocket.close(1000, "test") }
                        }
                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            result = reason(t) + " (${(SystemClock.elapsedRealtime() - t0) / 1000.0}초) — " + (t.message ?: "").take(60); latch.countDown()
                        }
                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { latch.countDown() }
                    })
                } catch (e: Exception) { return "실패: ${e.message}" }
                latch.await(8, TimeUnit.SECONDS)
                wsock.cancel()
                return result
            }
            lines.add("Wi-Fi로 붙기: " + (if (net == null) "건너뜀" else attempt(net)))
            lines.add("기본 경로로 붙기: " + attempt(null))
            lines.add("지금 연결: " + (if (connected) (if (where == "home") "집 모드" else "밖 모드") else "끊김"))
            if (homeNote.isNotBlank()) lines.add("마지막 자동 시도: $homeNote")
            done(lines.joinToString("\n"))
        }.start()
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

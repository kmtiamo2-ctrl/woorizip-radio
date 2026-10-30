package com.woorizip.radio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 무전기 핵심 동작 (화면·서비스와 분리된 상태 관리).
 * 화면(MainActivity), 알림, 볼륨키가 모두 이 객체를 통해 말하기/듣기를 함.
 */
object Radio : RadioClient.Listener {

    private lateinit var app: Context
    private lateinit var client: RadioClient
    private val player = Player()
    private val recorder = Recorder { buf, n -> client.sendAudio(buf, n) }
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private var focus: AudioFocusRequest? = null

    // ---- 화면에 보여줄 상태 ----
    @Volatile var connected = false; private set
    @Volatile var status = "수신 대기 꺼짐"; private set
    @Volatile var members: List<String> = listOf("엄마", "아빠", "첫째", "둘째", "셋째"); private set
    @Volatile var online: List<String> = emptyList(); private set
    @Volatile var me: String = ""; private set
    @Volatile var talking = false; private set        // 내가 지금 송신 중
    @Volatile var requesting = false; private set     // 송신 허락 기다리는 중
    @Volatile var incomingFrom: String? = null; private set
    @Volatile var incomingMode: String? = null; private set
    @Volatile var notice: String = ""; private set    // 최근 안내 문구
    /** 수신 대기 서비스가 마이크 권한을 가진 상태로 실행 중인지 (화면 꺼짐 송신 가능 여부) */
    @Volatile var micReady = false
    /** 앱 화면이 지금 보이는지 (보이면 서비스 상태와 상관없이 마이크 사용 가능) */
    @Volatile var uiVisible = false

    /** "all" 또는 가족 이름 */
    var target: String
        get() = Prefs(app).target
        set(v) { Prefs(app).target = v; changed() }

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        client = RadioClient(app, this)
        me = Prefs(app).name
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    /** 상태 변경은 모두 메인 스레드에서 일어나므로 바로 화면에 알림 */
    private fun changed() { main.post { listeners.forEach { it() } } }

    fun startConnection() { me = Prefs(app).name; client.start() }
    fun stopConnection() { stopTalk(); client.stop() }
    fun reconnect() { me = Prefs(app).name; client.reconnectNow() }

    // ---- 말하기 ----

    fun startTalk() {
        if (talking || requesting) return
        when {
            !connected -> { notice = "NAS에 연결되어 있지 않아요"; Feedback.denied(app); changed(); return }
            incomingFrom != null -> { notice = "${incomingFrom}님이 말하는 중이에요"; Feedback.denied(app); changed(); return }
            !micReady && !uiVisible -> { notice = "말하기를 쓰려면 앱을 한 번 열어 주세요"; Feedback.denied(app); changed(); return }
        }
        requesting = true
        if (!client.sendJson(JSONObject().put("type", "talk_start").put("to", target))) {
            requesting = false
            notice = "NAS에 연결되어 있지 않아요"
            Feedback.denied(app)
        }
        changed()
    }

    fun stopTalk() {
        val wasActive = talking || requesting
        requesting = false
        if (talking) {
            talking = false
            recorder.stop()
        }
        if (wasActive) {
            client.sendJson(JSONObject().put("type", "talk_end"))
            changed()
        }
    }

    fun toggleTalk() { if (talking || requesting) stopTalk() else startTalk() }

    // ---- 서버 메시지 ----

    override fun onText(msg: JSONObject) {
        main.post { handleText(msg) }
    }

    private fun handleText(msg: JSONObject) {
        when (msg.optString("type")) {
            "welcome" -> {
                members = msg.optJSONArray("members").toList().ifEmpty { members }
                notice = ""
            }
            "presence" -> online = msg.optJSONArray("online").toList()
            "talk_granted" -> {
                if (!requesting) return   // 이미 손을 뗀 경우 무시 (서버는 곧 talk_end를 받음)
                requesting = false
                talking = true
                Feedback.granted(app)
                if (!recorder.start()) {
                    notice = "마이크를 사용할 수 없어요"
                    stopTalk()
                } else {
                    val to = msg.optString("to")
                    notice = if (to == "all") "전체 방송 중" else "${to}님에게 말하는 중"
                }
            }
            "talk_denied" -> {
                requesting = false
                Feedback.denied(app)
                notice = when (msg.optString("reason")) {
                    "busy" -> "${msg.optString("speaker")}님이 말하는 중이에요"
                    "offline" -> "${msg.optString("to")}님이 지금 접속해 있지 않아요"
                    else -> "지금은 말할 수 없어요"
                }
            }
            "talk_stopped" -> {
                talking = false
                requesting = false
                recorder.stop()
                Feedback.denied(app)
                notice = if (msg.optString("reason") == "too_long") "1분이 지나 자동으로 끝났어요" else "송신이 끝났어요"
            }
            "incoming" -> onIncoming(msg.optString("from"), msg.optString("mode"))
            "incoming_end" -> endIncoming()
        }
        changed()
    }

    override fun onAudio(data: ByteArray) {
        player.feed(data)
    }

    override fun onConnectionChanged(connected: Boolean, detail: String) {
        main.post { handleConnection(connected, detail) }
    }

    private fun handleConnection(connected: Boolean, detail: String) {
        this.connected = connected
        status = detail
        if (!connected) {
            if (talking) { talking = false; recorder.stop() }
            requesting = false
            endIncoming()
            online = emptyList()
        }
        changed()
    }

    // ---- 듣기 ----

    private fun onIncoming(from: String, mode: String) {
        incomingFrom = from
        incomingMode = mode
        val prefs = Prefs(app)
        if (prefs.dnd) {
            notice = "방해금지 중 — ${from}님의 말을 받지 않았어요"
            return
        }
        val am = app.getSystemService(AudioManager::class.java)
        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) {
            // 무음·진동 모드: 휴대폰 설정을 존중해서 소리 없이 진동 + 알림만
            Feedback.vibrate(app, longArrayOf(0, 200, 150, 200))
            Notifs.showSilentIncoming(app, from, mode)
            return
        }
        requestFocus(am)
        Feedback.incoming()
        player.start()
    }

    private fun endIncoming() {
        if (incomingFrom == null) return
        incomingFrom = null
        incomingMode = null
        player.stop()
        Notifs.cancelSilentIncoming(app)
        abandonFocus()
    }

    private fun requestFocus(am: AudioManager) {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            ).build()
        focus = req
        am.requestAudioFocus(req)
    }

    private fun abandonFocus() {
        val req = focus ?: return
        focus = null
        app.getSystemService(AudioManager::class.java).abandonAudioFocusRequest(req)
    }

    private fun JSONArray?.toList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).map { optString(it) }
    }
}

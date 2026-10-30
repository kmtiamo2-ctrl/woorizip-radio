package com.woorizip.radio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArraySet

/** 보관 음성(클립) 한 개: 밖에 있을 때나 접속이 끊겼을 때 받은 가족의 말 */
data class Clip(val id: Int, val from: String, val mode: String, val ts: Long, val seconds: Double, val file: File) {
    val title get() = if (mode == "all") "${from}님 전체 방송" else "${from}님이 나에게"
}

/**
 * 무전기 핵심 동작 (화면·서비스와 분리된 상태 관리).
 * 화면(MainActivity), 알림, 볼륨키가 모두 이 객체를 통해 말하기/듣기를 함.
 * 상태 변경은 전부 메인 스레드에서 일어남.
 */
object Radio : RadioClient.Listener {

    private const val LIVE: Byte = 1
    private const val CLIP: Byte = 2

    private lateinit var app: Context
    private lateinit var client: RadioClient
    private val player = Player()
    private val recorder = Recorder { buf, n -> client.sendAudio(buf, n) }
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private var focus: AudioFocusRequest? = null
    private val pendingClips = HashMap<Int, JSONObject>()   // 헤더는 왔는데 음성은 아직

    // ---- 화면에 보여줄 상태 ----
    @Volatile var connected = false; private set
    @Volatile var where: String? = null; private set          // "home" | "out"
    @Volatile var status = "수신 대기 꺼짐"; private set
    @Volatile var members: List<String> = listOf("엄마", "아빠", "첫째", "둘째", "셋째"); private set
    @Volatile var parents: Set<String> = setOf("엄마", "아빠"); private set
    @Volatile var online: List<String> = emptyList(); private set
    @Volatile var memberWhere: Map<String, String> = emptyMap(); private set
    @Volatile var me: String = ""; private set
    @Volatile var talking = false; private set
    @Volatile var requesting = false; private set
    @Volatile var incomingFrom: String? = null; private set
    @Volatile var incomingMode: String? = null; private set
    @Volatile var playingClip: Clip? = null; private set
    @Volatile var notice: String = ""; private set
    @Volatile var clips: List<Clip> = emptyList(); private set   // 최근 받은 메시지 (새것이 앞)
    @Volatile var micReady = false
    @Volatile var uiVisible = false

    val isParent get() = me in parents
    val atHome get() = where == "home"

    var target: String
        get() = Prefs(app).target
        set(v) { Prefs(app).target = v; changed() }

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        client = RadioClient(app, this)
        me = Prefs(app).name
        loadClips()
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
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
        stopClip()
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

    override fun onText(msg: JSONObject) { main.post { handleText(msg) } }

    private fun handleText(msg: JSONObject) {
        when (msg.optString("type")) {
            "welcome" -> {
                members = msg.optJSONArray("members").toList().ifEmpty { members }
                msg.optJSONArray("parents")?.let { parents = it.toList().toSet() }
                notice = ""
            }
            "presence" -> {
                online = msg.optJSONArray("online").toList()
                val w = msg.optJSONObject("where")
                memberWhere = if (w == null) emptyMap() else w.keys().asSequence().associateWith { w.optString(it) }
            }
            "talk_granted" -> {
                if (!requesting) return
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
            "clip" -> pendingClips[msg.optInt("id")] = msg
        }
        changed()
    }

    override fun onAudio(data: ByteArray) {
        if (data.isEmpty()) return
        when (data[0]) {
            LIVE -> player.feed(data.copyOfRange(1, data.size))
            CLIP -> if (data.size > 5) {
                val id = ByteBuffer.wrap(data, 1, 4).int
                val pcm = data.copyOfRange(5, data.size)
                main.post { onClipArrived(id, pcm) }
            }
        }
    }

    override fun onConnectionChanged(connected: Boolean, where: String?, detail: String) {
        main.post {
            this.connected = connected
            this.where = where
            status = detail
            if (!connected) {
                if (talking) { talking = false; recorder.stop() }
                requesting = false
                endIncoming()
                online = emptyList()
                memberWhere = emptyMap()
            }
            changed()
        }
    }

    // ---- 실시간 듣기 ----

    private fun onIncoming(from: String, mode: String) {
        incomingFrom = from
        incomingMode = mode
        stopClip()
        val prefs = Prefs(app)
        if (prefs.dnd) {
            notice = "방해금지 중 — ${from}님의 말을 받지 않았어요"
            return
        }
        val am = app.getSystemService(AudioManager::class.java)
        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) {
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

    // ---- 보관 음성(클립): 밖에 있을 때 / 접속이 끊겼을 때 받은 말 ----

    private fun clipDir() = File(app.filesDir, "clips").apply { mkdirs() }

    private fun loadClips() {
        clips = clipDir().listFiles()?.mapNotNull { f ->
            // 파일 이름: id_from_mode_ts_seconds.pcm
            val p = f.nameWithoutExtension.split("_")
            if (p.size < 5) return@mapNotNull null
            Clip(p[0].toIntOrNull() ?: return@mapNotNull null, p[1], p[2],
                p[3].toLongOrNull() ?: 0L, p[4].toDoubleOrNull() ?: 0.0, f)
        }?.sortedByDescending { it.ts }?.take(30) ?: emptyList()
    }

    private fun onClipArrived(id: Int, pcm: ByteArray) {
        val meta = pendingClips.remove(id) ?: return
        client.sendJson(JSONObject().put("type", "clip_ack").put("id", id))
        val from = meta.optString("from")
        val mode = meta.optString("mode")
        val ts = meta.optLong("ts", System.currentTimeMillis() / 1000)
        val seconds = meta.optDouble("seconds", pcm.size / 32000.0)
        if (clips.any { it.from == from && it.ts == ts }) return   // 다른 기기에서 이미 받은 것
        val file = File(clipDir(), "${id}_${from}_${mode}_${ts}_${seconds}.pcm")
        try { file.writeBytes(pcm) } catch (_: Exception) { return }
        val clip = Clip(id, from, mode, ts, seconds, file)
        clips = (listOf(clip) + clips).take(30).also { keep ->
            clipDir().listFiles()?.forEach { f -> if (keep.none { it.file == f }) f.delete() }
        }
        if (Prefs(app).dnd) return
        if (atHome || isParent) {
            // 집에 있거나 부모면 바로 들려줌 (접속이 끊겼던 사이에 온 말)
            Feedback.vibrate(app, longArrayOf(0, 120))
            playClip(clip)
        } else {
            // 밖에 있는 아이: 소리 없이 진동 + 알림만. 알림의 [듣기]로 재생
            Feedback.vibrate(app, longArrayOf(0, 250, 150, 250))
        }
        Notifs.showClip(app, clip, autoPlayed = atHome || isParent)
        changed()
    }

    fun clipById(id: Int): Clip? = clips.firstOrNull { it.id == id }

    fun playClip(clip: Clip) {
        if (incomingFrom != null || talking) return
        stopClip()
        val am = app.getSystemService(AudioManager::class.java)
        requestFocus(am)
        playingClip = clip
        Feedback.incoming()
        player.start()
        Thread {
            try {
                val pcm = clip.file.readBytes()
                var i = 0
                while (i < pcm.size && playingClip === clip) {
                    val end = minOf(i + AudioSpec.FRAME_BYTES, pcm.size)
                    player.feed(pcm.copyOfRange(i, end))
                    i = end
                    Thread.sleep(18)   // 20ms 프레임보다 살짝 빠르게 넣어 끊김 방지
                }
                Thread.sleep(400)
            } catch (_: Exception) {
            }
            main.post { if (playingClip === clip) stopClip() }
        }.start()
        changed()
    }

    fun stopClip() {
        if (playingClip == null) return
        playingClip = null
        player.stop()
        abandonFocus()
        changed()
    }

    fun deleteClip(clip: Clip) {
        if (playingClip === clip) stopClip()
        clip.file.delete()
        clips = clips.filter { it !== clip }
        changed()
    }

    // ---- 오디오 포커스 ----

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

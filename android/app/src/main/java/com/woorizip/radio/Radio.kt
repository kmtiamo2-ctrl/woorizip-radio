package com.woorizip.radio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import android.speech.tts.TextToSpeech
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 받은 메시지 한 개. 음성(file)이거나 문자(text).
 * 음성: 밖에 있을 때나 접속이 끊겼을 때 받은 가족의 말. 문자: 가족이 글로 보낸 것
 */
data class Clip(
    val id: Int, val from: String, val mode: String, val ts: Long, val seconds: Double,
    val file: File?, val text: String? = null, val mine: Boolean = false,
    val mid: Int = 0, val voiceSent: Boolean = false
) {
    val isText get() = text != null
    val title get() = when {
        mine && voiceSent -> "내가 ${if (mode == "all") "모두에게" else "${from}님에게"} (음성 ${seconds.toInt().coerceAtLeast(1)}초)"
        mine -> "내가 ${if (mode == "all") "모두에게" else "${from}님에게"}"
        mode == "all" -> "${from}님 전체 방송"
        mode == "group" -> "${from}님이 몇 명에게"
        else -> "${from}님이 나에게"
    }
}

/** 대화방: 모두 / 가족 한 명 / 묶음. members == null 이면 모두 */
data class Room(val key: String, val label: String, val members: List<String>?, val icon: String, val color: Int)

/** 가족별 고정 아이콘·색 (PC와 동일) */
object Family {
    private val style = mapOf(
        "엄마" to ("👩" to 0xFFF472B6.toInt()), "아빠" to ("👨" to 0xFF60A5FA.toInt()), "첫째" to ("🧒" to 0xFF34D399.toInt()),
        "둘째" to ("🧒" to 0xFFFB923C.toInt()), "셋째" to ("🧒" to 0xFFA78BFA.toInt())
    )
    private val groupStyle = mapOf("아이들" to ("👧" to 0xFF2DD4BF.toInt()), "부모님" to ("👫" to 0xFF818CF8.toInt()))
    const val ALL_ICON = "🏠"
    const val ALL_COLOR = 0xFFF5B301.toInt()
    const val GREY = 0xFF9AA6BC.toInt()
    fun icon(name: String?) = style[name]?.first ?: "🙂"
    fun color(name: String?) = style[name]?.second ?: GREY
    fun groupIcon(name: String) = groupStyle[name]?.first ?: "👪"
    fun groupColor(name: String) = groupStyle[name]?.second ?: GREY

    /** "아이들=첫째,둘째;부모님=엄마,아빠" → [(이름, 멤버)] */
    fun parseGroups(text: String): List<Pair<String, List<String>>> = text.split(";").mapNotNull { part ->
        val i = part.indexOf('=')
        if (i <= 0) null else {
            val name = part.substring(0, i).trim()
            val members = part.substring(i + 1).split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (name.isEmpty() || members.isEmpty()) null else name to members
        }
    }
}

/** 예약 메시지 (서버가 보관하고 시간이 되면 내 이름으로 보냄) */
data class Schedule(val sid: Int, val to: String, val text: String, val at: Long, val repeat: String)

/**
 * 무전기 핵심 동작 (화면·서비스와 분리된 상태 관리).
 * 화면(MainActivity), 알림, 볼륨키가 모두 이 객체를 통해 말하기/듣기를 함.
 * 상태 변경은 전부 메인 스레드에서 일어남.
 */
object Radio : RadioClient.Listener {

    private const val MAX_CLIPS = 150

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
    private val outbox = ArrayList<JSONObject>()             // 연결이 없을 때 보낸 문자: 연결되면 자동 전송
    private val seenSent = HashSet<Int>()                    // 내가 '봤어요/들었어요'라고 알린 mid
    @Volatile var receipts: Map<Int, Map<String, String>> = emptyMap(); private set   // 내가 보낸 메시지 mid -> {이름: sent|received|seen}
    @Volatile var schedules: List<Schedule> = emptyList(); private set
    @Volatile var scheduleNotice: String = ""; private set
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    val quickReplies: List<String> get() = Prefs(app).quickReplies

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

    /** 서버에 보낼 대상: "all" 또는 이름 배열 */
    private fun targetJson(t: String = target): Any =
        if (t == "all") "all" else JSONArray().also { arr -> t.split(",").filter { it.isNotBlank() }.forEach { arr.put(it) } }

    /** 화면 표시용: "모두" 또는 "엄마, 아빠" */
    fun targetLabel(t: String = target): String = if (t == "all") "모두" else t.split(",").filter { it.isNotBlank() }.joinToString(", ")

    // ---- 대화방 ----

    fun rooms(): List<Room> {
        val others = members.filter { it != me }
        val out = ArrayList<Room>()
        out.add(Room("all", "모두", null, Family.ALL_ICON, Family.ALL_COLOR))
        others.forEach { out.add(Room(it, it, listOf(it), Family.icon(it), Family.color(it))) }
        Family.parseGroups(Prefs(app).groups).forEach { (name, mem) ->
            val m = mem.filter { it in others }
            if (m.size >= 2) out.add(Room("g:$name", name, m, Family.groupIcon(name), Family.groupColor(name)))
        }
        return out
    }

    /** 지금 고른 상대에 해당하는 방 (방에 없는 조합이면 custom) */
    fun currentRoom(): Room {
        val tl = Prefs(app).targetList
        for (r in rooms()) {
            if (r.members == null) { if (target == "all") return r }
            else if (r.members.toSet() == tl.toSet()) return r
        }
        return Room("custom", tl.joinToString(", "), tl, "👥", Family.GREY)
    }

    fun selectRoom(key: String) {
        if (talking) return
        val r = rooms().firstOrNull { it.key == key } ?: return
        target = r.members?.joinToString(",") ?: "all"
    }

    /** 그 방에 보이는 메시지: 모두 방은 전부, 나머지는 그 사람(들)과 주고받은 것 */
    fun roomClips(room: Room): List<Clip> {
        val ms = room.members?.toSet() ?: return clips.toList()
        return clips.filter { c ->
            if (c.mine) c.mode != "all" && c.from.split(", ").any { it in ms }
            else c.from in ms
        }
    }

    fun unreadCount(room: Room): Int = roomClips(room).count { !it.mine && !it.voiceSent && it.mid != 0 && it.mid !in seenSent }

    /** 가족 버튼 눌렀을 때: 선택/해제 토글 (여러 명 가능) */
    fun toggleTarget(name: String) {
        if (talking) return
        if (name == "all") { target = "all"; return }
        val cur = Prefs(app).targetList.toMutableList()
        if (name in cur) cur.remove(name) else cur.add(name)
        target = if (cur.isEmpty()) "all" else cur.joinToString(",")
    }

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        client = RadioClient(app, this)
        me = Prefs(app).name
        loadClips()
        loadOutbox()
        tts = TextToSpeech(app) { status ->
            ttsReady = status == TextToSpeech.SUCCESS && tts?.setLanguage(Locale.KOREAN) != TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun changed() { main.post { listeners.forEach { it() } } }

    val httpBase: String? get() = client.httpBase
    val currentNetwork: android.net.Network? get() = client.currentNetwork
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
        if (!client.sendJson(JSONObject().put("type", "talk_start").put("to", targetJson()))) {
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
                flushOutbox()
                Updater.maybeCheck(app, client.httpBase, client.currentNetwork)   // 6시간에 한 번 새 버전 확인
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
                    notice = if (msg.opt("to") == "all") "전체 방송 중" else "${targetLabel()}에게 말하는 중"
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
            "talk_live" -> Feedback.live(app)   // 서버가 말소리를 확인해 지금부터 가족에게 나감: 짧은 진동
            "talk_silent" -> {                  // 말소리가 없어 가족에게 아무것도 안 나감 (버튼만 스친 경우 등)
                talking = false
                requesting = false
                recorder.stop()
                Feedback.vibrate(app, longArrayOf(0, 40, 60, 40))
                notice = "소리가 안 들려 보내지 않았어요"
            }
            "incoming" -> onIncoming(msg.optString("from"), msg.optString("mode"))
            "incoming_end" -> endIncoming()
            "clip" -> pendingClips[msg.optInt("id")] = msg
            "text" -> onTextArrived(msg)
            "text_sent" -> {
                val toAll = msg.opt("to") == "all"
                val names = msg.optJSONArray("to")?.toList() ?: listOfNotNull(msg.optString("to").takeIf { it.isNotBlank() && it != "all" })
                addClip(Clip(msg.optInt("id"), if (toAll) me else names.joinToString(", "), if (toAll) "all" else "direct",
                    msg.optLong("ts", System.currentTimeMillis() / 1000), 0.0, null, msg.optString("text"), mine = true,
                    mid = msg.optInt("mid")))
                notice = "보냈어요"
            }
            "talk_sent" -> {
                val toAll = msg.optString("mode") == "all"
                val names = msg.optJSONArray("to")?.toList() ?: emptyList()
                addClip(Clip(msg.optInt("mid"), if (toAll) me else names.joinToString(", "), if (toAll) "all" else "direct",
                    msg.optLong("ts", System.currentTimeMillis() / 1000), msg.optDouble("seconds", 0.0), null, null,
                    mine = true, mid = msg.optInt("mid"), voiceSent = true))
            }
            "history" -> mergeHistory(msg.optJSONArray("items"))
            "receipt" -> {
                val st = msg.optJSONObject("status") ?: JSONObject()
                receipts = receipts + (msg.optInt("mid") to st.keys().asSequence().associateWith { st.optString(it) })
            }
            "receipts" -> {
                val m = receipts.toMutableMap()
                msg.optJSONArray("items")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val it = arr.getJSONObject(i)
                        val st = it.optJSONObject("status") ?: JSONObject()
                        m[it.optInt("mid")] = st.keys().asSequence().associateWith { k -> st.optString(k) }
                    }
                }
                receipts = m
            }
            "schedules" -> {
                schedules = msg.optJSONArray("items")?.let { arr ->
                    (0 until arr.length()).map { i ->
                        val it = arr.getJSONObject(i)
                        val to = it.opt("to")
                        val toStr = if (to is JSONArray) to.toList().joinToString(", ") else to?.toString() ?: "all"
                        Schedule(it.optInt("sid"), toStr, it.optString("text"), it.optLong("at"), it.optString("repeat"))
                    }
                } ?: emptyList()
                scheduleNotice = ""
            }
            "schedule_failed" -> scheduleNotice = "예약 실패: ${msg.optString("reason")}"
            "text_failed" -> notice = "보낼 수 없어요"
        }
        changed()
    }

    // ---- 문자 메시지 (말할 수 없을 때) ----

    /** to: "all" 또는 이름. 비우면 지금 고른 상대. 연결이 없으면 보관했다가 연결되면 자동 전송 */
    fun sendText(text: String, to: String? = null): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        val toJson: Any = if (to != null) to else targetJson()
        val payload = JSONObject().put("type", "text").put("to", toJson).put("text", t)
        if (connected && client.sendJson(payload)) return true
        outbox.add(payload.put("ts", System.currentTimeMillis() / 1000))
        while (outbox.size > 50) outbox.removeAt(0)
        saveOutbox()
        notice = "지금 연결이 없어서 보관했어요. 연결되면 자동으로 보내요 (${outbox.size}개 대기)"
        client.reconnectNow()
        changed()
        return true
    }

    private fun outboxFile() = File(app.filesDir, "outbox.json")

    private fun loadOutbox() {
        try {
            val arr = JSONArray(outboxFile().readText())
            for (i in 0 until arr.length()) outbox.add(arr.getJSONObject(i))
        } catch (_: Exception) {}
    }

    private fun saveOutbox() {
        try { outboxFile().writeText(JSONArray(outbox).toString()) } catch (_: Exception) {}
    }

    /** 연결된 직후: 밀린 문자를 순서대로 보냄 (하루 넘은 건 버림) */
    private fun flushOutbox() {
        if (outbox.isEmpty()) return
        val pending = ArrayList(outbox)
        outbox.clear()
        var sent = 0
        val now = System.currentTimeMillis() / 1000
        for (p in pending) {
            if (now - p.optLong("ts", now) > 24 * 3600) continue
            if (client.sendJson(p)) sent++ else outbox.add(p)
        }
        saveOutbox()
        if (sent > 0) notice = "연결돼서 밀린 문자 ${sent}개를 보냈어요"
    }

    private fun onTextArrived(msg: JSONObject) {
        val id = msg.optInt("id")
        client.sendJson(JSONObject().put("type", "clip_ack").put("id", id))
        val from = msg.optString("from")
        val ts = msg.optLong("ts", System.currentTimeMillis() / 1000)
        val text = msg.optString("text")
        if (clips.any { it.from == from && it.ts == ts && it.text == text }) return
        val clip = Clip(id, from, msg.optString("mode"), ts, 0.0, null, text, mid = msg.optInt("mid"))
        addClip(clip)
        if (Prefs(app).dnd) return
        val speak = atHome || isParent
        if (speak) {
            Feedback.vibrate(app, longArrayOf(0, 120))
            if (speakText(from, text)) markSeen(clip)   // 소리로 읽어 줬으면 '봤음'
        } else {
            Feedback.vibrate(app, longArrayOf(0, 250, 150, 250))
        }
        Notifs.showClip(app, clip, autoPlayed = speak)
    }

    /** 집에 있는 폰은 받은 글을 소리로 읽어 줌 (무음·진동 모드면 읽지 않음). 읽었으면 true */
    private fun speakText(from: String, text: String): Boolean {
        val am = app.getSystemService(AudioManager::class.java)
        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL || !ttsReady || incomingFrom != null || talking) return false
        Feedback.incoming()
        tts?.speak("$from: $text", TextToSpeech.QUEUE_ADD, null, "msg${System.currentTimeMillis()}")
        return true
    }

    // ---- 읽음·들음 확인 ----

    /** 받은 메시지를 봤다/들었다고 보낸 사람에게 알림 (한 번만) */
    fun markSeen(clip: Clip) {
        if (clip.mine || clip.mid == 0 || clip.mid in seenSent) return
        seenSent.add(clip.mid)
        client.sendJson(JSONObject().put("type", "seen").put("mid", clip.mid))
    }

    /** 내가 보낸 메시지의 가족별 확인 표시: · 보냄, ✓ 받음, ✓✓ 봄(들음) */
    fun receiptText(clip: Clip): String {
        val st = receipts[clip.mid] ?: return ""
        return st.entries.joinToString("  ") { (n, v) -> n + when (v) { "seen" -> "✓✓"; "received" -> "✓"; else -> "·" } }
    }

    // ---- 예약 메시지 ----

    fun scheduleAdd(text: String, at: Long, repeat: String): Boolean =
        client.sendJson(JSONObject().put("type", "schedule_add").put("to", targetJson()).put("text", text)
            .put("at", at).put("repeat", repeat))

    fun scheduleDelete(sid: Int) { client.sendJson(JSONObject().put("type", "schedule_delete").put("sid", sid)) }
    fun scheduleRefresh() { client.sendJson(JSONObject().put("type", "schedule_list")) }

    private fun saveTextIndex() {
        val arr = JSONArray()
        clips.filter { it.isText || it.voiceSent }.take(120).forEach {
            arr.put(JSONObject().put("id", it.id).put("from", it.from).put("mode", it.mode)
                .put("ts", it.ts).put("text", it.text).put("mine", it.mine).put("mid", it.mid)
                .put("voice", it.voiceSent).put("seconds", it.seconds))
        }
        try { File(app.filesDir, "texts.json").writeText(arr.toString()) } catch (_: Exception) {}
    }

    private fun addClip(clip: Clip) {
        clips = (listOf(clip) + clips).sortedByDescending { it.ts }.take(MAX_CLIPS)
        clipDir().listFiles()?.forEach { f -> if (clips.none { it.file == f }) f.delete() }
        if (clip.isText || clip.voiceSent) saveTextIndex()
    }

    /** 서버가 보관한 문자 기록을 합침 (이미 있는 것은 mid로 건너뜀) → 폰·PC 어디서나 같은 대화 */
    private fun mergeHistory(items: JSONArray?) {
        if (items == null) return
        val have = clips.mapNotNull { if (it.mid != 0) it.mid else null }.toHashSet()
        val added = ArrayList<Clip>()
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            val mid = it.optInt("mid")
            if (mid == 0 || mid in have) continue
            val mine = it.optString("from") == me
            val to = it.optJSONArray("to").toList()
            val mode = it.optString("mode", "direct")
            added.add(Clip(mid, if (mine) (if (mode == "all") me else to.joinToString(", ")) else it.optString("from"), mode,
                it.optLong("ts"), 0.0, null, it.optString("text"), mine = mine, mid = mid))
            have.add(mid)
        }
        if (added.isEmpty()) return
        clips = (clips + added).sortedByDescending { it.ts }.take(MAX_CLIPS)
        saveTextIndex()
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
        val texts = try {
            val arr = JSONArray(File(app.filesDir, "texts.json").readText())
            (0 until arr.length()).map { arr.getJSONObject(it) }.map {
                Clip(it.optInt("id"), it.optString("from"), it.optString("mode"), it.optLong("ts"), it.optDouble("seconds", 0.0), null,
                    if (it.isNull("text")) null else it.optString("text"), it.optBoolean("mine"),
                    mid = it.optInt("mid"), voiceSent = it.optBoolean("voice"))
            }
        } catch (_: Exception) { emptyList() }
        val voices = clipDir().listFiles()?.mapNotNull { f ->
            // 파일 이름: id_from_mode_ts_seconds.pcm
            val p = f.nameWithoutExtension.split("_")
            if (p.size < 5) return@mapNotNull null
            Clip(p[0].toIntOrNull() ?: return@mapNotNull null, p[1], p[2],
                p[3].toLongOrNull() ?: 0L, p[4].toDoubleOrNull() ?: 0.0, f, mid = p.getOrNull(5)?.toIntOrNull() ?: 0)
        } ?: emptyList()
        clips = (texts + voices).sortedByDescending { it.ts }.take(MAX_CLIPS)
    }

    private fun onClipArrived(id: Int, pcm: ByteArray) {
        val meta = pendingClips.remove(id) ?: return
        client.sendJson(JSONObject().put("type", "clip_ack").put("id", id))
        val from = meta.optString("from")
        val mode = meta.optString("mode")
        val ts = meta.optLong("ts", System.currentTimeMillis() / 1000)
        val seconds = meta.optDouble("seconds", pcm.size / 32000.0)
        if (clips.any { it.from == from && it.ts == ts }) return   // 다른 기기에서 이미 받은 것
        val file = File(clipDir(), "${id}_${from}_${mode}_${ts}_${seconds}_${meta.optInt("mid")}.pcm")
        try { file.writeBytes(pcm) } catch (_: Exception) { return }
        val clip = Clip(id, from, mode, ts, seconds, file, mid = meta.optInt("mid"))
        addClip(clip)
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
        val f = clip.file
        if (f == null) {   // 문자 메시지는 읽어 줌
            if (ttsReady && clip.text != null) tts?.speak("${clip.from}: ${clip.text}", TextToSpeech.QUEUE_FLUSH, null, "msg${clip.id}")
            markSeen(clip)
            return
        }
        stopClip()
        val am = app.getSystemService(AudioManager::class.java)
        requestFocus(am)
        playingClip = clip
        markSeen(clip)
        Feedback.incoming()
        player.start()
        Thread {
            try {
                val pcm = f.readBytes()
                val t0 = System.nanoTime()
                var i = 0
                var n = 0L
                while (i < pcm.size && playingClip === clip) {
                    val end = minOf(i + AudioSpec.FRAME_BYTES, pcm.size)
                    player.feed(pcm.copyOfRange(i, end))
                    i = end
                    n++
                    // 실제 시간에 맞춰 20ms마다 한 프레임 (너무 빨리 넣으면 버퍼가 넘쳐 끊김)
                    val waitMs = (t0 + n * 20_000_000L - System.nanoTime()) / 1_000_000
                    if (waitMs > 0) Thread.sleep(waitMs)
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
        clip.file?.delete()
        clips = clips.filter { it !== clip }
        if (clip.isText || clip.voiceSent) saveTextIndex()
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

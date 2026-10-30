package com.woorizip.radio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : ComponentActivity() {

    private lateinit var prefs: Prefs
    private lateinit var statusText: TextView
    private lateinit var incomingText: TextView
    private lateinit var noticeText: TextView
    private lateinit var memberRow1: LinearLayout
    private lateinit var memberRow2: LinearLayout
    private lateinit var talkButton: TextView
    private lateinit var standbySwitch: Switch
    private lateinit var settingsBox: LinearLayout
    private lateinit var clipsBox: LinearLayout
    private lateinit var clipsList: LinearLayout
    private lateinit var input: EditText
    private lateinit var chipRow: LinearLayout
    private var chipSig = ""

    private fun renderChips() {
        val list = prefs.quickReplies
        val sig = list.joinToString("|")
        if (sig == chipSig) return
        chipSig = sig
        chipRow.removeAllViews()
        list.forEach { q ->
            chipRow.addView(TextView(this).apply {
                text = q; textSize = 14f; setTextColor(textMain); background = rounded(Color.parseColor("#334155"), 20)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) }
                setOnClickListener { if (Radio.sendText(q)) toast("보냈어요: $q") }
            })
        }
    }
    private val refresh: () -> Unit = { render() }
    private val h = Handler(Looper.getMainLooper())

    // 화면이 켜져 앱이 보일 때는 볼륨 버튼의 누름/뗌을 정확히 받을 수 있음
    private var volTalking = false
    private val volHoldStart = Runnable { volTalking = true; Radio.startTalk() }

    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        if (hasMic() && prefs.isConfigured) startStandby()
        else if (!hasMic()) toast("마이크 권한이 있어야 말할 수 있어요")
        render()
    }

    // ---------- 색 ----------
    private val bg = Color.parseColor("#0F172A")
    private val card = Color.parseColor("#1E293B")
    private val textMain = Color.parseColor("#F1F5F9")
    private val textSub = Color.parseColor("#94A3B8")
    private val accent = Color.parseColor("#F59E0B")
    private val green = Color.parseColor("#22C55E")
    private val red = Color.parseColor("#EF4444")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Radio.init(this)
        Notifs.createChannels(this)
        volumeControlStream = AudioManager.STREAM_MUSIC
        setContentView(buildUi())
        if (!prefs.isConfigured) settingsBox.visibility = View.VISIBLE
        else if (prefs.standby && !RadioService.running) ensurePermissionsThenStart()
    }

    override fun onStart() {
        super.onStart()
        Radio.uiVisible = true
        Radio.addListener(refresh)
        // 앱이 보이는 지금 서비스를 다시 올려 마이크 권한을 확실히 받아 둠 (백그라운드에서 시작됐으면 마이크가 무음이기 때문)
        if (RadioService.running && hasMic()) RadioService.start(this)
        render()
    }

    override fun onStop() {
        Radio.uiVisible = false
        Radio.removeListener(refresh)
        super.onStop()
    }

    // ---------- 화면 구성 ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int = 16) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }

    private fun label(text: String, size: Float = 15f, color: Int = textMain, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text; textSize = size; setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun lp(w: Int = LinearLayout.LayoutParams.MATCH_PARENT, h: Int = LinearLayout.LayoutParams.WRAP_CONTENT, top: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply { topMargin = dp(top) }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "" }
        root.addView(label("우리집 무전기  $ver", 26f, textMain, true))
        statusText = label("", 14f, textSub).also { root.addView(it, lp(top = 4)) }

        incomingText = label("", 17f, Color.BLACK, true).apply {
            background = rounded(accent); gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12)); visibility = View.GONE
        }
        root.addView(incomingText, lp(top = 16))

        root.addView(label("누구에게 말할까요?", 15f, textSub), lp(top = 20))
        memberRow1 = LinearLayout(this).also { root.addView(it, lp(top = 8)) }
        memberRow2 = LinearLayout(this).also { root.addView(it, lp(top = 8)) }

        talkButton = TextView(this).apply {
            gravity = Gravity.CENTER; textSize = 24f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { v.isPressed = true; Radio.startTalk() }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; Radio.stopTalk() }
                }
                true
            }
        }
        root.addView(talkButton, lp(h = dp(200), top = 20))

        noticeText = label("", 15f, accent).apply { gravity = Gravity.CENTER }
        root.addView(noticeText, lp(top = 10))

        // 글로 보내기 (말할 수 없을 때)
        val textBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = rounded(card)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        textBox.addView(label("말하기 어려우면 글로 보내기", 13f, textSub))
        val chips = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        chipRow = LinearLayout(this)
        renderChips()
        chips.addView(chipRow)
        textBox.addView(chips, lp(top = 8))
        val inputRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        input = EditText(this).apply {
            hint = "직접 입력"; setTextColor(textMain); setHintTextColor(textSub); maxLines = 2
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val send = Button(this).apply {
            text = "보내기"
            setOnClickListener {
                val t = input.text.toString()
                if (t.isBlank()) return@setOnClickListener
                if (Radio.sendText(t)) { input.setText(""); toast("보냈어요") }
            }
        }
        inputRow.addView(input); inputRow.addView(send)
        textBox.addView(inputRow, lp(top = 6))
        root.addView(textBox, lp(top = 12))

        // 받은 메시지 (밖에 있을 때 / 연결이 끊겼을 때 온 말)
        clipsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = rounded(card)
            setPadding(dp(16), dp(12), dp(16), dp(12)); visibility = View.GONE
        }
        clipsBox.addView(label("받은 메시지", 15f, textSub))
        clipsList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        clipsBox.addView(clipsList, lp(top = 6))
        root.addView(clipsBox, lp(top = 16))

        // 스위치들
        val switches = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = rounded(card)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        standbySwitch = Switch(this).apply {
            text = "수신 대기 (화면 꺼져도 듣기·볼륨키로 말하기)"; setTextColor(textMain); textSize = 15f
            setPadding(0, dp(8), 0, dp(8))
            setOnClickListener {
                if (isChecked) ensurePermissionsThenStart() else { prefs.standby = false; RadioService.stop(this@MainActivity) }
                h.postDelayed({ render() }, 300)
            }
        }
        switches.addView(standbySwitch)
        root.addView(switches, lp(top = 20))

        val battery = Button(this).apply {
            text = "배터리 최적화 제외 (수신이 끊기면 눌러 주세요)"
            setOnClickListener { requestBatteryExemption() }
        }
        root.addView(battery, lp(top = 12))

        val toggleSettings = Button(this).apply {
            text = "설정"
            setOnClickListener {
                settingsBox.visibility = if (settingsBox.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }
        root.addView(toggleSettings, lp(top = 4))

        settingsBox = buildSettings().apply { visibility = View.GONE }
        root.addView(settingsBox, lp(top = 8))

        val scroll = ScrollView(this).apply { setBackgroundColor(bg); addView(root) }
        // 안드로이드 15: 화면이 상태바·내비게이션바 아래까지 그려지므로 그만큼 여백 추가
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        return scroll
    }

    private fun field(parent: LinearLayout, title: String, value: String, type: Int): EditText {
        parent.addView(label(title, 13f, textSub), lp(top = 10))
        return EditText(this).apply {
            setText(value); inputType = type; setTextColor(textMain); setHintTextColor(textSub)
            parent.addView(this, lp())
        }
    }

    private fun buildSettings(): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = rounded(card)
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }
        val host = field(box, "집 주소 — NAS의 집 안 주소 (예: 192.168.0.10)", prefs.host, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val outHost = field(box, "밖 주소 — Tailscale 주소(예: 100.101.102.103) 또는 DDNS 주소. 비우면 집에서만", prefs.outHost, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        // 누구의 기기인지: 타이핑 대신 버튼으로 고름
        box.addView(label("이 폰은 누구 것인가요? (고르고 아래 '저장하고 연결'만 누르면 돼요)", 13f, textSub), lp(top = 14))
        val whoGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val names = Radio.members.ifEmpty { listOf("엄마", "아빠", "첫째", "둘째", "셋째") }
        val whoButtons = names.map { n ->
            RadioButton(this).apply { text = n; setTextColor(textMain); id = View.generateViewId(); textSize = 15f }
        }
        whoButtons.forEach { whoGroup.addView(it) }
        whoButtons.firstOrNull { it.text == prefs.name }?.let { whoGroup.check(it.id) }
        box.addView(whoGroup)
        val key = field(box, "가족 암호 (NAS 설정과 같게)", prefs.key, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val quick = field(box, "빠른 답장 문구 (쉼표로 구분)", prefs.quickReplies.joinToString(", "), InputType.TYPE_CLASS_TEXT)

        box.addView(label("화면 꺼짐 상태 볼륨 버튼", 13f, textSub), lp(top = 14))
        val modes = RadioGroup(this)
        val hold = RadioButton(this).apply { text = "길게 누르는 동안 말하기"; setTextColor(textMain); id = View.generateViewId() }
        val dbl = RadioButton(this).apply { text = "두 번 눌러 켜기 / 두 번 눌러 끄기"; setTextColor(textMain); id = View.generateViewId() }
        modes.addView(hold); modes.addView(dbl)
        modes.check(if (prefs.keyMode == "double") dbl.id else hold.id)
        box.addView(modes)
        box.addView(label("길게 누르기가 화면 꺼진 상태에서 잘 안 되는 기종이면 두 번 누르기로 바꿔 주세요.", 12f, textSub))

        box.addView(Button(this).apply {
            text = "저장하고 연결"
            setOnClickListener {
                prefs.host = host.text.toString()
                prefs.outHost = outHost.text.toString()
                prefs.name = whoButtons.firstOrNull { it.id == whoGroup.checkedRadioButtonId }?.text?.toString() ?: ""
                if (prefs.name.isBlank()) { toast("이 폰이 누구 것인지 골라 주세요"); return@setOnClickListener }
                prefs.key = key.text.toString()
                prefs.quickReplies = quick.text.toString().split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    .ifEmpty { Prefs.DEFAULT_QUICK.split(",") }
                renderChips()
                prefs.keyMode = if (modes.checkedRadioButtonId == dbl.id) "double" else "hold"
                if (!prefs.isConfigured) { toast("집 주소와 가족 암호를 입력하세요"); return@setOnClickListener }
                settingsBox.visibility = View.GONE
                if (RadioService.running) Radio.reconnect() else ensurePermissionsThenStart()
                render()
            }
        }, lp(top = 12))
        return box
    }

    private fun memberButton(text: String, value: String, isOnline: Boolean): TextView {
        val selected = Radio.target == value
        val place = when {
            value == "all" || !isOnline -> ""
            Radio.memberWhere[value] == "out" -> "\n밖"
            else -> "\n집"
        }
        return TextView(this).apply {
            this.text = if (value == "all") text else "${if (isOnline) "●" else "○"} $text$place"
            gravity = Gravity.CENTER; textSize = 16f; typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(0f, 0.9f)
            setTextColor(if (selected) Color.BLACK else if (isOnline) textMain else textSub)
            background = rounded(if (selected) accent else card, 12)
            setPadding(0, dp(14), 0, dp(14))
            setOnClickListener { if (!Radio.talking) Radio.target = value }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(4); marginEnd = dp(4)
            }
        }
    }

    // ---------- 화면 갱신 ----------

    private fun render() {
        val me = prefs.name
        val others = Radio.members.filter { it != me }
        val buttons = listOf(memberButton("전체", "all", true)) +
            others.map { memberButton(it, it, Radio.online.contains(it)) }
        memberRow1.removeAllViews(); memberRow2.removeAllViews()
        buttons.forEachIndexed { i, b -> (if (i < 3) memberRow1 else memberRow2).addView(b) }
        // 선택했던 사람이 목록에 없으면 전체로
        if (Radio.target != "all" && Radio.target !in others) Radio.target = "all"

        val online = Radio.online.size
        val place = if (Radio.atHome) "집" else "밖"
        statusText.text = if (Radio.connected) "● $me · 지금 $place · 가족 ${online}명 접속 중" else Radio.status
        statusText.setTextColor(if (Radio.connected) green else textSub)
        renderClips()

        val from = Radio.incomingFrom
        if (from != null) {
            incomingText.visibility = View.VISIBLE
            incomingText.text = if (Radio.incomingMode == "all") "🔊 ${from}님 전체 방송 중" else "🔊 ${from}님이 나에게 말하는 중"
        } else incomingText.visibility = View.GONE

        val who = if (Radio.target == "all") "전체" else Radio.target
        when {
            Radio.talking -> { talkButton.text = "🎙 말하는 중…\n손을 떼면 끝"; talkButton.background = rounded(red, 24) }
            Radio.requesting -> { talkButton.text = "잠깐만요…"; talkButton.background = rounded(Color.parseColor("#B45309"), 24) }
            !Radio.connected -> { talkButton.text = "연결 대기 중"; talkButton.background = rounded(Color.parseColor("#334155"), 24) }
            else -> { talkButton.text = "누르고 말하기\n($who)"; talkButton.background = rounded(Color.parseColor("#2563EB"), 24) }
        }
        noticeText.text = Radio.notice
        standbySwitch.isChecked = RadioService.running
    }

    private fun renderClips() {
        val clips = Radio.clips
        clipsBox.visibility = if (clips.isEmpty()) View.GONE else View.VISIBLE
        clipsList.removeAllViews()
        val fmt = java.text.SimpleDateFormat("M/d HH:mm", java.util.Locale.KOREA)
        clips.take(10).forEach { c ->
            val playing = Radio.playingClip === c
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val when_ = fmt.format(java.util.Date(c.ts * 1000))
            val desc = if (c.isText) "${c.title}\n💬 ${c.text}\n$when_"
                       else "${if (playing) "🔊 " else ""}${c.title}\n$when_ · ${c.seconds.toInt().coerceAtLeast(1)}초"
            val info = label(desc, 14f, if (c.mine) textSub else textMain)
            info.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            row.addView(info)
            if (!c.isText) row.addView(Button(this).apply {
                text = if (playing) "정지" else "듣기"
                setOnClickListener { if (playing) Radio.stopClip() else Radio.playClip(c) }
            })
            if (!c.mine) row.addView(Button(this).apply {
                text = "답장"
                setOnClickListener { Radio.target = c.from; input.requestFocus(); toast("${c.from}님에게 보내기로 바뀌었어요") }
            })
            row.addView(Button(this).apply { text = "삭제"; setOnClickListener { Radio.deleteClip(c) } })
            clipsList.addView(row, lp(top = 4))
        }
    }

    // ---------- 볼륨 버튼 (앱이 화면에 보일 때) ----------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if ((code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) || !Radio.connected)
            return super.dispatchKeyEvent(event)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                volTalking = false
                h.postDelayed(volHoldStart, 500)
            }
            KeyEvent.ACTION_UP -> {
                h.removeCallbacks(volHoldStart)
                if (volTalking) {
                    volTalking = false
                    Radio.stopTalk()
                } else {
                    // 짧게 눌렀으면 원래 음량 조절
                    getSystemService(AudioManager::class.java).adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        if (code == KeyEvent.KEYCODE_VOLUME_UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                        AudioManager.FLAG_SHOW_UI
                    )
                }
            }
        }
        return true
    }

    // ---------- 권한·서비스 ----------

    private fun hasMic() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun ensurePermissionsThenStart() {
        if (!prefs.isConfigured) {
            settingsBox.visibility = View.VISIBLE
            toast("먼저 설정을 입력해 주세요")
            render()
            return
        }
        val need = mutableListOf<String>()
        if (!hasMic()) need += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) need += Manifest.permission.POST_NOTIFICATIONS
        if (need.isEmpty()) startStandby() else askPerms.launch(need.toTypedArray())
    }

    private fun startStandby() {
        RadioService.start(this)
        h.postDelayed({ render() }, 300)
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("이미 배터리 최적화에서 제외되어 있어요")
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

package com.woorizip.radio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
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
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 우리집 — 메인 화면.
 * 어두운 남색 바탕에 별 아이콘과 같은 앰버 포인트. 둥근 카드, 알약 모양 가족 버튼, 큰 말하기 버튼.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private val h = Handler(Looper.getMainLooper())
    private val refresh: () -> Unit = { render() }
    private val fmt = SimpleDateFormat("M/d HH:mm", Locale.KOREA)
    private val fmtTime = SimpleDateFormat("HH:mm", Locale.KOREA)
    private var lastNewestKey: String? = null

    // ---------- 팔레트 ----------
    private val cBg = Color.parseColor("#0B1220")
    private val cCard = Color.parseColor("#141C2E")
    private val cCard2 = Color.parseColor("#1C2740")
    private val cText = Color.parseColor("#F3F5F9")
    private val cSub = Color.parseColor("#9AA6BC")
    private val cAmber = Color.parseColor("#F5B301")
    private val cAmberDark = Color.parseColor("#C98F00")
    private val cOnAmber = Color.parseColor("#1A1200")
    private val cGreen = Color.parseColor("#34D399")
    private val cRed = Color.parseColor("#F26D6D")
    private val cBlue = Color.parseColor("#8FB3FF")
    private val cOutline = Color.parseColor("#2B3752")

    // ---------- 화면 요소 ----------
    private lateinit var root: LinearLayout
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var incomingCard: MaterialCardView
    private lateinit var incomingText: TextView
    private lateinit var memberGroup: ChipGroup
    private lateinit var talkCard: MaterialCardView
    private lateinit var talkTitle: TextView
    private lateinit var talkSub: TextView
    private lateinit var noticeText: TextView
    private lateinit var quickRow: LinearLayout
    private lateinit var input: TextInputEditText
    private lateinit var msgCard: MaterialCardView
    private lateinit var msgHead: TextView
    private lateinit var msgList: LinearLayout
    private lateinit var standbySwitch: MaterialSwitch
    private lateinit var settingsBox: LinearLayout
    private var quickSig = ""
    private var memberSig = ""
    private var msgSig = ""
    private var stickBottom = false
    private lateinit var scrollRoot: NestedScrollView
    private val dayNames = arrayOf("일", "월", "화", "수", "목", "금", "토")

    private var volTalking = false
    private val volHoldStart = Runnable { volTalking = true; Radio.startTalk() }

    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        if (hasMic() && prefs.isConfigured) startStandby()
        else if (!hasMic()) toast("마이크 권한이 있어야 말할 수 있어요")
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Radio.init(this)
        Notifs.createChannels(this)
        volumeControlStream = AudioManager.STREAM_MUSIC
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(buildUi())
        if (!prefs.isConfigured) settingsBox.visibility = View.VISIBLE
        else if (prefs.standby && !RadioService.running) ensurePermissionsThenStart()
        showCrashReportIfAny()
    }

    /** 지난번에 앱이 멈췄으면 원인을 보여 줌 (스크린샷으로 보내 주면 고칠 수 있음) */
    private fun showCrashReportIfAny() {
        val report = WoorizipApp.takeCrashReport(this) ?: return
        val tv = TextView(this).apply {
            text = report.take(2500)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val scroll = NestedScrollView(this).apply { addView(tv) }
        MaterialAlertDialogBuilder(this)
            .setTitle("지난번에 앱이 멈췄어요")
            .setMessage("아래 내용을 화면 캡처해서 보내 주시면 고칠 수 있어요.")
            .setView(scroll)
            .setPositiveButton("닫기", null)
            .setNeutralButton("복사") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("우리집 오류", report))
                toast("복사했어요")
            }
            .show()
    }

    override fun onStart() {
        super.onStart()
        Radio.uiVisible = true
        Radio.addListener(refresh)
        if (RadioService.running && hasMic()) RadioService.start(this)   // 보이는 동안 마이크 권한 확실히
        render()
    }

    override fun onStop() {
        Radio.uiVisible = false
        Radio.removeListener(refresh)
        super.onStop()
    }

    // ---------- 작은 도우미 ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Int) = v * resources.displayMetrics.density

    private fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
                   top: Int = 0, bottom: Int = 0, start: Int = 0, end: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply { topMargin = dp(top); bottomMargin = dp(bottom); marginStart = dp(start); marginEnd = dp(end) }

    private fun label(text: CharSequence, size: Float = 15f, color: Int = cText, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text; textSize = size; setTextColor(color)
            typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
            setLineSpacing(0f, 1.15f)
        }

    private fun card(bg: Int = cCard, radius: Int = 22, pad: Int = 18): Pair<MaterialCardView, LinearLayout> {
        val c = MaterialCardView(this).apply {
            setCardBackgroundColor(bg); this.radius = dpf(radius); cardElevation = 0f
            strokeWidth = dp(1); strokeColor = cOutline
        }
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(pad), dp(pad), dp(pad), dp(pad)) }
        c.addView(inner)
        return c to inner
    }

    private fun sectionTitle(text: String) = label(text, 13f, cSub, true).apply { letterSpacing = 0.04f }

    private fun pill(text: String, filled: Boolean, onClick: () -> Unit) = MaterialButton(
        this, null, if (filled) com.google.android.material.R.attr.materialButtonStyle else com.google.android.material.R.attr.materialButtonOutlinedStyle
    ).apply {
        this.text = text; isAllCaps = false; textSize = 14f; cornerRadius = dp(14)
        if (filled) { backgroundTintList = ColorStateList.valueOf(cAmber); setTextColor(cOnAmber) }
        else { strokeColor = ColorStateList.valueOf(cOutline); setTextColor(cText) }
        setOnClickListener { onClick() }
    }

    private fun textButton(text: String, color: Int = cBlue, onClick: () -> Unit) = MaterialButton(
        this, null, androidx.appcompat.R.attr.borderlessButtonStyle
    ).apply {
        this.text = text; isAllCaps = false; textSize = 13f; setTextColor(color)
        minWidth = 0; minimumWidth = 0; setPadding(dp(10), 0, dp(10), 0)
        setOnClickListener { onClick() }
    }

    private fun inputField(hint: String, value: String = "", type: Int = InputType.TYPE_CLASS_TEXT): Pair<TextInputLayout, TextInputEditText> {
        val til = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            this.hint = hint; boxStrokeColor = cAmber; setBoxCornerRadii(dpf(14), dpf(14), dpf(14), dpf(14))
            hintTextColor = ColorStateList.valueOf(cAmber); defaultHintTextColor = ColorStateList.valueOf(cSub)
        }
        val et = TextInputEditText(til.context).apply { setText(value); inputType = type; setTextColor(cText); textSize = 15f }
        til.addView(et)
        return til to et
    }

    // ---------- 화면 구성 ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi(): View {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(28)) }

        // 머리글: 별 + 우리집 + 버전
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (_: Exception) { "" }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(label("우리집", 26f, cText, true))
        val statusRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        statusDot = View(this).apply { background = circle(cSub) }
        statusRow.addView(statusDot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(6) })
        statusText = label("", 13f, cSub)
        statusRow.addView(statusText)
        titles.addView(statusRow, lp(top = 2))
        head.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(label(ver, 12f, cSub))
        root.addView(head)

        // 지금 말하는 사람 배너
        incomingCard = MaterialCardView(this).apply {
            setCardBackgroundColor(cAmber); radius = dpf(18); cardElevation = 0f; visibility = View.GONE
        }
        incomingText = label("", 16f, cOnAmber, true).apply { gravity = Gravity.CENTER; setPadding(dp(14), dp(14), dp(14), dp(14)) }
        incomingCard.addView(incomingText)
        root.addView(incomingCard, lp(top = 16))

        // 누구에게
        root.addView(sectionTitle("대화방  ·  누르면 그 방  ·  길게 누르면 몇 명만 묶기"), lp(top = 22))
        memberGroup = ChipGroup(this).apply { chipSpacingHorizontal = dp(8); chipSpacingVertical = dp(6) }
        root.addView(memberGroup, lp(top = 8))

        // 말하기 버튼
        talkCard = MaterialCardView(this).apply {
            radius = dpf(30); cardElevation = dpf(6); setCardBackgroundColor(cAmber)
            isClickable = true
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { v.isPressed = true; Radio.startTalk() }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; Radio.stopTalk() }
                }
                true
            }
        }
        val talkInner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(16), dp(34), dp(16), dp(34)) }
        talkTitle = label("", 24f, cOnAmber, true)
        talkSub = label("", 14f, cOnAmber).apply { alpha = 0.8f }
        talkInner.addView(talkTitle); talkInner.addView(talkSub, lp(w = ViewGroup.LayoutParams.WRAP_CONTENT, top = 4))
        talkCard.addView(talkInner)
        root.addView(talkCard, lp(top = 18))
        noticeText = label("", 14f, cAmber).apply { gravity = Gravity.CENTER }
        root.addView(noticeText, lp(top = 10))

        // 대화 카드: 말풍선 목록(최신이 아래) + 빠른 답장 + 입력칸
        val (mc, mi) = card(pad = 14)
        msgCard = mc
        msgHead = sectionTitle("대화").apply { setPadding(dp(4), 0, 0, 0) }
        mi.addView(msgHead)
        msgList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        mi.addView(msgList, lp(top = 6))
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        quickRow = LinearLayout(this)
        scroll.addView(quickRow)
        mi.addView(scroll, lp(top = 12))
        val inputRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val (til, et) = inputField("메시지 입력")
        input = et
        inputRow.addView(til, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        inputRow.addView(pill("보내기", true) { val t = input.text.toString(); if (t.isNotBlank() && Radio.sendText(t)) { input.setText(""); stickBottom = true } },
            lp(w = ViewGroup.LayoutParams.WRAP_CONTENT, start = 8))
        inputRow.addView(pill("⏰", false) { openScheduleDialog() }, lp(w = ViewGroup.LayoutParams.WRAP_CONTENT, start = 6))
        mi.addView(inputRow, lp(top = 8))
        root.addView(msgCard, lp(top = 14))

        // 수신 대기 / 배터리 / 설정
        val (ctrlCard, ctrl) = card(pad = 14)
        standbySwitch = MaterialSwitch(this).apply {
            text = "수신 대기 — 화면이 꺼져도 듣고, 볼륨키로 말하기"; setTextColor(cText); textSize = 14f
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setOnClickListener {
                if (isChecked) ensurePermissionsThenStart() else { prefs.standby = false; RadioService.stop(this@MainActivity) }
                h.postDelayed({ render() }, 300)
            }
        }
        ctrl.addView(standbySwitch)
        val ctrlBtns = LinearLayout(this)
        ctrlBtns.addView(textButton("배터리 최적화 제외") { requestBatteryExemption() })
        ctrlBtns.addView(textButton("설정") { toggleSettings() })
        ctrl.addView(ctrlBtns, lp(top = 4))
        root.addView(ctrlCard, lp(top = 14))

        settingsBox = buildSettings().apply { visibility = View.GONE }
        root.addView(settingsBox, lp(top = 14))

        scrollRoot = NestedScrollView(this).apply { setBackgroundColor(cBg); addView(root); isFillViewport = true }
        ViewCompat.setOnApplyWindowInsetsListener(scrollRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        return scrollRoot
    }

    private fun circle(color: Int) = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(color) }
    private fun roundRect(color: Int, radiusDp: Int) = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dpf(radiusDp); setColor(color) }

    private fun toggleSettings() {
        if (settingsBox.visibility == View.VISIBLE) { settingsBox.visibility = View.GONE; return }
        val idx = root.indexOfChild(settingsBox)
        root.removeView(settingsBox)
        settingsBox = buildSettings()
        root.addView(settingsBox, idx, lp(top = 14))
    }

    // ---------- 가족 칩 / 빠른 답장 칩 ----------

    private fun roomChip(room: Room, selected: Boolean, unread: Int): Chip {
        val m = room.members
        val base = if (m == null) "${Radio.online.size}명 접속"
            else if (m.size == 1) (if (m[0] in Radio.online) (if (Radio.memberWhere[m[0]] == "out") "밖" else "집") else "꺼짐")
            else "${m.size}명"
        val sub = base + (if (unread > 0) " · 새 글 $unread" else "")
        val dim = m != null && m.size == 1 && m[0] !in Radio.online
        return Chip(this).apply {
            text = "${room.icon} ${room.label} · $sub"
            isCheckable = true; isChecked = selected; isCheckedIconVisible = false
            textSize = 14f
            chipCornerRadius = dpf(18)
            chipStrokeWidth = dpf(1)
            chipStrokeColor = ColorStateList.valueOf(if (selected) room.color else cOutline)
            chipBackgroundColor = ColorStateList.valueOf(if (selected) room.color else cCard)
            setTextColor(if (selected) Color.parseColor("#101418") else if (dim) cSub else room.color)
            setEnsureMinTouchTargetSize(false)
            setOnClickListener { Radio.selectRoom(room.key); render() }
            if (m != null && m.size == 1) setOnLongClickListener { Radio.toggleTarget(m[0]); render(); true }   // 몇 명만 묶기
        }
    }

    private fun renderMembers() {
        val me = prefs.name
        val others = Radio.members.filter { it != me }
        if (Radio.target != "all") {
            val kept = prefs.targetList.filter { it in others }
            if (kept.size != prefs.targetList.size) prefs.target = if (kept.isEmpty()) "all" else kept.joinToString(",")
        }
        val rooms = Radio.rooms()
        val cur = Radio.currentRoom()
        val unread = rooms.associate { it.key to Radio.unreadCount(it) }
        val sig = listOf(rooms.joinToString { it.key }, Radio.online.joinToString(), Radio.target, Radio.memberWhere.toString(), unread.toString()).joinToString("|")
        if (sig == memberSig) return
        memberSig = sig
        memberGroup.removeAllViews()
        rooms.forEach { memberGroup.addView(roomChip(it, it.key == cur.key, unread[it.key] ?: 0)) }
    }

    private fun renderQuick() {
        val list = prefs.quickReplies
        val sig = list.joinToString("|")
        if (sig == quickSig) return
        quickSig = sig
        quickRow.removeAllViews()
        list.forEach { q ->
            quickRow.addView(Chip(this).apply {
                text = q; textSize = 13f; chipCornerRadius = dpf(16)
                chipBackgroundColor = ColorStateList.valueOf(cCard2); setTextColor(cText)
                chipStrokeWidth = dpf(1); chipStrokeColor = ColorStateList.valueOf(cOutline)
                setEnsureMinTouchTargetSize(false)
                setOnClickListener { if (Radio.sendText(q)) toast("보냈어요: $q") }
            }, lp(w = ViewGroup.LayoutParams.WRAP_CONTENT, end = 8))
        }
    }

    // ---------- 설정 ----------

    private fun whoPicker(parent: LinearLayout, title: String): ChipGroup {
        parent.addView(sectionTitle(title), lp(top = 6))
        val names = Radio.members.ifEmpty { listOf("엄마", "아빠", "첫째", "둘째", "셋째") }
        val group = ChipGroup(this).apply { isSingleSelection = true; chipSpacingHorizontal = dp(8) }
        names.forEach { n ->
            group.addView(Chip(this).apply {
                text = n; isCheckable = true; isChecked = (n == prefs.name); textSize = 15f; tag = n
                chipCornerRadius = dpf(18); isCheckedIconVisible = false
                chipBackgroundColor = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(cAmber, cCard2))
                setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(cOnAmber, cText)))
                setEnsureMinTouchTargetSize(false)
            })
        }
        parent.addView(group, lp(top = 8))
        return group
    }

    private fun pickedName(group: ChipGroup): String =
        (0 until group.childCount).map { group.getChildAt(it) as Chip }.firstOrNull { it.isChecked }?.tag?.toString() ?: ""

    private fun buildSettings(): LinearLayout {
        val (cardV, box) = card()
        val wrapper = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrapper.addView(cardV)

        // 처음 실행: 누구 것인지만
        val firstRun = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val who0 = whoPicker(firstRun, "이 폰은 누구 것인가요?")
        firstRun.addView(pill("시작", true) {
            val n = pickedName(who0)
            if (n.isBlank()) { toast("누구 것인지 골라 주세요"); return@pill }
            prefs.name = n
            settingsBox.visibility = View.GONE
            ensurePermissionsThenStart()
            render()
        }, lp(top = 14))
        box.addView(firstRun)

        // 평소 설정
        val normal = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        normal.addView(label("이 폰: ${prefs.name}", 16f, cText, true))
        val (quickTil, quick) = inputField("빠른 답장 문구 (쉼표로 구분)", prefs.quickReplies.joinToString(", "))
        normal.addView(quickTil, lp(top = 12))
        val (groupsTil, groupsEt) = inputField("대화방 묶음 (이름=가족,가족 · ;로 구분)", prefs.groups)
        normal.addView(groupsTil, lp(top = 10))
        normal.addView(label("예: 아이들=첫째,둘째,셋째;부모님=엄마,아빠;큰애들=첫째,둘째", 12f, cSub), lp(top = 4))
        normal.addView(sectionTitle("화면 꺼짐 상태 볼륨 버튼"), lp(top = 14))
        val modes = MaterialButtonToggleGroup(this).apply { isSingleSelection = true; isSelectionRequired = true }
        val hold = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { text = "길게 누르는 동안"; isAllCaps = false; id = View.generateViewId() }
        val dbl = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { text = "두 번 눌러 켜고 끄기"; isAllCaps = false; id = View.generateViewId() }
        modes.addView(hold); modes.addView(dbl)
        modes.check(if (prefs.keyMode == "double") dbl.id else hold.id)
        normal.addView(modes, lp(top = 6))
        normal.addView(label("길게 누르기가 화면 꺼진 상태에서 잘 안 되는 기종이면 두 번 누르기로 바꿔 주세요.", 12f, cSub), lp(top = 4))
        normal.addView(pill("저장", true) {
            prefs.quickReplies = quick.text.toString().split(",").map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { Prefs.DEFAULT_QUICK.split(",") }
            prefs.groups = groupsEt.text.toString().ifBlank { Prefs.DEFAULT_GROUPS }
            memberSig = ""
            prefs.keyMode = if (modes.checkedButtonId == dbl.id) "double" else "hold"
            renderQuick()
            settingsBox.visibility = View.GONE
            toast("저장했어요")
        }, lp(top = 14))

        // 관리자: 암호 필요
        val admin = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val who = whoPicker(admin, "이 폰은 누구 것인가요? (바꾸면 바로 적용)")
        val (hostTil, host) = inputField("집 주소", prefs.host, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val (outTil, outHost) = inputField("밖 주소 (Tailscale)", prefs.outHost, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val (keyTil, key) = inputField("가족 암호", prefs.key)
        admin.addView(hostTil, lp(top = 10)); admin.addView(outTil, lp(top = 10)); admin.addView(keyTil, lp(top = 10))
        admin.addView(pill("변경 저장하고 다시 연결", true) {
            val n = pickedName(who)
            if (n.isBlank() || host.text.isNullOrBlank() || key.text.isNullOrBlank()) { toast("사용자·집 주소·암호는 비울 수 없어요"); return@pill }
            prefs.name = n; prefs.host = host.text.toString(); prefs.outHost = outHost.text.toString(); prefs.key = key.text.toString()
            settingsBox.visibility = View.GONE
            if (RadioService.running) Radio.reconnect() else ensurePermissionsThenStart()
            render()
        }, lp(top = 12))
        normal.addView(textButton("지금 업데이트 확인", cSub) {
            toast("확인 중…")
            Updater.checkNow(this) { r -> runOnUiThread { toast(r) } }
        }, lp(top = 4))
        normal.addView(textButton("사용자·주소 변경 (가족 암호 필요)", cSub) {
            val pw = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD; hint = "가족 암호" }
            val wrap = FrameLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(pw) }
            MaterialAlertDialogBuilder(this).setTitle("가족 암호를 입력하세요").setView(wrap)
                .setPositiveButton("확인") { _, _ -> if (pw.text.toString() == prefs.key) admin.visibility = View.VISIBLE else toast("암호가 달라요") }
                .setNegativeButton("취소", null).show()
        }, lp(top = 10))
        normal.addView(admin)
        box.addView(normal)

        firstRun.visibility = if (prefs.name.isBlank()) View.VISIBLE else View.GONE
        normal.visibility = if (prefs.name.isBlank()) View.GONE else View.VISIBLE
        return wrapper
    }

    // ---------- 예약 메시지 ----------

    private fun openScheduleDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0) }
        box.addView(label("받는 사람: ${Radio.targetLabel()}", 14f, cSub))
        val (tTil, tEt) = inputField("내용", input.text?.toString()?.trim() ?: "")
        box.addView(tTil, lp(top = 10))
        val cal = Calendar.getInstance().apply { add(Calendar.MINUTE, 10); set(Calendar.MINUTE, (get(Calendar.MINUTE) / 5) * 5) }
        var hour = cal.get(Calendar.HOUR_OF_DAY); var minute = cal.get(Calendar.MINUTE)
        val timeBtn = pill(String.format(Locale.KOREA, "시간  %02d:%02d", hour, minute), false) {}
        timeBtn.setOnClickListener {
            val picker = MaterialTimePicker.Builder().setTimeFormat(TimeFormat.CLOCK_24H).setHour(hour).setMinute(minute).setTitleText("보낼 시간").build()
            picker.addOnPositiveButtonClickListener {
                hour = picker.hour; minute = picker.minute
                timeBtn.text = String.format(Locale.KOREA, "시간  %02d:%02d", hour, minute)
            }
            picker.show(supportFragmentManager, "time")
        }
        box.addView(timeBtn, lp(top = 10))
        val dayGroup = MaterialButtonToggleGroup(this).apply { isSingleSelection = true; isSelectionRequired = true }
        val today = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { text = "오늘"; isAllCaps = false; id = View.generateViewId() }
        val tomorrow = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { text = "내일"; isAllCaps = false; id = View.generateViewId() }
        dayGroup.addView(today); dayGroup.addView(tomorrow); dayGroup.check(today.id)
        box.addView(dayGroup, lp(top = 10))
        box.addView(sectionTitle("반복"), lp(top = 12))
        val repGroup = ChipGroup(this).apply { isSingleSelection = true; isSelectionRequired = true; chipSpacingHorizontal = dp(6) }
        val reps = listOf("once" to "한 번", "daily" to "매일", "weekdays" to "평일", "weekends" to "주말")
        reps.forEach { (v, l) ->
            repGroup.addView(Chip(this).apply {
                text = l; tag = v; isCheckable = true; isChecked = v == "once"; isCheckedIconVisible = false
                chipBackgroundColor = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(cAmber, cCard2))
                setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(cOnAmber, cText)))
                setEnsureMinTouchTargetSize(false)
            })
        }
        box.addView(repGroup, lp(top = 4))
        val listTitle = sectionTitle("내 예약 목록")
        box.addView(listTitle, lp(top = 14))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(list, lp(top = 4))

        fun renderList() {
            list.removeAllViews()
            val items = Radio.schedules
            if (items.isEmpty()) { list.addView(label("예약이 없어요", 13f, cSub)); return }
            val repLabel = mapOf("once" to "한 번", "daily" to "매일", "weekdays" to "평일", "weekends" to "주말")
            items.forEach { it ->
                val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
                val to = if (it.to == "all") "모두" else it.to
                row.addView(label("${fmt.format(Date(it.at * 1000))} · ${repLabel[it.repeat] ?: ""} · $to\n${it.text}", 13f, cText),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(textButton("삭제", cRed) { Radio.scheduleDelete(it.sid) })
                list.addView(row, lp(top = 4))
            }
        }
        renderList()
        val listener: () -> Unit = { renderList(); if (Radio.scheduleNotice.isNotBlank()) toast(Radio.scheduleNotice) }
        Radio.addListener(listener)
        Radio.scheduleRefresh()

        val scroll = NestedScrollView(this).apply { addView(box) }
        val dlg = MaterialAlertDialogBuilder(this).setTitle("⏰ 예약 메시지").setView(scroll)
            .setPositiveButton("예약 추가", null).setNegativeButton("닫기", null)
            .setOnDismissListener { Radio.removeListener(listener) }
            .create()
        dlg.setOnShowListener {
            dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = tEt.text?.toString()?.trim() ?: ""
                if (text.isBlank()) { toast("내용을 입력하세요"); return@setOnClickListener }
                val c = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                    if (dayGroup.checkedButtonId == tomorrow.id) add(Calendar.DAY_OF_MONTH, 1)
                }
                val repeat = (0 until repGroup.childCount).map { repGroup.getChildAt(it) as Chip }.firstOrNull { it.isChecked }?.tag?.toString() ?: "once"
                if (repeat == "once" && c.timeInMillis < System.currentTimeMillis() - 60_000) { toast("이미 지난 시간이에요. '내일'을 고르세요"); return@setOnClickListener }
                if (!Radio.scheduleAdd(text, c.timeInMillis / 1000, repeat)) { toast("지금 연결이 없어요. 연결된 뒤 다시 시도하세요"); return@setOnClickListener }
                tEt.setText("")
                toast("예약했어요")
            }
        }
        dlg.show()
    }

    // ---------- 화면 갱신 ----------

    private fun render() {
        renderMembers()
        renderQuick()
        renderMessages()

        val me = prefs.name
        val place = if (Radio.atHome) "집" else "밖"
        if (Radio.connected) {
            statusText.text = "$me · 지금 $place · 가족 ${Radio.online.size}명 접속 중"
            statusText.setTextColor(cGreen); statusDot.background = circle(cGreen)
        } else {
            statusText.text = Radio.status
            statusText.setTextColor(cSub); statusDot.background = circle(cSub)
        }

        val from = Radio.incomingFrom
        if (from != null) {
            incomingCard.visibility = View.VISIBLE
            incomingText.text = if (Radio.incomingMode == "all") "🔊  ${from}님 전체 방송 중" else "🔊  ${from}님이 나에게 말하는 중"
        } else incomingCard.visibility = View.GONE

        val room = Radio.currentRoom()
        val onRoom = Color.parseColor("#101418")
        when {
            Radio.talking -> { talkTitle.text = "${room.label}에게 말하는 중…"; talkSub.text = "손을 떼면 끝"; talkCard.setCardBackgroundColor(cRed); talkTitle.setTextColor(Color.WHITE); talkSub.setTextColor(Color.WHITE) }
            Radio.requesting -> { talkTitle.text = "잠깐만요…"; talkSub.text = ""; talkCard.setCardBackgroundColor(cAmberDark); talkTitle.setTextColor(cOnAmber); talkSub.setTextColor(cOnAmber) }
            !Radio.connected -> { talkTitle.text = "연결 대기 중"; talkSub.text = Radio.status; talkCard.setCardBackgroundColor(cCard2); talkTitle.setTextColor(cSub); talkSub.setTextColor(cSub) }
            else -> { talkTitle.text = "${room.icon} ${room.label}에게 말하기"; talkSub.text = "누르고 있는 동안 말해요"; talkCard.setCardBackgroundColor(room.color); talkTitle.setTextColor(onRoom); talkSub.setTextColor(onRoom) }
        }
        msgHead.text = "${room.icon} ${if (room.key == "all") "모두의 대화" else room.label + "와의 대화"}"
        msgHead.setTextColor(room.color)
        noticeText.text = Radio.notice
        standbySwitch.isChecked = RadioService.running
    }

    private fun dayLabel(ts: Long): String {
        val d = Calendar.getInstance().apply { timeInMillis = ts * 1000 }
        val now = Calendar.getInstance()
        val yd = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        fun same(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        return when {
            same(d, now) -> "오늘"
            same(d, yd) -> "어제"
            else -> "%d/%d (%s)".format(d.get(Calendar.MONTH) + 1, d.get(Calendar.DAY_OF_MONTH), dayNames[d.get(Calendar.DAY_OF_WEEK) - 1])
        }
    }

    private fun bubbleMenu(c: Clip) {
        val items = ArrayList<String>()
        if (c.isText) items.add("복사")
        if (!c.mine) items.add("${c.from} 방으로")
        items.add("삭제")
        MaterialAlertDialogBuilder(this).setItems(items.toTypedArray()) { _, i ->
            when (items[i]) {
                "복사" -> { (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("우리집", c.text ?: "")); toast("복사했어요") }
                "삭제" -> Radio.deleteClip(c)
                else -> { Radio.selectRoom(c.from); render() }
            }
        }.show()
    }

    private fun renderMessages() {
        val room = Radio.currentRoom()
        val shown = Radio.roomClips(room).take(40).reversed()   // 오래된 것 → 최신 (최신이 아래)
        val sig = room.key + "|" + shown.joinToString("|") { "${it.id}:${it.ts}:${Radio.playingClip === it}:${Radio.receiptText(it)}" }
        if (sig == msgSig) return
        val newestKey = shown.lastOrNull()?.let { "${it.id}:${it.ts}" }
        val newestChanged = newestKey != null && newestKey != lastNewestKey
        lastNewestKey = newestKey
        val child = scrollRoot.getChildAt(0)
        val nearBottom = child == null || child.bottom - (scrollRoot.height + scrollRoot.scrollY) < dp(160)
        msgSig = sig
        msgList.removeAllViews()
        if (shown.isEmpty()) msgList.addView(label("아직 대화가 없어요. 아래에 적어 보내거나, 큰 버튼을 누르고 말해 보세요.", 13f, cSub).apply { setPadding(dp(4), dp(20), dp(4), dp(20)); gravity = Gravity.CENTER })
        var prevDay: String? = null
        var prevFrom: String? = null
        var prevTs = 0L
        val onDark = cText
        shown.forEach { c ->
            val day = dayLabel(c.ts)
            if (day != prevDay) {
                msgList.addView(label(day, 11f, cSub).apply { gravity = Gravity.CENTER; background = roundRect(cBg, 10); setPadding(dp(10), dp(3), dp(10), dp(3)) },
                    lp(w = ViewGroup.LayoutParams.WRAP_CONTENT, top = 10).apply { gravity = Gravity.CENTER_HORIZONTAL })
                prevDay = day; prevFrom = null
            }
            val sender = if (c.mine) "나" else c.from
            val same = sender == prevFrom && c.ts - prevTs < 180
            prevFrom = sender; prevTs = c.ts
            val whoColor = Family.color(c.from)
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val colLp = lp(w = ViewGroup.LayoutParams.WRAP_CONTENT, top = if (same) 2 else 10).apply {
                gravity = if (c.mine) Gravity.END else Gravity.START
                if (c.mine) marginStart = dp(48) else marginEnd = dp(48)
            }
            if (!c.mine && !same) col.addView(label("${Family.icon(c.from)} ${c.from}", 13f, whoColor, true).apply { setPadding(dp(6), 0, 0, dp(2)) })
            val bubble = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = roundRect(if (c.mine) Color.parseColor("#2A3F66") else cCard2, 16)
                setPadding(dp(14), dp(9), dp(14), dp(9))
                setOnLongClickListener { bubbleMenu(c); true }
            }
            if (c.mine && c.mode != "all") bubble.addView(label("→ ${c.from}", 11f, cSub))
            val playing = Radio.playingClip === c
            when {
                c.isText -> bubble.addView(label(c.text ?: "", 19f, onDark).apply { setTextIsSelectable(false) })
                c.voiceSent -> bubble.addView(label("🎙  음성 ${c.seconds.toInt().coerceAtLeast(1)}초 보냄", 15f, onDark))
                else -> bubble.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                    text = if (playing) "■  재생 중 · ${c.seconds.toInt().coerceAtLeast(1)}초" else "▶  음성 ${c.seconds.toInt().coerceAtLeast(1)}초"
                    isAllCaps = false; textSize = 16f; setTextColor(onDark); insetTop = 0; insetBottom = 0; minHeight = 0; minimumHeight = 0
                    setPadding(dp(4), dp(4), dp(4), dp(4))
                    setOnClickListener { if (playing) Radio.stopClip() else Radio.playClip(c) }
                    setOnLongClickListener { bubbleMenu(c); true }
                })
            }
            col.addView(bubble, lp(w = ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = if (c.mine) Gravity.END else Gravity.START })
            var meta = fmtTime.format(Date(c.ts * 1000))
            var metaColor = cSub
            if (c.mine) {
                val rt = Radio.receiptText(c)
                if (rt.isNotBlank()) { meta = "$rt  ·  $meta"; metaColor = cAmber }
            } else if (c.isText) {
                Radio.markSeen(c)   // 화면에 표시됨 = 봤음
            }
            col.addView(label(meta, 10f, metaColor).apply { setPadding(dp(6), dp(1), dp(6), 0) }, lp(w = ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = if (c.mine) Gravity.END else Gravity.START })
            msgList.addView(col, colLp)
        }
        if (stickBottom || (newestChanged && nearBottom)) scrollRoot.post { scrollRoot.fullScroll(View.FOCUS_DOWN) }
        stickBottom = false
    }

    // ---------- 볼륨 버튼 (앱이 보일 때) ----------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if ((code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) || !Radio.connected)
            return super.dispatchKeyEvent(event)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) { volTalking = false; h.postDelayed(volHoldStart, 500) }
            KeyEvent.ACTION_UP -> {
                h.removeCallbacks(volHoldStart)
                if (volTalking) { volTalking = false; Radio.stopTalk() }
                else getSystemService(AudioManager::class.java).adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    if (code == KeyEvent.KEYCODE_VOLUME_UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                    AudioManager.FLAG_SHOW_UI
                )
            }
        }
        return true
    }

    // ---------- 권한·서비스 ----------

    private fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun ensurePermissionsThenStart() {
        if (!prefs.isConfigured) { settingsBox.visibility = View.VISIBLE; toast("먼저 이 폰이 누구 것인지 골라 주세요"); render(); return }
        val need = mutableListOf<String>()
        if (!hasMic()) need += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.POST_NOTIFICATIONS
        if (need.isEmpty()) startStandby() else askPerms.launch(need.toTypedArray())
    }

    private fun startStandby() { RadioService.start(this); h.postDelayed({ render() }, 300) }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) { toast("이미 배터리 최적화에서 제외되어 있어요"); return }
        try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
        catch (_: Exception) { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

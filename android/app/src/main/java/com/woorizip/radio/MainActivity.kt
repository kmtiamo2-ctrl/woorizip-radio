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
import android.view.inputmethod.EditorInfo
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
    private lateinit var outer: LinearLayout
    private var settingsSheet: com.google.android.material.bottomsheet.BottomSheetDialog? = null
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var incomingCard: MaterialCardView
    private lateinit var incomingText: TextView
    private lateinit var memberGroup: ChipGroup
    private lateinit var memberHolder: FrameLayout          // 평소: 여러 줄로 펼침
    private lateinit var memberScroll: HorizontalScrollView // 키패드 열림: 한 줄로 접어 옆으로 넘김
    private lateinit var roomsGuide: TextView
    private lateinit var talkCard: MaterialCardView
    private lateinit var talkTitle: TextView
    private lateinit var talkSub: TextView
    private lateinit var noticeText: TextView
    private lateinit var quickRow: LinearLayout
    private lateinit var input: TextInputEditText
    private lateinit var inputRow: LinearLayout
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
        setContentView(buildUi())
        if (!prefs.isConfigured) openSettingsSheet()
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
        // 메신저처럼 3단: 위(머리글·대화방·말하기, 고정) / 가운데(대화만 스크롤) / 아래(입력칸, 화면 바닥 고정)
        outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(cBg)
            isFocusableInTouchMode = true; descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS   // 켤 때 입력칸이 먼저 포커스를 잡아 키패드가 뜨지 않게
        }
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), 0) }

        // 머리글: 별 + 우리집 + 상태 + (설정·버전)
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (_: Exception) { "" }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(label("우리집", 24f, cText, true))
        val statusRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        statusDot = View(this).apply { background = circle(cSub) }
        statusRow.addView(statusDot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(6) })
        statusText = label("", 13f, cSub)
        statusRow.addView(statusText)
        titles.addView(statusRow, lp(top = 2))
        head.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        right.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "⚙ 설정"; isAllCaps = false; textSize = 13f; cornerRadius = dp(14); setTextColor(cText)
            strokeColor = ColorStateList.valueOf(cOutline); minHeight = 0; minimumHeight = 0; insetTop = 0; insetBottom = 0
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { openSettingsSheet() }
        })
        right.addView(label(ver, 11f, cSub).apply { gravity = Gravity.END }, lp(w = ViewGroup.LayoutParams.WRAP_CONTENT, top = 2))
        head.addView(right)
        root.addView(head)

        // 지금 말하는 사람 배너
        incomingCard = MaterialCardView(this).apply {
            setCardBackgroundColor(cAmber); radius = dpf(18); cardElevation = 0f; visibility = View.GONE
        }
        incomingText = label("", 16f, cOnAmber, true).apply { gravity = Gravity.CENTER; setPadding(dp(14), dp(12), dp(14), dp(12)) }
        incomingCard.addView(incomingText)
        root.addView(incomingCard, lp(top = 12))

        // 대화방 (평소엔 여러 줄로 펼침, 키패드가 열리면 한 줄로 접어 대화 칸에 자리를 내줌)
        roomsGuide = sectionTitle("대화방  ·  누르면 그 방  ·  길게 누르면 몇 명만 묶기")
        root.addView(roomsGuide, lp(top = 14))
        memberGroup = ChipGroup(this).apply { chipSpacingHorizontal = dp(8); chipSpacingVertical = dp(4) }
        memberHolder = FrameLayout(this)
        memberHolder.addView(memberGroup, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(memberHolder, lp(top = 6))
        memberScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipToPadding = false; visibility = View.GONE }
        root.addView(memberScroll, lp(top = 6))

        // 말하기 버튼
        talkCard = MaterialCardView(this).apply {
            radius = dpf(26); cardElevation = dpf(6); setCardBackgroundColor(cAmber)
            isClickable = true
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { v.isPressed = true; Radio.startTalk() }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; Radio.stopTalk() }
                }
                true
            }
        }
        val talkInner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(16), dp(22), dp(16), dp(22)) }
        talkTitle = label("", 22f, cOnAmber, true)
        talkSub = label("", 13f, cOnAmber).apply { alpha = 0.8f }
        talkInner.addView(talkTitle); talkInner.addView(talkSub, lp(w = ViewGroup.LayoutParams.WRAP_CONTENT, top = 2))
        talkCard.addView(talkInner)
        root.addView(talkCard, lp(top = 12))
        noticeText = label("", 13f, cAmber).apply { gravity = Gravity.CENTER }
        root.addView(noticeText, lp(top = 6, bottom = 6))
        outer.addView(root)

        // 가운데: 대화 (여기만 스크롤, 최신이 아래)
        val (mc, mi) = card(pad = 14)
        msgCard = mc
        msgHead = sectionTitle("대화").apply { setPadding(dp(4), 0, 0, 0) }
        mi.addView(msgHead)
        msgList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        mi.addView(msgList, lp(top = 6))
        scrollRoot = NestedScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        scrollRoot.addView(msgCard, lp(top = 4, bottom = 8))
        scrollRoot.setPadding(dp(14), 0, dp(14), 0)
        outer.addView(scrollRoot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // 아래: 빠른 답장 + 입력칸 (화면 바닥에 고정, 키패드가 올라오면 같이 올라옴)
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(cCard); setPadding(dp(14), dp(8), dp(14), dp(10)) }
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        quickRow = LinearLayout(this)
        scroll.addView(quickRow)
        bottom.addView(scroll)
        inputRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val (til, et) = inputField("메시지 입력")
        input = et
        inputRow.addView(til, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        input.setOnFocusChangeListener { _, has -> if (has) scrollRoot.postDelayed({ scrollRoot.fullScroll(View.FOCUS_DOWN) }, 300) }
        input.imeOptions = EditorInfo.IME_ACTION_SEND   // 키패드의 '완료' 대신 '전송' — 치고 바로 보내고, 키패드는 그대로 열려 있음
        input.setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_SEND) { sendTyped(); true } else false }
        inputRow.addView(pill("보내기", true) { sendTyped() }, lp(w = ViewGroup.LayoutParams.WRAP_CONTENT, start = 8))
        inputRow.addView(pill("⏰", false) { openScheduleDialog() }, lp(w = ViewGroup.LayoutParams.WRAP_CONTENT, start = 6))
        bottom.addView(inputRow, lp(top = 6))
        outer.addView(bottom)

        // 키패드: 시스템이 창을 줄여 주면 아래 줄이 자연히 올라오고(adjustResize), 가장자리까지 그리는 기기(Android 15+)는 키패드 높이만큼 아래 여백을 줌.
        // 어느 쪽이든 실제로 보이는 영역을 재서 가린 높이를 보정함.
        ViewCompat.setOnApplyWindowInsetsListener(outer) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            barsBottom = bars.bottom; imeBottom = ime.bottom
            insetPad = intArrayOf(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            applyKeyboardPadding()
            applyKeyboardLayout()
            insets
        }
        outer.viewTreeObserver.addOnGlobalLayoutListener {
            val r = android.graphics.Rect()
            outer.getWindowVisibleDisplayFrame(r)
            val loc = IntArray(2); outer.getLocationOnScreen(loc)
            val covered = (loc[1] + outer.height) - r.bottom
            val kb = (covered - insetPad[3]).coerceAtLeast(0)
            // 키패드가 차지한 높이: 창이 줄어드는 기기(옛 방식)도, 가장자리까지 그리는 기기도 모두 보이는 영역으로 잼
            val taken = (outer.rootView.height - r.bottom - barsBottom).coerceAtLeast(0)
            keyboardTaken = maxOf(kb, taken)
            if (kb != keyboardOverlap) { keyboardOverlap = kb; applyKeyboardPadding() }
            applyKeyboardLayout()
        }
        return outer
    }

    private fun sendTyped() {
        val t = input.text.toString()
        if (t.isNotBlank() && Radio.sendText(t)) { input.setText(""); stickBottom = true }
    }

    private var compactMode = false

    /** 키패드가 열리면 큰 말하기 버튼·안내 줄을 숨기고 대화방 칩을 한 줄로 접어, 그 자리에 선택한 방의 대화가 보이게 함. 내리면 원래대로. */
    private fun applyKeyboardLayout() {
        if (!::talkCard.isInitialized) return
        val compact = imeBottom > 0 || keyboardOverlap > 0 || keyboardTaken > dp(80)
        if (compact == compactMode) return
        compactMode = compact
        val shown = if (compact) View.GONE else View.VISIBLE
        roomsGuide.visibility = shown
        talkCard.visibility = shown
        noticeText.visibility = shown
        (memberGroup.parent as? ViewGroup)?.removeView(memberGroup)
        memberGroup.isSingleLine = compact
        if (compact) {
            memberScroll.addView(memberGroup, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            memberHolder.visibility = View.GONE; memberScroll.visibility = View.VISIBLE
            memberScroll.post { scrollToSelectedChip() }
            scrollRoot.post { scrollRoot.fullScroll(View.FOCUS_DOWN) }   // 선택한 방의 최신 글이 입력칸 바로 위에 오게
        } else {
            memberHolder.addView(memberGroup, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            memberScroll.visibility = View.GONE; memberHolder.visibility = View.VISIBLE
        }
    }

    private fun scrollToSelectedChip() {
        if (!compactMode) return
        for (i in 0 until memberGroup.childCount) {
            val c = memberGroup.getChildAt(i) as? Chip ?: continue
            if (c.isChecked) { memberScroll.smoothScrollTo((c.left - dp(8)).coerceAtLeast(0), 0); break }
        }
    }

    private fun circle(color: Int) = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(color) }
    private fun roundRect(color: Int, radiusDp: Int) = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dpf(radiusDp); setColor(color) }

    /** 설정 시트: 수신 대기 스위치 + 배터리 + 설정 항목 (첫 실행이면 누구 폰인지 고를 때까지 닫히지 않음) */
    private fun openSettingsSheet() {
        settingsSheet?.dismiss()
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(cBg); setPadding(dp(20), dp(16), dp(20), dp(28)) }
        standbySwitch = MaterialSwitch(this).apply {
            text = "수신 대기 — 화면이 꺼져도 듣고, 볼륨키로 말하기"; setTextColor(cText); textSize = 14f
            isChecked = RadioService.running
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setOnClickListener {
                if (isChecked) ensurePermissionsThenStart() else { prefs.standby = false; RadioService.stop(this@MainActivity) }
                h.postDelayed({ render() }, 300)
            }
        }
        box.addView(standbySwitch)
        box.addView(textButton("배터리 최적화 제외") { requestBatteryExemption() }, lp(top = 2))
        settingsBox = buildSettings()
        box.addView(settingsBox, lp(top = 10))
        sheet.setContentView(NestedScrollView(this).apply { addView(box) })
        sheet.setCancelable(prefs.isConfigured)
        sheet.behavior.skipCollapsed = true
        sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        settingsSheet = sheet
        sheet.show()
    }

    private fun closeSettings() { settingsSheet?.dismiss(); settingsSheet = null }

    // ---------- 가족 칩 / 빠른 답장 칩 ----------

    private fun roomChip(room: Room, selected: Boolean, unread: Int): Chip {
        val m = room.members
        val single = m != null && m.size == 1
        val on = single && m!![0] in Radio.online
        // 사람 방: 연결된 사람은 초록 점만, 안 된 사람만 "꺼짐" (집/밖 글자는 안 씀)
        val base = if (m == null) "${Radio.online.size}명 접속" else if (single) (if (on) "" else "꺼짐") else "${m.size}명"
        val sub = listOf(base, if (unread > 0) "새 글 $unread" else "").filter { it.isNotEmpty() }.joinToString(" · ")
        val dim = single && !on
        return Chip(this).apply {
            text = "${room.icon} ${room.label}" + (if (sub.isNotEmpty()) " · $sub" else "")
            if (on) { chipIcon = circle(if (selected) Color.parseColor("#101418") else cGreen); chipIconSize = dpf(9); isChipIconVisible = true; iconStartPadding = dpf(6) }
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
        if (compactMode) memberScroll.post { scrollToSelectedChip() }   // 한 줄로 접힌 상태면 선택한 방이 보이게 옆으로 맞춤
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
            closeSettings()
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
            closeSettings()
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
            closeSettings()
            if (RadioService.running) Radio.reconnect() else ensurePermissionsThenStart()
            render()
        }, lp(top = 12))
        normal.addView(textButton("지금 업데이트 확인", cSub) {
            toast("확인 중…")
            Updater.checkNow(this) { r -> runOnUiThread { toast(r) } }
        }, lp(top = 4))
        normal.addView(textButton("집 주소 연결 시험 (왜 밖 경로인지 확인)", cSub) {
            val dlg = MaterialAlertDialogBuilder(this).setTitle("집 주소 연결 시험").setMessage("시험 중… (최대 20초)").setPositiveButton("닫기", null).show()
            Radio.testHome { r -> runOnUiThread { try { dlg.setMessage(r) } catch (_: Exception) {} } }
        }, lp(top = 4))
        if (Radio.homeNote.isNotBlank()) normal.addView(label("마지막 집 주소 시도: ${Radio.homeNote}", 12f, cSub), lp(top = 2))
        normal.addView(textButton(if (Notifs.standbyHidden(this)) "상단 '수신 대기' 알림: 숨김 (설정 열기)" else "상단 '수신 대기' 알림 숨기기…", cSub) {
            MaterialAlertDialogBuilder(this).setTitle("수신 대기 알림")
                .setMessage("뒤에서 듣고 있다는 표시라 안드로이드가 붙이는 알림이에요. 이 앱은 가장 작게(상단 아이콘 없이) 보이도록 해 두었지만, 기종에 따라 남을 수 있어요.\n\n열리는 화면에서 '알림 표시'를 끄면 이 표시만 사라지고 수신·메시지 알림은 그대로 돼요.")
                .setPositiveButton("설정 열기") { _, _ -> Notifs.openStandbyChannelSettings(this) }
                .setNegativeButton("닫기", null).show()
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
        if (Radio.connected) {
            statusText.text = "$me · 연결됨 · 가족 ${Radio.online.size}명"   // 집/밖 구분은 동작(밖의 아이는 진동)에만 쓰고 글자로는 안 보여줌
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
        if (::standbySwitch.isInitialized) standbySwitch.isChecked = RadioService.running
    }

    private var insetPad = intArrayOf(0, 0, 0, 0)
    private var keyboardOverlap = 0   // 창이 안 줄어드는 기기에서 키패드가 가린 높이 (아래 여백으로 보정)
    private var keyboardTaken = 0     // 키패드가 차지한 높이 (열림/닫힘 판단용)
    private var barsBottom = 0
    private var imeBottom = 0

    private fun applyKeyboardPadding() {
        if (!::outer.isInitialized) return
        val bottom = insetPad[3] + keyboardOverlap
        if (outer.paddingBottom != bottom || outer.paddingTop != insetPad[1] || outer.paddingLeft != insetPad[0])
            outer.setPadding(insetPad[0], insetPad[1], insetPad[2], bottom)
        if ((keyboardOverlap > 0 || insetPad[3] > dp(100)) && ::input.isInitialized && input.hasFocus())
            scrollRoot.post { scrollRoot.fullScroll(View.FOCUS_DOWN) }   // 키패드가 뜨면 대화는 맨 아래(최신)로
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

    private class Conv(val key: String, val label: String, val color: Int)

    /** 이 메시지가 '누구와의 대화'인지. 모두에게 보낸 글은 null(묶음 없음), 그 외는 상대 키·꼬리표·색 */
    private fun convOf(c: Clip): Conv? {
        if (c.mode == "all") return null
        val names = if (c.mine) c.from.split(", ").filter { it.isNotBlank() } else listOf(c.from)
        if (names.size == 1) return Conv(names[0], "${Family.icon(names[0])} ${names[0]}와의 대화", Family.color(names[0]))
        val set = names.toSet()
        val room = Radio.rooms().firstOrNull { it.members != null && it.members.toSet() == set }
        return if (room != null) Conv(room.key, "${room.icon} ${room.label}에게", room.color)
        else Conv("c:" + names.sorted().joinToString(","), names.joinToString(", ") + "에게", cSub)
    }

    private fun blendColor(fg: Int, bg: Int, a: Float): Int = Color.rgb(
        (Color.red(bg) + (Color.red(fg) - Color.red(bg)) * a).toInt(),
        (Color.green(bg) + (Color.green(fg) - Color.green(bg)) * a).toInt(),
        (Color.blue(bg) + (Color.blue(fg) - Color.blue(bg)) * a).toInt())

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
        var block: LinearLayout? = null   // 같은 상대와 주고받은 연속 글은 그 사람 색 테두리 상자로 묶음 (모두에게 보낸 글은 상자 없음)
        var blockKey: String? = null
        shown.forEach { c ->
            val conv = convOf(c)
            if (conv?.key != blockKey) {
                blockKey = conv?.key
                block = if (conv == null) null else LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dpf(16); setStroke(dp(2), conv.color); setColor(blendColor(conv.color, cBg, 0.10f))
                    }
                    setPadding(dp(8), dp(6), dp(8), dp(8))
                    addView(label(conv.label, 11f, conv.color, true).apply { setPadding(dp(4), 0, 0, 0) })
                    msgList.addView(this, lp(top = 10))
                }
                prevFrom = null
            }
            val holder: LinearLayout = block ?: msgList
            val day = dayLabel(c.ts)
            if (day != prevDay) {
                holder.addView(label(day, 11f, cSub).apply { gravity = Gravity.CENTER; background = roundRect(cBg, 10); setPadding(dp(10), dp(3), dp(10), dp(3)) },
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
            holder.addView(col, colLp)
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
        if (!prefs.isConfigured) { openSettingsSheet(); toast("먼저 이 폰이 누구 것인지 골라 주세요"); render(); return }
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

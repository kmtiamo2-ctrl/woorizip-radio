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
    private lateinit var memberGrid: LinearLayout           // 평소: 3줄 격자 (모두·아이들 / 아빠·엄마 / 첫째·둘째·셋째), 칸 폭·여백 모두 같게
    private lateinit var memberLine: LinearLayout           // 키패드 열림: 한 줄로 접어 옆으로 넘김
    private lateinit var memberHolder: FrameLayout
    private lateinit var memberScroll: HorizontalScrollView
    private var shownCount = 40                              // 대화 칸에 그려 둔 글 수 (맨 위로 올리면 40개씩 더)
    private var loadOlderBusy = false
    private lateinit var roomsGuide: TextView
    private lateinit var talkCard: MaterialCardView
    private lateinit var talkWrap: FrameLayout
    private val familyGradient by lazy {
        android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, Family.colors()).apply { cornerRadius = dpf(29) }
    }
    private lateinit var talkTitle: TextView
    private lateinit var talkSub: TextView
    private lateinit var noticeText: TextView
    private lateinit var quickRow: LinearLayout
    private lateinit var input: TextInputEditText
    private lateinit var inputTil: TextInputLayout
    private lateinit var sendBtn: MaterialButton
    private lateinit var inputRow: LinearLayout
    private lateinit var uploadText: TextView
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

    // ---- 첨부: 사진·파일 고르기, 카메라, 공유 시트 ----
    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris -> sendUris(uris) }
    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> sendUris(uris) }
    private var cameraUri: Uri? = null
    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = cameraUri; cameraUri = null
        if (ok && u != null) sendUris(listOf(u))
    }

    private fun sendUris(uris: List<Uri>, to: String? = null) {
        if (uris.isEmpty()) return
        Thread {
            val picked = uris.mapNotNull { u -> try { FileXfer.describe(this, u) } catch (_: Exception) { null } }
            runOnUiThread { Radio.sendFiles(picked, to); stickBottom = true; render() }
        }.start()
    }

    private fun openAttachMenu() {
        val items = arrayOf("📷 사진·동영상 고르기", "📁 파일 고르기", "📸 카메라로 찍기", "⏰ 예약 보내기")
        MaterialAlertDialogBuilder(this).setTitle("${Radio.currentRoom().label}에게 보내기").setItems(items) { _, i ->
            when (i) {
                0 -> pickMedia.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                1 -> pickFiles.launch(arrayOf("*/*"))
                2 -> {
                    val dir = java.io.File(cacheDir, "camera").apply { mkdirs() }
                    val f = java.io.File(dir, "IMG_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA).format(Date()) + ".jpg")
                    cameraUri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", f)
                    try { takePhoto.launch(cameraUri!!) } catch (_: Exception) { toast("카메라를 열 수 없어요") }
                }
                3 -> openScheduleDialog()
            }
        }.show()
    }

    /** 갤러리·파일 앱에서 "공유 → 우리집"으로 들어온 것: 누구에게 보낼지 고르고 올림 */
    private fun handleShareIntent(intent: Intent?) {
        if (intent == null || (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE)) return
        val uris = ArrayList<Uri>()
        if (intent.action == Intent.ACTION_SEND) {
            (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM))?.let { uris.add(it) }
        } else {
            (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM))?.let { uris.addAll(it) }
        }
        intent.action = null   // 화면이 다시 만들어져도 두 번 묻지 않게
        if (uris.isEmpty()) return
        if (!prefs.isConfigured) { toast("먼저 설정에서 이 폰이 누구 것인지 골라 주세요"); return }
        val rooms = Radio.rooms()
        MaterialAlertDialogBuilder(this).setTitle("${uris.size}개를 누구에게 보낼까요?")
            .setItems(rooms.map { "${it.icon} ${it.label}" }.toTypedArray()) { _, i ->
                val r = rooms[i]
                Radio.selectRoom(r.key)
                sendUris(uris, if (r.members == null) "all" else r.members.joinToString(","))
            }.setNegativeButton("취소", null).show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
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
        handleShareIntent(intent)
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
        memberGrid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        memberHolder = FrameLayout(this)
        memberHolder.addView(memberGrid, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(memberHolder, lp(top = 6))
        memberLine = LinearLayout(this)
        memberScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipToPadding = false; visibility = View.GONE }
        memberScroll.addView(memberLine, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(memberScroll, lp(top = 6))

        // 말하기 버튼
        talkCard = MaterialCardView(this).apply {
            radius = dpf(26); cardElevation = dpf(6); setCardBackgroundColor(cAmber)
            isClickable = true
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { if (Radio.isMeRoom(Radio.currentRoom())) toast("나에게는 글·사진만 보낼 수 있어요") else { v.isPressed = true; Radio.startTalk() } }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; Radio.stopTalk() }
                }
                true
            }
        }
        val talkInner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(14), dp(15), dp(14), dp(15)) }   // 예전보다 약 1/4 낮게
        talkTitle = label("", 19f, cOnAmber, true)
        talkSub = label("", 12f, cOnAmber).apply { alpha = 0.8f }
        talkInner.addView(talkTitle); talkInner.addView(talkSub, lp(w = ViewGroup.LayoutParams.WRAP_CONTENT, top = 2))
        talkCard.addView(talkInner)
        // '모두' 방일 때는 카드 둘레에 가족 5명 색을 이어 붙인 테두리 (모두 = 가족 전부의 색)
        talkWrap = FrameLayout(this).apply { setPadding(dp(3), dp(3), dp(3), dp(3)); background = roundRect(cBg, 29) }
        talkWrap.addView(talkCard, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(talkWrap, lp(top = 12))
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
        // 대화 칸 크기가 바뀔 때(키패드 열림/닫힘, 말하기 버튼 접힘/펼침) 바닥 근처를 보고 있었으면 바닥으로 다시 붙임 — 옛 글을 보던 중이면 그대로 둠
        scrollRoot.setOnScrollChangeListener(NestedScrollView.OnScrollChangeListener { v, _, sy, _, oldSy ->
            val c = v.getChildAt(0) ?: return@OnScrollChangeListener
            chatNearBottom = c.bottom - (v.height + v.scrollY) < dp(160)
            if (sy < dp(40) && sy < oldSy) loadOlder()   // 맨 위로 올리면 더 옛 글을 이어 붙임
        })
        scrollRoot.addOnLayoutChangeListener { v, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if ((bottom - top) != (oldBottom - oldTop) && chatNearBottom) v.post { scrollRoot.fullScroll(View.FOCUS_DOWN) }
        }

        // 아래: 빠른 답장 + 입력칸 (화면 바닥에 고정, 키패드가 올라오면 같이 올라옴)
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(cCard); setPadding(dp(14), dp(8), dp(14), dp(10)) }
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        quickRow = LinearLayout(this)
        scroll.addView(quickRow)
        bottom.addView(scroll)
        inputRow = LinearLayout(this).apply { gravity = Gravity.BOTTOM }   // 입력칸 테두리(아래쪽)에 보내기를 맞춤
        val (til, et) = inputField("메시지 입력")
        input = et; inputTil = til
        // [+] 사진·파일·카메라·예약 — 작은 정사각형(40dp). 입력칸 테두리의 세로 가운데에 오게, 실제 그려진 위치를 재서 맞춤
        val plusBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "+"; textSize = 22f; isAllCaps = false; setTextColor(cText); cornerRadius = dp(12)
            strokeColor = ColorStateList.valueOf(cOutline); minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
            insetTop = 0; insetBottom = 0; setPadding(0, 0, 0, 0); includeFontPadding = false; gravity = Gravity.CENTER
            contentDescription = "사진·파일·예약 보내기"
            setOnClickListener { openAttachMenu() }
        }
        inputRow.addView(plusBtn, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(8) })
        inputRow.addView(til, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val alignPlus = {
            // 입력칸(EditText)의 세로 중심에 맞춤. TextInputLayout 은 안에 틀을 하나 더 두므로 et 에서 til 까지 올라가며 top 을 더해 실제 위치를 구함
            var top = 0; var v: View = et
            while (v !== til) { top += v.top; v = v.parent as? View ?: break }
            val center = til.top + top + et.height / 2f
            val myCenter = plusBtn.top + plusBtn.height / 2f
            val dy = center - myCenter
            if (plusBtn.translationY != dy) plusBtn.translationY = dy
        }
        til.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> alignPlus() }
        plusBtn.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> alignPlus() }
        input.setOnFocusChangeListener { _, has -> if (has) scrollRoot.postDelayed({ scrollRoot.fullScroll(View.FOCUS_DOWN) }, 300) }
        input.imeOptions = EditorInfo.IME_ACTION_SEND   // 키패드의 '완료' 대신 '전송' — 치고 바로 보내고, 키패드는 그대로 열려 있음
        input.setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_SEND) { sendTyped(); true } else false }
        sendBtn = pill("보내기", true) { sendTyped() }.apply { minWidth = 0; minimumWidth = 0; setPadding(dp(14), 0, dp(14), 0) }   // 글자 폭만큼만
        sendBtn.apply { insetTop = 0; insetBottom = 0; minHeight = 0; minimumHeight = 0 }
        inputRow.addView(sendBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56)).apply { marginStart = dp(8) })
        uploadText = label("", 12f, cAmber).apply { visibility = View.GONE; setPadding(dp(6), 0, 0, 0) }
        bottom.addView(uploadText, lp(top = 4))
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
        talkWrap.visibility = shown
        noticeText.visibility = shown
        memberSig = ""   // 접힘/펼침이 바뀌면 방 버튼을 그 모양으로 다시 그림
        if (compact) {
            memberHolder.visibility = View.GONE; memberScroll.visibility = View.VISIBLE
            renderMembers()
            memberScroll.post { scrollToSelectedChip() }
            scrollRoot.post { scrollRoot.fullScroll(View.FOCUS_DOWN) }   // 선택한 방의 최신 글이 입력칸 바로 위에 오게
        } else {
            memberScroll.visibility = View.GONE; memberHolder.visibility = View.VISIBLE
            renderMembers()
        }
    }

    private fun scrollToSelectedChip() {
        if (!compactMode) return
        for (i in 0 until memberLine.childCount) {
            val c = memberLine.getChildAt(i)
            if (c.tag == "sel") { memberScroll.smoothScrollTo((c.left - dp(8)).coerceAtLeast(0), 0); break }
        }
    }

    /** 대화 칸을 맨 위까지 올렸을 때: 그려 둔 것보다 더 있으면 40개 더 그리고, 그것도 다 보였으면 서버에 더 옛 기록을 달라고 함. 보던 자리가 튀지 않게 스크롤 위치를 보정 */
    private fun loadOlder() {
        if (loadOlderBusy) return
        val room = Radio.currentRoom()
        val total = Radio.roomClips(room).size
        if (shownCount < total) {
            loadOlderBusy = true
            val child = scrollRoot.getChildAt(0)
            val before = child?.height ?: 0
            shownCount += 40
            renderMessages()
            scrollRoot.post {
                val after = scrollRoot.getChildAt(0)?.height ?: 0
                scrollRoot.scrollBy(0, (after - before).coerceAtLeast(0))
                loadOlderBusy = false
            }
        } else if (Radio.loadOlder()) {
            loadOlderBusy = true
            val child = scrollRoot.getChildAt(0)
            val before = child?.height ?: 0
            shownCount += 100
            h.postDelayed({
                renderMessages()
                scrollRoot.post {
                    val after = scrollRoot.getChildAt(0)?.height ?: 0
                    scrollRoot.scrollBy(0, (after - before).coerceAtLeast(0))
                    loadOlderBusy = false
                }
            }, 700)
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

    /** 방 버튼 하나. 글자는 가운데, 칸 폭은 격자가 정함. 내 자리('나')는 메모장 — 접속 표시 없음 */
    private fun roomButton(room: Room, selected: Boolean, unread: Int): View {
        val m = room.members
        val meRoom = Radio.isMeRoom(room)
        val single = m != null && m.size == 1 && !meRoom
        val on = single && m!![0] in Radio.online
        // 사람 방: 연결된 사람은 초록 점만, 안 된 사람만 "꺼짐" (집/밖 글자는 안 씀)
        val base = if (m == null) "${Radio.online.size}명 접속" else if (meRoom) "" else if (single) (if (on) "" else "꺼짐") else "${m.size}명"
        val sub = listOf(base, if (unread > 0) "새 글 $unread" else "").filter { it.isNotEmpty() }.joinToString(" · ")
        val dim = single && !on
        val onColor = Color.parseColor("#101418")
        val btn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "${room.icon} ${room.label}" + (if (sub.isNotEmpty()) "  ·  $sub" else "")
            isAllCaps = false; textSize = 14f; maxLines = 2
            textAlignment = View.TEXT_ALIGNMENT_CENTER; gravity = Gravity.CENTER
            cornerRadius = dp(18)
            strokeWidth = if (room.key == "all") 0 else dp(1)
            strokeColor = ColorStateList.valueOf(if (selected) room.color else cOutline)
            backgroundTintList = ColorStateList.valueOf(if (selected) room.color else cCard)
            setTextColor(if (selected) onColor else if (dim) cSub else room.color)
            insetTop = 0; insetBottom = 0; minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
            setPadding(dp(10), dp(9), dp(10), dp(9))
            if (on) { icon = circle(if (selected) onColor else cGreen); iconSize = dp(9); iconPadding = dp(6); iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START; iconTint = null }
            tag = if (selected) "sel" else null
            setOnClickListener { Radio.selectRoom(room.key); render() }
            if (single) setOnLongClickListener { Radio.toggleTarget(m!![0]); render(); true }   // 몇 명만 묶기
        }
        if (room.key != "all") return btn
        // '모두' 방 버튼은 가족 5명 색 테두리로 감쌈
        return FrameLayout(this).apply {
            setPadding(dp(2), dp(2), dp(2), dp(2))
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, Family.colors()).apply { cornerRadius = dpf(20) }
            tag = btn.tag
            addView(btn, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
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
        val sig = listOf(compactMode, rooms.joinToString { it.key + it.label }, Radio.online.joinToString(), Radio.target, Radio.memberWhere.toString(), unread.toString()).joinToString("|")
        if (sig == memberSig) return
        memberSig = sig
        val btn = { r: Room -> roomButton(r, r.key == cur.key, unread[r.key] ?: 0) }
        if (compactMode) {
            // 한 줄: 모두 · 아이들 · 아빠 · 엄마 · 첫째 · 둘째 · 셋째 순서로 옆으로 넘김
            memberLine.removeAllViews()
            rooms.forEach { r -> memberLine.addView(btn(r), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }) }
            memberScroll.post { scrollToSelectedChip() }
        } else {
            // 3줄 격자: [모두][아이들] / [아빠][엄마] / [첫째][둘째][셋째] — 한 줄 안의 칸은 폭이 같고, 칸 사이 여백은 모두 같게
            memberGrid.removeAllViews()
            val byKey = rooms.associateBy { it.key }
            val lines = listOf(listOf("all", "g:아이들"), listOf("아빠", "엄마"), listOf("첫째", "둘째", "셋째"))
            val used = HashSet<String>()
            val rowsOf = ArrayList<List<Room>>()
            lines.forEach { keys -> val l = keys.mapNotNull { byKey[it] }; if (l.isNotEmpty()) { rowsOf.add(l); used.addAll(l.map { it.key }) } }
            val rest = rooms.filter { it.key !in used }   // 직접 묶은 방이 더 있으면 아래 줄에 2개씩
            rest.chunked(2).forEach { rowsOf.add(it) }
            rowsOf.forEachIndexed { i, l ->
                val row = LinearLayout(this)
                l.forEach { r -> row.addView(btn(r), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = dp(4); marginEnd = dp(4) }) }
                memberGrid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = if (i == 0) 0 else dp(8); marginStart = -dp(4); marginEnd = -dp(4) })
            }
        }
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

        val ups = Radio.uploads.filter { !it.finished }
        if (ups.isNotEmpty()) {
            val u = ups[0]
            val pct = if (u.total > 0) (u.done * 100 / u.total).toInt() else 0
            uploadText.text = "📎 ${u.name} 보내는 중 $pct%" + (if (ups.size > 1) " (+${ups.size - 1}개)" else "")
            uploadText.visibility = View.VISIBLE
            h.removeCallbacks(uploadTick); h.postDelayed(uploadTick, 400)
        } else uploadText.visibility = View.GONE

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
            incomingText.text = if (Radio.incomingMode == "all") "🔊  ${Radio.disp(from)} 전체 방송 중" else "🔊  ${Radio.disp(from)}가 나에게 말하는 중"
        } else incomingCard.visibility = View.GONE

        val room = Radio.currentRoom()
        val onRoom = Color.parseColor("#101418")
        when {
            Radio.talking -> { talkTitle.text = "${room.label}에게 말하는 중…"; talkSub.text = "손을 떼면 끝"; talkCard.setCardBackgroundColor(cRed); talkTitle.setTextColor(Color.WHITE); talkSub.setTextColor(Color.WHITE) }
            Radio.requesting -> { talkTitle.text = "잠깐만요…"; talkSub.text = ""; talkCard.setCardBackgroundColor(cAmberDark); talkTitle.setTextColor(cOnAmber); talkSub.setTextColor(cOnAmber) }
            !Radio.connected -> { talkTitle.text = "연결 대기 중"; talkSub.text = Radio.status; talkCard.setCardBackgroundColor(cCard2); talkTitle.setTextColor(cSub); talkSub.setTextColor(cSub) }
            Radio.isMeRoom(room) -> { talkTitle.text = "📝 나에게 메모"; talkSub.text = "말하기는 안 되고 글·사진만 — 내 기기에서만 보여요"; talkCard.setCardBackgroundColor(cCard2); talkTitle.setTextColor(cSub); talkSub.setTextColor(cSub) }
            else -> { talkTitle.text = "${room.icon} ${room.label}에게 말하기"; talkSub.text = "누르고 있는 동안 말해요"; talkCard.setCardBackgroundColor(room.color); talkTitle.setTextColor(onRoom); talkSub.setTextColor(onRoom) }
        }
        msgHead.text = "${room.icon} ${if (room.key == "all") "모두의 대화" else if (Radio.isMeRoom(room)) "나의 메모" else room.label + Family.josa(room.label, "과", "와") + "의 대화"}"
        msgHead.setTextColor(room.color)
        if (inputRoomKey != room.key) {   // 입력칸 테두리·보내기 버튼·안내 글을 고른 방 색으로 — 키패드 위 입력줄만 봐도 누구에게 보내는지 보이게
            inputRoomKey = room.key
            talkWrap.background = if (room.key == "all") familyGradient else roundRect(cBg, 29)
            inputTil.boxStrokeColor = room.color
            inputTil.hintTextColor = ColorStateList.valueOf(room.color)
            inputTil.defaultHintTextColor = ColorStateList.valueOf(room.color)
            inputTil.hint = if (Radio.isMeRoom(room)) "나에게 메모" else "${if (room.key == "all") "모두" else room.label}에게 메시지"
            sendBtn.backgroundTintList = ColorStateList.valueOf(room.color)
            sendBtn.setTextColor(onRoom)
        }
        noticeText.text = Radio.notice
        if (::standbySwitch.isInitialized) standbySwitch.isChecked = RadioService.running
    }

    private var inputRoomKey: String? = null
    private val uploadTick = Runnable { render() }   // 보내는 중 진행률 갱신
    private var chatNearBottom = true   // 대화 칸을 바닥 근처에서 보고 있는지 (크기가 바뀔 때 바닥으로 다시 붙일지 판단)
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
        if (c.isFile && !c.expired) { items.add("열기"); if (c.isImage || (c.mime ?: "").startsWith("video/")) items.add("갤러리에 저장"); items.add("공유") }
        if (!c.mine) items.add("${c.from} 방으로")
        items.add("삭제")
        MaterialAlertDialogBuilder(this).setItems(items.toTypedArray()) { _, i ->
            when (items[i]) {
                "복사" -> { (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("우리집", c.text ?: "")); toast("복사했어요") }
                "열기" -> openAttachment(c)
                "갤러리에 저장" -> Radio.withFile(c) { f ->
                    if (FileXfer.saveToGallery(this, f, c.name ?: f.name, c.mime)) toast("갤러리(우리집 앨범)에 저장했어요")
                    else try { startActivity(Intent.createChooser(FileXfer.shareIntent(this, f, c.mime), "저장할 앱 고르기")) } catch (_: Exception) { toast("저장할 수 없어요") }
                }
                "공유" -> Radio.withFile(c) { f -> try { startActivity(Intent.createChooser(FileXfer.shareIntent(this, f, c.mime), "공유")) } catch (_: Exception) { toast("공유할 수 없어요") } }
                "삭제" -> Radio.deleteClip(c)
                else -> { Radio.selectRoom(c.from); render() }
            }
        }.show()
    }

    private fun openAttachment(c: Clip) {
        Radio.withFile(c) { f ->
            try { startActivity(FileXfer.openIntent(this, f, c.mime)) } catch (_: Exception) {
                try { startActivity(Intent.createChooser(FileXfer.shareIntent(this, f, c.mime), "열 앱 고르기")) } catch (_: Exception) { toast("열 수 있는 앱이 없어요") }
            }
        }
    }

    /** 첨부 말풍선: 사진은 미리보기(누르면 원본 열기), 파일은 📎 이름·크기 */
    private fun fileBubble(bubble: LinearLayout, c: Clip) {
        val name = c.name ?: "file"
        if (c.expired) {
            bubble.addView(label("📎 $name\n기간이 지나 삭제됐어요 (7일 보관)", 13f, cSub))
            return
        }
        val dl = Radio.downloading[c.fid ?: ""]
        val caption = if (dl != null) "내려받는 중 ${(dl * 100).toInt()}%" else "$name · ${Notifs.humanSize(c.size)}"
        if (c.thumb) {
            val tf = Radio.thumbFile(c)
            if (tf.exists()) {
                val bmp = try { android.graphics.BitmapFactory.decodeFile(tf.path) } catch (_: Throwable) { null }
                if (bmp != null) {
                    val maxW = dp(240)
                    val scale = minOf(1f, maxW.toFloat() / bmp.width, dp(260).toFloat() / bmp.height)
                    bubble.addView(ImageView(this).apply {
                        setImageBitmap(bmp); adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER
                        background = roundRect(cBg, 12); clipToOutline = true
                        setOnClickListener { openAttachment(c) }
                        setOnLongClickListener { bubbleMenu(c); true }
                    }, LinearLayout.LayoutParams((bmp.width * scale).toInt(), (bmp.height * scale).toInt()))
                    bubble.addView(label(caption, 11f, cSub).apply { setPadding(dp(2), dp(4), 0, 0) })
                    return
                }
            } else Radio.ensureThumb(c)   // 받아 오면 changed()로 다시 그림
        }
        bubble.addView(TextView(this).apply {
            text = "📎 $name\n$caption"; textSize = 15f; setTextColor(cText)
            setOnClickListener { openAttachment(c) }
            setOnLongClickListener { bubbleMenu(c); true }
        })
    }

    private class Conv(val key: String, val label: String, val color: Int)

    /** 이 메시지가 '누구와의 대화'인지. 모두에게 보낸 글은 null(묶음 없음), 그 외는 상대 키·꼬리표·색 */
    private fun convOf(c: Clip): Conv? {
        if (c.mode == "all") return null
        val names = if (c.mine) c.from.split(", ").filter { it.isNotBlank() } else listOf(c.from)
        if (names.size == 1) {
            val d = Radio.disp(names[0])
            val lab = if (names[0] == prefs.name) "📝 나의 메모" else "${Family.icon(names[0])} $d${Family.josa(d, "과", "와")}의 대화"
            return Conv(names[0], lab, Family.color(names[0]))
        }
        val set = names.toSet()
        val room = Radio.rooms().firstOrNull { it.members != null && it.members.toSet() == set }
        return if (room != null) Conv(room.key, "${room.icon} ${room.label}에게", room.color)
        else Conv("c:" + names.sorted().joinToString(","), names.joinToString(", ") { Radio.disp(it) } + "에게", cSub)
    }

    private fun blendColor(fg: Int, bg: Int, a: Float): Int = Color.rgb(
        (Color.red(bg) + (Color.red(fg) - Color.red(bg)) * a).toInt(),
        (Color.green(bg) + (Color.green(fg) - Color.green(bg)) * a).toInt(),
        (Color.blue(bg) + (Color.blue(fg) - Color.blue(bg)) * a).toInt())

    private var shownRoomKey: String? = null
    private fun renderMessages() {
        val room = Radio.currentRoom()
        if (room.key != shownRoomKey) { shownRoomKey = room.key; shownCount = 40 }   // 방을 바꾸면 최근 40개부터 다시
        val shown = Radio.roomClips(room).take(shownCount).reversed()   // 오래된 것 → 최신 (최신이 아래). 맨 위로 올리면 더 그림
        val sig = room.key + "|" + Radio.schedules.joinToString(",") { "${it.sid}:${it.at}" } + "|" + shown.joinToString("|") { "${it.id}:${it.ts}:${Radio.playingClip === it}:${Radio.receiptText(it)}:${it.expired}:${if (it.thumb) Radio.thumbFile(it).exists() else ""}:${Radio.downloading[it.fid ?: ""] ?: ""}" }
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
            if (!c.mine && !same) col.addView(label("${Family.icon(c.from)} ${Radio.disp(c.from)}", 13f, whoColor, true).apply { setPadding(dp(6), 0, 0, dp(2)) })
            val bubble = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = roundRect(if (c.mine) Color.parseColor("#2A3F66") else cCard2, 16)
                setPadding(dp(14), dp(9), dp(14), dp(9))
                setOnLongClickListener { bubbleMenu(c); true }
            }
            val playing = Radio.playingClip === c
            when {
                c.isFile -> fileBubble(bubble, c)
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
            } else if (c.isText || c.isFile) {
                Radio.markSeen(c)   // 화면에 표시됨 = 봤음
            }
            col.addView(label(meta, 10f, metaColor).apply { setPadding(dp(6), dp(1), dp(6), 0) }, lp(w = ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = if (c.mine) Gravity.END else Gravity.START })
            holder.addView(col, colLp)
        }
        // 보낼 예정인 예약: 이 방 대화 맨 아래에 흐린 말풍선 (길게 누르면 취소). 시간이 되어 나가면 진짜 말풍선으로 바뀜
        val pending = Radio.schedules.filter { sc -> scheduleInRoom(sc, room) }.sortedBy { it.at }
        if (pending.isNotEmpty() && shown.isEmpty()) msgList.removeAllViews()
        pending.forEach { sc ->
            val whenTxt = scheduleWhen(sc)
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; alpha = 0.6f }
            col.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dpf(16); setColor(cCard2); setStroke(dp(1), cSub, dpf(6), dpf(4))
                }
                setPadding(dp(14), dp(9), dp(14), dp(9))
                addView(label("⏰ $whenTxt 보낼 예정" + (if (room.key == "all" && sc.to != "all") " · ${Radio.dispList(sc.to.replace(",", ", "))}에게" else ""), 11f, cAmber))
                addView(label(sc.text, 17f, cText))
                setOnLongClickListener {
                    MaterialAlertDialogBuilder(this@MainActivity).setTitle("예약 취소할까요?").setMessage("$whenTxt · ${sc.text}")
                        .setPositiveButton("예약 취소") { _, _ -> Radio.scheduleDelete(sc.sid); toast("예약을 취소했어요") }
                        .setNegativeButton("닫기", null).show()
                    true
                }
            }, lp(w = ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.END })
            col.addView(label("길게 누르면 취소", 10f, cSub).apply { setPadding(dp(6), dp(1), dp(6), 0) },
                lp(w = ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.END })
            msgList.addView(col, lp(w = ViewGroup.LayoutParams.WRAP_CONTENT, top = 10).apply { gravity = Gravity.END; marginStart = dp(48) })
        }
        if (stickBottom || (newestChanged && nearBottom)) scrollRoot.post { scrollRoot.fullScroll(View.FOCUS_DOWN) }
        stickBottom = false
    }

    private fun scheduleInRoom(sc: Schedule, room: Room): Boolean {
        if (room.members == null) return true   // 모두 방: 내 예약 전부
        if (sc.to == "all") return false
        return sc.to.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet() == room.members.toSet()
    }

    private fun scheduleWhen(sc: Schedule): String {
        val d = Calendar.getInstance().apply { timeInMillis = sc.at * 1000 }
        val today = Calendar.getInstance()
        val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
        fun same(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        val day = when { same(d, today) -> "오늘"; same(d, tomorrow) -> "내일"; else -> "%d/%d".format(d.get(Calendar.MONTH) + 1, d.get(Calendar.DAY_OF_MONTH)) }
        val rep = when (sc.repeat) { "daily" -> " (매일)"; "weekdays" -> " (평일)"; "weekends" -> " (주말)"; else -> "" }
        return "$day ${fmtTime.format(Date(sc.at * 1000))}$rep"
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

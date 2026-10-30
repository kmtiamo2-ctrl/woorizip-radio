package com.woorizip.radio

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media.VolumeProviderCompat

/**
 * 화면이 꺼진 상태에서 볼륨 버튼으로 말하기.
 *
 * 원리: 수신 대기 서비스가 "원격 재생 중인 미디어 세션"을 하나 열어 두면,
 * 다른 음악·영상이 재생 중이 아닐 때 볼륨 버튼 입력이 이 세션으로 전달됨.
 * 안드로이드는 이 경로로 "누름/뗌"을 정확히 주지 않고 "볼륨 올림/내림 요청"만 주기 때문에
 * 요청이 연달아 들어오는지(=누르고 있는지)로 길게 누름을 판단함.
 *
 * - hold 모드: 0.5초 이상 누르고 있으면 말하기 시작, 손을 떼면 끝
 * - double 모드: 빠르게 두 번 누르면 말하기 켜기, 다시 두 번 누르면 끄기
 *   (기종에 따라 길게 누름이 인식되지 않으면 이 모드를 사용)
 * - 짧게 한 번 누르면 원래처럼 미디어 음량 조절
 */
class VolumeKeyPtt(private val ctx: Context) {

    private val h = Handler(Looper.getMainLooper())
    private val am = ctx.getSystemService(AudioManager::class.java)

    private enum class State { IDLE, PENDING, HOLDING }
    private var state = State.IDLE
    private var firstAt = 0L
    private var lastAt = 0L
    private var pendingDir = 0
    private var lastPressAt = 0L
    private var lastEventAt = 0L

    val provider = object : VolumeProviderCompat(VOLUME_CONTROL_RELATIVE, 15, 7) {
        override fun onAdjustVolume(direction: Int) {
            h.post { onKey(direction) }
        }
    }

    private val shortPress = Runnable {
        if (state == State.PENDING) {
            state = State.IDLE
            adjust(pendingDir)
        }
    }

    private val releaseCheck = object : Runnable {
        override fun run() {
            if (state != State.HOLDING) return
            if (SystemClock.uptimeMillis() - lastAt > RELEASE_GAP) release() else h.postDelayed(this, 80)
        }
    }

    private val doubleSingle = Runnable { adjust(pendingDir) }

    private fun onKey(dir: Int) {
        val now = SystemClock.uptimeMillis()
        if (Prefs(ctx).keyMode == "double") {
            onKeyDouble(dir, now)
            return
        }
        when (state) {
            State.IDLE -> {
                if (dir == 0) return
                state = State.PENDING
                firstAt = now
                lastAt = now
                pendingDir = dir
                h.postDelayed(shortPress, HOLD_DECIDE)
            }
            State.PENDING -> {
                if (dir == 0) {               // 일부 기종은 손을 뗄 때 0을 보냄 → 짧은 누름
                    h.removeCallbacks(shortPress)
                    state = State.IDLE
                    adjust(pendingDir)
                    return
                }
                lastAt = now
                if (now - firstAt >= HOLD_MIN) {   // 계속 들어옴 = 누르고 있음
                    h.removeCallbacks(shortPress)
                    state = State.HOLDING
                    Radio.startTalk()
                    h.postDelayed(releaseCheck, 80)
                }
            }
            State.HOLDING -> if (dir == 0) release() else lastAt = now
        }
    }

    private fun onKeyDouble(dir: Int, now: Long) {
        if (dir == 0) return
        val repeat = now - lastEventAt < 150     // 누르고 있을 때 반복 입력은 무시
        lastEventAt = now
        if (repeat) return
        if (now - lastPressAt < DOUBLE_GAP) {
            h.removeCallbacks(doubleSingle)
            lastPressAt = 0
            Radio.toggleTalk()
        } else {
            lastPressAt = now
            pendingDir = dir
            h.postDelayed(doubleSingle, DOUBLE_GAP)
        }
    }

    private fun release() {
        state = State.IDLE
        h.removeCallbacks(releaseCheck)
        Radio.stopTalk()
    }

    private fun adjust(dir: Int) {
        if (dir == 0) return
        am.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (dir > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI
        )
    }

    fun reset() {
        h.removeCallbacksAndMessages(null)
        if (state == State.HOLDING) Radio.stopTalk()
        state = State.IDLE
    }

    companion object {
        const val HOLD_MIN = 300L      // 첫 입력 후 이 시간 넘어서 또 들어오면 길게 누름
        const val HOLD_DECIDE = 700L   // 이 시간 동안 추가 입력이 없으면 짧은 누름
        const val RELEASE_GAP = 350L   // 입력이 이만큼 끊기면 손을 뗀 것으로 판단
        const val DOUBLE_GAP = 600L
    }
}

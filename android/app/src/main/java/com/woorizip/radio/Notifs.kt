package com.woorizip.radio

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

object Notifs {
    const val CH_STANDBY = "standby"
    const val CH_INCOMING = "incoming"
    const val ID_STANDBY = 1
    const val ID_INCOMING = 2
    const val ID_BOOT = 3

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_STANDBY, "수신 대기", NotificationManager.IMPORTANCE_LOW).apply {
                description = "무전기 수신 대기 중 표시"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_INCOMING, "무음 모드 방송 알림", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "무음·진동 모드일 때 가족이 말하면 알려줌"
            }
        )
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun serviceAction(ctx: Context, action: String, code: Int): PendingIntent = PendingIntent.getService(
        ctx, code, Intent(ctx, RadioService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** 상단에 계속 떠 있는 수신 대기 알림 (말하기 시작/끝 버튼 포함) */
    fun standby(ctx: Context): Notification {
        val target = Radio.target
        val who = if (target == "all") "전체" else target
        val title = when {
            Radio.talking -> "🎙 $who 에게 말하는 중"
            Radio.incomingFrom != null -> "🔊 ${Radio.incomingFrom}님이 말하는 중"
            Radio.connected -> "우리집 무전기 · 수신 대기 중"
            else -> "우리집 무전기 · ${Radio.status}"
        }
        val text = when {
            !Radio.micReady -> "말하기를 쓰려면 앱을 한 번 열어 주세요"
            Prefs(ctx).keyMode == "double" -> "볼륨 버튼 두 번 = 말하기 켜기/끄기 · 상대: $who"
            else -> "볼륨 버튼을 길게 누르는 동안 말하기 · 상대: $who"
        }
        val talkLabel = if (Radio.talking || Radio.requesting) "말하기 끝" else "말하기 시작"
        return NotificationCompat.Builder(ctx, CH_STANDBY)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)   // 잠금 화면에서도 버튼 사용
            .setContentIntent(openApp(ctx))
            .addAction(0, talkLabel, serviceAction(ctx, RadioService.ACTION_TOGGLE, 1))
            .addAction(0, "수신 끄기", serviceAction(ctx, RadioService.ACTION_STOP, 2))
            .build()
    }

    fun showSilentIncoming(ctx: Context, from: String, mode: String) {
        if (!canNotify(ctx)) return
        val text = if (mode == "all") "전체 방송 중 (무음 모드라 소리 없이 알려드려요)" else "나에게 말하는 중 (무음 모드)"
        val n = NotificationCompat.Builder(ctx, CH_INCOMING)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode)
            .setContentTitle("${from}님")
            .setContentText(text)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .setContentIntent(openApp(ctx))
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(ID_INCOMING, n)
    }

    fun cancelSilentIncoming(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).cancel(ID_INCOMING)
    }

    /** 재부팅 후: 안드로이드 정책상 마이크 서비스를 자동으로 켤 수 없어서 한 번 눌러 달라고 안내 */
    fun showBootReminder(ctx: Context) {
        if (!canNotify(ctx)) return
        val n = NotificationCompat.Builder(ctx, CH_STANDBY)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("우리집 무전기")
            .setContentText("눌러서 수신 대기를 다시 켜 주세요")
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(ID_BOOT, n)
    }

    private fun canNotify(ctx: Context): Boolean =
        android.os.Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}

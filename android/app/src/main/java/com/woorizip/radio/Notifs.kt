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
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat

object Notifs {
    const val CH_STANDBY = "standby_quiet"   // 예전 "standby"(보통 등급)는 지우고 최소 등급으로 새로 만듦 — 안드로이드는 만들어진 종류의 등급을 앱이 못 내리므로
    const val CH_STANDBY_OLD = "standby"
    const val CH_INFO = "info"
    const val CH_INCOMING = "incoming"
    const val ID_STANDBY = 1
    const val ID_INCOMING = 2
    const val ID_BOOT = 3
    const val ID_CLIP = 4
    const val CH_CLIP = "clip"

    fun createChannels(ctx: Context) {
        Updater.createChannel(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        try { nm.deleteNotificationChannel(CH_STANDBY_OLD) } catch (_: Exception) {}
        nm.createNotificationChannel(
            // 최소 등급: 상단 줄에 아이콘이 안 뜨고, 알림창을 내렸을 때 맨 아래에 작게만 보임 (수신은 그대로)
            NotificationChannel(CH_STANDBY, "수신 대기 (조용히 표시)", NotificationManager.IMPORTANCE_MIN).apply {
                description = "뒤에서 듣고 있다는 표시. 안드로이드 규칙상 완전히 없앨 수는 없어 가장 작게 보여줌"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_INFO, "안내", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "재부팅 후 수신 대기를 다시 켜 달라는 안내 등"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_INCOMING, "무음 모드 방송 알림", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "무음·진동 모드일 때 가족이 말하면 알려줌"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_CLIP, "받은 메시지", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "밖에 있을 때나 연결이 끊겼을 때 받은 가족의 말"
                enableVibration(true)
                setSound(null, null)   // 소리는 앱이 직접 처리 (아이는 진동만, 부모·집은 바로 재생)
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

    /** 수신 대기 알림 (최소 등급이라 알림창 맨 아래에 작게 보임, 말하기 시작/끝 버튼 포함). '수신 끄기'는 아이들이 눌러 버리지 않게 앱 설정에서만 */
    fun standby(ctx: Context): Notification {
        val who = Radio.targetLabel()
        val place = ""   // 집/밖은 글자로 보여주지 않음
        val title = when {
            Radio.talking -> "🎙 $who 에게 말하는 중"
            Radio.playingClip != null -> "🔊 ${Radio.playingClip?.title} 재생 중"
            Radio.incomingFrom != null -> "🔊 ${Radio.incomingFrom}님이 말하는 중"
            Radio.connected -> "우리집 · 수신 대기 중$place"
            else -> "우리집 · ${Radio.status}"
        }
        val text = when {
            !Radio.micReady -> "말하기를 쓰려면 앱을 한 번 열어 주세요"
            Prefs(ctx).keyMode == "double" -> "볼륨 버튼 두 번 = 말하기 켜기/끄기 · 상대: $who"
            else -> "볼륨 버튼을 길게 누르는 동안 말하기 · 상대: $who"
        }
        val talkLabel = if (Radio.talking || Radio.requesting) "말하기 끝" else "말하기 시작"
        return NotificationCompat.Builder(ctx, CH_STANDBY)
            .setSmallIcon(R.drawable.ic_star)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)   // 잠금 화면에서도 버튼 사용
            .setContentIntent(openApp(ctx))
            .addAction(0, talkLabel, serviceAction(ctx, RadioService.ACTION_TOGGLE, 1))
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

    const val KEY_REPLY = "reply_text"

    /** 받은 메시지(음성·문자) 알림. 알림에서 바로 글로 답장할 수 있음 */
    fun showClip(ctx: Context, clip: Clip, autoPlayed: Boolean) {
        if (!canNotify(ctx)) return
        val play = PendingIntent.getService(
            ctx, 100 + clip.id,
            Intent(ctx, RadioService::class.java).setAction(RadioService.ACTION_PLAY_CLIP).putExtra("id", clip.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // 답장: 보낸 사람에게 글로 (RemoteInput은 MUTABLE 필요)
        val replyIntent = PendingIntent.getService(
            ctx, 5000 + clip.id,
            Intent(ctx, RadioService::class.java).setAction(RadioService.ACTION_REPLY).putExtra("to", clip.from),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val reply = NotificationCompat.Action.Builder(0, "답장", replyIntent)
            .addRemoteInput(
                RemoteInput.Builder(KEY_REPLY).setLabel("글로 답장")
                    .setChoices(Radio.quickReplies.toTypedArray()).build()
            )
            .setAllowGeneratedReplies(false)
            .build()
        val secs = clip.seconds.toInt().coerceAtLeast(1)
        val body = when {
            clip.isText -> clip.text ?: ""
            autoPlayed -> "${secs}초 · 방금 재생했어요"
            else -> "${secs}초 · 눌러서 들어 보세요"
        }
        val b = NotificationCompat.Builder(ctx, CH_CLIP)
            .setSmallIcon(R.drawable.ic_star)
            .setContentTitle(clip.title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 250, 150, 250))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(if (clip.isText) openApp(ctx) else play)
        if (!clip.isText) b.addAction(0, if (autoPlayed) "다시 듣기" else "듣기", play)
        b.addAction(reply)
        b.addAction(0, "열기", openApp(ctx))
        ctx.getSystemService(NotificationManager::class.java).notify(ID_CLIP, b.build())
    }

    fun cancelClip(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).cancel(ID_CLIP)
    }

    /** 재부팅 후: 안드로이드 정책상 마이크 서비스를 자동으로 켤 수 없어서 한 번 눌러 달라고 안내 */
    fun showBootReminder(ctx: Context) {
        if (!canNotify(ctx)) return
        val n = NotificationCompat.Builder(ctx, CH_INFO)
            .setSmallIcon(R.drawable.ic_star)
            .setContentTitle("우리집")
            .setContentText("눌러서 수신 대기를 다시 켜 주세요")
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(ID_BOOT, n)
    }

    /** 수신 대기 알림이 이 폰에서 아예 꺼져(숨겨져) 있는지 */
    fun standbyHidden(ctx: Context): Boolean = try {
        ctx.getSystemService(NotificationManager::class.java).getNotificationChannel(CH_STANDBY)?.importance == NotificationManager.IMPORTANCE_NONE
    } catch (_: Exception) { false }

    /** 기종에 따라 최소 등급으로도 상단 아이콘이 남을 때: 시스템의 '수신 대기' 알림 설정 화면을 바로 열어 줌 (거기서 끄면 알림만 사라지고 수신은 계속됨) */
    fun openStandbyChannelSettings(ctx: Context) {
        val i = Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, CH_STANDBY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(i) } catch (_: Exception) {
            try {
                ctx.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {}
        }
    }

    private fun canNotify(ctx: Context): Boolean =
        android.os.Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}

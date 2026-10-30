package com.woorizip.radio

import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * 수신 대기 서비스 (포그라운드 서비스).
 * - 화면이 꺼져도 NAS 연결을 유지하고 가족의 말을 재생
 * - 볼륨 버튼 길게 누르기 / 알림의 "말하기 시작" 버튼으로 송신
 * - 안드로이드 14+ 정책: 마이크를 쓰는 서비스는 앱 화면이 열려 있을 때 시작해야 함
 */
class RadioService : Service() {

    companion object {
        const val ACTION_TOGGLE = "com.woorizip.radio.TOGGLE"
        const val ACTION_STOP = "com.woorizip.radio.STOP"
        const val ACTION_PLAY_CLIP = "com.woorizip.radio.PLAY_CLIP"
        @Volatile var running = false; private set

        /** 반드시 앱 화면이 보이는 상태에서 호출 (마이크 사용 권한 때문) */
        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, RadioService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, RadioService::class.java).setAction(ACTION_STOP))
        }
    }

    private var wake: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var session: MediaSessionCompat? = null
    private var keys: VolumeKeyPtt? = null
    private val refresh: () -> Unit = { updateNotification() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Radio.init(this)
        Notifs.createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Prefs(this).standby = false
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE -> if (running) {
                Radio.toggleTalk()
                return START_STICKY
            }
            ACTION_PLAY_CLIP -> {
                Radio.clipById(intent.getIntExtra("id", -1))?.let { Radio.playClip(it) }
                if (running) return START_STICKY
            }
        }
        if (!goForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) {
            running = true
            Prefs(this).standby = true
            acquireLocks()
            setupVolumeKeys()
            Radio.addListener(refresh)
            Radio.startConnection()
        }
        updateNotification()
        return START_STICKY
    }

    /** 마이크+미디어 재생 유형으로 포그라운드 시작. 백그라운드에서 재시작된 경우엔 재생 전용으로 시작 */
    private fun goForeground(): Boolean {
        val notif = Notifs.standby(this)
        val withMic = if (Build.VERSION.SDK_INT >= 30)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        return try {
            ServiceCompat.startForeground(this, Notifs.ID_STANDBY, notif, withMic)
            // 앱 화면이 보일 때 시작한 경우에만 마이크를 믿을 수 있음 (백그라운드 시작은 안드로이드가 마이크를 무음 처리)
            Radio.micReady = Radio.uiVisible
            true
        } catch (_: Exception) {
            try {
                ServiceCompat.startForeground(
                    this, Notifs.ID_STANDBY, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
                Radio.micReady = false   // 듣기만 가능. 앱을 열면 다시 마이크 포함으로 올라감
                true
            } catch (_: Exception) {
                // 둘 다 실패하면 앱이 강제 종료되지 않도록 유형 없이 잠깐 올렸다가 바로 내림
                try {
                    startForeground(Notifs.ID_STANDBY, notif)
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                } catch (_: Exception) {
                }
                false
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        val pm = getSystemService(PowerManager::class.java)
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "woorizip:radio").apply {
            setReferenceCounted(false); acquire()
        }
        val wm = applicationContext.getSystemService(WifiManager::class.java)
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "woorizip:radio").apply {
            setReferenceCounted(false); acquire()
        }
    }

    private fun setupVolumeKeys() {
        val k = VolumeKeyPtt(this)
        keys = k
        session = MediaSessionCompat(this, "woorizip-radio").apply {
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setState(PlaybackStateCompat.STATE_PLAYING, 0, 1f)
                    .build()
            )
            setPlaybackToRemote(k.provider)
            isActive = true
        }
    }

    private fun updateNotification() {
        if (!running) return
        getSystemService(NotificationManager::class.java).notify(Notifs.ID_STANDBY, Notifs.standby(this))
    }

    override fun onDestroy() {
        running = false
        Radio.removeListener(refresh)
        Radio.stopConnection()
        Radio.micReady = false
        keys?.reset()
        session?.run { isActive = false; release() }
        session = null
        wake?.release()
        wifiLock?.release()
        super.onDestroy()
    }
}

/** 재부팅 후 수신 대기를 다시 켜 달라는 알림 (안드로이드 정책상 자동으로 마이크 서비스를 켤 수 없음) */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Prefs(context).standby) {
            Notifs.createChannels(context)
            Notifs.showBootReminder(context)
        }
    }
}

package com.woorizip.radio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 음성 형식: 16kHz, 모노, 16비트, 20ms 단위 (1단계는 압축 없이 전송 — 집 Wi-Fi에서는 충분) */
object AudioSpec {
    const val RATE = 16000
    const val FRAME_BYTES = 640  // 20ms
}

/** 마이크 녹음 → 20ms마다 콜백 */
class Recorder(private val onFrame: (ByteArray, Int) -> Unit) {
    @Volatile private var running = false
    private val gen = AtomicInteger()

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        val min = AudioRecord.getMinBufferSize(
            AudioSpec.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, AudioSpec.RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(min, AudioSpec.FRAME_BYTES * 8)
            )
        } catch (_: Exception) {
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        running = true
        val my = gen.incrementAndGet()
        Thread({
            val buf = ByteArray(AudioSpec.FRAME_BYTES)
            try {
                rec.startRecording()
                while (running && gen.get() == my) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) onFrame(buf, n) else if (n < 0) break
                }
            } catch (_: Exception) {
            } finally {
                try { rec.stop() } catch (_: Exception) {}
                rec.release()
            }
        }, "radio-rec").apply { priority = Thread.MAX_PRIORITY; start() }
        return true
    }

    fun stop() {
        running = false
        gen.incrementAndGet()
    }
}

/** 받은 음성 재생. 60ms 정도 모아 두었다가 재생해서 끊김을 줄임 */
class Player {
    @Volatile private var queue = LinkedBlockingQueue<ByteArray>()
    private val gen = AtomicInteger()

    fun start() {
        val q = LinkedBlockingQueue<ByteArray>()   // 재생마다 새 큐 → 이전 재생과 섞이지 않음
        queue = q
        val my = gen.incrementAndGet()
        Thread({ playLoop(q, my) }, "radio-play").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    fun feed(b: ByteArray) {
        val q = queue
        q.offer(b)
        while (q.size > 25) q.poll()  // 0.5초 넘게 밀리면 오래된 소리부터 버림
    }

    fun stop() {
        gen.incrementAndGet()
    }

    private fun playLoop(q: LinkedBlockingQueue<ByteArray>, my: Int) {
        fun alive() = gen.get() == my
        val min = AudioTrack.getMinBufferSize(
            AudioSpec.RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)          // 스피커로 바로 출력
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(AudioSpec.RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(min, AudioSpec.FRAME_BYTES * 6))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (_: Exception) {
            return
        }
        try {
            val pre = ArrayList<ByteArray>()
            while (alive() && pre.size < 3) {
                val f = q.poll(200, TimeUnit.MILLISECONDS)
                if (f != null) pre.add(f) else if (pre.isNotEmpty()) break
            }
            track.play()
            for (f in pre) track.write(f, 0, f.size)
            while (alive()) {
                val f = q.poll(300, TimeUnit.MILLISECONDS) ?: continue
                track.write(f, 0, f.size)
            }
            while (true) {  // 끝나기 직전에 도착한 소리까지 재생
                val f = q.poll() ?: break
                track.write(f, 0, f.size)
            }
            track.stop()
        } catch (_: Exception) {
        } finally {
            track.release()
        }
    }
}

/** 신호음·진동 (화면이 꺼져 있어도 송신 시작/거절을 알 수 있게) */
object Feedback {
    private val tone: ToneGenerator? by lazy {
        try { ToneGenerator(AudioManager.STREAM_MUSIC, 70) } catch (_: Exception) { null }
    }

    /** 송신 시작됨: 짧은 삐 + 짧은 진동 */
    fun granted(ctx: Context) {
        tone?.startTone(ToneGenerator.TONE_PROP_ACK, 150)
        vibrate(ctx, longArrayOf(0, 60))
    }

    /** 송신 불가 (다른 가족이 말하는 중 등): 낮은 음 + 두 번 진동 */
    fun denied(ctx: Context) {
        tone?.startTone(ToneGenerator.TONE_PROP_NACK, 250)
        vibrate(ctx, longArrayOf(0, 80, 100, 80))
    }

    /** 방송 들어옴 알림음 */
    fun incoming() {
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
    }

    fun vibrate(ctx: Context, pattern: LongArray) {
        try {
            val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
                ctx.getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Vibrator::class.java)
            }
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (_: Exception) {
        }
    }
}

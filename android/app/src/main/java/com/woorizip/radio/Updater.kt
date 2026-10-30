package com.woorizip.radio

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Network
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 자동 업데이트.
 * NAS 서버의 /update/latest.json 을 보고 새 버전이면 APK를 받아(sha256 확인) 설치를 요청함.
 * - 처음 한 번은 안드로이드가 "이 출처의 앱 설치 허용"과 [설치] 확인을 요구함 (알림을 눌러 진행)
 * - 그 뒤로는 이 앱이 자기 자신의 설치자가 되어 Android 12+에서는 확인 없이 교체될 수 있음
 */
object Updater {
    private const val TAG = "Updater"
    private const val CHECK_EVERY_MS = 6 * 3600 * 1000L
    const val CH_UPDATE = "update"
    const val ID_UPDATE = 7
    const val ACTION_RESULT = "com.woorizip.radio.UPDATE_RESULT"

    @Volatile private var lastCheck = 0L
    @Volatile private var busy = false

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun createChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            android.app.NotificationChannel(CH_UPDATE, "앱 업데이트", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "새 버전 설치 안내"
            }
        )
    }

    /** 서버에 연결될 때마다 부름. 6시간에 한 번만 실제로 확인 */
    fun maybeCheck(ctx: Context, httpBase: String?, network: Network? = null, force: Boolean = false) {
        if (httpBase.isNullOrBlank() || busy) return
        val now = System.currentTimeMillis()
        if (!force && now - lastCheck < CHECK_EVERY_MS) return
        lastCheck = now
        busy = true
        val app = ctx.applicationContext
        thread(name = "updater", isDaemon = true) {
            try {
                check(app, httpBase.trimEnd('/'), network)
            } catch (e: Exception) {
                Log.w(TAG, "업데이트 확인 실패: $e")
            } finally {
                busy = false
            }
        }
    }

    /** 설정의 [지금 업데이트 확인] 버튼: 결과를 글로 돌려줌 */
    fun checkNow(ctx: Context, onResult: (String) -> Unit) {
        val base = Radio.httpBase
        if (base.isNullOrBlank() || !Radio.connected) { onResult("서버에 연결된 뒤에 확인할 수 있어요"); return }
        if (busy) { onResult("이미 확인 중이에요"); return }
        busy = true
        lastCheck = System.currentTimeMillis()
        val app = ctx.applicationContext
        val net = Radio.currentNetwork
        thread(name = "updater", isDaemon = true) {
            try {
                onResult(check(app, base.trimEnd('/'), net))
            } catch (e: Exception) {
                onResult("확인 실패: ${e.message ?: e}")
            } finally {
                busy = false
            }
        }
    }

    private fun currentVersionCode(ctx: Context): Long = try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    } catch (_: Exception) { 0L }

    private fun check(ctx: Context, base: String, network: Network?): String {
        // 집 주소는 Wi-Fi 망으로만 (폰이 모바일 데이터를 기본으로 쓰더라도)
        val client = if (network == null) http else http.newBuilder()
            .socketFactory(network.socketFactory)
            .dns(object : Dns { override fun lookup(hostname: String): List<InetAddress> = network.getAllByName(hostname).toList() })
            .build()
        val infoText = client.newCall(Request.Builder().url("$base/update/latest.json").build()).execute().use { r ->
            if (!r.isSuccessful) return "서버에 아직 업데이트 정보가 없어요"
            r.body?.string() ?: return "서버에 아직 업데이트 정보가 없어요"
        }
        val info = JSONObject(infoText)
        val phone = info.optJSONObject("phone") ?: return "서버에 폰용 새 버전이 없어요"
        val number = info.optLong("number", 0)
        if (number <= currentVersionCode(ctx)) return "지금이 최신 버전이에요 (v${currentVersionCode(ctx)})"
        val name = File(phone.optString("file")).name
        val sha = phone.optString("sha256")
        if (name.isBlank() || sha.isBlank()) return "서버의 업데이트 정보가 이상해요"
        val dir = File(ctx.cacheDir, "update").apply { mkdirs() }
        dir.listFiles()?.forEach { if (it.name != name) it.delete() }
        val apk = File(dir, name)
        if (!(apk.exists() && sha256(apk) == sha)) {
            val tmp = File(dir, "$name.part")
            client.newCall(Request.Builder().url("$base/update/$name").build()).execute().use { r ->
                if (!r.isSuccessful) return "새 버전 파일을 받지 못했어요"
                val body = r.body ?: return "새 버전 파일을 받지 못했어요"
                tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
            }
            if (sha256(tmp) != sha) { tmp.delete(); Log.w(TAG, "받은 파일이 손상됨"); return "받은 파일이 손상돼서 다음에 다시 받아요" }
            if (!tmp.renameTo(apk)) { tmp.delete(); return "파일 저장 실패" }
        }
        Log.i(TAG, "새 버전 v$number 받음 → 설치 요청")
        install(ctx, apk, number)
        return "새 버전 v$number 을 받았어요. 설치를 진행해요 (처음이면 알림을 눌러 허용)"
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** 가능하면 조용히(PackageInstaller), 안 되면 설치 화면을 여는 알림으로 */
    private fun install(ctx: Context, apk: File, number: Long) {
        try {
            val pi = ctx.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            val id = pi.createSession(params)
            pi.openSession(id).use { s ->
                s.openWrite("app.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    s.fsync(out)
                }
                val intent = Intent(ctx, UpdateReceiver::class.java).setAction(ACTION_RESULT)
                    .putExtra("apk", apk.absolutePath).putExtra("number", number)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                s.commit(PendingIntent.getBroadcast(ctx, 0, intent, flags).intentSender)
            }
        } catch (e: Exception) {
            Log.w(TAG, "조용한 설치 실패, 설치 화면으로: $e")
            notifyManualInstall(ctx, apk, number)
        }
    }

    fun manualInstallIntent(ctx: Context, apk: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", apk)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun notifyManualInstall(ctx: Context, apk: File, number: Long) {
        notify(ctx, "새 버전 v$number 설치", "눌러서 [설치]를 확인해 주세요", manualInstallIntent(ctx, apk))
    }

    fun notify(ctx: Context, title: String, text: String, open: Intent) {
        try {
            createChannel(ctx)
            val pending = PendingIntent.getActivity(ctx, 1, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(ctx, CH_UPDATE)
                .setSmallIcon(R.drawable.ic_star)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build()
            ctx.getSystemService(NotificationManager::class.java).notify(ID_UPDATE, n)
        } catch (e: Exception) {
            Log.w(TAG, "알림 실패: $e")
        }
    }
}

/** PackageInstaller 결과: 사용자 확인이 필요하면 알림으로 이어 주고, 실패하면 설치 화면 알림 */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Updater.ACTION_RESULT) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val number = intent.getLongExtra("number", 0)
        val apkPath = intent.getStringExtra("apk")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (Radio.uiVisible) {
                    try { context.startActivity(confirm); return } catch (_: Exception) { }
                }
                Updater.notify(context, "새 버전 v$number 설치", "눌러서 설치를 허용해 주세요 (처음 한 번만)", confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> Log.i("Updater", "설치 완료 v$number")
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w("Updater", "설치 실패 $status: $msg")
                if (apkPath != null) Updater.notifyManualInstall(context, File(apkPath), number)
            }
        }
    }
}

package com.woorizip.radio

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 앱이 예기치 않게 멈추면 원인을 파일에 남겨 두고, 다음에 열 때 화면에 보여 줌 (스크린샷으로 보내 주면 고칠 수 있게).
 */
class WoorizipApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.KOREA).format(Date())
                val ver = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (_: Exception) { "" }
                crashFile(this).writeText("$stamp $ver 스레드 ${thread.name}\n$sw")
            } catch (_: Exception) {
            }
            previous?.uncaughtException(thread, e)
        }
    }

    companion object {
        fun crashFile(app: android.content.Context): File = File(app.filesDir, "crash.txt")

        /** 지난번 멈춤 기록을 읽고 지움 (없으면 null) */
        fun takeCrashReport(app: android.content.Context): String? {
            val f = crashFile(app)
            if (!f.exists()) return null
            val text = try { f.readText() } catch (_: Exception) { null }
            f.delete()
            return text?.takeIf { it.isNotBlank() }
        }
    }
}

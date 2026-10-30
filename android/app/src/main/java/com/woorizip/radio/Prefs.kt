package com.woorizip.radio

import android.content.Context

/** 앱 설정 저장소 */
class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("radio", Context.MODE_PRIVATE)

    /** NAS 주소 (예: 192.168.0.10) */
    var host: String
        get() = sp.getString("host", "") ?: ""
        set(v) = sp.edit().putString("host", v.trim()).apply()

    var port: Int
        get() = sp.getInt("port", 8765)
        set(v) = sp.edit().putInt("port", v).apply()

    /** 내 이름 (서버의 MEMBERS 중 하나) */
    var name: String
        get() = sp.getString("name", "") ?: ""
        set(v) = sp.edit().putString("name", v.trim()).apply()

    /** 가족 공용 암호 (서버의 FAMILY_KEY) */
    var key: String
        get() = sp.getString("key", "") ?: ""
        set(v) = sp.edit().putString("key", v).apply()

    /** 화면 꺼짐 볼륨키 방식: "hold" = 길게 누르는 동안 말하기, "double" = 두 번 눌러 켜고 끄기 */
    var keyMode: String
        get() = sp.getString("keyMode", "hold") ?: "hold"
        set(v) = sp.edit().putString("keyMode", v).apply()

    /** 방해금지: 켜면 소리·진동 없이 무시 */
    var dnd: Boolean
        get() = sp.getBoolean("dnd", false)
        set(v) = sp.edit().putBoolean("dnd", v).apply()

    /** 사용자가 수신 대기를 켜 두었는지 (재부팅 후 안내용) */
    var standby: Boolean
        get() = sp.getBoolean("standby", false)
        set(v) = sp.edit().putBoolean("standby", v).apply()

    /** 마지막으로 고른 대화 상대: "all" 또는 가족 이름 */
    var target: String
        get() = sp.getString("target", "all") ?: "all"
        set(v) = sp.edit().putString("target", v).apply()

    val isConfigured: Boolean get() = host.isNotBlank() && name.isNotBlank() && key.isNotBlank()
}

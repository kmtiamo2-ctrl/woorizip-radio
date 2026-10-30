package com.woorizip.radio

import android.content.Context

/** 앱 설정 저장소 */
class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("radio", Context.MODE_PRIVATE)

    /** 집 안에서 쓰는 NAS 주소 (예: 192.168.0.10 또는 192.168.0.10:8765) */
    var host: String
        get() = sp.getString("host", "") ?: ""
        set(v) = sp.edit().putString("host", v.trim()).apply()

    /** 밖에서 쓰는 주소 (Tailscale 주소 100.x.x.x 또는 DDNS 주소 wss://xxx.synology.me). 비우면 집에서만 동작 */
    var outHost: String
        get() = sp.getString("outHost", "") ?: ""
        set(v) = sp.edit().putString("outHost", v.trim()).apply()

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

    companion object {
        /** "192.168.0.10", "192.168.0.10:8765", "wss://xxx.synology.me" 등을 WebSocket 주소로 */
        fun toWsUrl(addr: String): String {
            val a = addr.trim()
            if (a.startsWith("ws://") || a.startsWith("wss://")) return a
            if (a.startsWith("https://")) return "wss://" + a.removePrefix("https://")
            if (a.startsWith("http://")) return "ws://" + a.removePrefix("http://")
            return if (a.contains(":")) "ws://$a/" else "ws://$a:8765/"
        }
    }
}

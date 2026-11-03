package com.woorizip.radio

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 첨부파일(사진·파일) 올리기/내려받기 — NAS 서버의 HTTP (POST /file, GET /file|thumb/<id>)
 * - 올릴 때 사진이면 360px JPEG 미리보기를 먼저 만들어 같은 요청에 동봉 (X-Thumb-Length)
 * - 가족 암호는 X-Key 헤더로, 보낸 사람 이름은 주소의 from= (한글이라 헤더에 못 넣음)
 * - 내려받기는 스트리밍으로 파일에 바로 씀 (큰 파일도 메모리 안 씀)
 */
object FileXfer {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.MINUTES)
        .build()

    class Upload(val name: String, val total: Long) {
        @Volatile var done = 0L
        @Volatile var finished = false
        @Volatile var error: String? = null
    }

    class Picked(val uri: Uri, val name: String, val size: Long, val mime: String)

    fun client(network: Network?): OkHttpClient =
        if (network == null) http else http.newBuilder()
            .socketFactory(network.socketFactory)
            .dns(object : Dns { override fun lookup(hostname: String): List<InetAddress> = network.getAllByName(hostname).toList() })
            .build()

    /** 고른 Uri의 이름·크기·종류 */
    fun describe(ctx: Context, uri: Uri): Picked {
        var name = uri.lastPathSegment ?: "file"
        var size = -1L
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    val si = c.getColumnIndex(OpenableColumns.SIZE); if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        } catch (_: Exception) {}
        val mime = ctx.contentResolver.getType(uri) ?: guessMime(name)
        return Picked(uri, name.take(100), size, mime)
    }

    fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "gif" -> "image/gif"; "webp" -> "image/webp"; "heic" -> "image/heic"
        "mp4" -> "video/mp4"; "mov" -> "video/quicktime"; "pdf" -> "application/pdf"; "txt" -> "text/plain"
        "zip" -> "application/zip"; "mp3" -> "audio/mpeg"; "m4a" -> "audio/mp4"
        else -> "application/octet-stream"
    }

    fun isImage(mime: String?, name: String?) = (mime ?: "").startsWith("image/") ||
        (name ?: "").substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "bmp")

    /** 사진 미리보기(JPEG, 긴 변 360px). 방향(EXIF)도 바로잡음. 실패하면 null */
    fun makeThumb(ctx: Context, uri: Uri, maxPx: Int = 360): ByteArray? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > maxPx * 2 || bounds.outHeight / sample > maxPx * 2) sample *= 2
        val bmp = ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val rot = try {
            ctx.contentResolver.openInputStream(uri)?.use { s ->
                when (ExifInterface(s).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f; ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f; else -> 0f
                }
            } ?: 0f
        } catch (_: Exception) { 0f }
        val scale = minOf(1f, maxPx.toFloat() / maxOf(bmp.width, bmp.height))
        val m = Matrix().apply { postScale(scale, scale); if (rot != 0f) postRotate(rot) }
        val small = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 78, out)
        if (small !== bmp) bmp.recycle()
        out.toByteArray()
    } catch (_: Throwable) { null }

    /**
     * 올리기 (호출한 스레드에서 블로킹 — 백그라운드에서 부를 것). 성공하면 서버가 돌려준 첨부 정보(JSON), 실패하면 예외
     * to: "all" 또는 "엄마,첫째"
     */
    fun upload(ctx: Context, base: String, network: Network?, key: String, from: String, to: String, picked: Picked, up: Upload): JSONObject {
        val thumb = if (isImage(picked.mime, picked.name)) makeThumb(ctx, picked.uri) else null
        // 크기를 모르는 Uri(일부 공유 앱)는 캐시에 복사해 크기를 확정
        var src: File? = null
        var size = picked.size
        if (size < 0) {
            src = File(ctx.cacheDir, "upload_tmp").apply { parentFile?.mkdirs() }
            ctx.contentResolver.openInputStream(picked.uri)?.use { i -> src!!.outputStream().use { o -> i.copyTo(o) } }
            size = src.length()
        }
        up.done = 0
        val total = size + (thumb?.size ?: 0)
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = total
            override fun writeTo(sink: BufferedSink) {
                if (thumb != null) { sink.write(thumb); up.done += thumb.size }
                val input: InputStream = src?.inputStream() ?: (ctx.contentResolver.openInputStream(picked.uri) ?: throw IllegalStateException("파일을 열 수 없어요"))
                input.use { i ->
                    val buf = ByteArray(256 * 1024)
                    var sent = 0L
                    while (true) {
                        val n = i.read(buf); if (n <= 0) break
                        sink.write(buf, 0, n); sent += n; up.done += n
                    }
                    if (sent != size) throw IllegalStateException("파일 크기가 달라요")
                }
            }
        }
        val url = base + "/file?from=" + enc(from) + "&to=" + enc(to) + "&name=" + enc(picked.name) + "&mime=" + enc(picked.mime)
        val req = Request.Builder().url(url).post(body).header("X-Key", key).header("X-Thumb-Length", (thumb?.size ?: 0).toString()).build()
        try {
            client(network).newCall(req).execute().use { r ->
                val text = r.body?.string() ?: ""
                if (!r.isSuccessful) {
                    val err = try { JSONObject(text).optString("error") } catch (_: Exception) { "" }
                    throw IllegalStateException(when (err) {
                        "too_large" -> "파일이 너무 커요 (최대 4GB)"; "disk_full" -> "NAS 저장 공간이 부족해요"
                        "no_target" -> "받을 사람이 없어요"; "auth" -> "가족 암호가 달라요"; else -> "보내지 못했어요 (${r.code})"
                    })
                }
                return JSONObject(text)
            }
        } finally {
            src?.delete()
        }
    }

    /** 내려받기 (블로킹). 410이면 "expired" 예외 */
    fun download(base: String, network: Network?, key: String, kind: String, fid: String, dest: File, onProgress: ((Float) -> Unit)? = null) {
        val tmp = File(dest.path + ".part")
        dest.parentFile?.mkdirs()
        val req = Request.Builder().url("$base/$kind/$fid").header("X-Key", key).build()
        client(network).newCall(req).execute().use { r ->
            if (r.code == 410) throw IllegalStateException("expired")
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
            val b = r.body ?: throw IllegalStateException("empty")
            val total = b.contentLength()
            var got = 0L
            b.byteStream().use { i ->
                tmp.outputStream().use { o ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = i.read(buf); if (n <= 0) break
                        o.write(buf, 0, n); got += n
                        if (total > 0) onProgress?.invoke(got.toFloat() / total)
                    }
                }
            }
        }
        if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true); tmp.delete() }
    }

    /** 다른 앱으로 열기 (FileProvider) */
    fun openIntent(ctx: Context, file: File, mime: String?): Intent {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime?.takeIf { it.isNotBlank() } ?: guessMime(file.name))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun shareIntent(ctx: Context, file: File, mime: String?): Intent {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
        return Intent(Intent.ACTION_SEND).setType(mime?.takeIf { it.isNotBlank() } ?: guessMime(file.name))
            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** 사진·동영상을 갤러리(사진 앱)에 저장 — Pictures/우리집 (Android 10+). 그 아래 버전은 공유 시트로 대신 */
    fun saveToGallery(ctx: Context, file: File, name: String, mime: String?): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        val type = mime?.takeIf { it.isNotBlank() } ?: guessMime(name)
        val isVideo = type.startsWith("video/")
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, type)
            put(MediaStore.MediaColumns.RELATIVE_PATH, (if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/우리집")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri = ctx.contentResolver.insert(collection, values) ?: return false
        return try {
            ctx.contentResolver.openOutputStream(uri)?.use { o -> file.inputStream().use { it.copyTo(o) } }
            values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            ctx.contentResolver.update(uri, values, null, null)
            true
        } catch (_: Exception) {
            try { ctx.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
            false
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}

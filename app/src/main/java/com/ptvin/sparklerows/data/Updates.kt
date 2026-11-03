package com.ptvin.sparklerows.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.ptvin.sparklerows.R
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Atualizacoes pela API publica das releases do GitHub.
 * Verifica no maximo uma vez por dia.
 */
object Updates {
    private const val API =
        "https://api.github.com/repos/vinnyoliveira/sparkle-rows-for-monet/releases/latest"
    private const val CHECK_EVERY_MS = 24L * 60 * 60 * 1000

    class Release(val version: String, val url: String, val name: String)

    fun check(context: Context, current: String): Release? {
        val now = System.currentTimeMillis()
        if (now - Prefs.lastUpdateCheck(context) < CHECK_EVERY_MS) return null
        val body = get(API) ?: return null
        Prefs.setLastUpdateCheck(context, now)

        val json = JSONObject(body)
        val version = json.optString("tag_name").removePrefix("v").trim()
        if (!isNewer(version, current)) return null
        if (Prefs.ignoredVersion(context) == version) return null

        val assets = json.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name")
            if (name.endsWith(".apk")) {
                return Release(version, asset.optString("browser_download_url"), name)
            }
        }
        return null
    }

    /** Descarrega o APK e entrega-o ao instalador do sistema. Fora da thread principal. */
    fun downloadAndInstall(context: Context, release: Release) {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        val file = File(dir, release.name)
        val conn = (URL(release.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("Accept", "application/octet-stream")
            setRequestProperty("User-Agent", "SparkleRows/${com.ptvin.sparklerows.BuildConfig.VERSION_NAME}")
        }
        conn.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
        if (file.length() < 100_000) throw IOException(context.getString(R.string.update_error))

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** "1.10" e mais recente que "1.9"; compara numero a numero. */
    fun isNewer(latest: String, current: String): Boolean {
        val a = latest.split('.').mapNotNull { it.toIntOrNull() }
        val b = current.split('.').mapNotNull { it.toIntOrNull() }
        if (a.isEmpty() || b.isEmpty()) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(i) { 0 }
            val right = b.getOrElse(i) { 0 }
            if (left != right) return left > right
        }
        return false
    }

    private fun get(url: String): String? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "SparkleRows")
        }
        if (conn.responseCode !in 200..299) return null
        conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()
}

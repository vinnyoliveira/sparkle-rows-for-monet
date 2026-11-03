package com.ptvin.sparklerows.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.tvprovider.media.tv.TvContractCompat

/**
 * O Sparkle aceita um canal por deep link:
 *   content://se.hedekonsult.sparkle.base/channel/<id>  -> MainActivity
 * O id e a posicao do canal na lista importada + um deslocamento fixo, que
 * varia com o estado da base de dados do Sparkle. O deslocamento e calculado
 * comparando os canais recentes que o Sparkle publica no TvProvider com a lista.
 */
object Sparkle {
    const val PACKAGE = "se.hedekonsult.sparkle"
    private const val RECENT_CHANNEL = "homescreen_recent_channels"
    private const val ACTIVITY = "$PACKAGE.MainActivity"

    data class Recent(val title: String, val id: Long)

    /** Canais que o Sparkle publicou como "Canais recentes" (titulo + id interno). */
    fun recent(context: Context): List<Recent> {
        var channelId: Long? = null
        context.contentResolver.query(
            TvContractCompat.Channels.CONTENT_URI,
            arrayOf("_id", "package_name", "internal_provider_id"),
            null,
            null,
            null,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndex("_id")
            val pkgCol = cursor.getColumnIndex("package_name")
            val keyCol = cursor.getColumnIndex("internal_provider_id")
            while (cursor.moveToNext()) {
                if (cursor.getString(pkgCol) == PACKAGE && cursor.getString(keyCol) == RECENT_CHANNEL) {
                    channelId = cursor.getLong(idCol)
                }
            }
        }
        val id = channelId ?: return emptyList()

        val out = ArrayList<Recent>()
        val pattern = Regex("/channel/(\\d+)")
        context.contentResolver.query(
            TvContractCompat.buildPreviewProgramsUriForChannel(id),
            arrayOf("title", "intent_uri"),
            null,
            null,
            null,
        )?.use { cursor ->
            val titleCol = cursor.getColumnIndex("title")
            val intentCol = cursor.getColumnIndex("intent_uri")
            while (cursor.moveToNext()) {
                val title = cursor.getString(titleCol) ?: continue
                val uri = cursor.getString(intentCol) ?: continue
                val match = pattern.find(uri) ?: continue
                out += Recent(title, match.groupValues[1].toLong())
            }
        }
        return out
    }

    /** Deslocamento mais votado entre posicao na lista e id no Sparkle. */
    fun offset(channels: List<Channel>, recent: List<Recent>): Pair<Int, Int>? {
        if (recent.isEmpty() || channels.isEmpty()) return null
        val byName = HashMap<String, Int>()
        channels.forEachIndexed { index, channel -> byName.getOrPut(normalize(channel.name)) { index + 1 } }

        val votes = HashMap<Int, Int>()
        recent.forEach { entry ->
            val position = byName[normalize(entry.title)] ?: return@forEach
            val delta = (entry.id - position).toInt()
            votes[delta] = (votes[delta] ?: 0) + 1
        }
        val best = votes.maxByOrNull { it.value } ?: return null
        return best.key to best.value
    }

    fun deepLink(id: Long): String =
        Intent(Intent.ACTION_VIEW, Uri.parse("content://$PACKAGE.base/channel/$id"))
            .setComponent(ComponentName(PACKAGE, ACTIVITY))
            .toUri(Intent.URI_INTENT_SCHEME)

    /** Logo do canal no EPG do Sparkle (o mesmo que ele mostra nos cartoes dele). */
    fun channelLogo(context: Context, id: Long): android.graphics.Bitmap? = runCatching {
        val uri = Uri.parse("content://$PACKAGE.images/channel/$id?action_id=261120")
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 640 || bounds.outHeight / (sample * 2) >= 640) sample *= 2
        val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }.getOrNull()

    /** Abre o Sparkle na home (sem canal especifico). */
    fun openApp(context: Context): Intent =
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            .setPackage(PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun isInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess

    private fun normalize(value: String): String =
        value.lowercase().replace(Regex("\\s+"), " ").trim()
}

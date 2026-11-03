package com.ptvin.sparklerows.data

import android.content.Context

object Prefs {
    /** Deslocamento entre a posicao na lista e o id do Sparkle. */
    const val DEFAULT_OFFSET = 35

    private fun sp(context: Context) =
        context.getSharedPreferences("sparkle", Context.MODE_PRIVATE)

    fun url(context: Context): String = sp(context).getString("url", "").orEmpty()

    fun setUrl(context: Context, value: String) =
        sp(context).edit().putString("url", value).apply()

    fun epgUrl(context: Context): String = sp(context).getString("epg", "").orEmpty()

    fun setEpgUrl(context: Context, value: String) =
        sp(context).edit().putString("epg", value).apply()

    fun published(context: Context): Boolean = sp(context).getBoolean("published", false)

    fun setPublished(context: Context, value: Boolean) =
        sp(context).edit().putBoolean("published", value).apply()

    fun lastSync(context: Context): Long = sp(context).getLong("lastSync", 0L)

    fun setLastSync(context: Context, value: Long) =
        sp(context).edit().putLong("lastSync", value).apply()

    fun groups(context: Context): Set<String> =
        sp(context).getStringSet("groups", emptySet()).orEmpty()

    fun setGroups(context: Context, value: Set<String>) =
        sp(context).edit().putStringSet("groups", value).apply()

    fun channelId(context: Context, group: String): Long =
        sp(context).getLong("ch:$group", -1L)

    fun setChannelId(context: Context, group: String, id: Long) =
        sp(context).edit().putLong("ch:$group", id).apply()

    fun clearChannelId(context: Context, group: String) =
        sp(context).edit().remove("ch:$group").apply()

    /** Quantos programas seguintes aparecem no cartao (0 = nenhum). */
    fun nextCount(context: Context): Int = sp(context).getInt("nextCount", 1).coerceIn(0, 3)

    fun setNextCount(context: Context, value: Int) =
        sp(context).edit().putInt("nextCount", value.coerceIn(0, 3)).apply()

    fun ignoredVersion(context: Context): String? = sp(context).getString("ignored", null)

    fun setIgnoredVersion(context: Context, value: String) =
        sp(context).edit().putString("ignored", value).apply()

    fun lastUpdateCheck(context: Context): Long = sp(context).getLong("updateCheck", 0L)

    fun setLastUpdateCheck(context: Context, value: Long) =
        sp(context).edit().putLong("updateCheck", value).apply()

    fun intOrNull(context: Context, key: String): Int? {
        val prefs = sp(context)
        return if (prefs.contains(key)) prefs.getInt(key, 0) else null
    }

    fun setInt(context: Context, key: String, value: Int) =
        sp(context).edit().putInt(key, value).apply()
}

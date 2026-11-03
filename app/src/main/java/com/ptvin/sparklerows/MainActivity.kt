package com.ptvin.sparklerows

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.ptvin.sparklerows.channel.Art
import com.ptvin.sparklerows.data.Prefs
import com.ptvin.sparklerows.data.Sync
import com.ptvin.sparklerows.data.Updates
import kotlin.concurrent.thread

/**
 * Primeira vez: pede a lista e o EPG (o EPG pode vir do cabecalho da lista).
 * Depois disso: sincroniza sozinha e mostra o estado. O botao limpa o cache.
 */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var action: Button
    private lateinit var updateRow: LinearLayout
    private lateinit var updateText: TextView
    private lateinit var updateButton: Button
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SyncWorker.schedule(this)
        if (intent?.getBooleanExtra("dump", false) == true) Debug.dump(this)
        showMain()
    }

    /** Estado normal: titulo, resultado da sincronizacao e um botao. */
    private fun showMain() {
        val root = Ui.root(this)
        root.addView(Ui.title(this))
        root.addView(Ui.text(this, 15f, Ui.MUTED).apply {
            text = getString(R.string.status_intro)
            setPadding(0, Ui.dp(this@MainActivity, 6), 0, 0)
        })
        status = Ui.text(this, 17f, Ui.ACCENT).apply {
            text = getString(R.string.status_syncing)
            setPadding(0, Ui.dp(this@MainActivity, 26), 0, Ui.dp(this@MainActivity, 22))
        }
        root.addView(status)

        updateRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(0, 0, 0, Ui.dp(this@MainActivity, 20))
        }
        updateText = Ui.text(this, 16f, Ui.TEXT)
        updateButton = Ui.button(this, getString(R.string.update_now)) { install() }
        updateRow.addView(updateText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        updateRow.addView(updateButton)
        root.addView(updateRow)

        action = Ui.button(this, getString(R.string.action_refresh)) { sync(true) }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(action)
        // Um botao que roda entre nenhum/1/2/3: o cartao mostra os proximos programas do EPG.
        val next = Ui.button(this, nextLabel()) {
            Prefs.setNextCount(this, (Prefs.nextCount(this) + 1) % 4)
            showMain()
        }
        buttons.addView(next, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { leftMargin = Ui.dp(this@MainActivity, 16) })
        root.addView(buttons)

        setContentView(root)
        action.requestFocus()
        if (Prefs.url(this).isBlank()) showSetup() else sync(false)
        checkUpdate()
    }

    /** Primeira vez (ou dados apagados): lista + EPG, uma unica vez. */
    private fun showSetup() {
        val root = Ui.root(this)
        root.addView(Ui.title(this))
        root.addView(Ui.text(this, 15f, Ui.MUTED).apply {
            text = getString(R.string.setup_intro)
            setPadding(0, Ui.dp(this@MainActivity, 8), 0, Ui.dp(this@MainActivity, 18))
        })

        val list = Ui.field(this, getString(R.string.setup_list))
        val epg = Ui.field(this, getString(R.string.setup_epg))
        root.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(epg, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Ui.dp(this@MainActivity, 10)
        })
        root.addView(Ui.text(this, 13f, Ui.MUTED).apply {
            text = getString(R.string.setup_epg_hint)
            setPadding(Ui.dp(this@MainActivity, 4), Ui.dp(this@MainActivity, 6), 0, 0)
        })

        val save = Ui.button(this, getString(R.string.setup_save)) {
            val url = list.text.toString().trim()
            val guide = epg.text.toString().trim()
            when {
                !isLink(url) -> status.text = getString(R.string.setup_invalid)
                guide.isNotEmpty() && !isLink(guide) -> status.text = getString(R.string.setup_invalid)
                else -> {
                    Prefs.setUrl(this, url)
                    Prefs.setEpgUrl(this, guide)
                    showMain()
                }
            }
        }
        root.addView(save, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Ui.dp(this@MainActivity, 22)
        })
        status = Ui.text(this, 15f, Ui.DANGER)
        root.addView(status)

        setContentView(root)
        list.requestFocus()
    }

    private fun isLink(value: String) =
        value.startsWith("http://", true) || value.startsWith("https://", true)

    private fun nextLabel(): String {
        val count = Prefs.nextCount(this)
        return getString(
            R.string.next_setting,
            if (count == 0) getString(R.string.next_none) else count.toString(),
        )
    }

    private fun sync(clearCache: Boolean) {
        if (busy) return
        busy = true
        action.isEnabled = false
        status.text = getString(R.string.status_syncing)
        thread {
            if (clearCache) Art.cleanup(this, 0)
            val result = runCatching { Sync.run(this) }
                .getOrElse { getString(R.string.status_error, it.message.orEmpty()) }
            Prefs.setLastSync(this, System.currentTimeMillis())
            runOnUiThread {
                status.text = result
                action.isEnabled = true
                busy = false
                action.requestFocus()
            }
        }
    }

    /** Verifica a release mais recente no GitHub (uma vez por dia). */
    private fun checkUpdate() {
        thread {
            val update = runCatching { Updates.check(this, BuildConfig.VERSION_NAME) }.getOrNull()
                ?: return@thread
            runOnUiThread {
                updateText.text = getString(R.string.update_available, update.version)
                updateRow.visibility = View.VISIBLE
                updateRow.tag = update
            }
        }
    }

    private fun install() {
        val update = updateRow.tag as? Updates.Release ?: return
        updateButton.isEnabled = false
        updateText.text = getString(R.string.update_downloading)
        thread {
            val error = runCatching { Updates.downloadAndInstall(this, update) }.exceptionOrNull()
            runOnUiThread {
                updateButton.isEnabled = true
                updateText.text = error?.let { getString(R.string.update_error) }
                    ?: getString(R.string.update_available, update.version)
            }
        }
    }
}

package com.example.wolremote

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var st: Settings
    private lateinit var status: TextView
    private lateinit var game: TextView
    private lateinit var msg: TextView
    private lateinit var queue: QueueTab
    private lateinit var qb: QbTab
    private lateinit var views: List<View>
    private lateinit var navs: List<TextView>
    private var current = -1
    private val handler = Handler(Looper.getMainLooper())

    private val poll = object : Runnable {
        override fun run() {
            if (current == 0) refreshPc()
            if (current == 2) queue.refresh()
            if (current == 3) qb.refresh()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        st = Settings(this)
        window.statusBarColor = Pal.BG
        window.navigationBarColor = Pal.CARD
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        val search = SearchTab(this, st)
        val qbc = QbClient(st)
        queue = QueueTab(this, st, qbc)
        qb = QbTab(this, st, qbc)
        val links = LinksTab(this, st)
        val settings = SettingsTab(this, st)
        views = listOf(buildPcTab(), search.view, queue.view, qb.view, links.view, settings.view)

        val frame = FrameLayout(this)
        for (v in views) frame.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))

        val nav = LinearLayout(this)
        nav.orientation = LinearLayout.HORIZONTAL
        nav.setBackgroundColor(Pal.CARD)
        nav.setPadding(dp(6), dp(6), dp(6), dp(6))
        val icons = listOf("💻", "🔎", "⬇", "📦", "🔗", "⚙")
        val names = listOf("PC", "Chercher", "En cours", "qBit", "Liens", "Réglages")
        val list = ArrayList<TextView>()
        for (i in icons.indices) {
            val s = SpannableString(icons[i] + "\n" + names[i])
            s.setSpan(RelativeSizeSpan(1.5f), 0, icons[i].length, 0)
            val t = tv("", 11f, Pal.MUTED)
            t.text = s
            t.gravity = Gravity.CENTER
            t.setPadding(0, dp(8), 0, dp(8))
            t.setOnClickListener { show(i) }
            val lp = LinearLayout.LayoutParams(0, WRAP, 1f)
            lp.leftMargin = dp(2)
            lp.rightMargin = dp(2)
            nav.addView(t, lp)
            list.add(t)
        }
        navs = list

        val divider = View(this)
        divider.setBackgroundColor(Pal.BORDER)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Pal.BG)
        root.addView(frame, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(divider, LinearLayout.LayoutParams(MATCH, dp(1)))
        root.addView(nav, LinearLayout.LayoutParams(MATCH, WRAP))
        setContentView(root)
        show(0)
    }

    private val updater by lazy { Updater(this) }
    override fun onResume() { super.onResume(); handler.post(poll); updater.check() }
    override fun onPause() { super.onPause(); handler.removeCallbacks(poll) }

    private fun show(i: Int) {
        hideKeyboard()
        val changed = i != current
        current = i
        for ((k, v) in views.withIndex()) v.visibility = if (k == i) View.VISIBLE else View.GONE
        for ((k, t) in navs.withIndex()) {
            if (k == i) {
                t.setTextColor(Color.WHITE)
                t.background = rounded(Pal.CARD2, 12)
            } else {
                t.setTextColor(Pal.MUTED)
                t.background = null
            }
        }
        if (changed) {
            val v = views[i]
            v.alpha = 0f
            v.animate().alpha(1f).setDuration(160).start()
        }
        if (i == 0) refreshPc()
        if (i == 2) queue.refresh()
        if (i == 3) qb.refresh()
    }

    // ---------- Onglet PC (Wake-on-LAN inchangé + Chroniques) ----------
    private fun pair(l: Button, r: Button): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val lp1 = LinearLayout.LayoutParams(0, dp(72), 1f)
        lp1.topMargin = dp(10)
        lp1.rightMargin = dp(5)
        val lp2 = LinearLayout.LayoutParams(0, dp(72), 1f)
        lp2.topMargin = dp(10)
        lp2.leftMargin = dp(5)
        row.addView(l, lp1)
        row.addView(r, lp2)
        return row
    }

    private fun buildPcTab(): View {
        val sv = ScrollView(this)
        sv.isFillViewport = true
        sv.isVerticalScrollBarEnabled = false
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(20), dp(20), dp(20), dp(24))
        sv.addView(col)

        col.addView(title("Mon PC"))

        val hero = card()
        status = tv("Vérification…", 28f, Pal.MUTED)
        status.typeface = Typeface.DEFAULT_BOLD
        status.gravity = Gravity.CENTER
        game = tv("● Chroniques …", 16f, Pal.MUTED)
        game.gravity = Gravity.CENTER
        game.setPadding(0, dp(8), 0, 0)
        hero.setPadding(dp(16), dp(24), dp(16), dp(24))
        hero.addView(status)
        hero.addView(game)
        col.addView(hero)

        col.addView(section("Ordinateur"))
        col.addView(pair(
            btn("⏻  Allumer", Pal.GREEN, 72) { act("/wake") },
            btn("Éteindre", Pal.RED, 72) {
                dialogBuilder().setMessage("Éteindre le PC ?")
                    .setPositiveButton("Éteindre") { _, _ -> act("/shutdown") }
                    .setNegativeButton("Annuler", null).show()
            }
        ))

        col.addView(section("Chroniques"))
        col.addView(pair(
            btn("Lancer", Pal.PURPLE, 72) { act("/game/start") },
            btn("Arrêter", Pal.GRAY, 72) {
                dialogBuilder().setMessage("Arrêter le serveur Chroniques ?")
                    .setPositiveButton("Arrêter") { _, _ -> act("/game/stop") }
                    .setNegativeButton("Annuler", null).show()
            }
        ))

        msg = tv("", 14f, Pal.MUTED)
        msg.gravity = Gravity.CENTER
        msg.setPadding(0, dp(18), 0, 0)
        col.addView(msg)
        return sv
    }

    private fun refreshPc() {
        thread {
            try {
                val r = Net.call("GET", st.tablet() + "/status", timeout = 6000)
                val online = JSONObject(r.body).getBoolean("online")
                val running = try {
                    JSONObject(Net.call("GET", st.tablet() + "/game/status", timeout = 8000).body).getBoolean("running")
                } catch (e: Exception) { false }
                runOnUiThread {
                    status.textSize = 28f
                    status.text = if (online) "● Allumé" else "● Éteint"
                    status.setTextColor(if (online) Pal.GREEN else Pal.RED)
                    game.text = if (running) "● Chroniques allumé" else "● Chroniques éteint"
                    game.setTextColor(if (running) Pal.GREEN else Pal.RED)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.textSize = 22f
                    status.text = "Serveur injoignable"
                    status.setTextColor(Pal.MUTED)
                    game.text = "● Chroniques …"
                    game.setTextColor(Pal.MUTED)
                }
            }
        }
    }

    private fun act(path: String) {
        msg.text = "…"
        thread {
            val text = try {
                val body = JSONObject().put("pin", st.pin()).toString()
                val r = Net.call("POST", st.tablet() + path, json = body, timeout = 25000)
                JSONObject(r.body).optString("message", "OK")
            } catch (e: Exception) {
                "Erreur : " + e.message
            }
            runOnUiThread { msg.text = text }
            Thread.sleep(2000)
            runOnUiThread { refreshPc() }
        }
    }
}

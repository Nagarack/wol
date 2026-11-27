package com.example.wolremote

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
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
    private lateinit var views: List<View>
    private lateinit var navs: List<TextView>
    private var current = 0
    private val handler = Handler(Looper.getMainLooper())

    private val poll = object : Runnable {
        override fun run() {
            if (current == 0) refreshPc()
            if (current == 2) queue.refresh()
            handler.postDelayed(this, 4000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        st = Settings(this)
        window.statusBarColor = Pal.BG
        window.navigationBarColor = Pal.BG
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        val search = SearchTab(this, st)
        queue = QueueTab(this, st)
        val settings = SettingsTab(this, st)
        views = listOf(buildPcTab(), search.view, queue.view, settings.view)

        val frame = FrameLayout(this)
        for (v in views) frame.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))

        val nav = LinearLayout(this)
        nav.orientation = LinearLayout.HORIZONTAL
        nav.setBackgroundColor(Pal.CARD)
        val labels = listOf("⏻\nPC", "🔎\nRecherche", "⬇\nEn cours", "⚙\nRéglages")
        val list = ArrayList<TextView>()
        for ((i, l) in labels.withIndex()) {
            val t = tv(l, 13f, Pal.MUTED)
            t.gravity = Gravity.CENTER
            t.setPadding(0, dp(10), 0, dp(10))
            t.setOnClickListener { show(i) }
            nav.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
            list.add(t)
        }
        navs = list

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Pal.BG)
        root.addView(frame, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(nav, LinearLayout.LayoutParams(MATCH, WRAP))
        setContentView(root)
        show(0)
    }

    override fun onResume() { super.onResume(); handler.post(poll) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(poll) }

    private fun show(i: Int) {
        current = i
        for ((k, v) in views.withIndex()) v.visibility = if (k == i) View.VISIBLE else View.GONE
        for ((k, t) in navs.withIndex()) t.setTextColor(if (k == i) Color.WHITE else Pal.MUTED)
        if (i == 0) refreshPc()
        if (i == 2) queue.refresh()
    }

    // ---------- Onglet PC (Wake-on-LAN inchangé + Chroniques) ----------
    private fun buildPcTab(): View {
        val sv = ScrollView(this)
        sv.isFillViewport = true
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER
        col.setPadding(dp(28), dp(28), dp(28), dp(28))
        sv.addView(col)

        val title = tv("Mon PC", 28f)
        title.gravity = Gravity.CENTER
        col.addView(title)

        status = tv("Vérification…", 16f, Pal.MUTED)
        status.gravity = Gravity.CENTER
        status.setPadding(0, dp(6), 0, dp(4))
        col.addView(status)

        game = tv("● Chroniques …", 16f, Pal.MUTED)
        game.gravity = Gravity.CENTER
        game.setPadding(0, 0, 0, dp(24))
        col.addView(game)

        col.addView(btn("⏻  Allumer", Pal.GREEN, 64) { act("/wake") })
        col.addView(btn("Éteindre", Pal.RED, 64) {
            dialogBuilder().setMessage("Éteindre le PC ?")
                .setPositiveButton("Éteindre") { _, _ -> act("/shutdown") }
                .setNegativeButton("Annuler", null).show()
        })
        col.addView(btn("Lancer Chroniques", Pal.PURPLE, 64) { act("/game/start") })
        col.addView(btn("Arrêter Chroniques", Pal.GRAY, 64) {
            dialogBuilder().setMessage("Arrêter le serveur Chroniques ?")
                .setPositiveButton("Arrêter") { _, _ -> act("/game/stop") }
                .setNegativeButton("Annuler", null).show()
        })

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
                    status.text = if (online) "● Allumé" else "● Éteint"
                    status.setTextColor(if (online) Pal.GREEN else Pal.RED)
                    game.text = if (running) "● Chroniques allumé" else "● Chroniques éteint"
                    game.setTextColor(if (running) Pal.GREEN else Pal.RED)
                }
            } catch (e: Exception) {
                runOnUiThread {
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

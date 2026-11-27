package com.example.wolremote

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    private lateinit var vac: TextView
    private lateinit var upd: TextView
    private lateinit var bWake: Button
    private lateinit var bOff: Button
    private lateinit var bRestart: Button
    private lateinit var bScreens: Button
    private lateinit var bStart: Button
    private lateinit var bStop: Button
    private lateinit var queue: QueueTab
    private lateinit var qb: QbTab
    private lateinit var media: MediaTab
    private lateinit var settingsTab: SettingsTab
    private lateinit var searchTab: SearchTab
    private lateinit var views: List<View>
    private lateinit var navs: List<TextView>
    private var current = -1
    private val handler = Handler(Looper.getMainLooper())

    private val poll = object : Runnable {
        override fun run() {
            if (current == 0) { refreshPc(); power.refresh() }
            if (current == 2) queue.refresh()
            if (current == 3) qb.refresh()
            if (current == 4) media.refresh()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        st = Settings(this)
        window.statusBarColor = Pal.BG
        window.navigationBarColor = Pal.CARD
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        searchTab = SearchTab(this, st)
        val search = searchTab
        val qbc = QbClient(st)
        // Un téléchargement vient de se terminer : on laisse à Radarr/Sonarr le temps d'importer, puis on force Jellyfin
        // (si la surveillance en arrière-plan est active, c'est elle qui s'en charge)
        qbc.onComplete = { _ ->
            if (st.get("kWatch") != "1") thread {
                Thread.sleep(90000)
                Jellyfin.refresh(st)
                Thread.sleep(120000)
                Jellyfin.refresh(st)
                Subs.autoRecent(st)
            }
        }
        queue = QueueTab(this, st, qbc)
        qb = QbTab(this, st, qbc)
        media = MediaTab(this, st)
        settingsTab = SettingsTab(this, st)
        val settings = settingsTab
        views = listOf(buildPcTab(), search.view, queue.view, qb.view, media.view, settings.view)

        val frame = FrameLayout(this)
        for (v in views) frame.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))

        val nav = LinearLayout(this)
        nav.orientation = LinearLayout.HORIZONTAL
        nav.setBackgroundColor(Pal.CARD)
        nav.setPadding(dp(6), dp(6), dp(6), dp(6))
        val icons = listOf(R.drawable.ic_pc, R.drawable.ic_search, R.drawable.ic_download, R.drawable.ic_swap, R.drawable.ic_play, R.drawable.ic_settings)
        val names = listOf("PC", "Chercher", "En cours", "qBit", "Média", "Réglages")
        val list = ArrayList<TextView>()
        for (i in icons.indices) {
            val t = tv(names[i], 11f, Pal.MUTED)
            val ic = resources.getDrawable(icons[i], null).mutate()
            ic.setBounds(0, 0, dp(24), dp(24))
            t.setCompoundDrawables(null, ic, null, null)
            t.compoundDrawablePadding = dp(2)
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
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(i: Intent?) {
        super.onNewIntent(i)
        setIntent(i)
        handleIntent(i)
    }

    // Raccourcis de l'icône et notifications : « Allumer » direct, « Éteindre » après confirmation
    private fun handleIntent(i: Intent?) {
        when (i?.action) {
            "com.example.wolremote.WAKE" -> act("/wake")
            "com.example.wolremote.SHUTDOWN" -> dialogBuilder().setMessage("Éteindre le PC ?")
                .setPositiveButton("Éteindre") { _, _ -> act("/shutdown") }
                .setNegativeButton("Annuler", null).show()
        }
        i?.action = Intent.ACTION_MAIN
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        settingsTab.onResult(requestCode, resultCode, data)
    }

    private val power by lazy { PowerCard(this, st) }
    private val updater by lazy { Updater(this) }
    override fun onResume() {
        super.onResume()
        handler.post(poll)
        updater.check()
        thread {
            try {
                val s = ServerUpdate.check(st)
                val old = s.tablet != null && s.agent != null && (s.tabletOld || s.agentOld)
                runOnUiThread { upd.visibility = if (old) View.VISIBLE else View.GONE }
            } catch (e: Exception) { }
        }
        if (st.get("kWatch") == "1" && st.get("kVac") != "1") WatchService.start(this)
    }
    override fun onPause() { super.onPause(); handler.removeCallbacks(poll); settingsTab.persist() }

    private fun show(i: Int) {
        hideKeyboard()
        if (current == 5 && i != 5) settingsTab.persist()
        val changed = i != current
        current = i
        for ((k, v) in views.withIndex()) v.visibility = if (k == i) View.VISIBLE else View.GONE
        for ((k, t) in navs.withIndex()) {
            if (k == i) {
                t.setTextColor(Pal.BLUE)
                t.compoundDrawables[1]?.setTint(Pal.BLUE)
                t.background = rounded(Pal.CARD2, 12)
            } else {
                t.setTextColor(Pal.MUTED)
                t.compoundDrawables[1]?.setTint(Pal.MUTED)
                t.background = null
            }
        }
        if (changed) {
            val v = views[i]
            v.alpha = 0f
            v.animate().alpha(1f).setDuration(160).start()
        }
        if (i == 0) { refreshPc(); power.refresh() }
        if (i == 2) queue.refresh()
        if (i == 3) qb.refresh()
        if (i == 4) media.refresh()
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
        vac = tv("Mode vacances : alertes et surveillance désactivées.", 13f, Pal.MUTED)
        vac.visibility = View.GONE
        col.addView(vac)
        upd = tv("Mise à jour du serveur disponible : Réglages, Serveur.", 13f, Pal.BLUE)
        upd.visibility = View.GONE
        col.addView(upd)

        val hero = card()
        status = tv("Vérification…", 28f, Pal.MUTED)
        status.typeface = Typeface.DEFAULT_BOLD
        status.gravity = Gravity.CENTER
        game = tv("Chroniques …", 16f, Pal.MUTED)
        game.gravity = Gravity.CENTER
        game.setPadding(0, dp(8), 0, 0)
        hero.setPadding(dp(16), dp(24), dp(16), dp(24))
        hero.addView(status)
        hero.addView(game)
        col.addView(hero)
        msg = tv("", 14f, Pal.FG)
        msg.gravity = Gravity.CENTER
        msg.setPadding(0, dp(10), 0, 0)
        col.addView(msg)
        col.addView(power.view)

        col.addView(section("Ordinateur"))
        bWake = btn("Allumer", Pal.GREEN, 72) { act("/wake") }
        bOff = btn2("Éteindre", Pal.RED, 72) {
            dialogBuilder().setMessage("Éteindre le PC ?")
                .setPositiveButton("Éteindre") { _, _ -> act("/shutdown") }
                .setNegativeButton("Annuler", null).show()
        }
        col.addView(pair(bWake, bOff))

        bRestart = btn2("Redémarrer", Pal.FG, 56) {
            dialogBuilder().setMessage("Redémarrer le PC ? Il se reconnectera tout seul.")
                .setPositiveButton("Redémarrer") { _, _ -> act("/restart") }
                .setNegativeButton("Annuler", null).show()
        }
        col.addView(bRestart)
        bScreens = btn2("Éteindre les écrans", Pal.FG, 56) { act("/screens/off") }
        col.addView(bScreens)

        col.addView(section("Chroniques"))
        bStart = btn2("Lancer", Pal.FG, 72) { act("/game/start") }
        bStop = btn2("Arrêter", Pal.RED, 72) {
            dialogBuilder().setMessage("Arrêter le serveur Chroniques ?")
                .setPositiveButton("Arrêter") { _, _ -> act("/game/stop") }
                .setNegativeButton("Annuler", null).show()
        }
        col.addView(pair(bStart, bStop))

        col.addView(section("Jellyfin"))
        col.addView(btn2("Actualiser la bibliothèque", Pal.FG, 56) {
            msg.text = "…"
            thread {
                val t = Jellyfin.refresh(st)
                runOnUiThread { msg.text = t }
            }
        })

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
                    vac.visibility = if (st.get("kVac") == "1") View.VISIBLE else View.GONE
                    status.textSize = 28f
                    status.text = if (online) "Allumé" else "Éteint"
                    status.setTextColor(if (online) Pal.GREEN else Pal.RED)
                    game.text = if (running) "Chroniques allumé" else "Chroniques éteint"
                    game.setTextColor(if (running) Pal.GREEN else Pal.RED)
                    // Seuls les boutons utiles sont actifs : on ne peut pas allumer un PC allumé
                    avail(bWake, !online)
                    avail(bOff, online)
                    avail(bRestart, online)
                    avail(bScreens, online)
                    avail(bStart, online && !running)
                    avail(bStop, running)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.textSize = 22f
                    status.text = "Serveur injoignable"
                    status.setTextColor(Pal.MUTED)
                    game.text = "Chroniques …"
                    game.setTextColor(Pal.MUTED)
                    for (b in listOf(bWake, bOff, bRestart, bScreens, bStart, bStop)) avail(b, true)
                }
            }
        }
    }

    private fun avail(b: Button, on: Boolean) {
        b.isEnabled = on
        b.alpha = if (on) 1f else 0.35f
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
            Thread.sleep(6000)
            runOnUiThread { if (msg.text == text) msg.text = "" }
        }
    }
}

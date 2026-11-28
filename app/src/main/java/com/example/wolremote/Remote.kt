package com.example.wolremote

import android.app.Activity
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

// Télécommande : pilote les appareils connectés à Jellyfin (télévision, tablette…)
object Remote {
    class Sess(val id: String, val name: String, val title: String, val paused: Boolean, val pos: Long, val run: Long = 0L,
               val vol: Int = -1, val muted: Boolean = false, val audio: Int = -1, val sub: Int = -1, val streams: JSONArray = JSONArray())

    // Sessions vues par Jellyfin mais non pilotables (pour le diagnostic)
    var skipped: List<String> = emptyList()

    fun sessions(st: Settings): List<Sess> {
        // Par défaut Jellyfin n'affiche que les sessions actives depuis 16 min : on élargit à 1 h
        val r = Jellyfin.get(st, "/Sessions?ActiveWithinSeconds=3600", 8000)
        if (!r.ok) throw Exception("Jellyfin : HTTP " + r.code)
        val a = JSONArray(r.body)
        val out = ArrayList<Sess>()
        val sk = ArrayList<String>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (!o.optBoolean("SupportsRemoteControl", false)) {
                sk.add(o.str("DeviceName").ifEmpty { o.str("Client") } + " (" + o.str("Client") + ")")
                continue
            }
            val np = o.optJSONObject("NowPlayingItem")
            val ps = o.optJSONObject("PlayState")
            val title = if (np == null) "" else (if (np.str("Type") == "Episode") np.str("SeriesName") + " · " + np.str("Name") else np.str("Name"))
            out.add(Sess(o.str("Id"), o.str("DeviceName").ifEmpty { o.str("Client") }, title,
                ps?.optBoolean("IsPaused", false) ?: false, ps?.optLong("PositionTicks", 0L) ?: 0L,
                np?.optLong("RunTimeTicks", 0L) ?: 0L, if (ps == null || !ps.has("VolumeLevel") || ps.isNull("VolumeLevel")) -1 else ps.optInt("VolumeLevel"),
                ps?.optBoolean("IsMuted", false) ?: false, if (ps == null || ps.isNull("AudioStreamIndex")) -1 else ps.optInt("AudioStreamIndex", -1),
                if (ps == null || ps.isNull("SubtitleStreamIndex")) -1 else ps.optInt("SubtitleStreamIndex", -1), np?.optJSONArray("MediaStreams") ?: JSONArray()))
        }
        skipped = sk.distinct()
        return out
    }

    fun playing(st: Settings, id: String, cmd: String): Boolean = Jellyfin.post(st, "/Sessions/$id/Playing/$cmd").ok
    fun command(st: Settings, id: String, cmd: String): Boolean = Jellyfin.post(st, "/Sessions/$id/Command/$cmd").ok
    // Commande avec arguments (volume, piste audio, saisie au clavier…)
    // Touches de la télé Samsung (volume, muet, extinction) via la tablette ; renvoie null si OK, sinon le message d'erreur
    fun tvKeys(st: Settings, keys: List<String>): String? {
        val ip = st.get("kTvIp").trim()
        if (ip.isEmpty()) return "Renseigne l'adresse IP de la télé dans Réglages."
        return try {
            val r = Net.call("POST", st.tablet() + "/tv/key", json = JSONObject().put("pin", st.pin()).put("ip", ip).put("keys", JSONArray(keys)).toString(), timeout = 45000)
            if (r.code == 404) "Mets à jour le serveur de la tablette (Réglages, Serveur)."
            else if (!r.ok) "Tablette : HTTP " + r.code
            else JSONObject(r.body).let { if (it.optBoolean("ok")) null else it.optString("message") }
        } catch (e: Exception) { "Tablette injoignable" }
    }

    // Rallume la télé (Wake-on-LAN) ; null = envoyé
    // Renvoie (réussi, message de la tablette)
    fun tvWake(st: Settings): Pair<Boolean, String> {
        val ip = st.get("kTvIp").trim()
        if (ip.isEmpty()) return Pair(false, "Renseigne l'adresse IP de la télé dans Réglages.")
        return try {
            val r = Net.call("POST", st.tablet() + "/tv/wake", json = JSONObject().put("pin", st.pin()).put("ip", ip).put("mac", st.get("kTvMac").trim()).toString(), timeout = 40000)
            if (r.code == 404) Pair(false, "Mets à jour le serveur de la tablette (Réglages, Serveur).")
            else if (!r.ok) Pair(false, "Tablette : HTTP " + r.code + " " + errMsg(r.body))
            else JSONObject(r.body).let { Pair(it.optBoolean("ok"), it.optString("message")) }
        } catch (e: Exception) { Pair(false, "Tablette injoignable") }
    }

    // Télé allumée ? (null = inconnu)
    fun tvOn(st: Settings): Boolean? = try {
        val ip = st.get("kTvIp").trim()
        val r = Net.call("GET", st.tablet() + "/tv/status?ip=" + ip, timeout = 8000)
        if (r.ok) JSONObject(r.body).optBoolean("on") else null
    } catch (e: Exception) { null }

    // Ouvre la connexion à la télé à l'avance
    fun tvWarm(st: Settings) {
        try { Net.call("POST", st.tablet() + "/tv/key", json = JSONObject().put("pin", st.pin()).put("ip", st.get("kTvIp").trim()).put("keys", JSONArray()).put("warm", true).toString(), timeout = 45000) } catch (e: Exception) { }
    }

    fun commandArgs(st: Settings, id: String, name: String, args: JSONObject): Boolean =
        Jellyfin.postJson(st, "/Sessions/$id/Command", JSONObject().put("Name", name).put("Arguments", args).toString()).ok
    fun seek(st: Settings, id: String, ticks: Long): Boolean =
        Jellyfin.post(st, "/Sessions/$id/Playing/Seek?seekPositionTicks=" + maxOf(0L, ticks)).ok
    fun play(st: Settings, id: String, itemId: String, startTicks: Long = 0L): Boolean =
        Jellyfin.post(st, "/Sessions/$id/Playing?playCommand=PlayNow&itemIds=$itemId" + (if (startTicks > 0) "&startPositionTicks=$startTicks" else "")).ok
}

object RemoteUi {
    // Choisit l'appareil (si plusieurs) puis lance le film dessus
    fun playOn(a: Activity, st: Settings, itemId: String, title: String, startTicks: Long = 0L) {
        thread {
            val s = try { Remote.sessions(st) } catch (e: Exception) { null }
            a.runOnUiThread {
                if (s == null) { android.widget.Toast.makeText(a, "Jellyfin injoignable", android.widget.Toast.LENGTH_SHORT).show(); return@runOnUiThread }
                if (s.isEmpty()) {
                    a.dialogBuilder().setMessage("Aucun appareil Jellyfin connecté. Ouvre l'appli Jellyfin sur la télévision, puis réessaie.").setPositiveButton("OK", null).show()
                    return@runOnUiThread
                }
                val go = { x: Remote.Sess ->
                    thread {
                        val ok = try { Remote.play(st, x.id, itemId, startTicks) } catch (e: Exception) { false }
                        a.runOnUiThread { android.widget.Toast.makeText(a, if (ok) "Lecture lancée sur " + x.name else "Échec", android.widget.Toast.LENGTH_SHORT).show() }
                    }
                }
                if (s.size == 1) go(s[0])
                else a.dialogBuilder().setTitle("Lancer « $title » sur").setItems(s.map { it.name }.toTypedArray()) { _, i -> go(s[i]) }.show()
            }
        }
    }

    fun open(a: Activity, st: Settings) {
        val body = LinearLayout(a)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(a.dp(16), a.dp(8), a.dp(16), a.dp(8))
        val info = a.tv("Recherche des appareils…", 14f, Pal.MUTED)
        body.addView(info)
        val list = LinearLayout(a)
        list.orientation = LinearLayout.VERTICAL
        body.addView(list)
        var alive = true
        updaters.clear()
        if (st.get("kTvIp").trim().isNotEmpty()) body.addView(tvPanel(a, st) { alive }, 1)
        else body.addView(a.card().also { it.addView(a.tv("Télé Samsung", 17f, Pal.FG).also { t -> t.typeface = android.graphics.Typeface.DEFAULT_BOLD }); it.addView(a.tv("Renseigne l'adresse IP de la télé dans Réglages → Télévision Samsung pour avoir ici l'allumage, le volume et les flèches.", 13f, Pal.MUTED).also { t -> t.setPadding(0, a.dp(6), 0, 0) }) }, 1)
        a.bigDialog("Télécommande", body, "Fermer", false) { alive = false }
        thread {
            var sig = ""
            while (alive) {
                try {
                    val s = Remote.sessions(st)
                    val ns = s.joinToString("|") { it.id + it.title + it.paused + it.audio + it.sub + it.muted }
                    a.runOnUiThread {
                        info.text = if (s.isEmpty()) "Aucun appareil pilotable." + (if (Remote.skipped.isNotEmpty()) "\nJellyfin voit, sans pouvoir les piloter : " + Remote.skipped.joinToString(", ") + ".\n\nLa télé est probablement en veille ou l'appli est fermée : allume la télé, ouvre Jellyfin et reste sur son écran d'accueil, puis ouvre à nouveau la télécommande." else "\nJellyfin ne voit aucune session récente : ouvre l'appli Jellyfin sur la télévision, connectée à ton compte, sur l'écran d'accueil.") else "Appareils connectés"
                        if (ns != sig) {
                            sig = ns
                            list.removeAllViews()
                            updaters.clear()
                            if (s.isEmpty()) {
                                val ph = a.card()
                                ph.addView(a.tv("Jellyfin", 17f, Pal.FG).also { it.typeface = android.graphics.Typeface.DEFAULT_BOLD })
                                ph.addView(a.tv("Aucune session : ouvre Jellyfin sur la télé (écran d'accueil). Les touches Accueil, flèches, lecture et volume apparaîtront ici dès qu'elle est détectée.", 13f, Pal.MUTED).also { it.setPadding(0, a.dp(6), 0, 0) })
                                list.addView(ph)
                            }
                            for (x in s) list.addView(card(a, st, x))
                        }
                        for (x in s) updaters[x.id]?.invoke(x)
                    }
                } catch (e: Exception) {
                    a.runOnUiThread { info.text = "Jellyfin injoignable : " + e.message }
                }
                Thread.sleep(2000)
            }
        }
    }

    private val updaters = HashMap<String, (Remote.Sess) -> Unit>()

    // Boutons de rangée : une seule ligne, marges réduites, jamais de mot coupé
    private fun compact(v: android.widget.Button) {
        v.setSingleLine(true)
        v.ellipsize = null
        v.textSize = if (v.text.length > 7) 13f else if (v.text.length > 3) 12f else 15f
        v.setPadding(v.context.resources.displayMetrics.density.let { (4 * it).toInt() }, v.paddingTop, (4 * v.context.resources.displayMetrics.density).toInt(), v.paddingBottom)
        v.gravity = android.view.Gravity.CENTER
        v.includeFontPadding = false
        v.setPadding(v.paddingLeft, 0, v.paddingRight, 0)
    }

    // Les touches de la télé partent dans l'ordre, une à la fois ; si l'envoi prend du retard on abandonne les plus anciennes
    private val tvQueue = java.util.concurrent.ThreadPoolExecutor(1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS, java.util.concurrent.LinkedBlockingQueue<Runnable>())

    // Télé Samsung : allumer, éteindre, volume, navigation. Disponible même quand aucune session Jellyfin n'est active.
    private fun tvPanel(a: Activity, st: Settings, alive: () -> Boolean): LinearLayout {
        val c = a.card()
        val hdr = LinearLayout(a)
        hdr.orientation = LinearLayout.HORIZONTAL
        hdr.gravity = android.view.Gravity.CENTER_VERTICAL
        hdr.addView(a.tv("Télé Samsung", 17f, Pal.FG).also { it.typeface = android.graphics.Typeface.DEFAULT_BOLD }, LinearLayout.LayoutParams(0, WRAP, 1f))
        val stateBox = LinearLayout(a)
        hdr.addView(stateBox)
        c.addView(hdr)
        val msg = a.tv("", 12f, Pal.MUTED)
        msg.setPadding(0, a.dp(6), 0, 0)
        c.addView(msg)
        val ui = android.os.Handler(android.os.Looper.getMainLooper())
        val keyBtns = ArrayList<android.widget.Button>()

        fun setMsg(t: String, ok: Boolean?) { msg.text = t; msg.setTextColor(if (ok == null) Pal.MUTED else if (ok) Pal.GREEN else Pal.RED) }
        fun fire(label: String, keys: List<String>) {
            if (tvQueue.queue.size > 3) return
            tvQueue.execute {
                val err = Remote.tvKeys(st, keys)
                a.runOnUiThread { if (err != null) setMsg(err, false) else setMsg(label, true) }
            }
        }
        fun row(vararg b: android.widget.Button): LinearLayout {
            val r = LinearLayout(a)
            r.orientation = LinearLayout.HORIZONTAL
            r.isBaselineAligned = false
            for (v in b) { compact(v); v.layoutParams = LinearLayout.LayoutParams(0, v.minimumHeight, 1f).also { it.leftMargin = a.dp(3); it.rightMargin = a.dp(3); it.topMargin = a.dp(5) }; r.addView(v) }
            return r
        }
        // Touche avec retour tactile immédiat ; maintenue, elle se répète (volume, flèches)
        fun key(label: String, k: String, repeat: Boolean = false, h: Int = 48): android.widget.Button {
            val b = a.btn2(label, Pal.FG, h) {}
            var rep: Runnable? = null
            b.setOnTouchListener { v, e ->
                when (e.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        v.isPressed = true
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                        fire(label, listOf(k))
                        if (repeat) {
                            val r = object : Runnable { override fun run() { fire(label, listOf(k)); ui.postDelayed(this, 170) } }
                            rep = r
                            ui.postDelayed(r, 420)
                        }
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> { v.isPressed = false; rep?.let { ui.removeCallbacks(it) }; rep = null }
                }
                true
            }
            keyBtns.add(b)
            return b
        }
        val wake = a.btn("Allumer", Pal.GREEN, 46) {
            setMsg("Allumage… (jusqu'à 15 s)", null)
            thread {
                val (ok, m) = Remote.tvWake(st)
                a.runOnUiThread { setMsg(m, ok) }
            }
        }
        val off = a.btn2("Éteindre", Pal.RED, 46) {
            a.dialogBuilder().setMessage("Éteindre la télévision ?").setPositiveButton("Éteindre") { _, _ -> fire("Extinction envoyée", listOf("KEY_POWER")) }.setNegativeButton("Annuler", null).show()
        }
        c.addView(row(wake, off))
        c.addView(row(a.btn2("Fermer Jellyfin (quitter l'appli)", Pal.AMBER, 46) {
            setMsg("Fermeture de l'appli…", null)
            thread {
                val m = try {
                    val rr = Net.call("POST", st.tablet() + "/tv/closeapp", json = JSONObject().put("pin", st.pin()).put("ip", st.get("kTvIp").trim()).toString(), timeout = 30000)
                    if (rr.ok) JSONObject(rr.body).optString("message", "OK") else "Erreur " + rr.code
                } catch (e: Exception) { "Tablette injoignable" }
                a.runOnUiThread { setMsg(m, true) }
            }
        }))
        c.addView(a.tv("Volume", 12f, Pal.MUTED).also { it.setPadding(0, a.dp(8), 0, 0) })
        c.addView(row(key("Vol −", "KEY_VOLDOWN", true, 50), key("Muet", "KEY_MUTE", false, 50), key("Vol +", "KEY_VOLUP", true, 50)))
        c.addView(a.tv("Navigation", 12f, Pal.MUTED).also { it.setPadding(0, a.dp(8), 0, 0) })
        c.addView(row(key("Accueil", "KEY_HOME"), key("▲", "KEY_UP", true), key("Retour", "KEY_RETURN")))
        c.addView(row(key("◀", "KEY_LEFT", true), key("OK", "KEY_ENTER"), key("▶", "KEY_RIGHT", true)))
        c.addView(row(key("Source", "KEY_SOURCE"), key("▼", "KEY_DOWN", true), key("Réglages", "KEY_MENU")))
        c.addView(row(key("⏪", "KEY_REWIND", true, 46), key("▶❚❚", "KEY_PLAY", false, 46), key("⏩", "KEY_FF", true, 46)))
        val more = LinearLayout(a)
        more.orientation = LinearLayout.VERTICAL
        more.visibility = android.view.View.GONE
        more.addView(row(key("Outils", "KEY_TOOLS", false, 48), key("Smart Hub", "KEY_CONTENTS", false, 48), key("Guide", "KEY_GUIDE", false, 48)))
        more.addView(row(key("Infos", "KEY_INFO", false, 48), key("Ch +", "KEY_CHUP", true, 48), key("Ch −", "KEY_CHDOWN", true, 48)))
        val moreBtn = a.btn2("Plus de touches ▾", Pal.MUTED, 40) {}
        moreBtn.setOnClickListener {
            val open = more.visibility == android.view.View.GONE
            more.visibility = if (open) android.view.View.VISIBLE else android.view.View.GONE
            moreBtn.text = if (open) "Moins de touches ▴" else "Plus de touches ▾"
        }
        c.addView(moreBtn)
        c.addView(more)

        // État de la télé : badge, boutons utiles seulement ; la connexion est préparée dès l'ouverture pour que la 1re touche soit instantanée
        var warmed = false
        thread {
            while (alive()) {
                val on = Remote.tvOn(st)
                a.runOnUiThread {
                    stateBox.removeAllViews()
                    stateBox.addView(a.badge(if (on == null) "Inconnu" else if (on) "Allumée" else "Éteinte", if (on == true) Pal.GREEN else Pal.MUTED))
                    wake.alpha = if (on == true) 0.4f else 1f
                    off.alpha = if (on == false) 0.4f else 1f
                    for (b in keyBtns) b.alpha = if (on == false) 0.4f else 1f
                }
                if (on == true && !warmed) { warmed = true; Remote.tvWarm(st) }
                if (on != true) warmed = false
                Thread.sleep(2000)
            }
        }
        return c
    }

    private fun hms(ticks: Long): String {
        val s = (ticks / 10000000L).toInt()
        return if (s >= 3600) String.format("%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60) else String.format("%d:%02d", s / 60, s % 60)
    }

    private fun bar(a: Activity, max: Int, change: (Int) -> Unit, drag: (Boolean) -> Unit, live: ((Int) -> Unit)? = null): android.widget.SeekBar {
        val b = android.widget.SeekBar(a)
        b.max = max
        b.progressTintList = android.content.res.ColorStateList.valueOf(Pal.BLUE)
        b.thumbTintList = android.content.res.ColorStateList.valueOf(Pal.BLUE)
        b.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar, p: Int, fromUser: Boolean) { if (fromUser) live?.invoke(p) }
            override fun onStartTrackingTouch(s: android.widget.SeekBar) { drag(true) }
            override fun onStopTrackingTouch(s: android.widget.SeekBar) { drag(false); change(s.progress) }
        })
        return b
    }

    private fun card(a: Activity, st: Settings, x: Remote.Sess): LinearLayout {
        val c = a.card()
        var cur = x   // dernier état connu : les boutons ±30 s partent de la position actuelle
        fun go(f: () -> Boolean) { thread { try { f() } catch (e: Exception) { } } }
        fun bt(lbl: String, f: () -> Boolean): android.widget.Button = a.btn2(lbl, Pal.FG, 42) { go(f) }
        fun row(vararg b: android.widget.Button): LinearLayout {
            val r = LinearLayout(a)
            r.orientation = LinearLayout.HORIZONTAL
            r.isBaselineAligned = false
            for (v in b) {
                compact(v)
                val lp = LinearLayout.LayoutParams(0, v.minimumHeight, 1f)
                lp.leftMargin = a.dp(3); lp.rightMargin = a.dp(3); lp.topMargin = a.dp(5)
                v.layoutParams = lp
                r.addView(v)
            }
            return r
        }

        // En-tête : appareil, état, titre
        val head = LinearLayout(a)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = android.view.Gravity.CENTER_VERTICAL
        head.addView(a.tv(x.name, 17f, Pal.FG).also { it.typeface = android.graphics.Typeface.DEFAULT_BOLD }, LinearLayout.LayoutParams(0, WRAP, 1f))
        val state = LinearLayout(a)
        head.addView(state)
        c.addView(head)
        val ttl = a.tv("", 14f, Pal.MUTED)
        ttl.setPadding(0, a.dp(4), 0, 0)
        c.addView(ttl)

        // Barre de lecture
        var dragPos = false
        val time = a.tv("", 14f, Pal.FG)
        val seek = bar(a, 1000, { p -> if (x.run > 0) go { Remote.seek(st, x.id, x.run * p / 1000) } }, { dragPos = it },
            { p -> if (x.run > 0) { time.text = hms(x.run * p / 1000) + " / " + hms(x.run); time.setTextColor(Pal.BLUE) } })
        time.gravity = android.view.Gravity.CENTER
        val playBox = LinearLayout(a)
        playBox.orientation = LinearLayout.VERTICAL
        playBox.addView(seek)
        playBox.addView(time)
        val playBtn = bt("⏯") { Remote.playing(st, x.id, "PlayPause") }
        playBox.addView(row(
            bt("⏮") { Remote.playing(st, x.id, "PreviousTrack") },
            bt("−30s") { Remote.seek(st, x.id, cur.pos - 300000000L) },
            playBtn,
            bt("+30s") { Remote.seek(st, x.id, cur.pos + 300000000L) },
            bt("⏭") { Remote.playing(st, x.id, "NextTrack") }
        ))
        playBox.addView(row(
            bt("−10 min") { Remote.seek(st, x.id, cur.pos - 6000000000L) },
            a.btn2("Arrêter", Pal.RED, 44) { go { Remote.playing(st, x.id, "Stop") } },
            bt("+10 min") { Remote.seek(st, x.id, cur.pos + 6000000000L) }
        ))
        c.addView(playBox)

        // Pistes audio et sous-titres
        fun tracks(type: String, label: String, cur: Int, cmd: String): android.widget.Button = a.btn2(label, Pal.FG, 44) {
            val names = ArrayList<String>(); val idx = ArrayList<Int>()
            if (type == "Subtitle") { names.add("Aucun"); idx.add(-1) }
            for (i in 0 until x.streams.length()) {
                val o = x.streams.optJSONObject(i) ?: continue
                if (o.str("Type") != type) continue
                names.add((if (o.optInt("Index") == cur) "● " else "") + o.str("DisplayTitle").ifEmpty { o.str("Language").ifEmpty { "Piste " + o.optInt("Index") } })
                idx.add(o.optInt("Index"))
            }
            if (names.isEmpty()) android.widget.Toast.makeText(a, "Aucune piste connue", android.widget.Toast.LENGTH_SHORT).show()
            else a.dialogBuilder().setTitle(label).setItems(names.toTypedArray()) { _, i ->
                go { Remote.commandArgs(st, x.id, cmd, JSONObject().put("Index", idx[i].toString())) }
            }.show()
        }
        val trackRow = row(tracks("Audio", "Audio", x.audio, "SetAudioStreamIndex"), tracks("Subtitle", "Sous-titres", x.sub, "SetSubtitleStreamIndex"))
        c.addView(trackRow)

        // Volume : par la télé Samsung si son adresse est renseignée (l'appli Jellyfin de la télé ne règle pas le volume du téléviseur)
        val tv = st.get("kTvIp").trim().isNotEmpty()
        var dragVol = false
        val mute = a.btn2("Muet", Pal.FG, 40) { go { Remote.command(st, x.id, "ToggleMute") } }
        val volBar = bar(a, 100, { p -> go { Remote.commandArgs(st, x.id, "SetVolume", JSONObject().put("Volume", p.toString())) } }, { dragVol = it })
        if (!tv) {
            val volRow = LinearLayout(a)
            volRow.orientation = LinearLayout.HORIZONTAL
            volRow.gravity = android.view.Gravity.CENTER_VERTICAL
            volRow.setPadding(0, a.dp(10), 0, 0)
            volRow.addView(a.tv("Volume", 13f, Pal.MUTED))
            volRow.addView(volBar, LinearLayout.LayoutParams(0, WRAP, 1f))
            mute.layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
            volRow.addView(mute)
            c.addView(volRow)
            c.addView(row(bt("Vol −") { Remote.command(st, x.id, "VolumeDown") }, bt("Vol +") { Remote.command(st, x.id, "VolumeUp") }))
            c.addView(a.tv("Si le volume ne réagit pas, renseigne l'adresse IP de ta télé Samsung dans Réglages : le volume, le muet et l'extinction passeront par la télé elle-même.", 12f, Pal.MUTED).also { it.setPadding(0, a.dp(6), 0, 0) })
        }

        // Navigation dans les menus de l'appli
        val nav = LinearLayout(a)
        nav.orientation = LinearLayout.VERTICAL
        nav.visibility = android.view.View.VISIBLE
        nav.addView(row(bt("Accueil") { Remote.command(st, x.id, "GoHome") }, bt("▲") { Remote.command(st, x.id, "MoveUp") }, bt("Retour") { Remote.command(st, x.id, "Back") }))
        nav.addView(row(bt("◀") { Remote.command(st, x.id, "MoveLeft") }, bt("OK") { Remote.command(st, x.id, "Select") }, bt("▶") { Remote.command(st, x.id, "MoveRight") }))
        nav.addView(row(bt("Recherche") { Remote.command(st, x.id, "GoToSearch") }, bt("▼") { Remote.command(st, x.id, "MoveDown") }, bt("Infos") { Remote.command(st, x.id, "ToggleOsd") }))
        val txt = a.field("Texte à saisir (recherche…)", "")
        nav.addView(txt)
        nav.addView(row(a.btn2("Envoyer le texte", Pal.FG, 44) {
            val t = txt.text.toString()
            if (t.isNotEmpty()) go { Remote.commandArgs(st, x.id, "SendString", JSONObject().put("String", t)) }
        }))
        val navHead = a.btn2("Navigation ▴", Pal.MUTED, 40) {}
        navHead.setOnClickListener {
            val open = nav.visibility == android.view.View.GONE
            nav.visibility = if (open) android.view.View.VISIBLE else android.view.View.GONE
            navHead.text = if (open) "Navigation ▴" else "Navigation ▾"
        }
        c.addView(navHead)
        c.addView(nav)

        // Mise à jour en place (sans reconstruire la carte) : état, position, volume
        val update: (Remote.Sess) -> Unit = { s ->
            cur = s
            state.removeAllViews()
            state.addView(a.badge(if (s.title.isEmpty()) "Rien en lecture" else if (s.paused) "En pause" else "En lecture", if (s.title.isEmpty()) Pal.MUTED else if (s.paused) Pal.AMBER else Pal.GREEN))
            ttl.text = s.title
            ttl.visibility = if (s.title.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
            playBox.visibility = if (s.title.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
            trackRow.visibility = if (s.title.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
            if (!dragPos && s.run > 0) { time.setTextColor(Pal.FG); seek.progress = (s.pos * 1000 / s.run).toInt(); time.text = hms(s.pos) + " / " + hms(s.run) }
            if (!dragVol && s.vol >= 0) volBar.progress = s.vol
            if (!tv) mute.text = if (s.muted) "Son" else "Muet"
        }
        update(x)
        updaters[x.id] = update
        return c
    }
}

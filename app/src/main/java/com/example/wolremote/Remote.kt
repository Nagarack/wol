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
        if (st.get("kTvIp").trim().isNotEmpty()) body.addView(tvPanel(a, st), 1)
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
                            for (x in s) list.addView(card(a, st, x))
                        }
                        for (x in s) updaters[x.id]?.invoke(x)
                    }
                } catch (e: Exception) {
                    a.runOnUiThread { info.text = "Jellyfin injoignable : " + e.message }
                }
                Thread.sleep(3000)
            }
        }
    }

    private val updaters = HashMap<String, (Remote.Sess) -> Unit>()

    // Télé Samsung : allumer, éteindre, volume. Disponible même quand aucune session Jellyfin n'est active.
    private fun tvPanel(a: Activity, st: Settings): LinearLayout {
        val c = a.card()
        c.addView(a.tv("Télé Samsung", 17f, Pal.FG).also { it.typeface = android.graphics.Typeface.DEFAULT_BOLD })
        val msg = a.tv("", 12f, Pal.MUTED)
        fun send(label: String, f: () -> String?) {
            msg.text = "$label…"
            thread {
                val err = try { f() } catch (e: Exception) { e.message ?: "erreur" }
                a.runOnUiThread { msg.text = err ?: "OK" ; msg.setTextColor(if (err == null) Pal.GREEN else Pal.RED) }
            }
        }
        fun keys(label: String, vararg k: String): () -> Unit = { send(label) { Remote.tvKeys(st, k.toList()) } }
        fun row(vararg b: android.widget.Button): LinearLayout {
            val r = LinearLayout(a)
            r.orientation = LinearLayout.HORIZONTAL
            for (v in b) { v.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).also { it.leftMargin = a.dp(3); it.rightMargin = a.dp(3); it.topMargin = a.dp(8) }; r.addView(v) }
            return r
        }
        c.addView(row(
            a.btn("Allumer", Pal.GREEN, 44) {
                msg.text = "Allumage… (jusqu'à 15 s)"
                msg.setTextColor(Pal.MUTED)
                thread {
                    val (ok, m) = Remote.tvWake(st)
                    a.runOnUiThread { msg.text = m; msg.setTextColor(if (ok) Pal.GREEN else Pal.RED) }
                }
            },
            a.btn2("Éteindre", Pal.RED, 44) {
                a.dialogBuilder().setMessage("Éteindre la télévision ?").setPositiveButton("Éteindre") { _, _ -> keys("Extinction", "KEY_POWER")() }.setNegativeButton("Annuler", null).show()
            }))
        c.addView(row(a.btn2("Vol −", Pal.FG, 44) { keys("Volume", "KEY_VOLDOWN")() }, a.btn2("Vol +", Pal.FG, 44) { keys("Volume", "KEY_VOLUP")() }, a.btn2("Muet", Pal.FG, 44) { keys("Muet", "KEY_MUTE")() }))
        c.addView(row(a.btn2("Vol −5", Pal.FG, 44) { keys("Volume", "KEY_VOLDOWN", "KEY_VOLDOWN", "KEY_VOLDOWN", "KEY_VOLDOWN", "KEY_VOLDOWN")() },
            a.btn2("Vol +5", Pal.FG, 44) { keys("Volume", "KEY_VOLUP", "KEY_VOLUP", "KEY_VOLUP", "KEY_VOLUP", "KEY_VOLUP")() }))
        // Pavé de navigation : touches de la télécommande Samsung (menus, Smart Hub, sources)
        fun k(label: String, key: String, h: Int = 48) = a.btn2(label, Pal.FG, h) { keys(label, key)() }
        c.addView(a.tv("Navigation", 12f, Pal.MUTED).also { it.setPadding(0, a.dp(14), 0, 0) })
        c.addView(row(k("Accueil", "KEY_HOME"), k("▲", "KEY_UP"), k("Retour", "KEY_RETURN")))
        c.addView(row(k("◀", "KEY_LEFT"), k("OK", "KEY_ENTER"), k("▶", "KEY_RIGHT")))
        c.addView(row(k("Source", "KEY_SOURCE"), k("▼", "KEY_DOWN"), k("Menu", "KEY_MENU")))
        c.addView(row(k("⏪", "KEY_REWIND"), k("▶❚❚", "KEY_PLAY"), k("⏩", "KEY_FF")))
        c.addView(row(k("Infos", "KEY_INFO"), k("Chaîne +", "KEY_CHUP"), k("Chaîne −", "KEY_CHDOWN")))
        msg.setPadding(0, a.dp(8), 0, 0)
        c.addView(msg)
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
        fun bt(lbl: String, f: () -> Boolean): android.widget.Button = a.btn2(lbl, Pal.FG, 44) { go(f) }
        fun row(vararg b: android.widget.Button): LinearLayout {
            val r = LinearLayout(a)
            r.orientation = LinearLayout.HORIZONTAL
            for (v in b) {
                val lp = LinearLayout.LayoutParams(0, WRAP, 1f)
                lp.leftMargin = a.dp(3); lp.rightMargin = a.dp(3); lp.topMargin = a.dp(8)
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
            bt("−30 s") { Remote.seek(st, x.id, cur.pos - 300000000L) },
            playBtn,
            bt("+30 s") { Remote.seek(st, x.id, cur.pos + 300000000L) },
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
        nav.visibility = android.view.View.GONE
        nav.addView(row(bt("Accueil") { Remote.command(st, x.id, "GoHome") }, bt("▲") { Remote.command(st, x.id, "MoveUp") }, bt("Retour") { Remote.command(st, x.id, "Back") }))
        nav.addView(row(bt("◀") { Remote.command(st, x.id, "MoveLeft") }, bt("OK") { Remote.command(st, x.id, "Select") }, bt("▶") { Remote.command(st, x.id, "MoveRight") }))
        nav.addView(row(bt("Recherche") { Remote.command(st, x.id, "GoToSearch") }, bt("▼") { Remote.command(st, x.id, "MoveDown") }, bt("Infos") { Remote.command(st, x.id, "ToggleOsd") }))
        val txt = a.field("Texte à saisir (recherche…)", "")
        nav.addView(txt)
        nav.addView(row(a.btn2("Envoyer le texte", Pal.FG, 44) {
            val t = txt.text.toString()
            if (t.isNotEmpty()) go { Remote.commandArgs(st, x.id, "SendString", JSONObject().put("String", t)) }
        }))
        val navHead = a.btn2("Navigation ▾", Pal.MUTED, 40) {}
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

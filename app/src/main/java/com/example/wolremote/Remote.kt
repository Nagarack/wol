package com.example.wolremote

import android.app.Activity
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

// Télécommande : pilote les appareils connectés à Jellyfin (télévision, tablette…)
object Remote {
    class Sess(val id: String, val name: String, val title: String, val paused: Boolean, val pos: Long)

    fun sessions(st: Settings): List<Sess> {
        val r = Jellyfin.get(st, "/Sessions", 8000)
        if (!r.ok) throw Exception("Jellyfin : HTTP " + r.code)
        val a = JSONArray(r.body)
        val out = ArrayList<Sess>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (!o.optBoolean("SupportsRemoteControl", false)) continue
            val np = o.optJSONObject("NowPlayingItem")
            val ps = o.optJSONObject("PlayState")
            val title = if (np == null) "" else (if (np.str("Type") == "Episode") np.str("SeriesName") + " · " + np.str("Name") else np.str("Name"))
            out.add(Sess(o.str("Id"), o.str("DeviceName").ifEmpty { o.str("Client") }, title,
                ps?.optBoolean("IsPaused", false) ?: false, ps?.optLong("PositionTicks", 0L) ?: 0L))
        }
        return out
    }

    fun playing(st: Settings, id: String, cmd: String): Boolean = Jellyfin.post(st, "/Sessions/$id/Playing/$cmd").ok
    fun command(st: Settings, id: String, cmd: String): Boolean = Jellyfin.post(st, "/Sessions/$id/Command/$cmd").ok
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
        a.bigDialog("Télécommande", body) { alive = false }
        thread {
            var sig = ""
            while (alive) {
                try {
                    val s = Remote.sessions(st)
                    val ns = s.joinToString("|") { it.id + it.title + it.paused }
                    a.runOnUiThread {
                        info.text = if (s.isEmpty()) "Aucun appareil connecté. Ouvre l'appli Jellyfin sur la télévision." else "Appareils connectés"
                        if (ns != sig) {
                            sig = ns
                            list.removeAllViews()
                            for (x in s) list.addView(card(a, st, x))
                        }
                    }
                } catch (e: Exception) {
                    a.runOnUiThread { info.text = "Jellyfin injoignable : " + e.message }
                }
                Thread.sleep(3000)
            }
        }
    }

    private fun card(a: Activity, st: Settings, x: Remote.Sess): LinearLayout {
        val c = a.card()
        c.addView(a.tv(x.name, 17f, Pal.FG).also { it.typeface = android.graphics.Typeface.DEFAULT_BOLD })
        c.addView(a.tv(if (x.title.isEmpty()) "Rien en lecture" else (if (x.paused) "En pause · " else "") + x.title, 13f, Pal.MUTED))
        fun bt(lbl: String, f: () -> Boolean): android.widget.Button = a.btn2(lbl, Pal.FG, 44) { thread { try { f() } catch (e: Exception) { } } }
        fun row(vararg b: android.widget.Button) {
            val r = LinearLayout(a)
            r.orientation = LinearLayout.HORIZONTAL
            for (v in b) {
                val lp = LinearLayout.LayoutParams(0, WRAP, 1f)
                lp.leftMargin = a.dp(3); lp.rightMargin = a.dp(3)
                v.layoutParams = lp.also { it.topMargin = a.dp(8) }
                r.addView(v)
            }
            c.addView(r)
        }
        if (x.title.isNotEmpty()) {
            row(bt(if (x.paused) "Lecture" else "Pause") { if (x.paused) Remote.playing(st, x.id, "Unpause") else Remote.playing(st, x.id, "Pause") },
                bt("− 30 s") { Remote.seek(st, x.id, x.pos - 300000000L) },
                bt("+ 30 s") { Remote.seek(st, x.id, x.pos + 300000000L) })
        }
        row(bt("Vol −") { Remote.command(st, x.id, "VolumeDown") },
            bt("Vol +") { Remote.command(st, x.id, "VolumeUp") },
            bt("Stop") { Remote.playing(st, x.id, "Stop") })
        return c
    }
}

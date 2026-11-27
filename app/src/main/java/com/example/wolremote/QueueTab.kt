package com.example.wolremote

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

class QueueTab(private val a: Activity, private val st: Settings) {
    val view = ScrollView(a)
    private val col = LinearLayout(a)
    private val info = a.tv("", 14f, Pal.MUTED)
    private val list = LinearLayout(a)
    private var busy = false

    private class Item(val who: String, val title: String, val pct: Int, val extra: String)

    init {
        view.isFillViewport = true
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(a.dp(16), a.dp(16), a.dp(16), a.dp(16))
        view.addView(col)
        col.addView(a.title("En cours"))
        info.setPadding(0, a.dp(6), 0, 0)
        col.addView(info)
        list.orientation = LinearLayout.VERTICAL
        col.addView(list)
        col.addView(a.btn("Ouvrir qBittorrent (téléchargements « autres »)", Pal.GRAY) {
            try {
                a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(st.base(8080))))
            } catch (e: Exception) {
                info.text = "Impossible d'ouvrir le navigateur."
            }
        })
    }

    private fun fetch(who: String, port: Int, keyName: String, items: MutableList<Item>, errs: MutableList<String>) {
        val key = st.get(keyName)
        if (key.isEmpty()) { errs.add("$who : clé API manquante (Réglages)"); return }
        try {
            val r = Net.call("GET", st.base(port) + "/api/v3/queue?pageSize=100", key, timeout = 8000)
            if (!r.ok) { errs.add("$who : erreur " + r.code); return }
            val t = r.body.trim()
            val arr: JSONArray = if (t.startsWith("[")) JSONArray(t) else (JSONObject(t).optJSONArray("records") ?: JSONArray())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val size = o.optDouble("size", 0.0)
                val left = o.optDouble("sizeleft", 0.0)
                val pct = if (size > 0) ((size - left) / size * 100).toInt() else 0
                val tl = o.str("timeleft")
                val status = o.str("status")
                items.add(Item(who, o.str("title"), pct, status + (if (tl.isNotEmpty()) " · reste $tl" else "")))
            }
        } catch (e: Exception) {
            errs.add("$who : injoignable")
        }
    }

    fun refresh() {
        if (busy) return
        busy = true
        thread {
            val items = ArrayList<Item>()
            val errs = ArrayList<String>()
            try {
                if (!st.probe()) {
                    errs.add("PC injoignable")
                } else {
                    fetch("Radarr", 7878, "kRadarr", items, errs)
                    fetch("Sonarr", 8989, "kSonarr", items, errs)
                }
            } catch (e: Exception) {
                errs.add("Erreur : " + e.message)
            }
            a.runOnUiThread {
                busy = false
                list.removeAllViews()
                info.text = if (errs.isEmpty()) (if (items.isEmpty()) "Aucun téléchargement Radarr / Sonarr." else "") else errs.joinToString("\n")
                for (item in items) {
                    val c = a.card()
                    c.addView(a.tv(item.title, 14f, Pal.FG))
                    c.addView(a.progress(item.pct))
                    c.addView(a.tv(item.who + " · " + item.pct + " % · " + item.extra, 12f, Pal.MUTED))
                    list.addView(c)
                }
            }
        }
    }
}

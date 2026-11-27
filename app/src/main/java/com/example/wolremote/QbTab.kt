package com.example.wolremote

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

// Résumé des téléchargements qBittorrent (API Web v2)
class QbTab(private val a: Activity, private val st: Settings, private val client: QbClient) {
    val view = ScrollView(a)
    private val col = LinearLayout(a)
    private val info = a.tv("", 14f, Pal.MUTED)
    private val summary = a.card()
    private val headActive = a.section("")
    private val listActive = LinearLayout(a)
    private val headDone = a.section("")
    private val listDone = LinearLayout(a)
    private var busy = false

    init {
        view.isFillViewport = true
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(a.dp(16), a.dp(16), a.dp(16), a.dp(16))
        view.addView(col)
        col.addView(a.title("qBittorrent"))
        info.setPadding(0, a.dp(6), 0, 0)
        col.addView(info)
        col.addView(summary)
        col.addView(headActive)
        listActive.orientation = LinearLayout.VERTICAL
        col.addView(listActive)
        col.addView(headDone)
        listDone.orientation = LinearLayout.VERTICAL
        col.addView(listDone)
        col.addView(a.btn("Ouvrir l'interface qBittorrent", Pal.GRAY) {
            thread {
                val ok = st.reach(st.qbPort())
                a.runOnUiThread {
                    if (ok) {
                        try { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(st.base(st.qbPort())))) } catch (e: Exception) { info.text = "Impossible d'ouvrir le navigateur." }
                    } else {
                        info.text = "qBittorrent injoignable."
                    }
                }
            }
        })
    }

    fun refresh() {
        if (busy) return
        busy = true
        thread {
            val r = client.torrents()
            a.runOnUiThread {
                busy = false
                render(r.data, r.err)
            }
        }
    }

    private fun fmtSize(b: Long): String =
        if (b >= 1000000000L) String.format(Locale.FRANCE, "%.2f Go", b / 1e9) else String.format(Locale.FRANCE, "%.0f Mo", b / 1e6)

    private fun fmtSpeed(b: Long): String =
        if (b >= 1000000L) String.format(Locale.FRANCE, "%.1f Mo/s", b / 1e6) else String.format(Locale.FRANCE, "%.0f Ko/s", b / 1e3)

    private fun fmtEta(s: Long): String = when {
        s >= 8640000L -> "∞"
        s < 60L -> "$s s"
        s < 3600L -> (s / 60).toString() + " min"
        else -> (s / 3600).toString() + " h " + ((s % 3600) / 60).toString() + " min"
    }

    private fun stateFr(s: String): String = when (s) {
        "downloading" -> "Téléchargement"
        "forcedDL" -> "Téléchargement (forcé)"
        "metaDL", "forcedMetaDL" -> "Récupération des infos"
        "stalledDL" -> "En attente de sources"
        "queuedDL", "queuedUP" -> "En file d'attente"
        "pausedDL", "stoppedDL", "pausedUP", "stoppedUP" -> "En pause"
        "checkingDL", "checkingUP", "checkingResumeData" -> "Vérification"
        "uploading", "stalledUP", "forcedUP" -> "Partage"
        "moving" -> "Déplacement"
        "error" -> "Erreur"
        "missingFiles" -> "Fichiers manquants"
        else -> s
    }

    private fun render(data: JSONArray, msg: String) {
        info.text = msg
        summary.removeAllViews()
        listActive.removeAllViews()
        listDone.removeAllViews()
        if (msg.isNotEmpty()) {
            headActive.text = ""
            headDone.text = ""
            return
        }
        val act = ArrayList<JSONObject>()
        val done = ArrayList<JSONObject>()
        var dl = 0L
        var up = 0L
        for (i in 0 until data.length()) {
            val o = data.optJSONObject(i) ?: continue
            if (o.optDouble("progress", 0.0) >= 1.0) done.add(o) else act.add(o)
            dl += o.optLong("dlspeed", 0L)
            up += o.optLong("upspeed", 0L)
        }
        act.sortByDescending { it.optLong("dlspeed", 0L) }
        done.sortByDescending { it.optLong("completion_on", 0L) }

        summary.addView(a.tv("⬇ " + act.size + " en cours    ✓ " + done.size + " terminés", 17f, Pal.FG))
        summary.addView(a.tv("↓ " + fmtSpeed(dl) + "    ↑ " + fmtSpeed(up), 14f, Pal.MUTED))

        headActive.text = "EN COURS (" + act.size + ")"
        if (act.isEmpty()) listActive.addView(a.tv("Aucun téléchargement en cours.", 14f, Pal.MUTED))
        for (o in act) {
            val c = a.card()
            c.addView(a.tv(o.str("name"), 14f, Pal.FG))
            val pct = (o.optDouble("progress", 0.0) * 100).toInt()
            c.addView(a.progress(pct))
            val line = pct.toString() + " % · " + fmtSize(o.optLong("size", 0L)) + " · " +
                fmtSpeed(o.optLong("dlspeed", 0L)) + " · reste " + fmtEta(o.optLong("eta", 8640000L)) +
                " · " + stateFr(o.str("state"))
            c.addView(a.tv(line, 12f, Pal.MUTED))
            listActive.addView(c)
        }

        headDone.text = "TERMINÉS (" + done.size + ")"
        if (done.isEmpty()) listDone.addView(a.tv("Rien de terminé pour l'instant.", 14f, Pal.MUTED))
        val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE)
        for (o in done.take(30)) {
            val c = a.card()
            c.addView(a.tv(o.str("name"), 14f, Pal.FG))
            val t = o.optLong("completion_on", 0L)
            val cat = o.str("category")
            val line = fmtSize(o.optLong("size", 0L)) +
                (if (t > 0L) " · terminé le " + fmt.format(Date(t * 1000L)) else "") +
                (if (cat.isNotEmpty()) " · $cat" else "")
            c.addView(a.tv(line, 12f, Pal.MUTED))
            listDone.addView(c)
        }
        if (done.size > 30) listDone.addView(a.tv("… et " + (done.size - 30) + " autres terminés.", 12f, Pal.MUTED))
    }
}

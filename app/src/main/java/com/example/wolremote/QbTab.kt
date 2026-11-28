package com.example.wolremote

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
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
    val view = TouchScroll(a)
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
        col.setPadding(a.dp(20), a.dp(20), a.dp(20), a.dp(28))
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

    // Noms de release : « Film.2026.1080p.mkv » devient « Film 2026 1080p »
    private fun pretty(n: String): String =
        if (n.contains(' ')) n else n.replace(Regex("\\.(mkv|mp4|avi)$", RegexOption.IGNORE_CASE), "").replace('.', ' ').replace('_', ' ')

    private fun title2(t0: String): android.widget.TextView {
        val t = pretty(t0)
        val v = a.tv(t, 15f, Pal.FG)
        v.typeface = android.graphics.Typeface.DEFAULT_BOLD
        v.maxLines = 2
        v.ellipsize = android.text.TextUtils.TruncateAt.END
        return v
    }

    // Boutons d'action discrets : contour, couleur du texte selon l'action
    private fun small(label: String, color: Int, onClick: () -> Unit): Button {
        val b = a.btn2(label, if (color == Pal.GRAY) Pal.FG else color, 40, onClick)
        b.textSize = 13f
        return b
    }

    private fun row(vararg bs: Button): LinearLayout {
        val r = LinearLayout(a)
        r.orientation = LinearLayout.HORIZONTAL
        for ((i, b) in bs.withIndex()) {
            val lp = LinearLayout.LayoutParams(0, WRAP, 1f)
            lp.topMargin = a.dp(8)
            if (i > 0) lp.leftMargin = a.dp(8)
            b.layoutParams = lp
            r.addView(b)
        }
        return r
    }

    private fun doIt(ok: String, f: () -> Boolean) {
        thread {
            val res = f()
            a.runOnUiThread {
                Toast.makeText(a, if (res) ok else "Échec de l'action", Toast.LENGTH_SHORT).show()
                refresh()
            }
        }
    }

    private fun limitDialog() {
        val names = arrayOf("Illimitée", "10 Mo/s", "5 Mo/s", "2 Mo/s", "500 Ko/s")
        val vals = longArrayOf(0L, 10000000L, 5000000L, 2000000L, 500000L)
        a.dialogBuilder().setTitle("Limite de téléchargement")
            .setItems(names) { _, i -> doIt("Limite : " + names[i]) { client.setDownloadLimit(vals[i]) } }
            .setNegativeButton("Annuler", null).show()
    }

    private fun render(data: JSONArray, msg: String) {
        if (android.os.SystemClock.uptimeMillis() - view.lastTouch < 1500) return
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

        val sp = a.tv("↓ " + fmtSpeed(dl) + "     ↑ " + fmtSpeed(up), 22f, Pal.FG)
        sp.typeface = android.graphics.Typeface.DEFAULT_BOLD
        summary.addView(sp)
        summary.addView(a.tv(act.size.toString() + " en cours  ·  " + done.size + " terminés", 13f, Pal.MUTED))
        summary.addView(row(
            small("Pause", Pal.GRAY) { doIt("Tout est en pause") { client.pause("all") } },
            small("Reprendre", Pal.GREEN) { doIt("Tout reprend") { client.resume("all") } },
            small("Limite", Pal.BLUE) { limitDialog() }
        ))

        headActive.text = "En cours (" + act.size + ")"
        if (act.isEmpty()) listActive.addView(a.tv("Aucun téléchargement en cours.", 14f, Pal.MUTED))
        for (o in act) {
            val c = a.card()
            c.addView(title2(o.str("name")))
            val pct = (o.optDouble("progress", 0.0) * 100).toInt()
            c.addView(a.progress(pct))
            val fl = FlowRow(a, a.dp(6), a.dp(6))
            fl.setPadding(0, a.dp(4), 0, 0)
            fl.addView(a.badge("$pct %", Pal.GREEN))
            fl.addView(a.badge(fmtSize(o.optLong("size", 0L)), Pal.MUTED))
            if (o.optLong("dlspeed", 0L) > 0) fl.addView(a.badge(fmtSpeed(o.optLong("dlspeed", 0L)), Pal.BLUE))
            if (o.optLong("eta", 8640000L) < 8640000L) fl.addView(a.badge("reste " + fmtEta(o.optLong("eta", 8640000L)), Pal.MUTED))
            fl.addView(a.badge(stateFr(o.str("state")), Pal.MUTED))
            c.addView(fl)
            val hash = o.str("hash")
            val name = o.str("name")
            val paused = o.str("state").let { it.startsWith("paused") || it.startsWith("stopped") }
            c.addView(row(
                if (paused) small("Reprendre", Pal.GREEN) { doIt("Repris") { client.resume(hash) } }
                else small("Pause", Pal.GRAY) { doIt("En pause") { client.pause(hash) } },
                small("Supprimer", Pal.RED) {
                    a.dialogBuilder().setTitle("Supprimer ce téléchargement ?").setMessage(name)
                        .setPositiveButton("Avec les fichiers") { _, _ -> doIt("Supprimé") { client.delete(hash, true) } }
                        .setNeutralButton("Garder les fichiers") { _, _ -> doIt("Retiré de qBittorrent") { client.delete(hash, false) } }
                        .setNegativeButton("Annuler", null).show()
                }
            ))
            listActive.addView(c)
        }

        headDone.text = "Terminés (" + done.size + ")"
        if (done.isEmpty()) listDone.addView(a.tv("Rien de terminé pour l'instant.", 14f, Pal.MUTED))
        val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE)
        for (o in done.take(30)) {
            val c = a.card()
            c.addView(title2(o.str("name")))
            val t = o.optLong("completion_on", 0L)
            val cat = o.str("category")
            val fl = FlowRow(a, a.dp(6), a.dp(6))
            fl.setPadding(0, a.dp(8), 0, 0)
            fl.addView(a.badge(fmtSize(o.optLong("size", 0L)), Pal.MUTED))
            if (t > 0L) fl.addView(a.badge(fmt.format(Date(t * 1000L)), Pal.MUTED))
            if (cat.isNotEmpty()) fl.addView(a.badge(cat, Pal.BLUE))
            c.addView(fl)
            listDone.addView(c)
        }
        if (done.size > 30) listDone.addView(a.tv("… et " + (done.size - 30) + " autres terminés.", 12f, Pal.MUTED))
    }
}

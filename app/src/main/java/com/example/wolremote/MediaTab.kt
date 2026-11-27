package com.example.wolremote

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONArray
import kotlin.concurrent.thread

// Média : Jellyfin (lecture en cours, ajouts récents, actualisation) + raccourcis vers les autres services.
class MediaTab(private val a: Activity, private val st: Settings) {
    val view = ScrollView(a)
    private val info = a.tv("", 13f, Pal.MUTED)
    private val nowCard = a.card()
    private val recentBox = LinearLayout(a)
    private var busy = false
    private var lastRecent = 0L

    init {
        val col = LinearLayout(a)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(a.dp(16), a.dp(16), a.dp(16), a.dp(16))
        view.addView(col)
        col.addView(a.title("Média"))
        col.addView(a.section("En lecture"))
        col.addView(nowCard)
        col.addView(a.btn2("Actualiser la bibliothèque", Pal.FG, 52) {
            info.text = "…"
            thread {
                val t = Jellyfin.refresh(st)
                a.runOnUiThread { info.text = t }
            }
        })
        info.setPadding(0, a.dp(8), 0, 0)
        col.addView(info)
        val recents = a.foldable(col, "Ajouts récents", st, "recent", true)
        recentBox.orientation = LinearLayout.VERTICAL
        recents.addView(recentBox)
        col.addView(a.rowItem("Découvrir", "Suggestions de films populaires (listes Radarr)") { LibraryUi5.discover(a, st) })
        col.addView(a.rowItem("Continuer à regarder", "Reprendre un film ou un épisode commencé") { LibraryUi5.resume(a, st) })
        col.addView(a.rowItem("Télécommande", "Piloter la télévision ou un autre appareil Jellyfin") { RemoteUi.open(a, st) })
        val tools = a.foldable(col, "Outils de bibliothèque", st, "tools", false)
        tools.addView(a.rowItem("Que regarder ce soir", "Films non vus, tirés au hasard") { LibraryUi3.tonight(a, st) })
        tools.addView(a.rowItem("Suivi des séries", "Prochains épisodes, recherche des manquants") { LibraryUi2.follow(a, st) })
        tools.addView(a.rowItem("Compléter les sagas", "Films manquants des sagas que tu as") { LibraryUi5.sagas(a, st) })
        tools.addView(a.rowItem("Stockage", "Espace libre et plus gros contenus") { LibraryUi.storage(a, st) })
        tools.addView(a.rowItem("Nettoyage", "Films vus depuis plus de 3 mois") { LibraryUi3.cleanup(a, st) })
        tools.addView(a.rowItem("Qualité et doublons", "Films sous 720p, titres en double") { LibraryUi2.quality(a, st) })
        tools.addView(a.rowItem("Sous-titres français", "Éléments qui n'en ont pas") { LibraryUi.subtitles(a, st) })
        val maint = a.foldable(col, "Maintenance", st, "maint", false)
        maint.addView(a.rowItem("Mettre à jour les conteneurs", "Sauvegarde puis mise à jour de Docker") { LibraryUi4.docker(a, st) })
        val links = a.foldable(col, "Ouvrir dans le navigateur", st, "links", false)
        links.addView(link("Jellyfin", 8096, Pal.PURPLE))
        links.addView(link("Prowlarr", 9696, Pal.GRAY))
        links.addView(link("Radarr", 7878, Pal.AMBER))
        links.addView(link("Sonarr", 8989, Pal.BLUE))
        links.addView(link("qBittorrent", st.qbPort(), Pal.GREEN))
    }

    private fun link(label: String, port: Int, color: Int): View =
        a.rowItem(label, "port " + port) { open(label, port, "") }

    private fun open(label: String, port: Int, path: String) {
        info.text = "Recherche du PC…"
        thread {
            val ok = st.reach(port)
            a.runOnUiThread {
                if (!ok) {
                    info.text = "$label ne répond pas : PC éteint ou service arrêté."
                } else {
                    val url = st.base(port) + path
                    info.text = ""
                    try {
                        a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (e: Exception) {
                        info.text = "Impossible d'ouvrir le navigateur."
                    }
                }
            }
        }
    }

    fun refresh() {
        if (busy) return
        busy = true
        thread {
            var now = ""
            var err = ""
            var items: JSONArray? = null
            try {
                if (st.get("kJellyfin").trim().isEmpty()) {
                    err = "Ajoute la clé API Jellyfin dans Réglages."
                } else if (st.active.isEmpty() && !st.reach(8096)) {
                    err = "Jellyfin injoignable (PC éteint ?)."
                } else {
                    val s = Jellyfin.get(st, "/Sessions?ActiveWithinSeconds=30")
                    if (s.code == 401) {
                        err = "Clé API Jellyfin refusée : recrée-en une."
                    } else if (s.ok) {
                        val arr = JSONArray(s.body)
                        val lines = ArrayList<String>()
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            val np = o.optJSONObject("NowPlayingItem") ?: continue
                            val ps = o.optJSONObject("PlayState")
                            val ticks = np.optLong("RunTimeTicks", 0L)
                            val pos = ps?.optLong("PositionTicks", 0L) ?: 0L
                            val pct = if (ticks > 0) (pos * 100 / ticks).toInt() else 0
                            val sn = np.str("SeriesName")
                            lines.add((if (ps?.optBoolean("IsPaused", false) == true) "En pause · " else "Lecture · ") +
                                o.str("UserName") + " · " + o.str("DeviceName") + "\n" +
                                (if (sn.isNotEmpty()) sn + " – " else "") + np.str("Name") + "   " + pct + " %")
                        }
                        now = lines.joinToString("\n\n")
                    }
                    if (System.currentTimeMillis() - lastRecent > 60000L) {
                        val r = Jellyfin.get(st, "/Items?SortBy=DateCreated&SortOrder=Descending&Recursive=true&IncludeItemTypes=Movie,Series&Limit=30&Fields=ProductionYear")
                        if (r.ok) {
                            items = org.json.JSONObject(r.body).optJSONArray("Items")
                            lastRecent = System.currentTimeMillis()
                        }
                    }
                }
            } catch (e: Exception) {
                err = "Jellyfin : " + e.message
            }
            val nowF = now
            val errF = err
            val itemsF = items
            a.runOnUiThread {
                busy = false
                nowCard.removeAllViews()
                nowCard.addView(a.tv(if (errF.isNotEmpty()) errF else if (nowF.isEmpty()) "Rien en lecture." else nowF, 14f, if (errF.isNotEmpty() || nowF.isEmpty()) Pal.MUTED else Pal.FG))
                if (itemsF != null) renderRecent(itemsF)
            }
        }
    }

    private fun renderRecent(items: JSONArray) {
        recentBox.removeAllViews()
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            val id = o.str("Id")
            val row = a.card()
            row.orientation = LinearLayout.HORIZONTAL
            val img = ImageView(a)
            img.scaleType = ImageView.ScaleType.CENTER_CROP
            img.setBackgroundColor(Pal.CARD2)
            row.addView(img, LinearLayout.LayoutParams(a.dp(56), a.dp(84)))
            val tx = LinearLayout(a)
            tx.orientation = LinearLayout.VERTICAL
            tx.setPadding(a.dp(12), 0, 0, 0)
            tx.addView(a.tv(o.str("Name"), 15f, Pal.FG))
            val type = if (o.str("Type") == "Series") "Série" else "Film"
            val yr = o.optInt("ProductionYear", 0)
            tx.addView(a.tv(type + (if (yr > 0) " · $yr" else ""), 12f, Pal.MUTED))
            row.addView(tx, LinearLayout.LayoutParams(0, WRAP, 1f))
            row.setOnClickListener { open("Jellyfin", 8096, "/web/index.html#!/details?id=$id") }
            recentBox.addView(row)
            Img.load(st.base(8096) + "/Items/" + id + "/Images/Primary?maxHeight=300&quality=80", img)
        }
    }
}

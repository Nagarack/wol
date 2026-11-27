package com.example.wolremote

import android.app.Activity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.concurrent.thread

fun fmtSize(b: Long): String =
    if (b >= 1L shl 40) String.format(Locale.FRANCE, "%.2f To", b / 1099511627776.0)
    else if (b >= 1L shl 30) String.format(Locale.FRANCE, "%.1f Go", b / 1073741824.0)
    else String.format(Locale.FRANCE, "%.0f Mo", b / 1048576.0)

// Espace disque et plus gros contenus (via Radarr / Sonarr)
object Storage {
    class Disk(val label: String, val free: Long, val total: Long) {
        val freePct: Int get() = if (total > 0) (free * 100 / total).toInt() else 100
    }
    class Entry(val kind: String, val id: Int, val title: String, val size: Long)

    fun disks(st: Settings): List<Disk> {
        val key = st.get("kRadarr").ifEmpty { st.get("kSonarr") }
        val port = if (st.get("kRadarr").isNotEmpty()) 7878 else 8989
        if (key.isEmpty()) return emptyList()
        val r = Net.call("GET", st.base(port) + "/api/v3/diskspace", key, timeout = 8000)
        if (!r.ok) return emptyList()
        val arr = JSONArray(r.body)
        val out = ArrayList<Disk>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val t = o.optLong("totalSpace", 0L)
            if (t > 0) out.add(Disk(o.str("label").ifEmpty { o.str("path") }, o.optLong("freeSpace", 0L), t))
        }
        return out.distinctBy { it.total to it.free }
    }

    fun biggest(st: Settings): List<Entry> {
        val out = ArrayList<Entry>()
        val kr = st.get("kRadarr")
        if (kr.isNotEmpty()) {
            val r = Net.call("GET", st.base(7878) + "/api/v3/movie", kr, timeout = 20000)
            if (r.ok) {
                val a = JSONArray(r.body)
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    val s = o.optLong("sizeOnDisk", 0L)
                    if (s > 0) out.add(Entry("movie", o.optInt("id"), o.str("title") + " (" + o.optInt("year") + ")", s))
                }
            }
        }
        val ks = st.get("kSonarr")
        if (ks.isNotEmpty()) {
            val r = Net.call("GET", st.base(8989) + "/api/v3/series", ks, timeout = 20000)
            if (r.ok) {
                val a = JSONArray(r.body)
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    val s = o.optJSONObject("statistics")?.optLong("sizeOnDisk", 0L) ?: 0L
                    if (s > 0) out.add(Entry("series", o.optInt("id"), o.str("title"), s))
                }
            }
        }
        return out.sortedByDescending { it.size }.take(20)
    }

    fun delete(st: Settings, e: Entry): Boolean {
        val movie = e.kind == "movie"
        val key = st.get(if (movie) "kRadarr" else "kSonarr")
        val url = st.base(if (movie) 7878 else 8989) + "/api/v3/" + (if (movie) "movie" else "series") + "/" + e.id + "?deleteFiles=true"
        return Net.call("DELETE", url, key, timeout = 30000).ok
    }

    // Texte d'alerte si l'espace libre est sous le seuil, sinon null
    fun lowAlert(st: Settings): String? {
        val min = st.get("kDiskMin").toIntOrNull() ?: 10
        val d = try { disks(st) } catch (e: Exception) { return null }
        val w = d.minByOrNull { it.freePct } ?: return null
        return if (w.freePct < min) "Il reste " + fmtSize(w.free) + " libres (" + w.freePct + " %) sur " + w.label else null
    }
}

// Films / épisodes sans sous-titres français (via Jellyfin)
object Subs {
    class Item(val id: String, val name: String)

    private fun hasFrench(o: JSONObject): Boolean {
        val s = o.optJSONArray("MediaStreams") ?: return false
        for (i in 0 until s.length()) {
            val m = s.optJSONObject(i) ?: continue
            if (m.str("Type") != "Subtitle") continue
            val l = (m.str("Language") + " " + m.str("DisplayLanguage")).lowercase()
            if (l.contains("fre") || l.contains("fra") || l.contains("french") || l.contains("français") || l.trim() == "fr") return true
        }
        return false
    }

    fun missing(st: Settings): List<Item> {
        val out = ArrayList<Item>()
        var start = 0
        while (true) {
            val r = Jellyfin.get(st, "/Items?IncludeItemTypes=Movie,Episode&Recursive=true&Fields=MediaStreams&SortBy=SortName&StartIndex=$start&Limit=100", 30000)
            if (!r.ok) throw Exception("Jellyfin : HTTP " + r.code)
            val a = JSONObject(r.body).optJSONArray("Items") ?: break
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                if (hasFrench(o)) continue
                val name = if (o.str("Type") == "Episode")
                    o.str("SeriesName") + " S" + o.optInt("ParentIndexNumber") + "E" + o.optInt("IndexNumber") + " " + o.str("Name")
                else o.str("Name") + (if (o.optInt("ProductionYear") > 0) " (" + o.optInt("ProductionYear") + ")" else "")
                out.add(Item(o.str("Id"), name))
            }
            if (a.length() < 100) break
            start += 100
            if (start > 5000) break
        }
        return out
    }

    // Télécharge le meilleur sous-titre trouvé ; renvoie null si OK, sinon le motif
    fun fetch(st: Settings, id: String): String? {
        for (lang in listOf("fra", "fre")) {
            val r = Jellyfin.get(st, "/Items/$id/RemoteSearch/Subtitles/$lang", 20000)
            if (!r.ok) return "aucun fournisseur de sous-titres (plugin Open Subtitles ?) HTTP " + r.code
            val a = JSONArray(r.body)
            if (a.length() == 0) continue
            var best: JSONObject? = null
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                if (best == null || (o.optBoolean("IsHashMatch") && !best.optBoolean("IsHashMatch")) ||
                    (o.optBoolean("IsHashMatch") == best.optBoolean("IsHashMatch") && o.optInt("DownloadCount") > best.optInt("DownloadCount"))) best = o
            }
            val sid = best?.str("Id") ?: continue
            val d = Jellyfin.post(st, "/Items/$id/RemoteSearch/Subtitles/" + Net.enc(sid))
            return if (d.ok) null else "téléchargement refusé HTTP " + d.code
        }
        return "aucun sous-titre trouvé"
    }
}

// Fenêtres d'affichage
object LibraryUi {
    private fun box(a: Activity, title: String, body: LinearLayout, close: String = "Fermer") {
        val sv = ScrollView(a)
        body.setPadding(a.dp(16), a.dp(8), a.dp(16), a.dp(8))
        sv.addView(body)
        a.dialogBuilder().setTitle(title).setView(sv).setPositiveButton(close, null).show()
    }

    fun storage(a: Activity, st: Settings) {
        val body = LinearLayout(a); body.orientation = LinearLayout.VERTICAL
        val info = a.tv("Chargement…", 14f, Pal.MUTED)
        body.addView(info)
        box(a, "Stockage", body)
        thread {
            try {
                val d = Storage.disks(st)
                val big = Storage.biggest(st)
                a.runOnUiThread {
                    info.text = if (d.isEmpty()) "Espace disque indisponible (clé Radarr/Sonarr ?)"
                    else d.joinToString("\n") { it.label + " : " + fmtSize(it.free) + " libres sur " + fmtSize(it.total) + " (" + it.freePct + " %)" }
                    body.addView(a.section("Plus gros contenus (touche pour supprimer)"))
                    for (e in big) {
                        val row = a.tv(fmtSize(e.size) + "   " + e.title, 14f, Pal.FG)
                        row.setPadding(0, a.dp(10), 0, a.dp(10))
                        row.setOnClickListener {
                            a.dialogBuilder().setMessage("Supprimer « " + e.title + " » et tous ses fichiers du disque ? C'est définitif.")
                                .setPositiveButton("Supprimer") { _, _ ->
                                    thread {
                                        val ok = try { Storage.delete(st, e) } catch (x: Exception) { false }
                                        a.runOnUiThread {
                                            row.text = if (ok) "Supprimé : " + e.title else "Échec : " + e.title
                                            row.setTextColor(if (ok) Pal.MUTED else Pal.RED)
                                            row.setOnClickListener(null)
                                        }
                                    }
                                }.setNegativeButton("Annuler", null).show()
                        }
                        body.addView(row)
                    }
                }
            } catch (x: Exception) {
                a.runOnUiThread { info.text = "Erreur : " + x.message }
            }
        }
    }

    fun subtitles(a: Activity, st: Settings) {
        val body = LinearLayout(a); body.orientation = LinearLayout.VERTICAL
        val info = a.tv("Analyse de la bibliothèque…", 14f, Pal.MUTED)
        body.addView(info)
        box(a, "Sous-titres français", body)
        thread {
            try {
                val miss = Subs.missing(st)
                a.runOnUiThread {
                    if (miss.isEmpty()) { info.text = "Tout a des sous-titres français."; return@runOnUiThread }
                    info.text = miss.size.toString() + " sans sous-titres français."
                    val log = a.tv("", 13f, Pal.MUTED)
                    val go = a.btn("Chercher les sous-titres (20 premiers)", Pal.BLUE, 48) { }
                    go.setOnClickListener {
                        go.isEnabled = false
                        thread {
                            var ok = 0
                            var last = ""
                            for ((n, it2) in miss.take(20).withIndex()) {
                                val err = try { Subs.fetch(st, it2.id) } catch (x: Exception) { x.message ?: "erreur" }
                                if (err == null) ok++ else last = err
                                a.runOnUiThread { log.text = (n + 1).toString() + "/20 · trouvés : " + ok + (if (last.isNotEmpty()) "\nDernier échec : $last" else "") }
                            }
                            a.runOnUiThread { go.isEnabled = true }
                        }
                    }
                    body.addView(go)
                    body.addView(log)
                    for (m in miss.take(100)) {
                        val t = a.tv(m.name, 13f, Pal.FG)
                        t.setPadding(0, a.dp(4), 0, a.dp(4))
                        body.addView(t)
                    }
                }
            } catch (x: Exception) {
                a.runOnUiThread { info.text = "Erreur : " + x.message }
            }
        }
    }
}

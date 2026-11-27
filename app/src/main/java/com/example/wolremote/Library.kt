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

    // Après un téléchargement : cherche les sous-titres français des derniers ajouts qui n'en ont pas
    fun autoRecent(st: Settings) {
        if (st.get("kAutoSubs") == "0" || st.get("kJellyfin").isEmpty()) return
        try {
            val r = Jellyfin.get(st, "/Items?IncludeItemTypes=Movie,Episode&Recursive=true&Fields=MediaStreams&SortBy=DateCreated&SortOrder=Descending&Limit=8", 20000)
            if (!r.ok) return
            val a = JSONObject(r.body).optJSONArray("Items") ?: return
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                if (!hasFrench(o)) fetch(st, o.str("Id"))
            }
        } catch (e: Exception) {
        }
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

// Films de mauvaise qualité (Radarr) et doublons (Jellyfin)
object Quality {
    class Low(val id: Int, val title: String, val q: String)

    fun lowMovies(st: Settings): List<Low> {
        val kr = st.get("kRadarr")
        if (kr.isEmpty()) throw Exception("clé Radarr manquante (Réglages)")
        val r = Net.call("GET", st.base(7878) + "/api/v3/movie", kr, timeout = 20000)
        if (!r.ok) throw Exception("Radarr : HTTP " + r.code)
        val a = JSONArray(r.body)
        val out = ArrayList<Low>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val q = o.optJSONObject("movieFile")?.optJSONObject("quality")?.optJSONObject("quality") ?: continue
            if (q.optInt("resolution", 0) < 720)
                out.add(Low(o.optInt("id"), o.str("title") + " (" + o.optInt("year") + ")", q.str("name")))
        }
        return out.sortedBy { it.title }
    }

    fun search(st: Settings, id: Int): Boolean =
        Net.call("POST", st.base(7878) + "/api/v3/command", st.get("kRadarr"),
            json = JSONObject().put("name", "MoviesSearch").put("movieIds", JSONArray().put(id)).toString(), timeout = 15000).ok

    fun duplicates(st: Settings): List<String> {
        val seen = HashMap<String, Int>()
        var start = 0
        while (true) {
            val r = Jellyfin.get(st, "/Items?IncludeItemTypes=Movie&Recursive=true&StartIndex=$start&Limit=200", 30000)
            if (!r.ok) break
            val a = JSONObject(r.body).optJSONArray("Items") ?: break
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val k = o.str("Name") + " (" + o.optInt("ProductionYear") + ")"
                seen[k] = (seen[k] ?: 0) + 1
            }
            if (a.length() < 200) break
            start += 200
            if (start > 5000) break
        }
        return seen.filter { it.value > 1 }.keys.sorted()
    }
}

// Suivi des séries : prochains épisodes à voir (Jellyfin) + recherche des suivants manquants (Sonarr)
object Follow {
    class Next(val series: String, val label: String)

    fun userId(st: Settings): String {
        val r = Jellyfin.get(st, "/Users")
        if (!r.ok) throw Exception("Jellyfin : HTTP " + r.code)
        val a = JSONArray(r.body)
        var best = ""
        var bestDate = ""
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val d = o.str("LastActivityDate")
            if (best.isEmpty() || d > bestDate) { best = o.str("Id"); bestDate = d }
        }
        if (best.isEmpty()) throw Exception("aucun utilisateur Jellyfin")
        return best
    }

    fun nextUp(st: Settings): List<Next> {
        val r = Jellyfin.get(st, "/Shows/NextUp?UserId=" + userId(st) + "&Limit=30", 20000)
        if (!r.ok) throw Exception("Jellyfin : HTTP " + r.code)
        val a = JSONObject(r.body).optJSONArray("Items") ?: return emptyList()
        val out = ArrayList<Next>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            out.add(Next(o.str("SeriesName"), "S" + o.optInt("ParentIndexNumber") + "E" + o.optInt("IndexNumber") + " · " + o.str("Name")))
        }
        return out
    }

    // Lance la recherche des épisodes diffusés mais absents ; renvoie un message
    fun searchMissing(st: Settings, name: String): String {
        val ks = st.get("kSonarr")
        if (ks.isEmpty()) return "clé Sonarr manquante"
        val sr = Net.call("GET", st.base(8989) + "/api/v3/series", ks, timeout = 20000)
        if (!sr.ok) return "Sonarr : HTTP " + sr.code
        val sa = JSONArray(sr.body)
        var sid = -1
        for (i in 0 until sa.length()) {
            val o = sa.optJSONObject(i) ?: continue
            if (o.str("title").equals(name, true)) { sid = o.optInt("id"); break }
        }
        if (sid < 0) return "introuvable dans Sonarr"
        val er = Net.call("GET", st.base(8989) + "/api/v3/episode?seriesId=$sid", ks, timeout = 20000)
        if (!er.ok) return "Sonarr : HTTP " + er.code
        val ea = JSONArray(er.body)
        val now = java.time.Instant.now().toString()
        val ids = JSONArray()
        for (i in 0 until ea.length()) {
            val o = ea.optJSONObject(i) ?: continue
            val d = o.str("airDateUtc")
            if (o.optBoolean("monitored") && !o.optBoolean("hasFile") && d.isNotEmpty() && d < now && ids.length() < 20) ids.put(o.optInt("id"))
        }
        if (ids.length() == 0) return "rien de manquant"
        val c = Net.call("POST", st.base(8989) + "/api/v3/command", ks,
            json = JSONObject().put("name", "EpisodeSearch").put("episodeIds", ids).toString(), timeout = 15000)
        return if (c.ok) "recherche de " + ids.length() + " épisode(s) lancée" else "Sonarr : HTTP " + c.code
    }
}

object LibraryUi2 {
    private fun box(a: Activity, title: String, body: LinearLayout) {
        val sv = ScrollView(a)
        body.setPadding(a.dp(16), a.dp(8), a.dp(16), a.dp(8))
        sv.addView(body)
        a.dialogBuilder().setTitle(title).setView(sv).setPositiveButton("Fermer", null).show()
    }

    fun follow(a: Activity, st: Settings) {
        val body = LinearLayout(a); body.orientation = LinearLayout.VERTICAL
        val info = a.tv("Chargement…", 14f, Pal.MUTED)
        body.addView(info)
        box(a, "Suivi des séries", body)
        thread {
            try {
                val n = Follow.nextUp(st)
                a.runOnUiThread {
                    info.text = if (n.isEmpty()) "Rien en attente : tu es à jour." else "Prochain épisode à voir. Touche une série pour chercher les épisodes manquants."
                    for (x in n) {
                        val row = a.card()
                        row.addView(a.tv(x.series, 15f, Pal.FG))
                        val sub = a.tv(x.label, 13f, Pal.MUTED)
                        row.addView(sub)
                        row.setOnClickListener {
                            sub.text = "Recherche…"
                            thread {
                                val m = try { Follow.searchMissing(st, x.series) } catch (e: Exception) { e.message ?: "erreur" }
                                a.runOnUiThread { sub.text = x.label + "\n" + m }
                            }
                        }
                        body.addView(row)
                    }
                }
            } catch (e: Exception) {
                a.runOnUiThread { info.text = "Erreur : " + e.message }
            }
        }
    }

    fun quality(a: Activity, st: Settings) {
        val body = LinearLayout(a); body.orientation = LinearLayout.VERTICAL
        val info = a.tv("Analyse…", 14f, Pal.MUTED)
        body.addView(info)
        box(a, "Qualité et doublons", body)
        thread {
            try {
                val low = Quality.lowMovies(st)
                val dup = try { Quality.duplicates(st) } catch (e: Exception) { emptyList() }
                a.runOnUiThread {
                    info.text = low.size.toString() + " film(s) sous 720p. Touche un film pour chercher une meilleure version (selon ton profil de qualité Radarr)."
                    for (m in low) {
                        val row = a.tv(m.title + "  ·  " + m.q, 14f, Pal.FG)
                        row.setPadding(0, a.dp(10), 0, a.dp(10))
                        row.setOnClickListener {
                            thread {
                                val ok = try { Quality.search(st, m.id) } catch (e: Exception) { false }
                                a.runOnUiThread {
                                    row.text = m.title + (if (ok) "  ·  recherche lancée" else "  ·  échec")
                                    row.setTextColor(Pal.MUTED)
                                }
                            }
                        }
                        body.addView(row)
                    }
                    body.addView(a.section("Doublons dans Jellyfin"))
                    body.addView(a.tv(if (dup.isEmpty()) "Aucun doublon." else dup.joinToString("\n"), 14f, if (dup.isEmpty()) Pal.MUTED else Pal.FG))
                }
            } catch (e: Exception) {
                a.runOnUiThread { info.text = "Erreur : " + e.message }
            }
        }
    }
}

// Nettoyage : films déjà vus il y a longtemps (Jellyfin) ↔ fichiers dans Radarr
object Cleanup {
    fun candidates(st: Settings, months: Int = 3): List<Storage.Entry> {
        val kr = st.get("kRadarr")
        if (kr.isEmpty()) throw Exception("clé Radarr manquante (Réglages)")
        val rr = Net.call("GET", st.base(7878) + "/api/v3/movie", kr, timeout = 20000)
        if (!rr.ok) throw Exception("Radarr : HTTP " + rr.code)
        val map = HashMap<String, Storage.Entry>()
        val ra = JSONArray(rr.body)
        for (i in 0 until ra.length()) {
            val o = ra.optJSONObject(i) ?: continue
            val s = o.optLong("sizeOnDisk", 0L)
            if (s > 0) map[o.str("title").lowercase() + "|" + o.optInt("year")] = Storage.Entry("movie", o.optInt("id"), o.str("title") + " (" + o.optInt("year") + ")", s)
        }
        val uid = Follow.userId(st)
        val r = Jellyfin.get(st, "/Users/$uid/Items?IncludeItemTypes=Movie&Recursive=true&IsPlayed=true&Limit=1000", 30000)
        if (!r.ok) throw Exception("Jellyfin : HTTP " + r.code)
        val cutoff = java.time.Instant.now().minus(months * 30L, java.time.temporal.ChronoUnit.DAYS).toString()
        val a = JSONObject(r.body).optJSONArray("Items") ?: return emptyList()
        val out = ArrayList<Storage.Entry>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val last = o.optJSONObject("UserData")?.str("LastPlayedDate") ?: ""
            if (last.isEmpty() || last >= cutoff) continue
            val e = map[o.str("Name").lowercase() + "|" + o.optInt("ProductionYear")] ?: continue
            out.add(e)
        }
        return out.sortedByDescending { it.size }
    }
}

// Que regarder ce soir : films non vus tirés au hasard
object Tonight {
    class Movie(val id: String, val title: String, val minutes: Int, val rating: Double)

    fun pick(st: Settings, minMin: Int, maxMin: Int): List<Movie> {
        val uid = Follow.userId(st)
        val r = Jellyfin.get(st, "/Users/$uid/Items?IncludeItemTypes=Movie&Recursive=true&IsPlayed=false&SortBy=Random&Limit=60&Fields=RunTimeTicks", 20000)
        if (!r.ok) throw Exception("Jellyfin : HTTP " + r.code)
        val a = JSONObject(r.body).optJSONArray("Items") ?: return emptyList()
        val out = ArrayList<Movie>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val min = (o.optLong("RunTimeTicks", 0L) / 600000000L).toInt()
            if (min < minMin || min > maxMin) continue
            out.add(Movie(o.str("Id"), o.str("Name") + (if (o.optInt("ProductionYear") > 0) " (" + o.optInt("ProductionYear") + ")" else ""), min, o.optDouble("CommunityRating", 0.0)))
            if (out.size >= 5) break
        }
        return out
    }
}

object LibraryUi3 {
    fun cleanup(a: Activity, st: Settings) {
        val body = LinearLayout(a); body.orientation = LinearLayout.VERTICAL
        val info = a.tv("Analyse…", 14f, Pal.MUTED)
        body.addView(info)
        val sv = ScrollView(a); body.setPadding(a.dp(16), a.dp(8), a.dp(16), a.dp(8)); sv.addView(body)
        a.dialogBuilder().setTitle("Nettoyage").setView(sv).setPositiveButton("Fermer", null).show()
        thread {
            try {
                val c = Cleanup.candidates(st)
                a.runOnUiThread {
                    info.text = if (c.isEmpty()) "Aucun film vu depuis plus de 3 mois." else c.size.toString() + " film(s) vus il y a plus de 3 mois, " + fmtSize(c.sumOf { it.size }) + " à récupérer. Touche pour supprimer."
                    for (e in c) {
                        val row = a.tv(fmtSize(e.size) + "   " + e.title, 14f, Pal.FG)
                        row.setPadding(0, a.dp(10), 0, a.dp(10))
                        row.setOnClickListener {
                            a.dialogBuilder().setMessage("Supprimer « " + e.title + " » et son fichier ? C'est définitif.")
                                .setPositiveButton("Supprimer") { _, _ ->
                                    thread {
                                        val ok = try { Storage.delete(st, e) } catch (x: Exception) { false }
                                        a.runOnUiThread {
                                            row.text = (if (ok) "Supprimé : " else "Échec : ") + e.title
                                            row.setTextColor(Pal.MUTED)
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

    fun tonight(a: Activity, st: Settings) {
        val body = LinearLayout(a); body.orientation = LinearLayout.VERTICAL
        body.setPadding(a.dp(16), a.dp(8), a.dp(16), a.dp(8))
        val list = LinearLayout(a); list.orientation = LinearLayout.VERTICAL
        val info = a.tv("", 14f, Pal.MUTED)
        fun load(lo: Int, hi: Int) {
            info.text = "Recherche…"
            list.removeAllViews()
            thread {
                try {
                    val m = Tonight.pick(st, lo, hi)
                    a.runOnUiThread {
                        info.text = if (m.isEmpty()) "Aucun film non vu dans cette durée." else ""
                        for (x in m) {
                            val row = LinearLayout(a); row.orientation = LinearLayout.HORIZONTAL
                            row.setPadding(0, a.dp(8), 0, a.dp(8))
                            val img = android.widget.ImageView(a)
                            img.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                            img.setBackgroundColor(Pal.CARD2)
                            row.addView(img, LinearLayout.LayoutParams(a.dp(64), a.dp(96)))
                            Img.load(st.base(8096) + "/Items/" + x.id + "/Images/Primary?maxHeight=300&quality=80", img)
                            val t = LinearLayout(a); t.orientation = LinearLayout.VERTICAL
                            t.setPadding(a.dp(12), 0, 0, 0)
                            t.addView(a.tv(x.title, 15f, Pal.FG))
                            t.addView(a.tv((x.minutes / 60).toString() + " h " + String.format("%02d", x.minutes % 60) + (if (x.rating > 0) "  ·  " + String.format(Locale.FRANCE, "%.1f", x.rating) + "/10" else ""), 13f, Pal.MUTED))
                            row.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
                            list.addView(row)
                        }
                    }
                } catch (x: Exception) {
                    a.runOnUiThread { info.text = "Erreur : " + x.message }
                }
            }
        }
        val bar = LinearLayout(a); bar.orientation = LinearLayout.HORIZONTAL
        for ((lbl, lo, hi) in listOf(Triple("Tous", 0, 999), Triple("Court", 0, 105), Triple("Long", 120, 999))) {
            val b = a.btn2(lbl, Pal.FG, 44) { load(lo, hi) }
            bar.addView(b, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        body.addView(bar)
        body.addView(info)
        body.addView(list)
        val sv = ScrollView(a); sv.addView(body)
        a.dialogBuilder().setTitle("Ce soir").setView(sv).setPositiveButton("Fermer", null).show()
        load(0, 999)
    }
}

@Suppress("DEPRECATION")
fun wifiGateway(c: android.content.Context): String {
    return try {
        val w = c.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        val g = w.dhcpInfo?.gateway ?: 0
        if (g == 0) "" else "${g and 255}.${(g shr 8) and 255}.${(g shr 16) and 255}.${(g shr 24) and 255}"
    } catch (e: Exception) { "" }
}

// Maintenance : mise à jour des conteneurs Docker (via l'agent du PC)
object LibraryUi4 {
    fun docker(a: Activity, st: Settings) {
        a.dialogBuilder().setTitle("Mettre à jour les conteneurs")
            .setMessage("Radarr, Sonarr et Prowlarr font d'abord une sauvegarde de leurs réglages, puis tous les conteneurs sont mis à jour et redémarrés. Les téléchargements et lectures en cours seront interrompus quelques minutes.")
            .setPositiveButton("Lancer") { _, _ -> run(a, st) }
            .setNegativeButton("Annuler", null).show()
    }

    private fun run(a: Activity, st: Settings) {
        val body = LinearLayout(a); body.orientation = LinearLayout.VERTICAL
        body.setPadding(a.dp(16), a.dp(8), a.dp(16), a.dp(8))
        val info = a.tv("Sauvegarde des réglages…", 14f, Pal.FG)
        body.addView(info)
        var open = true
        a.dialogBuilder().setTitle("Mise à jour").setView(body).setPositiveButton("Fermer") { _, _ -> open = false }
            .setOnDismissListener { open = false }.show()
        thread {
            for ((port, v, k) in listOf(Triple(7878, "v3", "kRadarr"), Triple(8989, "v3", "kSonarr"), Triple(9696, "v1", "kProwlarr"))) {
                val key = st.get(k)
                if (key.isNotEmpty()) try {
                    Net.call("POST", st.base(port) + "/api/$v/command", key, json = "{\"name\":\"Backup\"}", timeout = 10000)
                } catch (e: Exception) { }
            }
            try {
                Thread.sleep(3000)
                val r = Net.call("POST", st.tablet() + "/docker/update", json = JSONObject().put("pin", st.pin()).toString(), timeout = 25000)
                val res = JSONObject(r.body)
                if (res.optString("result") != "started" && res.optString("result") != "busy") {
                    a.runOnUiThread { info.text = res.optString("message", "Échec") }
                    return@thread
                }
            } catch (e: Exception) {
                a.runOnUiThread { info.text = "Échec : " + e.message }
                return@thread
            }
            Thread.sleep(4000)
            while (open) {
                try {
                    val d = JSONObject(Net.call("GET", st.tablet() + "/docker/status", timeout = 6000).body)
                    val s = d.optString("state")
                    if (s == "done") {
                        val u = d.optJSONArray("updated")
                        val names = ArrayList<String>()
                        if (u != null) for (i in 0 until u.length()) names.add(u.optString(i))
                        a.runOnUiThread { info.text = if (names.isEmpty()) "Terminé : tout était déjà à jour." else "Terminé. Mis à jour : " + names.joinToString(", ") }
                        return@thread
                    }
                    if (s == "error") { a.runOnUiThread { info.text = "Erreur : " + d.optString("msg") }; return@thread }
                    val m = if (s == "running") d.optString("msg") else "En attente de l'agent du PC…"
                    a.runOnUiThread { info.text = m }
                } catch (e: Exception) {
                    // le PC peut être injoignable quelques secondes pendant le redémarrage des conteneurs
                }
                Thread.sleep(4000)
            }
        }
    }
}

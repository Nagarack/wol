package com.example.wolremote

import android.app.Activity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

// Continuer à regarder (Jellyfin) et sagas incomplètes (Radarr)
object Resume {
    class Item(val id: String, val title: String, val posTicks: Long, val runTicks: Long)

    fun list(st: Settings): List<Item> {
        val uid = Follow.userId(st)
        val r = Jellyfin.get(st, "/Users/$uid/Items/Resume?Limit=15&MediaTypes=Video&Recursive=true", 15000)
        if (!r.ok) throw Exception("Jellyfin : HTTP " + r.code)
        val a = JSONObject(r.body).optJSONArray("Items") ?: return emptyList()
        val out = ArrayList<Item>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val t = if (o.str("Type") == "Episode") o.str("SeriesName") + " S" + o.optInt("ParentIndexNumber") + "E" + o.optInt("IndexNumber") else o.str("Name")
            out.add(Item(o.str("Id"), t, o.optJSONObject("UserData")?.optLong("PlaybackPositionTicks", 0L) ?: 0L, o.optLong("RunTimeTicks", 0L)))
        }
        return out
    }
}

object Sagas {
    class Missing(val tmdb: Int, val title: String)
    class Saga(val name: String, val missing: List<Missing>)

    fun incomplete(st: Settings): List<Saga> {
        val kr = st.get("kRadarr")
        if (kr.isEmpty()) throw Exception("clé Radarr manquante (Réglages)")
        val cr = Net.call("GET", st.base(7878) + "/api/v3/collection", kr, timeout = 30000)
        if (cr.code == 404) throw Exception("ta version de Radarr ne propose pas les collections")
        if (!cr.ok) throw Exception("Radarr : HTTP " + cr.code)
        val mr = Net.call("GET", st.base(7878) + "/api/v3/movie", kr, timeout = 30000)
        val have = HashSet<Int>()
        if (mr.ok) { val m = JSONArray(mr.body); for (i in 0 until m.length()) have.add(m.optJSONObject(i)?.optInt("tmdbId") ?: 0) }
        val out = ArrayList<Saga>()
        val ca = JSONArray(cr.body)
        for (i in 0 until ca.length()) {
            val c = ca.optJSONObject(i) ?: continue
            val ms = c.optJSONArray("movies") ?: continue
            val miss = ArrayList<Missing>()
            for (j in 0 until ms.length()) {
                val m = ms.optJSONObject(j) ?: continue
                val id = m.optInt("tmdbId")
                if (id > 0 && !have.contains(id)) miss.add(Missing(id, m.str("title") + (if (m.optInt("year") > 0) " (" + m.optInt("year") + ")" else "")))
            }
            if (miss.isNotEmpty()) out.add(Saga(c.str("title"), miss))
        }
        return out.sortedBy { it.name }
    }

    // Ajoute le film à Radarr et lance sa recherche ; null = OK
    fun add(st: Settings, tmdb: Int): String? {
        val base = st.base(7878)
        val key = st.get("kRadarr")
        val lk = Net.call("GET", "$base/api/v3/movie/lookup/tmdb?tmdbId=$tmdb", key, timeout = 30000)
        if (!lk.ok) return "introuvable (" + lk.code + ")"
        val pref = st.get("pRadarr").trim().toIntOrNull()
        val prof = pref ?: JSONArray(Net.call("GET", "$base/api/v3/qualityprofile", key).body).optJSONObject(0)?.optInt("id") ?: return "aucun profil de qualité"
        val body = JSONObject(lk.body)
        body.remove("id")
        body.put("qualityProfileId", prof)
        body.put("rootFolderPath", "/data/media/films")
        body.put("monitored", true)
        body.put("minimumAvailability", "released")
        body.put("addOptions", JSONObject().put("searchForMovie", true))
        val r = Net.call("POST", "$base/api/v3/movie", key, body.toString(), 30000)
        return if (r.ok) null else "refusé (" + r.code + ")"
    }
}

object Discover {
    // Listes TMDb créées dans Radarr : libellé → type TMDb (0 populaires, 1 mieux notés, 2 au cinéma, 3 à venir)
    val KINDS = listOf("Populaires" to 0, "Au cinéma" to 2, "À venir" to 3, "Mieux notés" to 1)
    private fun nameOf(label: String) = "Mon PC : $label"

    private fun get(st: Settings, path: String, t: Int = 30000): Resp =
        Net.call("GET", st.base(7878) + path, st.get("kRadarr"), timeout = t)

    private fun lists(st: Settings): List<JSONObject> {
        val r = get(st, "/api/v3/importlist")
        if (!r.ok) throw Exception("Radarr : HTTP " + r.code)
        val a = JSONArray(r.body)
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }

    fun hasLists(st: Settings): Boolean {
        if (st.get("kRadarr").isEmpty()) throw Exception("clé Radarr manquante (Réglages)")
        val names = lists(st).map { it.str("name") }
        return KINDS.any { names.contains(nameOf(it.first)) }
    }

    // Crée dans Radarr les listes TMDb manquantes (sans ajout automatique) ; null = OK
    fun enable(st: Settings): String? {
        val existing = lists(st).map { it.str("name") }
        val sc = get(st, "/api/v3/importlist/schema")
        if (!sc.ok) return "Radarr : HTTP " + sc.code
        val sa = JSONArray(sc.body)
        var template: JSONObject? = null
        for (i in 0 until sa.length()) {
            val o = sa.optJSONObject(i) ?: continue
            if (o.str("implementation") == "TMDbPopularImport") { template = o; break }
        }
        val t = template ?: return "Modèle TMDb introuvable dans ta version de Radarr."
        val pref = st.get("pRadarr").trim().toIntOrNull()
        val prof = pref ?: JSONArray(get(st, "/api/v3/qualityprofile").body).optJSONObject(0)?.optInt("id") ?: return "Aucun profil de qualité dans Radarr."
        for ((label, type) in KINDS) {
            if (existing.contains(nameOf(label))) continue
            val o = JSONObject(t.toString())
            o.put("name", nameOf(label)).put("enabled", true).put("enableAuto", false)
            o.put("qualityProfileId", prof).put("rootFolderPath", "/data/media/films")
            o.put("minimumAvailability", "released").put("searchOnAdd", false).put("tags", JSONArray())
            val f = o.optJSONArray("fields")
            if (f != null) for (k in 0 until f.length()) {
                val x = f.optJSONObject(k) ?: continue
                if (x.str("name") == "tmdbListType") x.put("value", type)
            }
            val r = Net.call("POST", st.base(7878) + "/api/v3/importlist", st.get("kRadarr"), o.toString(), 30000)
            if (!r.ok) return "Radarr a refusé « $label » (" + r.code + ") : " + r.body.take(160)
        }
        Net.call("POST", st.base(7878) + "/api/v3/command", st.get("kRadarr"), "{\"name\":\"ImportListSync\"}", 15000)
        return null
    }

    var lastInfo = ""

    fun items(st: Settings, label: String): List<JSONObject> {
        val id = lists(st).firstOrNull { it.str("name") == nameOf(label) }?.optInt("id", -1) ?: -1
        val r = get(st, "/api/v3/importlist/movie", 60000)
        if (!r.ok) throw Exception("Radarr : HTTP " + r.code)
        val a = JSONArray(r.body)
        val fresh = ArrayList<JSONObject>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.optBoolean("isExisting") || o.optBoolean("isExcluded") || o.optInt("id", 0) > 0) continue
            fresh.add(o)
        }
        val ofList = if (id < 0) fresh else fresh.filter { o ->
            val ls = o.optJSONArray("lists")
            ls == null || (0 until ls.length()).any { ls.optInt(it) == id }
        }
        lastInfo = "Radarr a renvoyé " + a.length() + " suggestion(s), " + fresh.size + " nouvelle(s), " + ofList.size + " dans cette liste. La synchronisation peut prendre quelques minutes ; sinon, utilise une clé TMDb (Réglages, Clés API)."
        return (if (ofList.isEmpty()) fresh else ofList).take(40)
    }
}

// Séries : nouveautés et populaires via TVMaze (gratuit, sans clé), ajout via Sonarr
object Shows {
    val KINDS = listOf("Nouvelles séries", "À venir", "Nouvelles saisons", "Populaires")
    private class Ep(val date: String, val season: Int, val number: Int, val show: JSONObject)
    private var eps: List<Ep> = emptyList()
    private var at = 0L

    private fun fetchSchedule(st: Settings): List<Ep> {
        if (System.currentTimeMillis() - at < 1800000L && eps.isNotEmpty()) return eps
        val out = ArrayList<Ep>()
        val today = java.time.LocalDate.now()
        for (d in -10..7) {
            val day = today.plusDays(d.toLong()).toString()
            var r = Net.call("GET", "https://api.tvmaze.com/schedule/web?date=$day", timeout = 15000)
            if (r.code == 429) { Thread.sleep(1500); r = Net.call("GET", "https://api.tvmaze.com/schedule/web?date=$day", timeout = 15000) }
            if (!r.ok) continue
            val a = JSONArray(r.body)
            for (k in 0 until a.length()) {
                val o = a.optJSONObject(k) ?: continue
                val sh = o.optJSONObject("_embedded")?.optJSONObject("show") ?: continue
                out.add(Ep(o.str("airdate").ifEmpty { day }, o.optInt("season"), o.optInt("number"), sh))
            }
            Thread.sleep(120)
        }
        if (out.isEmpty()) throw Exception("TVMaze injoignable")
        eps = out; at = System.currentTimeMillis()
        return out
    }

    private fun norm(sh: JSONObject, sub: String): JSONObject {
        val ext = sh.optJSONObject("externals")
        val img = sh.optJSONObject("image")
        return JSONObject().put("title", sh.str("name")).put("year", sh.str("premiered").take(4).toIntOrNull() ?: 0)
            .put("overview", sh.str("summary").replace(Regex("<[^>]+>"), "").trim())
            .put("remotePoster", img?.str("medium") ?: "").put("tvdbId", ext?.optInt("thetvdb", 0) ?: 0).put("sub", sub)
    }

    fun items(st: Settings, kind: String): List<JSONObject> {
        val all = fetchSchedule(st)
        val have = HashSet<Int>()
        val ks = st.get("kSonarr")
        if (ks.isNotEmpty()) {
            val r = Net.call("GET", st.base(8989) + "/api/v3/series", ks, timeout = 20000)
            if (r.ok) { val a = JSONArray(r.body); for (i in 0 until a.length()) have.add(a.optJSONObject(i)?.optInt("tvdbId") ?: 0) }
        }
        val today = java.time.LocalDate.now().toString()
        val okTypes = setOf("Scripted", "Animation", "Documentary")
        val valid = all.filter { (it.show.optJSONObject("externals")?.optInt("thetvdb", 0) ?: 0) > 0 &&
            okTypes.contains(it.show.str("type")) && !have.contains(it.show.optJSONObject("externals")?.optInt("thetvdb", 0) ?: 0) }
        val picked: List<Pair<Ep, String>> = when (kind) {
            "Nouvelles séries" -> valid.filter { it.season == 1 && it.number == 1 && it.date <= today }.sortedByDescending { it.date }.map { Pair(it, "Sortie le " + it.date) }
            "À venir" -> valid.filter { it.season == 1 && it.number == 1 && it.date > today }.sortedBy { it.date }.map { Pair(it, "Sortie le " + it.date) }
            "Nouvelles saisons" -> valid.filter { it.number == 1 && it.season > 1 && it.date <= today }.sortedByDescending { it.date }.map { Pair(it, "Saison " + it.season + " le " + it.date) }
            else -> valid.sortedByDescending { it.show.optDouble("weight", 0.0) }.map { Pair(it, "") }
        }
        val seen = HashSet<Int>()
        val out = ArrayList<JSONObject>()
        for ((e, sub) in picked) {
            if (!seen.add(e.show.optInt("id"))) continue
            out.add(norm(e.show, sub))
            if (out.size >= 40) break
        }
        return out
    }

    // Ajoute la série à Sonarr (première saison surveillée) et lance la recherche ; null = OK
    fun add(st: Settings, tvdb: Int): String? {
        val base = st.base(8989)
        val key = st.get("kSonarr")
        if (key.isEmpty()) return "clé Sonarr manquante"
        val lk = Net.call("GET", "$base/api/v3/series/lookup?term=tvdb:$tvdb", key, timeout = 30000)
        if (!lk.ok) return "introuvable (" + lk.code + ")"
        val first = JSONArray(lk.body).optJSONObject(0) ?: return "introuvable dans Sonarr"
        val pref = st.get("pSonarr").trim().toIntOrNull()
        val prof = pref ?: JSONArray(Net.call("GET", "$base/api/v3/qualityprofile", key).body).optJSONObject(0)?.optInt("id") ?: return "aucun profil de qualité"
        val body = JSONObject(first.toString())
        body.remove("id")
        body.put("qualityProfileId", prof).put("rootFolderPath", "/data/media/series").put("seasonFolder", true).put("monitored", true)
        body.put("addOptions", JSONObject().put("monitor", "firstSeason").put("searchForMissingEpisodes", true))
        try {
            val lp = Net.call("GET", "$base/api/v3/languageprofile", key)
            if (lp.ok) { val la = JSONArray(lp.body); if (la.length() > 0) body.put("languageProfileId", la.getJSONObject(0).getInt("id")) }
        } catch (e: Exception) { }
        val r = Net.call("POST", "$base/api/v3/series", key, body.toString(), 30000)
        return if (r.ok) null else "refusé (" + r.code + ")"
    }
}

// Suggestions via TMDb (clé gratuite, facultative) : populaires, au cinéma, à l'antenne…
object Tmdb {
    val MOVIE = listOf("Populaires" to "popular", "Au cinéma" to "now_playing", "À venir" to "upcoming", "Mieux notés" to "top_rated")
    val TV = listOf("Populaires" to "popular", "À l'antenne" to "on_the_air", "Aujourd'hui" to "airing_today", "Mieux notées" to "top_rated")
    fun has(st: Settings) = st.get("kTmdb").trim().isNotEmpty()

    fun list(st: Settings, tv: Boolean, label: String): List<JSONObject> {
        val kind = if (tv) "tv" else "movie"
        val ep = (if (tv) TV else MOVIE).firstOrNull { it.first == label }?.second ?: "popular"
        val key = st.get("kTmdb").trim()
        val have = HashSet<Int>()
        val k2 = st.get(if (tv) "kSonarr" else "kRadarr")
        if (k2.isNotEmpty()) {
            val r = Net.call("GET", st.base(if (tv) 8989 else 7878) + "/api/v3/" + (if (tv) "series" else "movie"), k2, timeout = 20000)
            if (r.ok) { val a = JSONArray(r.body); for (i in 0 until a.length()) have.add(a.optJSONObject(i)?.optInt("tmdbId") ?: 0) }
        }
        val out = ArrayList<JSONObject>()
        for (page in 1..2) {
            val r = Net.call("GET", "https://api.themoviedb.org/3/$kind/$ep?api_key=$key&language=fr-FR&region=FR&page=$page", timeout = 15000)
            if (r.code == 401) throw Exception("clé TMDb refusée")
            if (!r.ok) throw Exception("TMDb : HTTP " + r.code)
            val a = JSONObject(r.body).optJSONArray("results") ?: break
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val id = o.optInt("id")
                if (have.contains(id)) continue
                val date = o.str(if (tv) "first_air_date" else "release_date")
                val p = o.str("poster_path")
                out.add(JSONObject().put("title", o.str(if (tv) "name" else "title")).put("year", date.take(4).toIntOrNull() ?: 0)
                    .put("overview", o.str("overview")).put("remotePoster", if (p.isEmpty()) "" else "https://image.tmdb.org/t/p/w185$p")
                    .put("tmdbId", id).put("tv", tv)
                    .put("sub", if (o.optDouble("vote_average", 0.0) > 0) String.format(java.util.Locale.FRANCE, "Note %.1f / 10", o.optDouble("vote_average")) else ""))
            }
        }
        return out.take(40)
    }

    // Série : TMDb → identifiant TVDB → ajout dans Sonarr
    fun addTv(st: Settings, tmdb: Int): String? {
        val r = Net.call("GET", "https://api.themoviedb.org/3/tv/$tmdb/external_ids?api_key=" + st.get("kTmdb").trim(), timeout = 15000)
        if (!r.ok) return "TMDb : HTTP " + r.code
        val tvdb = JSONObject(r.body).optInt("tvdb_id", 0)
        if (tvdb == 0) return "pas d'identifiant TVDB pour cette série"
        return Shows.add(st, tvdb)
    }
}

object LibraryUi5 {
    fun discover(a: Activity, st: Settings) {
        val body = LinearLayout(a); body.orientation = LinearLayout.VERTICAL
        val modes = android.widget.HorizontalScrollView(a)
        modes.isHorizontalScrollBarEnabled = false
        val modeRow = LinearLayout(a); modeRow.orientation = LinearLayout.HORIZONTAL
        modes.addView(modeRow)
        val hs = android.widget.HorizontalScrollView(a)
        hs.isHorizontalScrollBarEnabled = false
        val chips = LinearLayout(a); chips.orientation = LinearLayout.HORIZONTAL
        hs.addView(chips)
        val info = a.tv("Chargement…", 14f, Pal.MUTED)
        info.setPadding(0, a.dp(8), 0, 0)
        val list = LinearLayout(a); list.orientation = LinearLayout.VERTICAL
        hs.setPadding(0, a.dp(8), 0, 0)
        body.addView(modes); body.addView(hs); body.addView(info); body.addView(list)
        dialog(a, "Découvrir", body)
        var mode = "films"
        var cur = Discover.KINDS[0].first
        fun kinds(): List<String> = if (Tmdb.has(st)) (if (mode == "films") Tmdb.MOVIE else Tmdb.TV).map { it.first } else if (mode == "films") Discover.KINDS.map { it.first } else Shows.KINDS

        fun chip(label: String, sel: Boolean, click: () -> Unit): android.widget.TextView {
            val t = a.tv(label, 14f, if (sel) android.graphics.Color.WHITE else Pal.MUTED)
            t.setPadding(a.dp(14), a.dp(8), a.dp(14), a.dp(8))
            t.background = a.rounded(if (sel) Pal.BLUE else Pal.CARD, 20, if (sel) Pal.BLUE else Pal.BORDER)
            t.setOnClickListener { click() }
            return t
        }
        fun add(row: LinearLayout, v: android.view.View) {
            val lp = LinearLayout.LayoutParams(WRAP, WRAP); lp.rightMargin = a.dp(8)
            row.addView(v, lp)
        }

        fun show(l: List<JSONObject>) {
            list.removeAllViews()
            info.text = if (l.isEmpty()) "Rien pour l'instant. " + (if (mode == "films" && !Tmdb.has(st)) Discover.lastInfo else "Réessaie dans une minute.") else "Touche un titre pour l'ajouter et lancer son téléchargement."
            for (o in l) {
                val isShow = o.has("tvdbId")
                val row = LinearLayout(a); row.orientation = LinearLayout.HORIZONTAL
                row.setPadding(0, a.dp(8), 0, a.dp(8))
                val img = ImageView(a)
                img.scaleType = ImageView.ScaleType.CENTER_CROP
                img.setBackgroundColor(Pal.CARD2)
                row.addView(img, LinearLayout.LayoutParams(a.dp(64), a.dp(96)))
                Img.load(posterUrl(o), img)
                val t = LinearLayout(a); t.orientation = LinearLayout.VERTICAL
                t.setPadding(a.dp(12), 0, 0, 0)
                t.addView(a.tv(o.str("title") + (if (o.optInt("year") > 0) " (" + o.optInt("year") + ")" else ""), 15f, Pal.FG))
                if (o.str("sub").isNotEmpty()) t.addView(a.tv(o.str("sub"), 12f, Pal.BLUE))
                val state = a.tv(o.str("overview").take(100) + "…", 12f, Pal.MUTED)
                t.addView(state)
                row.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
                row.setOnClickListener {
                    row.setOnClickListener(null)
                    state.text = "Ajout…"
                    thread {
                        val err = try { if (o.optBoolean("tv")) Tmdb.addTv(st, o.optInt("tmdbId")) else if (isShow) Shows.add(st, o.optInt("tvdbId")) else Sagas.add(st, o.optInt("tmdbId")) } catch (e: Exception) { e.message ?: "erreur" }
                        a.runOnUiThread { state.text = if (err == null) "Ajouté, recherche lancée." else "Échec : $err"; state.setTextColor(if (err == null) Pal.GREEN else Pal.RED) }
                    }
                }
                list.addView(row)
            }
        }

        fun load(label: String) {
            info.text = "Chargement…"
            list.removeAllViews()
            val m = mode
            thread {
                try {
                    val l = if (Tmdb.has(st)) Tmdb.list(st, m != "films", label) else if (m == "films") Discover.items(st, label) else Shows.items(st, label)
                    a.runOnUiThread { if (m == mode) show(l) }
                } catch (e: Exception) {
                    a.runOnUiThread { info.text = "Erreur : " + e.message }
                }
            }
        }

        fun chipsRender() {
            chips.removeAllViews()
            for (k in kinds()) add(chips, chip(k, k == cur) { cur = k; chipsRender(); load(k) })
        }

        fun start() {
            chipsRender()
            list.removeAllViews()
            if (mode == "series" || Tmdb.has(st)) { load(cur); return }
            info.text = "Chargement…"
            thread {
                try {
                    if (Discover.hasLists(st)) {
                        a.runOnUiThread { load(cur) }
                    } else {
                        a.runOnUiThread {
                            info.text = "Les suggestions de films viennent de listes TMDb gérées par Radarr (populaires, au cinéma, à venir, mieux notés). Elles ne sont pas encore activées."
                            val b = a.btn("Activer les suggestions", Pal.BLUE, 48) {}
                            b.setOnClickListener {
                                b.isEnabled = false
                                info.text = "Création des listes dans Radarr…"
                                thread {
                                    val err = try { Discover.enable(st) } catch (e: Exception) { e.message ?: "erreur" }
                                    a.runOnUiThread {
                                        if (err != null) { info.text = err; b.isEnabled = true }
                                        else {
                                            list.removeView(b)
                                            info.text = "Listes créées. Radarr les synchronise (jusqu'à une minute)…"
                                            thread { Thread.sleep(25000); a.runOnUiThread { if (mode == "films") load(cur) } }
                                        }
                                    }
                                }
                            }
                            list.addView(b)
                        }
                    }
                } catch (e: Exception) {
                    a.runOnUiThread { info.text = "Erreur : " + e.message }
                }
            }
        }

        fun modesRender() {
            modeRow.removeAllViews()
            add(modeRow, chip("Films", mode == "films") { mode = "films"; cur = kinds().first(); modesRender(); start() })
            add(modeRow, chip("Séries", mode == "series") { mode = "series"; cur = kinds().first(); modesRender(); start() })
        }
        modesRender()
        start()
    }

    private fun dialog(a: Activity, title: String, body: LinearLayout) {
        body.setPadding(a.dp(16), a.dp(8), a.dp(16), a.dp(8))
        val sv = ScrollView(a)
        sv.addView(body)
        a.dialogBuilder().setTitle(title).setView(sv).setPositiveButton("Fermer", null).show()
    }

    fun resume(a: Activity, st: Settings) {
        val body = LinearLayout(a); body.orientation = LinearLayout.VERTICAL
        val info = a.tv("Chargement…", 14f, Pal.MUTED)
        body.addView(info)
        dialog(a, "Continuer à regarder", body)
        thread {
            try {
                val l = Resume.list(st)
                a.runOnUiThread {
                    info.text = if (l.isEmpty()) "Rien en cours." else "Touche un titre pour reprendre sur la télévision, appui long pour le marquer comme vu."
                    for (x in l) {
                        val row = LinearLayout(a); row.orientation = LinearLayout.HORIZONTAL
                        row.setPadding(0, a.dp(8), 0, a.dp(8))
                        val img = ImageView(a)
                        img.scaleType = ImageView.ScaleType.CENTER_CROP
                        img.setBackgroundColor(Pal.CARD2)
                        row.addView(img, LinearLayout.LayoutParams(a.dp(96), a.dp(56)))
                        Img.load(st.base(8096) + "/Items/" + x.id + "/Images/Primary?maxHeight=200&quality=80", img)
                        val t = LinearLayout(a); t.orientation = LinearLayout.VERTICAL
                        t.setPadding(a.dp(12), 0, 0, 0)
                        t.addView(a.tv(x.title, 15f, Pal.FG))
                        val left = ((x.runTicks - x.posTicks) / 600000000L).toInt()
                        t.addView(a.tv("reste " + (if (left >= 60) (left / 60).toString() + " h " + (left % 60) + " min" else "$left min"), 13f, Pal.MUTED))
                        row.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
                        row.setOnClickListener { RemoteUi.playOn(a, st, x.id, x.title, x.posTicks) }
                        row.setOnLongClickListener {
                            a.dialogBuilder().setMessage("Marquer « " + x.title + " » comme vu ?")
                                .setPositiveButton("Oui") { _, _ ->
                                    thread {
                                        val ok = try { Follow.mark(st, x.id, true) } catch (e: Exception) { false }
                                        a.runOnUiThread { row.visibility = if (ok) android.view.View.GONE else android.view.View.VISIBLE }
                                    }
                                }.setNegativeButton("Annuler", null).show()
                            true
                        }
                        body.addView(row)
                    }
                }
            } catch (e: Exception) {
                a.runOnUiThread { info.text = "Erreur : " + e.message }
            }
        }
    }

    fun sagas(a: Activity, st: Settings) {
        val body = LinearLayout(a); body.orientation = LinearLayout.VERTICAL
        val info = a.tv("Analyse des sagas…", 14f, Pal.MUTED)
        body.addView(info)
        dialog(a, "Compléter les sagas", body)
        thread {
            try {
                val l = Sagas.incomplete(st)
                a.runOnUiThread {
                    info.text = if (l.isEmpty()) "Toutes tes sagas sont complètes." else l.size.toString() + " saga(s) incomplète(s). Touche un film pour l'ajouter et le télécharger."
                    for (s in l) {
                        body.addView(a.section(s.name))
                        for (m in s.missing) {
                            val row = a.tv(m.title, 15f, Pal.FG)
                            row.setPadding(0, a.dp(10), 0, a.dp(10))
                            row.setOnClickListener {
                                row.text = m.title + "  ·  ajout…"
                                row.setOnClickListener(null)
                                thread {
                                    val err = try { Sagas.add(st, m.tmdb) } catch (e: Exception) { e.message ?: "erreur" }
                                    a.runOnUiThread { row.text = m.title + (if (err == null) "  ·  ajouté, recherche lancée" else "  ·  échec : $err"); row.setTextColor(Pal.MUTED) }
                                }
                            }
                            body.addView(row)
                        }
                    }
                }
            } catch (e: Exception) {
                a.runOnUiThread { info.text = "Erreur : " + e.message }
            }
        }
    }
}

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
        val known = JSONObject(lk.body).optInt("id", 0)
        if (known > 0) {   // déjà suivi dans Radarr : on lance simplement la recherche
            val c = Net.call("POST", "$base/api/v3/command", key, JSONObject().put("name", "MoviesSearch").put("movieIds", JSONArray().put(known)).toString(), 30000)
            return if (c.ok) null else "refusé (" + c.code + ") " + errMsg(c.body)
        }
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
        return if (r.ok) null else "refusé (" + r.code + ") " + errMsg(r.body)
    }
}

// Retrait d'un film suivi : arrête ses téléchargements puis le retire de Radarr
object MovieRemove {
    fun run(st: Settings, tmdb: Int, deleteFiles: Boolean): String? {
        val base = st.base(7878)
        val key = st.get("kRadarr")
        if (key.isEmpty()) return "clé Radarr manquante"
        val lk = Net.call("GET", "$base/api/v3/movie/lookup/tmdb?tmdbId=$tmdb", key, timeout = 30000)
        if (!lk.ok) return "introuvable (" + lk.code + ")"
        val id = JSONObject(lk.body).optInt("id", 0)
        if (id == 0) return "film non suivi"
        try {
            val q = Net.call("GET", "$base/api/v3/queue?pageSize=200", key, timeout = 15000)
            if (q.ok) {
                val t = q.body.trim()
                val arr = if (t.startsWith("[")) JSONArray(t) else (JSONObject(t).optJSONArray("records") ?: JSONArray())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (o.optInt("movieId") == id) Net.call("DELETE", base + "/api/v3/queue/" + o.optInt("id") + "?removeFromClient=true&blocklist=false", key, timeout = 15000)
                }
            }
        } catch (e: Exception) { }
        val r = Net.call("DELETE", "$base/api/v3/movie/$id?deleteFiles=$deleteFiles&addImportListExclusion=false", key, timeout = 30000)
        return if (r.ok) null else "refusé (" + r.code + ") " + errMsg(r.body)
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
        var nEx = 0; var nExcl = 0; var nId = 0
        val all = ArrayList<JSONObject>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            all.add(o)
            if (o.optBoolean("isExisting")) nEx++
            if (o.optBoolean("isExcluded")) nExcl++
            if (o.optInt("id", 0) > 0) nId++
        }
        var fresh = all.filter { !it.optBoolean("isExisting") && !it.optBoolean("isExcluded") && it.optInt("id", 0) == 0 }
        if (fresh.isEmpty()) fresh = all.filter { !it.optBoolean("isExisting") && !it.optBoolean("isExcluded") }
        if (fresh.isEmpty()) fresh = all.filter { !it.optBoolean("isExisting") }
        val ofList = if (id < 0) fresh else fresh.filter { o ->
            val ls = o.optJSONArray("lists")
            ls == null || (0 until ls.length()).any { ls.optInt(it) == id }
        }
        lastInfo = "Radarr a renvoyé " + a.length() + " suggestion(s) (" + nEx + " déjà dans ta bibliothèque, " + nExcl + " exclues, " + nId + " avec identifiant), " + fresh.size + " nouvelle(s), " + ofList.size + " dans cette liste. La synchronisation peut prendre quelques minutes ; sinon, utilise une clé TMDb (Réglages, Clés API)."
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
    // Fiche Sonarr (lookup) d'une série ; id > 0 si elle est déjà suivie
    fun lookup(st: Settings, tvdb: Int): JSONObject {
        val key = st.get("kSonarr")
        if (key.isEmpty()) throw Exception("clé Sonarr manquante")
        val lk = Net.call("GET", st.base(8989) + "/api/v3/series/lookup?term=tvdb:$tvdb", key, timeout = 30000)
        if (!lk.ok) throw Exception("introuvable (" + lk.code + ")")
        return JSONArray(lk.body).optJSONObject(0) ?: throw Exception("introuvable dans Sonarr")
    }

    fun seasonNumbers(o: JSONObject): List<Int> {
        val a = o.optJSONArray("seasons") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optInt("seasonNumber") }.filter { it > 0 }.sorted()
    }

    // Série déjà suivie : on surveille les saisons choisies et on lance leur recherche
    private fun addSeasons(st: Settings, id: Int, seasons: List<Int>): String? {
        val base = st.base(8989)
        val key = st.get("kSonarr")
        val g = Net.call("GET", "$base/api/v3/series/$id", key, timeout = 20000)
        if (!g.ok) return "lecture refusée (" + g.code + ")"
        val s = JSONObject(g.body)
        val arr = s.optJSONArray("seasons") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val x = arr.optJSONObject(i) ?: continue
            if (seasons.contains(x.optInt("seasonNumber"))) x.put("monitored", true)
        }
        s.put("monitored", true)
        val p = Net.call("PUT", "$base/api/v3/series/$id", key, s.toString(), 30000)
        if (!p.ok) return "refusé (" + p.code + ") " + errMsg(p.body)
        for (n in seasons) Net.call("POST", "$base/api/v3/command", key, JSONObject().put("name", "SeasonSearch").put("seriesId", id).put("seasonNumber", n).toString(), 20000)
        return null
    }

    // Ne plus suivre : retire les téléchargements en cours de la série, puis la série de Sonarr
    fun remove(st: Settings, tvdb: Int, deleteFiles: Boolean): String? {
        val base = st.base(8989)
        val key = st.get("kSonarr")
        val id = lookup(st, tvdb).optInt("id", 0)
        if (id == 0) return "série non suivie"
        try {
            val q = Net.call("GET", "$base/api/v3/queue?pageSize=200", key, timeout = 15000)
            if (q.ok) {
                val t = q.body.trim()
                val arr = if (t.startsWith("[")) JSONArray(t) else (JSONObject(t).optJSONArray("records") ?: JSONArray())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (o.optInt("seriesId") == id) Net.call("DELETE", base + "/api/v3/queue/" + o.optInt("id") + "?removeFromClient=true&blocklist=false", key, timeout = 15000)
                }
            }
        } catch (e: Exception) { }
        val r = Net.call("DELETE", "$base/api/v3/series/$id?deleteFiles=$deleteFiles&addImportListExclusion=false", key, timeout = 30000)
        return if (r.ok) null else "refusé (" + r.code + ") " + errMsg(r.body)
    }

    // seasons : saisons à télécharger (null = la première)
    fun add(st: Settings, tvdb: Int, seasons: List<Int>? = null): String? {
        val base = st.base(8989)
        val key = st.get("kSonarr")
        if (key.isEmpty()) return "clé Sonarr manquante"
        val lk = Net.call("GET", "$base/api/v3/series/lookup?term=tvdb:$tvdb", key, timeout = 30000)
        if (!lk.ok) return "introuvable (" + lk.code + ")"
        val first = JSONArray(lk.body).optJSONObject(0) ?: return "introuvable dans Sonarr"
        if (seasons != null && first.optInt("id", 0) > 0) return addSeasons(st, first.optInt("id"), seasons)
        val pref = st.get("pSonarr").trim().toIntOrNull()
        val prof = pref ?: JSONArray(Net.call("GET", "$base/api/v3/qualityprofile", key).body).optJSONObject(0)?.optInt("id") ?: return "aucun profil de qualité"
        val body = JSONObject(first.toString())
        body.remove("id")
        body.put("qualityProfileId", prof).put("rootFolderPath", "/data/media/series").put("seasonFolder", true).put("monitored", true)
        if (seasons != null) {
            val arr = body.optJSONArray("seasons") ?: JSONArray()
            for (i in 0 until arr.length()) { val x = arr.optJSONObject(i) ?: continue; x.put("monitored", seasons.contains(x.optInt("seasonNumber"))) }
            body.put("addOptions", JSONObject().put("monitor", "unknown").put("searchForMissingEpisodes", true))
        } else body.put("addOptions", JSONObject().put("monitor", "firstSeason").put("searchForMissingEpisodes", true))
        try {
            val lp = Net.call("GET", "$base/api/v3/languageprofile", key)
            if (lp.ok) { val la = JSONArray(lp.body); if (la.length() > 0) body.put("languageProfileId", la.getJSONObject(0).getInt("id")) }
        } catch (e: Exception) { }
        val r = Net.call("POST", "$base/api/v3/series", key, body.toString(), 30000)
        return if (r.ok) null else "refusé (" + r.code + ") " + errMsg(r.body)
    }
}

// Suggestions via TMDb (clé gratuite, facultative) : populaires, au cinéma, à l'antenne…
fun errMsg(b: String): String = try {
    val t = b.trim()
    if (t.startsWith("[")) JSONArray(t).optJSONObject(0)?.str("errorMessage") ?: t.take(100)
    else JSONObject(t).let { it.str("message").ifEmpty { it.str("errorMessage") } }.ifEmpty { t.take(100) }
} catch (e: Exception) { b.take(100) }

object Tmdb {
    val MOVIE = listOf("Populaires" to "popular", "Au cinéma" to "now_playing", "À venir" to "upcoming", "Mieux notés" to "top_rated")
    val TV = listOf("Populaires" to "popular", "À l'antenne" to "on_the_air", "Aujourd'hui" to "airing_today", "Mieux notées" to "top_rated")
    fun has(st: Settings) = st.get("kTmdb").trim().isNotEmpty()

    private val GM = listOf("Action" to 28, "Science-fiction" to 878, "Comédie" to 35, "Horreur" to 27, "Thriller" to 53, "Animation" to 16,
        "Drame" to 18, "Aventure" to 12, "Fantastique" to 14, "Policier" to 80, "Romance" to 10749, "Famille" to 10751)
    private val GT = listOf("Action & aventure" to 10759, "Science-fiction" to 10765, "Comédie" to 35, "Drame" to 18, "Crime" to 80,
        "Animation" to 16, "Mystère" to 9648, "Documentaire" to 99)
    fun kinds(tv: Boolean): List<String> = (if (tv) TV else MOVIE).map { it.first } + listOf("Pour toi") + (if (tv) GT else GM).map { it.first }

    private fun api(st: Settings, path: String): JSONArray {
        val sep = if (path.contains("?")) "&" else "?"
        val r = Net.call("GET", "https://api.themoviedb.org/3$path${sep}api_key=" + st.get("kTmdb").trim() + "&language=fr-FR&region=FR", timeout = 15000)
        if (r.code == 401) throw Exception("clé TMDb refusée")
        if (!r.ok) throw Exception("TMDb : HTTP " + r.code)
        return JSONObject(r.body).optJSONArray("results") ?: JSONArray()
    }

    // Titres vus dans Jellyfin (récents d'abord) avec leur identifiant TMDb
    private fun watched(st: Settings, tv: Boolean): List<Pair<Int, String>> {
        val uid = Follow.userId(st)
        val r = Jellyfin.get(st, "/Users/$uid/Items?IncludeItemTypes=" + (if (tv) "Series" else "Movie") + "&Recursive=true" + (if (tv) "" else "&IsPlayed=true") +
            "&SortBy=DatePlayed&SortOrder=Descending&Limit=" + (if (tv) 30 else 12) + "&Fields=ProviderIds", 30000)
        if (!r.ok) throw Exception("Jellyfin : HTTP " + r.code)
        val a = JSONObject(r.body).optJSONArray("Items") ?: JSONArray()
        val out = ArrayList<Pair<Int, String>>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (tv && o.optJSONObject("UserData")?.str("LastPlayedDate").isNullOrEmpty()) continue
            val id = o.optJSONObject("ProviderIds")?.str("Tmdb")?.toIntOrNull() ?: continue
            out.add(id to o.str("Name"))
            if (out.size >= 8) break
        }
        return out
    }

    fun list(st: Settings, tv: Boolean, label: String): List<JSONObject> {
        val kind = if (tv) "tv" else "movie"
        val have = HashSet<Int>()      // déjà téléchargés : masqués
        val tracked = HashSet<Int>()   // suivis mais sans fichier : proposés
        val k2 = st.get(if (tv) "kSonarr" else "kRadarr")
        if (k2.isNotEmpty()) {
            val r = Net.call("GET", st.base(if (tv) 8989 else 7878) + "/api/v3/" + (if (tv) "series" else "movie"), k2, timeout = 20000)
            if (r.ok) {
                val a = JSONArray(r.body)
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    val id = o.optInt("tmdbId")
                    val files = if (tv) (o.optJSONObject("statistics")?.optInt("episodeFileCount", 0) ?: 0) > 0 else o.optBoolean("hasFile")
                    if (!files) tracked.add(id) else have.add(id)
                }
            }
        }
        val raw = ArrayList<JSONObject>()
        val why = HashMap<Int, String>()
        val genre = (if (tv) GT else GM).firstOrNull { it.first == label }?.second
        if (label == "Pour toi") {
            val seeds = watched(st, tv)
            if (seeds.isEmpty()) throw Exception("aucun titre vu trouvé dans Jellyfin (clé et utilisateur Jellyfin ?)")
            val score = HashMap<Int, Double>()
            val byId = HashMap<Int, JSONObject>()
            val seedIds = seeds.map { it.first }.toSet()
            for ((sid, name) in seeds) {
                val a = try { api(st, "/$kind/$sid/recommendations") } catch (e: Exception) { if (e.message?.contains("refusée") == true) throw e else continue }
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    val id = o.optInt("id")
                    if (seedIds.contains(id)) continue
                    score[id] = (score[id] ?: 0.0) + 1.0 + o.optDouble("vote_average", 0.0) / 20.0
                    if (!byId.containsKey(id)) { byId[id] = o; why[id] = name }
                }
            }
            for (id in score.keys.sortedByDescending { score[it] }) raw.add(byId[id]!!)
        } else {
            val ep = (if (tv) TV else MOVIE).firstOrNull { it.first == label }?.second ?: "popular"
            for (page in 1..4) {
                val path = if (genre != null) "/discover/$kind?with_genres=$genre&sort_by=popularity.desc&include_adult=false&vote_count.gte=" + (if (tv) 50 else 150) + "&page=$page"
                    else "/$kind/$ep?page=$page"
                val a = api(st, path)
                for (i in 0 until a.length()) a.optJSONObject(i)?.let { raw.add(it) }
            }
        }
        val out = ArrayList<JSONObject>()
        var hidden = 0
        for (o in raw) {
            val id = o.optInt("id")
            if (have.contains(id)) { hidden++; continue }
            val date = o.str(if (tv) "first_air_date" else "release_date")
            val p = o.str("poster_path")
            val note = if (o.optDouble("vote_average", 0.0) > 0) String.format(java.util.Locale.FRANCE, "Note %.1f / 10", o.optDouble("vote_average")) else ""
            out.add(JSONObject().put("title", o.str(if (tv) "name" else "title")).put("year", date.take(4).toIntOrNull() ?: 0)
                .put("overview", o.str("overview")).put("remotePoster", if (p.isEmpty()) "" else "https://image.tmdb.org/t/p/w185$p")
                .put("tmdbId", id).put("tv", tv)
                .put("rating", o.optDouble("vote_average", 0.0)).put("votes", o.optInt("vote_count", 0)).put("pop", o.optDouble("popularity", 0.0)).put("date", date)
                .put("track", tracked.contains(id)).put("why", why[id] ?: ""))
        }
        lastHidden = hidden
        return out.take(60)
    }
    var lastHidden = 0

    // Série : TMDb → identifiant TVDB → ajout dans Sonarr
    fun tvdbOf(st: Settings, tmdb: Int): Int {
        val r = Net.call("GET", "https://api.themoviedb.org/3/tv/$tmdb/external_ids?api_key=" + st.get("kTmdb").trim(), timeout = 15000)
        if (!r.ok) throw Exception("TMDb : HTTP " + r.code)
        return JSONObject(r.body).optInt("tvdb_id", 0)
    }

    fun addTv(st: Settings, tmdb: Int, seasons: List<Int>? = null): String? {
        val tvdb = try { tvdbOf(st, tmdb) } catch (e: Exception) { return e.message }
        if (tvdb == 0) return "pas d'identifiant TVDB pour cette série"
        return Shows.add(st, tvdb, seasons)
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
        val sortScroll = android.widget.HorizontalScrollView(a)
        sortScroll.isHorizontalScrollBarEnabled = false
        val sortRow = LinearLayout(a); sortRow.orientation = LinearLayout.HORIZONTAL
        sortScroll.addView(sortRow)
        sortScroll.setPadding(0, a.dp(8), 0, 0)
        sortScroll.visibility = if (Tmdb.has(st)) android.view.View.VISIBLE else android.view.View.GONE
        body.addView(modes); body.addView(hs); body.addView(sortScroll); body.addView(info); body.addView(list)
        var sortKey = ""
        var lastL: List<JSONObject> = emptyList()
        dialog(a, "Découvrir", body)
        var mode = "films"
        var cur = Discover.KINDS[0].first
        fun kinds(): List<String> = if (Tmdb.has(st)) Tmdb.kinds(mode != "films") else if (mode == "films") Discover.KINDS.map { it.first } else Shows.KINDS

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

        fun sorted(l: List<JSONObject>): List<JSONObject> = when (sortKey) {
            "rating" -> l.sortedByDescending { it.optDouble("rating", 0.0) * (if (it.optInt("votes") >= 50) 1.0 else 0.0) + it.optDouble("rating", 0.0) / 100 }
            "votes" -> l.sortedByDescending { it.optInt("votes") }
            "pop" -> l.sortedByDescending { it.optDouble("pop") }
            "date" -> l.sortedByDescending { it.str("date") }
            else -> l
        }
        fun show(l0: List<JSONObject>) {
            lastL = l0
            val l = sorted(l0)
            list.removeAllViews()
            info.text = if (l.isEmpty()) "Rien pour l'instant. " + (if (mode == "films" && !Tmdb.has(st)) Discover.lastInfo else "Réessaie dans une minute.") else "Touche un titre pour l'ajouter et lancer son téléchargement (déjà ajouté : relancer ou ne plus suivre). Appui long : résumé complet." + (if (Tmdb.has(st)) (if (Tmdb.lastHidden > 0) "\n" + Tmdb.lastHidden + " déjà dans ta bibliothèque, masqué(s)." else "") else if (mode == "films") "\n\n" + Discover.lastInfo else "")
            for (o in l) {
                val isShow = o.has("tvdbId")
                val row = LinearLayout(a); row.orientation = LinearLayout.HORIZONTAL
                row.setPadding(a.dp(12), a.dp(12), a.dp(12), a.dp(12))
                row.background = a.rounded(Pal.CARD, 14, Pal.BORDER)
                val rlp = LinearLayout.LayoutParams(MATCH, WRAP); rlp.topMargin = a.dp(10)
                row.layoutParams = rlp
                val img = ImageView(a)
                img.scaleType = ImageView.ScaleType.CENTER_CROP
                img.setBackgroundColor(Pal.CARD2)
                a.roundView(img, 8)
                row.addView(img, LinearLayout.LayoutParams(a.dp(88), a.dp(132)))
                Img.load(posterUrl(o), img)
                val t = LinearLayout(a); t.orientation = LinearLayout.VERTICAL
                t.setPadding(a.dp(14), 0, 0, 0)
                val ttl = a.tv(o.str("title"), 17f, Pal.FG)
                ttl.typeface = android.graphics.Typeface.DEFAULT_BOLD
                ttl.maxLines = 2; ttl.ellipsize = android.text.TextUtils.TruncateAt.END
                t.addView(ttl)
                val meta = FlowRow(a, a.dp(6), a.dp(6)); meta.setPadding(0, a.dp(6), 0, a.dp(6))
                if (o.optInt("year") > 0) meta.addView(a.badge(o.optInt("year").toString(), Pal.MUTED))
                if (o.optDouble("rating", 0.0) > 0) meta.addView(a.badge(String.format(java.util.Locale.FRANCE, "★ %.1f", o.optDouble("rating")), Pal.AMBER))
                if (o.optInt("votes") > 0) meta.addView(a.badge(o.optInt("votes").toString() + " votes", Pal.MUTED))
                if (o.optBoolean("track")) meta.addView(a.badge("Suivi, pas téléchargé", Pal.BLUE))
                t.addView(meta)
                if (o.str("sub").isNotEmpty()) t.addView(a.tv(o.str("sub"), 12f, Pal.BLUE))
                if (o.str("why").isNotEmpty()) t.addView(a.tv("Comme « " + o.str("why") + " »", 12f, Pal.BLUE))
                val ov = o.str("overview").ifEmpty { "Pas de résumé." }
                val state = a.tv(ov, 13f, Pal.MUTED)
                state.maxLines = 4; state.ellipsize = android.text.TextUtils.TruncateAt.END
                state.setLineSpacing(a.dp(2).toFloat(), 1f)
                t.addView(state)
                row.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
                row.setOnLongClickListener { state.maxLines = if (state.maxLines == 4) 40 else 4; true }
                row.setOnClickListener {
                    state.maxLines = 4
                    val isTv = o.optBoolean("tv") || isShow
                    fun finish(err: String?) = a.runOnUiThread {
                        state.text = if (err == null) "Ajouté, recherche lancée. Touche à nouveau pour " + (if (isTv) "ajuster ou ne plus suivre." else "relancer ou ne plus suivre.") else if (err.isEmpty()) if (isTv) "Série retirée de Sonarr." else "Film retiré de Radarr." else if (err.startsWith("Échec")) err else "Échec : $err"
                        state.setTextColor(if (err == null || err.isEmpty()) Pal.GREEN else Pal.RED)
                        
                    }
                    if (isTv) {
                        pickSeasons(a, st, o) { err -> state.post { state.text = "Mise à jour…" }; finish(err) }
                    } else {
                        val tmdb = o.optInt("tmdbId")
                        fun addIt() {
                            state.text = "Ajout…"
                            thread {
                                val err = try { Sagas.add(st, tmdb) } catch (e: Exception) { e.message ?: "erreur" }
                                if (err == null) o.put("track", true)
                                finish(err)
                            }
                        }
                        fun removeIt(files: Boolean) {
                            state.text = "Retrait…"
                            thread {
                                val err = try { MovieRemove.run(st, tmdb, files) } catch (e: Exception) { e.message ?: "erreur" }
                                if (err == null) o.put("track", false)
                                finish(err ?: "")
                            }
                        }
                        if (o.optBoolean("track")) {
                            a.dialogBuilder().setTitle(o.str("title")).setMessage("Ce film est déjà suivi dans Radarr.")
                                .setPositiveButton("Relancer la recherche") { _, _ -> addIt() }
                                .setNeutralButton("Ne plus suivre") { _, _ ->
                                    a.dialogBuilder().setTitle("Ne plus suivre ?").setMessage("Les téléchargements en cours sont arrêtés. Que faire du fichier déjà téléchargé ?")
                                        .setPositiveButton("Garder le fichier") { _, _ -> removeIt(false) }
                                        .setNeutralButton("Supprimer le fichier") { _, _ -> removeIt(true) }
                                        .setNegativeButton("Annuler", null).show()
                                }
                                .setNegativeButton("Annuler", null).show()
                        } else addIt()
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

        lateinit var showRef: (List<JSONObject>) -> Unit
        fun sortRender() {
            sortRow.removeAllViews()
            val add2 = LinearLayout.LayoutParams(WRAP, WRAP)
            sortRow.addView(a.tv("Trier :", 13f, Pal.MUTED).also { it.setPadding(0, a.dp(8), a.dp(8), 0) })
            for ((lbl, k) in listOf("Pertinence" to "", "Note" to "rating", "Votes" to "votes", "Popularité" to "pop", "Récent" to "date"))
                add(sortRow, chip(lbl, sortKey == k) { sortKey = k; sortRender(); showRef(lastL) })
        }
        showRef = { l -> show(l) }
        fun chipsRender() {
            chips.removeAllViews()
            for (k in kinds()) add(chips, chip(k, k == cur) { cur = k; chipsRender(); load(k) })
        }

        fun start() {
            chipsRender()
            if (Tmdb.has(st)) sortRender()
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
        a.bigDialog(title, body)
    }

    // Choix des saisons à télécharger avant d'ajouter une série
    private fun pickSeasons(a: Activity, st: Settings, o: JSONObject, done: (String?) -> Unit) {
        val body = LinearLayout(a); body.orientation = LinearLayout.VERTICAL
        body.setPadding(a.dp(20), a.dp(8), a.dp(20), a.dp(8))
        val info = a.tv("Chargement des saisons…", 14f, Pal.MUTED)
        body.addView(info)
        val chips = FlowRow(a, a.dp(8), a.dp(8))
        chips.setPadding(0, a.dp(10), 0, a.dp(10))
        body.addView(chips)
        val go = a.btn("Ajouter", Pal.BLUE, 48) {}
        go.visibility = android.view.View.GONE
        body.addView(go)
        val d = a.dialogBuilder().setTitle(o.str("title")).setView(body).setNegativeButton("Annuler", null).show()
        thread {
            try {
                val tvdb = if (o.has("tvdbId")) o.optInt("tvdbId") else Tmdb.tvdbOf(st, o.optInt("tmdbId"))
                if (tvdb == 0) throw Exception("pas d'identifiant TVDB pour cette série")
                val lk = Shows.lookup(st, tvdb)
                val nums = Shows.seasonNumbers(lk)
                val tracked = lk.optInt("id", 0) > 0
                a.runOnUiThread {
                    if (tracked) {
                        val rm = a.btn2("Ne plus suivre cette série", Pal.RED, 48) {
                            a.dialogBuilder().setTitle("Ne plus suivre ?").setMessage("Les téléchargements en cours sont arrêtés. Que faire des fichiers déjà téléchargés ?")
                                .setPositiveButton("Garder les fichiers") { _, _ -> d.dismiss(); thread { done(try { Shows.remove(st, tvdb, false)?.let { "Échec : $it" } ?: "" } catch (e: Exception) { "Échec : " + e.message }) } }
                                .setNeutralButton("Supprimer les fichiers") { _, _ -> d.dismiss(); thread { done(try { Shows.remove(st, tvdb, true)?.let { "Échec : $it" } ?: "" } catch (e: Exception) { "Échec : " + e.message }) } }
                                .setNegativeButton("Annuler", null).show()
                        }
                        body.addView(rm)
                    }
                    if (nums.isEmpty()) { info.text = "Aucune saison connue pour l'instant."; return@runOnUiThread }
                    val sel = linkedSetOf(nums.first())
                    info.text = nums.size.toString() + " saison(s)" + (if (tracked) ", série déjà suivie." else ".") + " Choisis celles à télécharger."
                    fun chip(label: String, on: Boolean, click: () -> Unit): android.widget.TextView {
                        val t = a.tv(label, 14f, if (on) android.graphics.Color.WHITE else Pal.MUTED)
                        t.setPadding(a.dp(14), a.dp(8), a.dp(14), a.dp(8))
                        t.background = a.rounded(if (on) Pal.BLUE else Pal.CARD, 20, if (on) Pal.BLUE else Pal.BORDER)
                        t.setOnClickListener { click() }
                        return t
                    }
                    fun render() {
                        chips.removeAllViews()
                        chips.addView(chip("Toutes", sel.size == nums.size) { sel.clear(); sel.addAll(nums); render() })
                        chips.addView(chip("Dernière", sel.size == 1 && sel.first() == nums.last()) { sel.clear(); sel.add(nums.last()); render() })
                        for (n in nums) chips.addView(chip("S$n", sel.contains(n)) { if (!sel.remove(n)) sel.add(n); if (sel.isEmpty()) sel.add(n); render() })
                        go.text = "Ajouter " + (if (sel.size == nums.size) "toutes les saisons" else if (sel.size == 1) "la saison " + sel.first() else sel.size.toString() + " saisons")
                    }
                    render()
                    go.visibility = android.view.View.VISIBLE
                    go.setOnClickListener {
                        d.dismiss()
                        val chosen = sel.toList()
                        thread { done(try { Shows.add(st, tvdb, chosen) } catch (e: Exception) { e.message ?: "erreur" }) }
                    }
                }
            } catch (e: Exception) {
                a.runOnUiThread { info.text = "Erreur : " + e.message }
            }
        }
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
                        val left = ((x.runTicks - x.posTicks) / 600000000L).toInt()
                        val pct = if (x.runTicks > 0) (x.posTicks * 100 / x.runTicks).toInt() else 0
                        val (row, tcol) = a.mediaCard(st.base(8096) + "/Items/" + x.id + "/Images/Primary?maxHeight=300&quality=80", 112, 64, x.title, emptyList())
                        tcol.addView(a.progress(pct))
                        tcol.addView(a.tv("$pct % vu  ·  reste " + (if (left >= 60) (left / 60).toString() + " h " + (left % 60) + " min" else "$left min"), 13f, Pal.MUTED))
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
                            row.setPadding(a.dp(14), a.dp(14), a.dp(14), a.dp(14))
                            row.background = a.rounded(Pal.CARD, 14, Pal.BORDER)
                            row.layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).also { it.topMargin = a.dp(8) }
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

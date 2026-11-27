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
    fun list(st: Settings): List<JSONObject> {
        val kr = st.get("kRadarr")
        if (kr.isEmpty()) throw Exception("clé Radarr manquante (Réglages)")
        val r = Net.call("GET", st.base(7878) + "/api/v3/importlist/movie", kr, timeout = 40000)
        if (!r.ok) throw Exception("Radarr : HTTP " + r.code)
        val a = JSONArray(r.body)
        val out = ArrayList<JSONObject>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.optBoolean("isExisting") || o.optBoolean("isExcluded") || o.optInt("id", 0) > 0) continue
            out.add(o)
            if (out.size >= 40) break
        }
        return out
    }
}

object LibraryUi5 {
    fun discover(a: Activity, st: Settings) {
        val body = LinearLayout(a); body.orientation = LinearLayout.VERTICAL
        val info = a.tv("Chargement…", 14f, Pal.MUTED)
        body.addView(info)
        dialog(a, "Découvrir", body)
        thread {
            try {
                val l = Discover.list(st)
                a.runOnUiThread {
                    info.text = if (l.isEmpty()) "Aucune suggestion. Dans Radarr, ajoute une liste d'import (Réglages, Listes, par exemple « TMDb Popular »)." else "Touche un film pour l'ajouter et lancer son téléchargement."
                    for (o in l) {
                        val row = LinearLayout(a); row.orientation = LinearLayout.HORIZONTAL
                        row.setPadding(0, a.dp(8), 0, a.dp(8))
                        val img = ImageView(a)
                        img.scaleType = ImageView.ScaleType.CENTER_CROP
                        img.setBackgroundColor(Pal.CARD2)
                        row.addView(img, LinearLayout.LayoutParams(a.dp(64), a.dp(96)))
                        Img.load(posterUrl(o), img)
                        val t = LinearLayout(a); t.orientation = LinearLayout.VERTICAL
                        t.setPadding(a.dp(12), 0, 0, 0)
                        val title = o.str("title") + (if (o.optInt("year") > 0) " (" + o.optInt("year") + ")" else "")
                        t.addView(a.tv(title, 15f, Pal.FG))
                        val state = a.tv(o.str("overview").take(100) + "…", 12f, Pal.MUTED)
                        t.addView(state)
                        row.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
                        row.setOnClickListener {
                            row.setOnClickListener(null)
                            state.text = "Ajout…"
                            thread {
                                val err = try { Sagas.add(st, o.optInt("tmdbId")) } catch (e: Exception) { e.message ?: "erreur" }
                                a.runOnUiThread { state.text = if (err == null) "Ajouté, recherche lancée." else "Échec : $err"; state.setTextColor(if (err == null) Pal.GREEN else Pal.RED) }
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

package com.example.wolremote

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.speech.RecognizerIntent
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.concurrent.thread

class SearchTab(private val a: Activity, private val st: Settings) {
    val view = LinearLayout(a)
    private val dots = listOf(a.tv("● Prowlarr", 11f, Pal.MUTED), a.tv("● Radarr", 11f, Pal.MUTED), a.tv("● Sonarr", 11f, Pal.MUTED))
    private val status = a.tv("", 13f, Pal.MUTED)
    private val query: EditText = a.field("Film, série, animé…", "")
    private val chips = LinearLayout(a)
    private val results = LinearLayout(a)
    private var all: List<Rel> = emptyList()
    private var filter = ""
    private var kind = st.get("sKind")        // "" = tout, "movie", "tv"
    private var lastQuery = ""
    private val dups = HashMap<String, Int>() // guid -> nombre d'indexeurs proposant la même release
    // Bibliothèque (Radarr / Sonarr) pour repérer ce qu'on a déjà ; sélection multiple
    private var libMovies: List<Pair<String, Int>> = emptyList()   // titre normalisé, année
    private var libHas: Set<String> = emptySet()                    // titre normalisé + année des films déjà téléchargés
    private var libSeries: List<String> = emptyList()
    private var libAt = 0L
    private var libBusy = false
    private val selected = LinkedHashSet<String>()
    private val bar = LinearLayout(a)
    private val barText = a.tv("", 15f, Pal.FG)
    private var fQ = st.get("sQ")      // qualité : "", 4K, 1080p, 720p, SD
    private var fL = st.get("sL")      // langue : "", vf (VF + MULTI), vostfr
    private var fS = st.get("sS").toIntOrNull() ?: 0   // taille max en Go (0 = sans limite)
    private var fSort = st.get("sSort").ifEmpty { "seeders" }

    init {
        view.orientation = LinearLayout.VERTICAL

        val header = LinearLayout(a)
        header.orientation = LinearLayout.VERTICAL
        header.setPadding(a.dp(16), a.dp(16), a.dp(16), a.dp(4))

        val top = LinearLayout(a)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.addView(a.title("Recherche"), LinearLayout.LayoutParams(0, WRAP, 1f))
        for (d in dots) {
            d.setPadding(a.dp(8), 0, 0, 0)
            top.addView(d)
        }
        header.addView(top)

        val sr = LinearLayout(a)
        sr.orientation = LinearLayout.HORIZONTAL
        sr.gravity = Gravity.CENTER_VERTICAL
        query.imeOptions = EditorInfo.IME_ACTION_SEARCH
        query.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEARCH) { search(); true } else false
        }
        val qlp = LinearLayout.LayoutParams(0, WRAP, 1f)
        qlp.topMargin = 0
        sr.addView(query, qlp)
        val sb = a.btn("Chercher", Pal.BLUE, 48) { search() }
        val slp = LinearLayout.LayoutParams(WRAP, a.dp(48))
        slp.leftMargin = a.dp(8)
        sb.layoutParams = slp
        sr.addView(sb)
        header.addView(sr)

        // Type de recherche : cible les catégories de Prowlarr pour réduire le bruit
        val kindRow = LinearLayout(a)
        kindRow.orientation = LinearLayout.HORIZONTAL
        kindRow.setPadding(0, a.dp(10), 0, 0)
        fun kindChips() {
            kindRow.removeAllViews()
            for ((lbl, v) in listOf("Tout" to "", "Films" to "movie", "Séries" to "tv")) {
                val sel = kind == v
                val t = a.tv(lbl, 14f, if (sel) Color.WHITE else Pal.MUTED)
                t.setPadding(a.dp(14), a.dp(8), a.dp(14), a.dp(8))
                t.background = a.rounded(if (sel) Pal.BLUE else Pal.CARD, 20, if (sel) Pal.BLUE else Pal.BORDER)
                t.setOnClickListener { kind = v; st.put("sKind", v); kindChips() }
                val lp = LinearLayout.LayoutParams(WRAP, WRAP); lp.rightMargin = a.dp(8)
                kindRow.addView(t, lp)
            }
            val mic = a.tv("Dicter", 14f, Pal.FG)
            mic.setPadding(a.dp(14), a.dp(8), a.dp(14), a.dp(8))
            mic.background = a.rounded(Pal.CARD, 20, Pal.BORDER)
            mic.setOnClickListener { dictate() }
            kindRow.addView(mic)
        }
        kindChips()
        header.addView(kindRow)

        header.addView(a.btn2("Réveiller le PC et chercher", Pal.FG, 46) { wakeAndSearch() })

        val hs = HorizontalScrollView(a)
        hs.isHorizontalScrollBarEnabled = false
        chips.orientation = LinearLayout.HORIZONTAL
        chips.setPadding(0, a.dp(10), 0, 0)
        hs.addView(chips)
        header.addView(hs)

        status.setPadding(a.dp(2), a.dp(8), 0, a.dp(4))
        header.addView(status)
        view.addView(header)

        val sc = ScrollView(a)
        sc.isVerticalScrollBarEnabled = false
        results.orientation = LinearLayout.VERTICAL
        results.setPadding(a.dp(16), 0, a.dp(16), a.dp(16))
        showHistory()
        sc.addView(results)
        view.addView(sc, LinearLayout.LayoutParams(MATCH, 0, 1f))

        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(Pal.CARD)
        bar.setPadding(a.dp(16), a.dp(8), a.dp(16), a.dp(8))
        bar.visibility = View.GONE
        bar.addView(barText, LinearLayout.LayoutParams(0, WRAP, 1f))
        val cancel = a.btn2("Annuler", Pal.FG, 44) { selected.clear(); updateBar(); render() }
        cancel.layoutParams = LinearLayout.LayoutParams(WRAP, a.dp(44))
        bar.addView(cancel)
        val send = a.btn("Envoyer", Pal.BLUE, 44) { sendBatch() }
        val slp2 = LinearLayout.LayoutParams(WRAP, a.dp(44))
        slp2.leftMargin = a.dp(8)
        send.layoutParams = slp2
        bar.addView(send)
        view.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    // ---------- Historique, dictée ----------
    private fun history(): List<String> = st.get("sHist").split("\n").filter { it.isNotBlank() }

    private fun addHistory(q: String) {
        val l = listOf(q) + history().filter { !it.equals(q, true) }
        st.put("sHist", l.take(8).joinToString("\n"))
    }

    private fun showHistory() {
        results.removeAllViews()
        val h = history()
        results.addView(a.tv("Saisis un titre puis touche Chercher. Si le PC est éteint, utilise « Réveiller le PC et chercher ».", 14f, Pal.MUTED))
        if (h.isEmpty()) return
        results.addView(a.section("Recherches récentes"))
        for (q in h) {
            val row = a.rowItem(q, "") { query.setText(q); search() }
            results.addView(row)
        }
        val clear = a.tv("Effacer l'historique", 13f, Pal.MUTED)
        clear.setPadding(a.dp(2), a.dp(14), 0, a.dp(8))
        clear.setOnClickListener { st.put("sHist", ""); showHistory() }
        results.addView(clear)
    }

    private fun dictate() {
        try {
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
            i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Titre à chercher")
            @Suppress("DEPRECATION")
            a.startActivityForResult(i, 81)
        } catch (e: Exception) {
            setStatus("Reconnaissance vocale indisponible sur ce téléphone.")
        }
    }

    fun onResult(req: Int, res: Int, data: Intent?) {
        if (req != 81 || res != Activity.RESULT_OK) return
        val t = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!t.isNullOrEmpty()) { query.setText(t); search() }
    }

    private fun norm(s: String): String =
        java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).filter { it.isLetterOrDigit() && it.code < 128 }

    private fun ensureLib() {
        if (libBusy || System.currentTimeMillis() - libAt < 300000L) return
        libBusy = true
        thread {
            val mv = ArrayList<Pair<String, Int>>()
            val has = HashSet<String>()
            val sr = ArrayList<String>()
            try {
                val kr = st.get("kRadarr")
                if (kr.isNotEmpty()) {
                    val r = Net.call("GET", st.base(7878) + "/api/v3/movie", kr, timeout = 20000)
                    if (r.ok) {
                        val arr = JSONArray(r.body)
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            val n = norm(o.str("title"))
                            mv.add(Pair(n, o.optInt("year")))
                            if (o.optBoolean("hasFile")) has.add(n + o.optInt("year"))
                        }
                    }
                }
                val ks = st.get("kSonarr")
                if (ks.isNotEmpty()) {
                    val r = Net.call("GET", st.base(8989) + "/api/v3/series", ks, timeout = 20000)
                    if (r.ok) {
                        val arr = JSONArray(r.body)
                        for (i in 0 until arr.length()) sr.add(norm(arr.optJSONObject(i)?.str("title") ?: ""))
                    }
                }
                libMovies = mv; libHas = has; libSeries = sr
                libAt = System.currentTimeMillis()
            } catch (e: Exception) {
                libAt = System.currentTimeMillis() - 240000L
            }
            libBusy = false
            ui { render() }
        }
    }

    // "" = inconnu, "file" = film déjà là, "tracked" = film suivi sans fichier, "series" = série déjà suivie
    private fun libState(r: Rel): String {
        val rt = norm(r.title)
        for ((n, y) in libMovies) {
            if (n.length >= 3 && rt.startsWith(n + y)) return if (libHas.contains(n + y)) "file" else "tracked"
        }
        val tl = r.title.lowercase()
        if (Regex("s\\d{1,2}e\\d{1,2}|season|saison").containsMatchIn(tl)) {
            for (n in libSeries) if (n.length >= 4 && rt.startsWith(n)) return "series"
        }
        return ""
    }

    private fun updateBar() {
        bar.visibility = if (selected.isEmpty()) View.GONE else View.VISIBLE
        barText.text = selected.size.toString() + " sélectionné(s)"
    }

    private fun toggle(r: Rel, c: View) {
        if (!selected.add(r.guid)) selected.remove(r.guid)
        c.background = a.rounded(Pal.CARD, 16, if (selected.contains(r.guid)) Pal.BLUE else Pal.BORDER)
        updateBar()
    }

    // Envoi groupé : films vers Radarr, séries vers Sonarr, le reste vers qBittorrent via Prowlarr
    private fun sendBatch() {
        val items = all.filter { selected.contains(it.guid) }
        if (items.isEmpty()) return
        a.dialogBuilder().setMessage("Envoyer " + items.size + " résultat(s) ? Les films vont à Radarr, les séries à Sonarr, le reste à qBittorrent.")
            .setPositiveButton("Envoyer") { _, _ ->
                setStatus("Envoi de " + items.size + " résultat(s)…")
                thread {
                    var ok = 0
                    val fails = ArrayList<String>()
                    for (r in items) {
                        val movie = r.cats.any { it.first in 2000..2999 }
                        val tv = r.cats.any { it.first in 5000..5999 }
                        val msg: String? = try {
                            if (movie && st.get("kRadarr").isNotEmpty()) pushDirect(st.base(7878), st.get("kRadarr"), r, "Radarr")
                            else if (tv && st.get("kSonarr").isNotEmpty()) pushDirect(st.base(8989), st.get("kSonarr"), r, "Sonarr")
                            else {
                                val body = JSONObject().put("guid", r.guid).put("indexerId", r.indexerId).toString()
                                val res = Net.call("POST", st.base(9696) + "/api/v1/search", st.get("kProwlarr"), body, 30000)
                                if (res.ok) null else "Prowlarr " + res.code
                            }
                        } catch (e: Exception) { e.message ?: "erreur" }
                        if (msg == null) ok++ else fails.add(r.title.take(40) + " : " + msg)
                    }
                    ui {
                        selected.clear(); updateBar(); render()
                        setStatus("$ok envoyé(s)" + (if (fails.isEmpty()) "." else ", " + fails.size + " refusé(s) :\n" + fails.take(4).joinToString("\n")))
                    }
                }
            }.setNegativeButton("Annuler", null).show()
    }

    // null = accepté, sinon la raison du refus
    private fun pushDirect(base: String, key: String, r: Rel, who: String): String? {
        val push = JSONObject().put("title", r.title).put("protocol", "torrent").put("size", r.size).put("indexer", r.indexer)
        if (r.date.isNotEmpty()) push.put("publishDate", r.date)
        if (r.magnetUrl.isNotEmpty()) push.put("magnetUrl", r.magnetUrl) else push.put("downloadUrl", r.downloadUrl)
        val res = Net.call("POST", "$base/api/v3/release/push", key, push.toString(), 60000)
        if (!res.ok) return who + " HTTP " + res.code
        try {
            val o = JSONArray(res.body).optJSONObject(0)
            if (o != null && (o.optBoolean("rejected", false) || reasonsOf(o).isNotEmpty())) {
                val rs = reasonsOf(o)
                return who + " : " + (if (rs.isEmpty()) "refusé" else rs.first())
            }
        } catch (e: Exception) { }
        return null
    }

    private fun ui(f: () -> Unit) { a.runOnUiThread { f() } }

    private fun setStatus(s: String) { status.text = s }

    private fun setDots(v: List<Boolean?>) {
        val names = listOf("Prowlarr", "Radarr", "Sonarr")
        for (i in 0 until 3) {
            dots[i].text = "● " + names[i]
            val c = when (v[i]) { true -> Pal.GREEN; false -> Pal.RED; else -> Pal.MUTED }
            dots[i].setTextColor(c)
        }
    }

    // ---------- Réveil + attente des services ----------
    private fun pingAll(): List<Boolean> {
        if (!st.probe()) return listOf(false, false, false)
        return listOf(true, st.ping(7878), st.ping(8989))
    }

    private fun wakeAndSearch() {
        setStatus("Vérification des services…")
        setDots(listOf(null, null, null))
        thread {
            var s = pingAll()
            val first = s
            ui { setDots(first) }
            if (!s[0]) {
                try {
                    val body = JSONObject().put("pin", st.pin()).toString()
                    val r = Net.call("POST", st.tablet() + "/wake", json = body, timeout = 10000)
                    val m = try { JSONObject(r.body).str("message") } catch (e: Exception) { "" }
                    ui { setStatus(if (m.isNotEmpty()) m else "Réveil envoyé…") }
                } catch (e: Exception) {
                    ui { setStatus("Serveur de la tablette injoignable : " + e.message) }
                    return@thread
                }
                val end = System.currentTimeMillis() + 180000
                while (System.currentTimeMillis() < end) {
                    Thread.sleep(3000)
                    s = pingAll()
                    val cur = s
                    val left = ((end - System.currentTimeMillis()) / 1000).toInt()
                    ui { setDots(cur); setStatus("Attente du PC et des services… (" + left + " s max)") }
                    if (cur.all { it }) break
                }
            }
            if (!s[0]) {
                ui { setStatus("Le PC ne répond pas (délai de 3 min dépassé). Réessaie dans un moment.") }
                return@thread
            }
            if (!(s[1] && s[2])) {
                ui { setStatus("Prowlarr répond, mais Radarr ou Sonarr pas encore : la recherche marche, pas encore Film/Série.") }
                Thread.sleep(1500)
            }
            ui { search() }
        }
    }

    // ---------- Recherche Prowlarr ----------
    private fun search() {
        a.hideKeyboard()
        val q = query.text.toString().trim()
        if (q.isEmpty()) { setStatus("Saisis une recherche."); return }
        lastQuery = q
        addHistory(q)
        val key = st.get("kProwlarr")
        if (key.isEmpty()) { setStatus("Renseigne la clé API de Prowlarr dans Réglages."); return }
        setStatus("Recherche en cours…")
        thread {
            try {
                if (!st.probe()) {
                    ui { setStatus("PC injoignable. Utilise « Réveiller et chercher ».") }
                    return@thread
                }
                val r = Net.call("GET", st.base(9696) + "/api/v1/search?query=" + Net.enc(q) + "&type=search" + (if (kind == "movie") "&categories=2000" else if (kind == "tv") "&categories=5000" else ""), key, timeout = 120000)
                if (!r.ok) {
                    ui { setStatus("Erreur Prowlarr (" + r.code + ") : " + r.body.take(200)) }
                    return@thread
                }
                val arr = JSONArray(r.body)
                val list = ArrayList<Rel>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    list.add(Rel.from(o))
                }
                ui { all = list; filter = ""; render() }
            } catch (e: Exception) {
                ui { setStatus("Erreur de recherche : " + e.message) }
            }
        }
    }

    private fun videoOnly(): Boolean = st.get("videoOnly") != "0"

    private fun render() {
        ensureLib()
        chips.removeAllViews()
        results.removeAllViews()
        val vo = videoOnly()
        val strict = st.get("sStrict") != "0" && lastQuery.isNotEmpty()
        val toks = lastQuery.split(Regex("\\s+")).map { norm(it) }.filter { it.length >= 2 || it.all { c -> c.isDigit() } }.filter { it.isNotEmpty() }
        val vis0 = all.filter { !vo || it.isVideo() }
        val vis = if (strict && toks.isNotEmpty()) vis0.filter { r -> val n = norm(r.title); toks.all { n.contains(it) } } else vis0
        val offTopic = vis0.size - vis.size
        val hidden = all.size - vis0.size
        setStatus(vis.size.toString() + " résultat(s)" + (if (hidden > 0) " · $hidden masqué(s) (jeux, musique, logiciels…)" else "") +
            (if (offTopic > 0) " · $offTopic hors sujet" else ""))
        val nf = listOf(fQ.isNotEmpty(), fL.isNotEmpty(), fS != 0, fSort != "seeders").count { it }
        val fc = a.tv(if (nf > 0) "Filtres ($nf)" else "Filtres", 14f, Color.WHITE)
        fc.setPadding(a.dp(14), a.dp(8), a.dp(14), a.dp(8))
        fc.background = a.rounded(if (nf > 0) Pal.BLUE else Pal.GRAY, 20)
        fc.setOnClickListener { filtersDialog() }
        val flp = LinearLayout.LayoutParams(WRAP, WRAP)
        flp.rightMargin = a.dp(8)
        chips.addView(fc, flp)
        addToggleChip("Films / séries / animés seulement", vo)
        val sc2 = a.tv(if (strict) "✓ Titre exact" else "Titre exact", 14f, Color.WHITE)
        sc2.setPadding(a.dp(14), a.dp(8), a.dp(14), a.dp(8))
        sc2.background = a.rounded(if (strict) Pal.GREEN else Pal.GRAY, 20)
        sc2.setOnClickListener { st.put("sStrict", if (strict) "0" else "1"); render() }
        val slp3 = LinearLayout.LayoutParams(WRAP, WRAP)
        slp3.rightMargin = a.dp(8)
        chips.addView(sc2, slp3)
        val names = vis.map { it.indexer }.distinct().sorted()
        addChip("Tous (" + vis.size + ")", "")
        for (n in names) addChip(n + " (" + vis.count { it.indexer == n } + ")", n)
        val shown = vis.filter { filter.isEmpty() || it.indexer == filter }
            .filter { fQ.isEmpty() || it.quality() == fQ }
            .filter { fL.isEmpty() || (fL == "vf" && (it.lang() == "VF" || it.lang() == "MULTI")) || (fL == "vostfr" && it.lang() == "VOSTFR") }
            .filter { fS == 0 || it.size <= fS * 1000000000L }
            .let { l ->
                // une même release proposée par plusieurs indexeurs n'est affichée qu'une fois
                dups.clear()
                val g = l.groupBy { norm(it.title) }
                val best = g.values.map { grp -> val b = grp.maxByOrNull { it.seeders }!!; dups[b.guid] = grp.map { it.indexer }.distinct().size; b }
                best
            }
            .let { l -> when (fSort) { "size" -> l.sortedBy { it.size }; "date" -> l.sortedByDescending { it.date }; else -> l.sortedByDescending { it.seeders } } }
            .take(100)
        for (r in shown) results.addView(card(r))
        if (shown.isEmpty()) results.addView(a.tv("Aucun résultat.", 14f, Pal.MUTED))
        else results.addView(a.tv("Appui long sur un résultat pour en sélectionner plusieurs.", 13f, Pal.MUTED), 0)
    }

    // Fenêtre de filtres : chaque choix s'applique tout de suite à la liste
    private fun filtersDialog() {
        val body = LinearLayout(a)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(a.dp(16), a.dp(8), a.dp(16), a.dp(8))
        fun build() {
            body.removeAllViews()
            fun group(title: String, opts: List<Pair<String, String>>, cur: String, set: (String) -> Unit) {
                body.addView(a.section(title))
                val hs = HorizontalScrollView(a)
                hs.isHorizontalScrollBarEnabled = false
                val row = LinearLayout(a)
                row.orientation = LinearLayout.HORIZONTAL
                for ((lbl, v) in opts) {
                    val sel = cur == v
                    val t = a.tv(lbl, 14f, if (sel) Color.WHITE else Pal.MUTED)
                    t.setPadding(a.dp(14), a.dp(8), a.dp(14), a.dp(8))
                    t.background = a.rounded(if (sel) Pal.BLUE else Pal.CARD, 20, if (sel) Pal.BLUE else Pal.BORDER)
                    t.setOnClickListener { set(v); render(); build() }
                    val lp = LinearLayout.LayoutParams(WRAP, WRAP)
                    lp.rightMargin = a.dp(8)
                    row.addView(t, lp)
                }
                hs.addView(row)
                body.addView(hs)
            }
            group("Qualité", listOf("Toutes" to "", "4K" to "4K", "1080p" to "1080p", "720p" to "720p", "SD" to "SD"), fQ) { fQ = it; st.put("sQ", it) }
            group("Langue", listOf("Toutes" to "", "VF / Multi" to "vf", "VOSTFR" to "vostfr"), fL) { fL = it; st.put("sL", it) }
            group("Taille maximale", listOf("Sans limite" to "0", "5 Go" to "5", "15 Go" to "15", "40 Go" to "40"), fS.toString()) { fS = it.toInt(); st.put("sS", it) }
            group("Trier par", listOf("Seeders" to "seeders", "Plus petit" to "size", "Plus récent" to "date"), fSort) { fSort = it; st.put("sSort", it) }
        }
        build()
        val sv = ScrollView(a)
        sv.addView(body)
        a.dialogBuilder().setTitle("Filtres").setView(sv)
            .setNeutralButton("Réinitialiser") { _, _ ->
                fQ = ""; fL = ""; fS = 0; fSort = "seeders"
                st.put("sQ", ""); st.put("sL", ""); st.put("sS", "0"); st.put("sSort", "seeders")
                render()
            }
            .setPositiveButton("Fermer", null).show()
    }

    private fun addToggleChip(label: String, on: Boolean) {
        val t = a.tv(if (on) "✓ $label" else label, 14f, Color.WHITE)
        t.setPadding(a.dp(14), a.dp(8), a.dp(14), a.dp(8))
        t.background = a.rounded(if (on) Pal.GREEN else Pal.GRAY, 20)
        t.setOnClickListener {
            st.put("videoOnly", if (on) "0" else "1")
            filter = ""
            render()
        }
        val lp = LinearLayout.LayoutParams(WRAP, WRAP)
        lp.rightMargin = a.dp(8)
        chips.addView(t, lp)
    }

    private fun addChip(label: String, value: String) {
        val sel = filter == value
        val t = a.tv(label, 14f, if (sel) Color.WHITE else Pal.MUTED)
        t.setPadding(a.dp(14), a.dp(8), a.dp(14), a.dp(8))
        t.background = a.rounded(if (sel) Pal.BLUE else Pal.CARD, 20, if (sel) Pal.BLUE else Pal.BORDER)
        t.setOnClickListener { filter = value; render() }
        val lp = LinearLayout.LayoutParams(WRAP, WRAP)
        lp.rightMargin = a.dp(8)
        chips.addView(t, lp)
    }

    private fun card(r: Rel): View {
        val c = a.card()
        val t = a.tv(r.title, 15f, Pal.FG)
        t.maxLines = 3
        t.ellipsize = android.text.TextUtils.TruncateAt.END
        c.addView(t)
        val go = r.size / 1000000000.0
        val bl = LinearLayout(a)
        bl.orientation = LinearLayout.HORIZONTAL
        bl.setPadding(0, a.dp(8), 0, 0)
        fun tag(txt: String, colr: Int) {
            val lp = LinearLayout.LayoutParams(WRAP, WRAP)
            lp.rightMargin = a.dp(6)
            bl.addView(a.badge(txt, colr), lp)
        }
        tag(String.format(Locale.FRANCE, "%.2f Go", go), Pal.MUTED)
        val sc = if (r.seeders >= 20) Pal.GREEN else if (r.seeders >= 5) Pal.MUTED else Pal.RED
        tag(r.seeders.toString() + " seeders", sc)
        if (r.quality().isNotEmpty()) tag(r.quality(), Pal.BLUE)
        if (r.lang().isNotEmpty()) tag(r.lang(), if (r.lang() == "VOSTFR") Pal.MUTED else Pal.GREEN)
        tag(if ((dups[r.guid] ?: 1) > 1) r.indexer + " +" + ((dups[r.guid] ?: 1) - 1) else r.indexer, Pal.MUTED)
        when (libState(r)) {
            "file" -> tag("Déjà dans la bibliothèque", Pal.GREEN)
            "series" -> tag("Série déjà suivie", Pal.GREEN)
            "tracked" -> tag("Suivi, pas encore téléchargé", Pal.MUTED)
        }
        if (r.date.length >= 10) tag(r.date.take(10), Pal.MUTED)
        c.addView(bl)
        val row = LinearLayout(a)
        row.orientation = LinearLayout.HORIZONTAL
        fun b(label: String, color: Int, f: () -> Unit) {
            val x = a.btn(label, color, 44, f)
            val lp = LinearLayout.LayoutParams(0, a.dp(44), 1f)
            lp.topMargin = a.dp(10)
            lp.leftMargin = a.dp(3)
            lp.rightMargin = a.dp(3)
            x.layoutParams = lp
            row.addView(x)
        }
        b("Autre", Pal.GRAY) { sendOther(r) }
        b("Film", Pal.BLUE) { flowMovie(r) }
        b("Série", Pal.BLUE) { flowSeries(r) }
        c.addView(row)
        c.background = a.rounded(Pal.CARD, 16, if (selected.contains(r.guid)) Pal.BLUE else Pal.BORDER)
        c.setOnLongClickListener { toggle(r, c); true }
        c.setOnClickListener { if (selected.isNotEmpty()) toggle(r, c) }
        return c
    }

    // ---------- Autre : Prowlarr -> qBittorrent (catégorie « autres ») ----------
    private fun sendOther(r: Rel) {
        val key = st.get("kProwlarr")
        if (key.isEmpty()) { setStatus("Renseigne la clé API de Prowlarr dans Réglages."); return }
        setStatus("Envoi à qBittorrent…")
        thread {
            try {
                val body = JSONObject().put("guid", r.guid).put("indexerId", r.indexerId).toString()
                val res = Net.call("POST", st.base(9696) + "/api/v1/search", key, body, 30000)
                ui { setStatus(if (res.ok) "Envoyé à qBittorrent (catégorie « autres »)." else "Échec Prowlarr (" + res.code + ") : " + res.body.take(200)) }
            } catch (e: Exception) {
                ui { setStatus("Erreur : " + e.message) }
            }
        }
    }

    // ---------- Sélection de la bonne fiche (affiche, titre, année) ----------
    private fun lookupList(url: String, key: String): List<JSONObject> {
        val r = Net.call("GET", url, key, timeout = 30000)
        if (!r.ok) throw Exception("HTTP " + r.code)
        val arr = JSONArray(r.body)
        val out = ArrayList<JSONObject>()
        for (i in 0 until minOf(arr.length(), 15)) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(o)
        }
        return out
    }

    private fun matchRow(o: JSONObject, click: () -> Unit): View {
        val row = LinearLayout(a)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(0, a.dp(8), 0, a.dp(8))
        val iv = ImageView(a)
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        row.addView(iv, LinearLayout.LayoutParams(a.dp(64), a.dp(96)))
        Img.load(posterUrl(o), iv)
        val txt = LinearLayout(a)
        txt.orientation = LinearLayout.VERTICAL
        txt.setPadding(a.dp(12), 0, 0, 0)
        val year = o.optInt("year", 0)
        txt.addView(a.tv(o.str("title") + (if (year > 0) " ($year)" else ""), 16f, Pal.FG))
        if (o.optInt("id", 0) > 0) txt.addView(a.tv("Déjà dans la bibliothèque", 12f, Pal.GREEN))
        val ov = o.str("overview")
        if (ov.isNotEmpty()) txt.addView(a.tv(ov.take(110) + "…", 12f, Pal.MUTED))
        row.addView(txt, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.setOnClickListener { click() }
        return row
    }

    private fun pickMatch(title: String, term: String, lookup: (String) -> List<JSONObject>, onPick: (JSONObject) -> Unit) {
        val box = LinearLayout(a)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(a.dp(16), a.dp(8), a.dp(16), 0)
        val f = a.field("Titre à chercher", term)
        val list = LinearLayout(a)
        list.orientation = LinearLayout.VERTICAL
        val sc = ScrollView(a)
        sc.addView(list)
        val dlg = a.dialogBuilder().setTitle(title).setView(box).setNegativeButton("Annuler", null).create()

        fun doLookup() {
            list.removeAllViews()
            list.addView(a.tv("Recherche…", 14f, Pal.MUTED))
            val t = f.text.toString().trim()
            thread {
                var err = ""
                val items = try { lookup(t) } catch (e: Exception) { err = e.message ?: "erreur"; emptyList<JSONObject>() }
                a.runOnUiThread {
                    list.removeAllViews()
                    if (items.isEmpty()) {
                        list.addView(a.tv(if (err.isEmpty()) "Aucune correspondance." else "Erreur : $err", 14f, Pal.MUTED))
                    }
                    for (o in items) list.addView(matchRow(o) { dlg.dismiss(); onPick(o) })
                }
            }
        }

        box.addView(f)
        box.addView(a.btn("Rechercher", Pal.BLUE, 44) { doLookup() })
        box.addView(sc, LinearLayout.LayoutParams(MATCH, a.dp(380)))
        dlg.show()
        doLookup()
    }

    private fun profileId(base: String, key: String, pref: String, who: String): Int {
        val p = pref.trim().toIntOrNull()
        if (p != null) return p
        val r = Net.call("GET", "$base/api/v3/qualityprofile", key)
        val arr = JSONArray(r.body)
        if (arr.length() == 0) throw Exception("Aucun profil de qualité dans $who")
        return arr.getJSONObject(0).getInt("id")
    }

    // ---------- Récupérer exactement ce fichier via Radarr / Sonarr ----------
    // À appeler depuis un thread de fond. Renvoie le message à afficher.
    private fun grabFrom(base: String, key: String, list: JSONArray, r: Rel, who: String): String {
        var found: JSONObject? = null
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            if (r.guid.isNotEmpty() && o.str("guid") == r.guid) { found = o; break }
        }
        if (found == null) {
            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                if (o.str("title").equals(r.title, ignoreCase = true)) { found = o; break }
            }
        }
        val f = found
        if (f != null) {
            val reasons = reasonsOf(f)
            if (f.optBoolean("rejected", false) || reasons.isNotEmpty()) {
                val txt = if (reasons.isEmpty()) "(aucune raison détaillée)" else reasons.joinToString("\n") { "• $it" }
                val ok = a.confirmWait("$who a rejeté ce résultat", txt + "\n\nForcer quand même ?", "Forcer")
                if (!ok) return "Annulé."
            }
            val body = JSONObject().put("guid", f.str("guid")).put("indexerId", f.optInt("indexerId", 0)).toString()
            val res = Net.call("POST", "$base/api/v3/release", key, body, 60000)
            return if (res.ok) "Envoyé : $who a lancé le téléchargement. Suis-le dans « En cours »."
            else "$who a refusé (" + res.code + ") : " + res.body.take(250)
        }
        // Secours : envoi direct
        val push = JSONObject().put("title", r.title).put("protocol", "torrent").put("size", r.size).put("indexer", r.indexer)
        if (r.date.isNotEmpty()) push.put("publishDate", r.date)
        if (r.magnetUrl.isNotEmpty()) push.put("magnetUrl", r.magnetUrl) else push.put("downloadUrl", r.downloadUrl)
        val res = Net.call("POST", "$base/api/v3/release/push", key, push.toString(), 60000)
        if (!res.ok) return "$who n'a pas retrouvé ce fichier et l'envoi direct a échoué (" + res.code + ") : " + res.body.take(250)
        try {
            val arr = JSONArray(res.body)
            val o = arr.optJSONObject(0)
            if (o != null && (o.optBoolean("rejected", false) || reasonsOf(o).isNotEmpty())) {
                val rs = reasonsOf(o)
                return "$who a rejeté l'envoi direct : " + (if (rs.isEmpty()) "raison inconnue" else rs.joinToString(" ; "))
            }
        } catch (e: Exception) {
        }
        return "Envoyé en direct à $who. Suis-le dans « En cours »."
    }

    private fun reasonsOf(o: JSONObject): List<String> {
        val out = ArrayList<String>()
        val rej = o.optJSONArray("rejections") ?: return out
        for (i in 0 until rej.length()) {
            val x = rej.opt(i)
            if (x is JSONObject) out.add(x.str("reason")) else if (x != null) out.add(x.toString())
        }
        return out
    }

    // ---------- Film (Radarr) ----------
    private fun flowMovie(r: Rel) {
        val key = st.get("kRadarr")
        if (key.isEmpty()) { setStatus("Renseigne la clé API de Radarr dans Réglages."); return }
        val term = Guess.movieTerm(r.title)
        pickMatch("Quel film ?", term, { t ->
            lookupList(st.base(7878) + "/api/v3/movie/lookup?term=" + Net.enc(t), key)
        }) { m -> grabMovie(r, m) }
    }

    private fun grabMovie(r: Rel, m: JSONObject) {
        val base = st.base(7878)
        val key = st.get("kRadarr")
        setStatus("Préparation dans Radarr…")
        thread {
            try {
                val tmdb = m.optInt("tmdbId", 0)
                var movieId = 0
                val ex = Net.call("GET", "$base/api/v3/movie?tmdbId=$tmdb", key)
                if (ex.ok) {
                    val arr = JSONArray(ex.body)
                    if (arr.length() > 0) movieId = arr.getJSONObject(0).optInt("id", 0)
                }
                if (movieId == 0) {
                    val prof = profileId(base, key, st.get("pRadarr"), "Radarr")
                    val body = JSONObject(m.toString())
                    body.remove("id")
                    body.put("qualityProfileId", prof)
                    body.put("rootFolderPath", "/data/media/films")
                    body.put("monitored", true)
                    body.put("minimumAvailability", "released")
                    body.put("addOptions", JSONObject().put("searchForMovie", false))
                    val add = Net.call("POST", "$base/api/v3/movie", key, body.toString(), 30000)
                    if (!add.ok) {
                        ui { setStatus("Ajout dans Radarr refusé (" + add.code + ") : " + add.body.take(250)) }
                        return@thread
                    }
                    movieId = JSONObject(add.body).optInt("id", 0)
                }
                if (movieId == 0) {
                    ui { setStatus("Radarr n'a pas renvoyé d'identifiant pour ce film.") }
                    return@thread
                }
                ui { setStatus("Radarr cherche cette version (jusqu'à une minute)…") }
                val rel = Net.call("GET", "$base/api/v3/release?movieId=$movieId", key, timeout = 240000)
                if (!rel.ok) {
                    ui { setStatus("Recherche Radarr en erreur (" + rel.code + ") : " + rel.body.take(200)) }
                    return@thread
                }
                val msg = grabFrom(base, key, JSONArray(rel.body), r, "Radarr")
                ui { setStatus(msg) }
            } catch (e: Exception) {
                ui { setStatus("Erreur : " + e.message) }
            }
        }
    }

    // ---------- Série (Sonarr) ----------
    private fun flowSeries(r: Rel) {
        val key = st.get("kSonarr")
        if (key.isEmpty()) { setStatus("Renseigne la clé API de Sonarr dans Réglages."); return }
        val term = Guess.seriesTerm(r.title)
        pickMatch("Quelle série ?", term, { t ->
            lookupList(st.base(8989) + "/api/v3/series/lookup?term=" + Net.enc(t), key)
        }) { m ->
            val se = Guess.seasonEpisode(r.title)
            askSeasonEpisode(se.first, se.second) { s, e -> grabSeries(r, m, s, e) }
        }
    }

    private fun askSeasonEpisode(s0: String, e0: String, done: (Int, Int?) -> Unit) {
        val box = LinearLayout(a)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(a.dp(16), a.dp(8), a.dp(16), 0)
        val fs = a.field("Saison", s0, InputType.TYPE_CLASS_NUMBER)
        val fe = a.field("Épisode (vide = saison complète)", e0, InputType.TYPE_CLASS_NUMBER)
        box.addView(fs)
        box.addView(fe)
        a.dialogBuilder().setTitle("Saison / épisode").setView(box)
            .setPositiveButton("Continuer") { _, _ ->
                val s = fs.text.toString().trim().toIntOrNull()
                val e = fe.text.toString().trim().toIntOrNull()
                if (s == null) setStatus("Saison invalide.") else done(s, e)
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun grabSeries(r: Rel, m: JSONObject, season: Int, episode: Int?) {
        val base = st.base(8989)
        val key = st.get("kSonarr")
        setStatus("Préparation dans Sonarr…")
        thread {
            try {
                val tvdb = m.optInt("tvdbId", 0)
                var seriesId = 0
                val ex = Net.call("GET", "$base/api/v3/series?tvdbId=$tvdb", key)
                if (ex.ok) {
                    val arr = JSONArray(ex.body)
                    if (arr.length() > 0) seriesId = arr.getJSONObject(0).optInt("id", 0)
                }
                if (seriesId == 0) {
                    val prof = profileId(base, key, st.get("pSonarr"), "Sonarr")
                    val body = JSONObject(m.toString())
                    body.remove("id")
                    body.put("qualityProfileId", prof)
                    body.put("rootFolderPath", "/data/media/series")
                    body.put("seasonFolder", true)
                    body.put("monitored", true)
                    body.put("addOptions", JSONObject().put("monitor", "none").put("searchForMissingEpisodes", false))
                    try {
                        val lp = Net.call("GET", "$base/api/v3/languageprofile", key)
                        if (lp.ok) {
                            val la = JSONArray(lp.body)
                            if (la.length() > 0) body.put("languageProfileId", la.getJSONObject(0).getInt("id"))
                        }
                    } catch (e: Exception) {
                    }
                    val add = Net.call("POST", "$base/api/v3/series", key, body.toString(), 30000)
                    if (!add.ok) {
                        ui { setStatus("Ajout dans Sonarr refusé (" + add.code + ") : " + add.body.take(250)) }
                        return@thread
                    }
                    seriesId = JSONObject(add.body).optInt("id", 0)
                }
                if (seriesId == 0) {
                    ui { setStatus("Sonarr n'a pas renvoyé d'identifiant pour cette série.") }
                    return@thread
                }
                ui { setStatus("Sonarr charge les épisodes…") }
                var eps = JSONArray()
                for (i in 0 until 12) {
                    val er = Net.call("GET", "$base/api/v3/episode?seriesId=$seriesId", key)
                    if (er.ok) {
                        eps = JSONArray(er.body)
                        if (eps.length() > 0) break
                    }
                    Thread.sleep(2500)
                }
                var url = "$base/api/v3/release?seriesId=$seriesId&seasonNumber=$season"
                if (episode != null) {
                    var epId = 0
                    for (i in 0 until eps.length()) {
                        val o = eps.optJSONObject(i) ?: continue
                        if (o.optInt("seasonNumber", -1) == season && o.optInt("episodeNumber", -1) == episode) {
                            epId = o.optInt("id", 0)
                            break
                        }
                    }
                    if (epId == 0) {
                        ui { setStatus("Épisode S" + season + "E" + episode + " introuvable dans Sonarr.") }
                        return@thread
                    }
                    url = "$base/api/v3/release?episodeId=$epId"
                }
                ui { setStatus("Sonarr cherche cette version (jusqu'à une minute)…") }
                val rel = Net.call("GET", url, key, timeout = 240000)
                if (!rel.ok) {
                    ui { setStatus("Recherche Sonarr en erreur (" + rel.code + ") : " + rel.body.take(200)) }
                    return@thread
                }
                val msg = grabFrom(base, key, JSONArray(rel.body), r, "Sonarr")
                ui { setStatus(msg) }
            } catch (e: Exception) {
                ui { setStatus("Erreur : " + e.message) }
            }
        }
    }
}

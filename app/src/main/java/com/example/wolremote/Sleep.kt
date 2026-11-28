package com.example.wolremote

import android.app.Activity
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

// Minuteur d'arrêt : le plan est exécuté par la tablette (toujours allumée), même si l'appli est fermée.
object Sleep {
    fun status(st: Settings): JSONObject = JSONObject(Net.call("GET", st.tablet() + "/sleep/status", timeout = 5000).body)

    fun describe(j: JSONObject): String {
        if (!j.optBoolean("active")) return "Aucun minuteur"
        val cd = if (j.has("countdown")) j.optInt("countdown") else -1
        if (cd >= 0) return "Extinction dans $cd s…"
        val v = j.optInt("value")
        val acts = j.optJSONArray("actions") ?: JSONArray()
        val names = (0 until acts.length()).map { when (acts.optString(it)) { "stop" -> "arrêt lecture + fermeture Jellyfin"; "screens" -> "écrans"; "tv" -> "télé"; else -> "PC" } }
        val what = when (j.optString("mode")) {
            "timer" -> { val m = (j.optInt("remaining") + 59) / 60; "dans " + (if (m >= 60) (m / 60).toString() + " h " + (m % 60) + " min" else "$m min") }
            "end" -> "à la fin de la lecture"
            "episodes" -> "après $v épisode(s) (" + j.optInt("seen") + " vu(s))"
            else -> "après $v min sans lecture"
        }
        return "Actif : $what · " + names.joinToString(", ")
    }

    fun open(a: Activity, st: Settings, onChange: () -> Unit) {
        val body = LinearLayout(a)
        body.orientation = LinearLayout.VERTICAL
        var mode = "timer"
        var value = 60
        val acts = linkedSetOf("stop", "shutdown")
        val info = a.tv("", 14f, Pal.FG)
        val cancel = a.btn2("Annuler le minuteur en cours", Pal.RED, 48) {}
        cancel.visibility = android.view.View.GONE
        val modes = FlowRow(a, a.dp(8), a.dp(8))
        val vals = FlowRow(a, a.dp(8), a.dp(8))
        val aRow = FlowRow(a, a.dp(8), a.dp(8))
        val msg = a.tv("", 13f, Pal.MUTED)

        fun chip(label: String, sel: Boolean, click: () -> Unit): TextView {
            val t = a.tv(label, 14f, if (sel) android.graphics.Color.WHITE else Pal.MUTED)
            t.setPadding(a.dp(14), a.dp(8), a.dp(14), a.dp(8))
            t.background = a.rounded(if (sel) Pal.BLUE else Pal.CARD, 20, if (sel) Pal.BLUE else Pal.BORDER)
            t.setOnClickListener { click() }
            return t
        }
        fun render() {
            modes.removeAllViews(); vals.removeAllViews(); aRow.removeAllViews()
            for ((k, l) in listOf("timer" to "Minuteur", "end" to "Fin du film", "episodes" to "Nb d'épisodes", "idle" to "Inactivité")) {
                modes.addView(chip(l, mode == k) {
                    mode = k
                    value = when (k) { "timer" -> 60; "episodes" -> 2; "idle" -> 20; else -> 0 }
                    render()
                })
            }
            val opts = when (mode) { "timer" -> listOf(30, 45, 60, 90, 120, 180); "episodes" -> listOf(1, 2, 3, 4, 5); "idle" -> listOf(10, 20, 30, 60); else -> emptyList() }
            for (o in opts) vals.addView(chip(if (mode == "episodes") (if (o == 1) "1 épisode" else "$o épisodes") else (if (o >= 60 && o % 60 == 0) "${o / 60} h" else "$o min"), value == o) { value = o; render() })
            vals.visibility = if (opts.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
            for ((k, l) in listOf("stop" to (if (st.get("kTvIp").trim().isNotEmpty()) "1. Arrêter la lecture et fermer Jellyfin" else "Arrêter la lecture"), "screens" to "3. Éteindre les écrans", "tv" to "2. Éteindre la télé (ferme Jellyfin avant)", "shutdown" to "3. Éteindre le PC").filter { it.first != "tv" || st.get("kTvIp").trim().isNotEmpty() })
                aRow.addView(chip(l, acts.contains(k)) { if (!acts.remove(k)) acts.add(k); render() })
        }

        fun refresh() {
            thread {
                val j = try { status(st) } catch (e: Exception) { null }
                a.runOnUiThread {
                    info.text = if (j == null) "Tablette injoignable." else describe(j)
                    cancel.visibility = if (j != null && j.optBoolean("active")) android.view.View.VISIBLE else android.view.View.GONE
                }
            }
        }
        cancel.setOnClickListener {
            thread {
                try { Net.call("POST", st.tablet() + "/sleep/cancel", json = JSONObject().put("pin", st.pin()).toString(), timeout = 8000) } catch (e: Exception) { }
                a.runOnUiThread { refresh(); onChange() }
            }
        }

        body.addView(info)
        body.addView(cancel)
        body.addView(a.section("Quand"))
        body.addView(modes)
        body.addView(vals.also { it.setPadding(0, a.dp(10), 0, 0) })
        body.addView(a.tv("Fin du film : après la fin de la lecture. Nb d'épisodes : s'arrête avant le suivant. Inactivité : rien n'est lu pendant la durée choisie.", 12f, Pal.MUTED).also { it.setPadding(0, a.dp(8), 0, 0) })
        body.addView(a.section("Que faire"))
        body.addView(aRow)
        body.addView(a.btn("Démarrer le minuteur", Pal.BLUE, 52) {
            if (acts.isEmpty()) { msg.text = "Choisis au moins une action."; return@btn }
            if (st.get("kJellyfin").isEmpty() && mode != "timer") { msg.text = "Renseigne la clé Jellyfin dans Réglages."; return@btn }
            msg.text = "…"
            thread {
                val r = try {
                    Net.call("POST", st.tablet() + "/sleep/set", json = JSONObject().put("pin", st.pin()).put("mode", mode).put("value", value)
                        .put("actions", JSONArray(acts.toList())).put("jf_key", st.get("kJellyfin").trim()).put("tv_ip", st.get("kTvIp").trim()).toString(), timeout = 8000)
                } catch (e: Exception) { null }
                a.runOnUiThread {
                    msg.text = if (r != null && r.ok) "Minuteur programmé. Une notification te prévient 1 minute avant." else if (r != null && r.code == 404) "Mets à jour le serveur de la tablette (Réglages, Serveur)." else "Échec (PIN ou tablette ?)"
                    refresh(); onChange()
                }
            }
        })
        body.addView(msg.also { it.setPadding(0, a.dp(8), 0, 0) })
        render()
        refresh()
        a.bigDialog("Minuteur d'arrêt", body)
    }
}

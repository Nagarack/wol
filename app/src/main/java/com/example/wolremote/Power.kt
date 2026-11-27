package com.example.wolremote

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import org.json.JSONObject
import java.util.Locale
import kotlin.concurrent.thread

// Consommation estimée du PC : CPU + GPU (capteurs LibreHardwareMonitor, port 8085) + base forfaitaire.
class PowerCard(private val a: Activity, private val st: Settings) {
    val view = a.card()
    private val big = a.tv("Consommation…", 28f, Pal.MUTED)
    private val detail = a.tv("", 13f, Pal.MUTED)
    private val total = a.tv("", 13f, Pal.MUTED)
    private var busy = false
    private var lastT = 0L
    private var wh = 0.0

    init {
        big.typeface = Typeface.DEFAULT_BOLD
        big.gravity = Gravity.CENTER
        detail.gravity = Gravity.CENTER
        total.gravity = Gravity.CENTER
        detail.setPadding(0, a.dp(6), 0, 0)
        total.setPadding(0, a.dp(4), 0, 0)
        view.addView(a.tv("⚡ CONSOMMATION (ESTIMÉE)", 12f, Pal.MUTED))
        view.addView(big)
        view.addView(detail)
        view.addView(total)
    }

    private fun watts(v: String): Double? {
        val t = v.trim()
        if (!t.endsWith("W") || t.endsWith("kW")) return null
        return t.removeSuffix("W").trim().replace(',', '.').toDoubleOrNull()
    }

    // Parcourt l'arbre ; collecte (matériel, capteur, watts)
    private fun walk(n: JSONObject, hw: String, out: MutableList<Triple<String, String, Double>>) {
        val text = n.optString("Text")
        val w = watts(n.optString("Value"))
        if (w != null) out.add(Triple(hw, text, w))
        val ch = n.optJSONArray("Children") ?: return
        // le nom du matériel = nœud contenant Ryzen / Radeon / Intel / NVIDIA / Core i
        val l = text.lowercase()
        val nhw = if (l.contains("ryzen") || l.contains("radeon") || l.contains("nvidia") || l.contains("geforce") || l.contains("intel")) text else hw
        for (i in 0 until ch.length()) walk(ch.getJSONObject(i), nhw, out)
    }

    fun refresh() {
        if (busy) return
        busy = true
        thread {
            var cpu = -1.0
            var gpu = 0.0
            var err = ""
            try {
                var body = ""
                val tried = ArrayList<String>()
                if (st.active.isNotEmpty()) tried.add(st.active)
                tried.addAll(st.hosts())
                for (h in tried.distinct()) {
                    try {
                        val r = Net.call("GET", "http://$h:8085/data.json", timeout = 3000)
                        if (r.ok) { body = r.body; break }
                    } catch (e: Exception) {
                    }
                }
                if (body.isEmpty()) {
                    err = "Capteurs injoignables (PC éteint ou LibreHardwareMonitor non lancé)."
                } else {
                    val out = ArrayList<Triple<String, String, Double>>()
                    walk(JSONObject(body), "", out)
                    // CPU : « Package »
                    val c = out.firstOrNull { it.first.lowercase().let { h -> h.contains("ryzen") || h.contains("intel") } && it.second.equals("Package", true) }
                    if (c != null) cpu = c.third
                    // GPU : carte dédiée (pas l'iGPU « Radeon(TM) Graphics »)
                    val gpus = out.filter { x ->
                        val h = x.first.lowercase()
                        (h.contains("radeon") || h.contains("nvidia") || h.contains("geforce")) && !h.contains("radeon(tm)")
                    }
                    val pref = gpus.firstOrNull { it.second.contains("Package", true) || it.second.contains("Board", true) }
                        ?: gpus.maxByOrNull { it.third }
                    if (pref != null) gpu = pref.third
                    if (cpu < 0 && pref == null) err = "Aucun capteur de puissance trouvé (lance LibreHardwareMonitor en administrateur)."
                }
            } catch (e: Exception) {
                err = "Erreur capteurs : " + e.message
            }
            val cpuF = cpu
            val gpuF = gpu
            val msg = err
            a.runOnUiThread {
                busy = false
                if (msg.isNotEmpty()) {
                    big.text = "—"
                    big.setTextColor(Pal.MUTED)
                    detail.text = msg
                    total.text = ""
                    lastT = 0L
                    return@runOnUiThread
                }
                val base = st.get("kBaseW").replace(',', '.').toDoubleOrNull() ?: 50.0
                val price = st.get("kTarif").replace(',', '.').toDoubleOrNull() ?: 0.25
                val sum = (if (cpuF > 0) cpuF else 0.0) + gpuF + base
                val now = System.currentTimeMillis()
                if (lastT > 0L) wh += sum * (now - lastT) / 3600000.0
                lastT = now
                big.text = "≈ " + Math.round(sum) + " W"
                big.setTextColor(if (sum > 400) Pal.AMBER else Pal.GREEN)
                detail.text = String.format(Locale.FRANCE, "CPU %.0f W  ·  GPU %.0f W  ·  base %.0f W", if (cpuF > 0) cpuF else 0.0, gpuF, base)
                total.text = String.format(Locale.FRANCE, "Depuis l'ouverture de l'appli : %.1f Wh  ·  %.3f €", wh, wh / 1000.0 * price)
            }
        }
    }
}

package com.example.wolremote

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import org.json.JSONObject
import java.util.Locale
import kotlin.concurrent.thread

// Consommation estimée du PC. Le compteur tourne en continu sur la tablette (même appli fermée) :
// ici on affiche seulement ce qu'elle a mesuré.
class PowerCard(private val a: Activity, private val st: Settings) {
    val view = a.card()
    private val big = a.tv("Consommation…", 28f, Pal.MUTED)
    private val detail = a.tv("", 13f, Pal.MUTED)
    private val today = a.tv("", 14f, Pal.FG)
    private val month = a.tv("", 13f, Pal.MUTED)
    private var busy = false

    init {
        big.typeface = Typeface.DEFAULT_BOLD
        for (t in listOf(big, detail, today, month)) t.gravity = Gravity.CENTER
        detail.setPadding(0, a.dp(6), 0, 0)
        today.setPadding(0, a.dp(10), 0, 0)
        month.setPadding(0, a.dp(2), 0, 0)
        view.addView(a.tv("⚡ CONSOMMATION (ESTIMÉE)", 12f, Pal.MUTED))
        view.addView(big)
        view.addView(detail)
        view.addView(today)
        view.addView(month)
    }

    private fun kwh(wh: Double, price: Double): String =
        String.format(Locale.FRANCE, "%.2f kWh  ·  %.2f €", wh / 1000.0, wh / 1000.0 * price)

    fun refresh() {
        if (busy) return
        busy = true
        thread {
            var j: JSONObject? = null
            var err = ""
            try {
                val r = Net.call("GET", st.tablet() + "/power", timeout = 4000)
                if (r.ok) j = JSONObject(r.body) else err = "Mets à jour wol_app.py sur la tablette (compteur absent)."
            } catch (e: Exception) {
                err = "Serveur de la tablette injoignable."
            }
            val d = j
            val msg = err
            a.runOnUiThread {
                busy = false
                if (d == null) {
                    big.text = "—"
                    big.setTextColor(Pal.MUTED)
                    detail.text = msg
                    today.text = ""
                    month.text = ""
                    return@runOnUiThread
                }
                val price = st.get("kTarifKwh").replace(',', '.').toDoubleOrNull() ?: 0.20
                if (d.optBoolean("ok")) {
                    val w = d.optInt("watts")
                    big.text = "≈ $w W"
                    big.setTextColor(if (w > 400) Pal.AMBER else Pal.GREEN)
                    detail.text = String.format(Locale.FRANCE, "CPU %d W  ·  GPU %d W  ·  base %d W (pertes alim. incluses)",
                        d.optInt("cpu"), d.optInt("gpu"), d.optInt("base"))
                } else if (!d.optBoolean("online")) {
                    big.text = "0 W"
                    big.setTextColor(Pal.MUTED)
                    detail.text = "PC éteint"
                } else {
                    big.text = "—"
                    big.setTextColor(Pal.MUTED)
                    detail.text = "PC allumé, capteurs injoignables (LibreHardwareMonitor non lancé ?)"
                }
                today.text = "Aujourd'hui : " + kwh(d.optDouble("today_wh"), price)
                month.text = "Ce mois-ci : " + kwh(d.optDouble("month_wh"), price)
            }
        }
    }
}

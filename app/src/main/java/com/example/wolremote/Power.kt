package com.example.wolremote

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import kotlin.concurrent.thread

// Histogramme simple : une barre par jour (kWh).
class BarChart(ctx: Context) : View(ctx) {
    var data: List<Pair<String, Double>> = emptyList()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(getDefaultSize(0, w), (resources.displayMetrics.density * 120).toInt())
    }

    private fun label(d: String): String = if (d.length >= 10) d.substring(8, 10) + "/" + d.substring(5, 7) else d

    override fun onDraw(c: Canvas) {
        val n = data.size
        if (n == 0) return
        val dens = resources.displayMetrics.density
        val max = data.maxOf { it.second }.coerceAtLeast(0.1)
        val w = width.toFloat()
        val top = 16 * dens
        val bottom = height - 16 * dens
        val gap = w / n
        val bw = gap * 0.7f
        for (i in 0 until n) {
            val bh = ((bottom - top) * (data[i].second / max)).toFloat().coerceAtLeast(2f * dens)
            val x = i * gap + (gap - bw) / 2
            p.color = if (i == n - 1) Pal.GREEN else Pal.BLUE
            c.drawRoundRect(RectF(x, bottom - bh, x + bw, bottom), 4 * dens, 4 * dens, p)
        }
        p.color = Pal.MUTED
        p.textSize = 11 * dens
        p.textAlign = Paint.Align.LEFT
        c.drawText(label(data.first().first), 0f, height - 3 * dens, p)
        p.textAlign = Paint.Align.RIGHT
        c.drawText("auj.", w, height - 3 * dens, p)
        p.textAlign = Paint.Align.LEFT
        c.drawText(String.format(Locale.FRANCE, "max %.2f kWh", max / 1000.0), 0f, 11 * dens, p)
    }
}

// Consommation estimée du PC. Le compteur tourne en continu sur la tablette (même appli fermée) :
// ici on affiche seulement ce qu'elle a mesuré.
class PowerCard(private val a: Activity, private val st: Settings) {
    val view = a.card()
    private val big = a.tv("Consommation…", 28f, Pal.MUTED)
    private val detail = a.tv("", 13f, Pal.MUTED)
    private val temps = a.tv("", 13f, Pal.MUTED)
    private val today = a.tv("", 14f, Pal.FG)
    private val month = a.tv("", 13f, Pal.MUTED)
    private val forecast = a.tv("", 13f, Pal.MUTED)
    private val chartTitle = a.tv("", 12f, Pal.MUTED)
    private val chart = BarChart(a)
    private var busy = false
    private var span = 14
    private var last: JSONObject? = null

    init {
        big.typeface = Typeface.DEFAULT_BOLD
        for (t in listOf(big, detail, temps, today, month, forecast)) t.gravity = Gravity.CENTER
        detail.setPadding(0, a.dp(6), 0, 0)
        temps.setPadding(0, a.dp(2), 0, 0)
        today.setPadding(0, a.dp(10), 0, 0)
        month.setPadding(0, a.dp(2), 0, 0)
        chartTitle.setPadding(0, a.dp(14), 0, a.dp(4))
        view.addView(a.tv("Consommation estimée", 14f, Pal.MUTED))
        view.addView(big)
        view.addView(detail)
        view.addView(temps)
        view.addView(today)
        view.addView(month)
        view.addView(forecast)
        view.addView(chartTitle)
        view.addView(chart)
        view.setOnClickListener {
            span = when (span) { 7 -> 14; 14 -> 30; else -> 7 }
            last?.let { drawChart(it) }
        }
    }

    private fun kwh(wh: Double, price: Double): String =
        String.format(Locale.FRANCE, "%.2f kWh  ·  %.2f €", wh / 1000.0, wh / 1000.0 * price)

    private fun drawChart(d: JSONObject) {
        val h = d.optJSONArray("history")
        if (h == null) { chart.data = emptyList(); chart.invalidate(); return }
        val all = ArrayList<Pair<String, Double>>()
        for (i in 0 until h.length()) {
            val e = h.optJSONArray(i) ?: continue
            all.add(Pair(e.optString(0), e.optDouble(1)))
        }
        chart.data = all.takeLast(span)
        chartTitle.text = "Historique : $span derniers jours (touche pour changer)"
        chart.invalidate()
    }

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
                    temps.text = ""
                    today.text = ""
                    month.text = ""
                    forecast.text = ""
                    return@runOnUiThread
                }
                last = d
                val price = st.get("kTarifKwh").replace(',', '.').toDoubleOrNull() ?: 0.20
                temps.text = ""
                if (d.optBoolean("ok")) {
                    val w = d.optInt("watts")
                    big.text = "≈ $w W"
                    big.setTextColor(Pal.FG)
                    detail.text = String.format(Locale.FRANCE, "CPU %d W  ·  GPU %d W  ·  base %d W (pertes alim. incluses)",
                        d.optInt("cpu"), d.optInt("gpu"), d.optInt("base"))
                    val parts = ArrayList<String>()
                    if (!d.isNull("cpu_t") && d.has("cpu_t")) parts.add("CPU " + d.optInt("cpu_t") + " °C")
                    if (!d.isNull("gpu_t") && d.has("gpu_t")) parts.add("GPU " + d.optInt("gpu_t") + " °C")
                    if (!d.isNull("disk_used") && d.has("disk_used")) parts.add("disque " + d.optInt("disk_used") + " % plein")
                    temps.text = parts.joinToString("  ·  ")
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
                val cal = Calendar.getInstance()
                val elapsed = (cal.get(Calendar.DAY_OF_MONTH) - 1) + cal.get(Calendar.HOUR_OF_DAY) / 24.0 + cal.get(Calendar.MINUTE) / 1440.0
                forecast.text = if (elapsed >= 1.0 && d.optDouble("month_wh") > 0)
                    "Prévision du mois : " + kwh(d.optDouble("month_wh") / elapsed * cal.getActualMaximum(Calendar.DAY_OF_MONTH), price)
                else ""
                drawChart(d)
            }
        }
    }
}

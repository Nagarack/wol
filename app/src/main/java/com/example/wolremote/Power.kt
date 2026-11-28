package com.example.wolremote

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
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
    private val statusBadge = LinearLayout(a)
    private val big = a.tv("—", 34f, Pal.MUTED)
    private val detail = a.tv("", 12f, Pal.MUTED)
    private val sensors = FlowRow(a, a.dp(6), a.dp(6))
    private val chartTitle = a.tv("", 12f, Pal.MUTED)
    private val chart = BarChart(a)
    private var busy = false
    private var span = 14
    private var last: JSONObject? = null

    // Trois blocs côte à côte : session, aujourd'hui, ce mois-ci
    private class Stat(val label: android.widget.TextView, val value: android.widget.TextView, val sub: android.widget.TextView, val box: LinearLayout)
    private fun stat(): Stat {
        val box = LinearLayout(a)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(a.dp(10), a.dp(10), a.dp(10), a.dp(10))
        box.background = a.rounded(Pal.CARD2, 12)
        val l = a.tv("", 11f, Pal.MUTED)
        val v = a.tv("—", 17f, Pal.FG)
        v.typeface = Typeface.DEFAULT_BOLD
        val s = a.tv("", 11f, Pal.MUTED)
        box.addView(l); box.addView(v); box.addView(s)
        return Stat(l, v, s, box)
    }
    private val sSession = stat()
    private val sToday = stat()
    private val sMonth = stat()
    private val sessionsLink = a.tv("Historique des sessions  ›", 13f, Pal.BLUE)
    private val forecast = a.tv("", 12f, Pal.MUTED)

    init {
        big.typeface = Typeface.DEFAULT_BOLD
        big.gravity = Gravity.CENTER
        detail.gravity = Gravity.CENTER
        forecast.gravity = Gravity.CENTER
        val head = LinearLayout(a)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(a.tv("Consommation estimée", 14f, Pal.MUTED), LinearLayout.LayoutParams(0, WRAP, 1f))
        head.addView(statusBadge)
        view.addView(head)
        big.setPadding(0, a.dp(8), 0, 0)
        view.addView(big)
        view.addView(detail)
        sensors.setPadding(0, a.dp(8), 0, 0)
        view.addView(sensors)
        val grid = LinearLayout(a)
        grid.orientation = LinearLayout.HORIZONTAL
        grid.setPadding(0, a.dp(14), 0, 0)
        for ((k, x) in listOf(sSession, sToday, sMonth).withIndex()) {
            val lp = LinearLayout.LayoutParams(0, WRAP, 1f)
            if (k > 0) lp.leftMargin = a.dp(8)
            grid.addView(x.box, lp)
        }
        view.addView(grid)
        forecast.setPadding(0, a.dp(8), 0, 0)
        view.addView(forecast)
        sessionsLink.setPadding(0, a.dp(12), 0, a.dp(2))
        sessionsLink.gravity = Gravity.CENTER
        sessionsLink.setOnClickListener { showSessions() }
        view.addView(sessionsLink)
        chartTitle.setPadding(0, a.dp(10), 0, a.dp(4))
        view.addView(chartTitle)
        view.addView(chart)
        chart.setOnClickListener {
            span = when (span) { 7 -> 14; 14 -> 30; else -> 7 }
            last?.let { drawChart(it) }
        }
    }

    private fun dur(s: Int): String {
        val m = s / 60
        return if (m >= 60) (m / 60).toString() + " h " + String.format(Locale.FRANCE, "%02d", m % 60) else maxOf(m, 1).toString() + " min"
    }

    private fun kwhS(wh: Double) = String.format(Locale.FRANCE, "%.2f kWh", wh / 1000.0)
    private fun eur(wh: Double, price: Double) = String.format(Locale.FRANCE, "%.2f €", wh / 1000.0 * price)

    private fun setStatus(text: String, color: Int) {
        statusBadge.removeAllViews()
        statusBadge.addView(a.badge(text, color))
    }

    private fun price(): Double = st.get("kTarifKwh").replace(',', '.').toDoubleOrNull() ?: 0.20

    private fun showSessions() {
        val d = last
        val body = LinearLayout(a)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(a.dp(16), a.dp(8), a.dp(16), a.dp(8))
        val arr = d?.optJSONArray("sessions")
        val p = price()
        val fmt = java.text.SimpleDateFormat("EEE d MMM, HH:mm", Locale.FRANCE)
        if (arr == null || arr.length() == 0) body.addView(a.tv("Aucune session terminée pour l'instant. Elles sont comptées à partir de la mise à jour du serveur.", 14f, Pal.MUTED))
        else for (i in 0 until arr.length()) {
            val e = arr.optJSONArray(i) ?: continue
            val c = a.card()
            c.addView(a.tv(fmt.format(java.util.Date(e.optLong(0) * 1000)), 15f, Pal.FG))
            val fl = FlowRow(a, a.dp(6), a.dp(6))
            fl.setPadding(0, a.dp(6), 0, 0)
            fl.addView(a.badge(dur(e.optInt(1)), Pal.MUTED))
            fl.addView(a.badge(kwhS(e.optDouble(2)), Pal.BLUE))
            fl.addView(a.badge(eur(e.optDouble(2), p), Pal.GREEN))
            c.addView(fl)
            body.addView(c)
        }
        val sv = android.widget.ScrollView(a)
        sv.addView(body)
        a.dialogBuilder().setTitle("Sessions du PC").setView(sv).setPositiveButton("Fermer", null).show()
    }

    private fun drawChart(d: JSONObject) {
        val h = d.optJSONArray("history")
        if (h == null) { chart.data = emptyList(); chart.invalidate(); return }
        val all = ArrayList<Pair<String, Double>>()
        for (i in 0 until h.length()) {
            val e = h.optJSONArray(i) ?: continue
            all.add(Pair(e.optString(0), e.optDouble(1)))
        }
        chart.data = all.takeLast(span)
        chartTitle.text = "Par jour, $span derniers jours (touche le graphique pour changer)"
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
                    sensors.removeAllViews()
                    setStatus("Indisponible", Pal.MUTED)
                    return@runOnUiThread
                }
                last = d
                val p = price()
                sensors.removeAllViews()
                if (d.optBoolean("ok")) {
                    big.text = "≈ " + d.optInt("watts") + " W"
                    big.setTextColor(Pal.FG)
                    setStatus("Allumé", Pal.GREEN)
                    detail.text = String.format(Locale.FRANCE, "CPU %d W  ·  GPU %d W  ·  base %d W", d.optInt("cpu"), d.optInt("gpu"), d.optInt("base"))
                    if (!d.isNull("cpu_t") && d.has("cpu_t")) sensors.addView(a.badge("CPU " + d.optInt("cpu_t") + " °C", if (d.optInt("cpu_t") >= 85) Pal.RED else Pal.MUTED))
                    if (!d.isNull("gpu_t") && d.has("gpu_t")) sensors.addView(a.badge("GPU " + d.optInt("gpu_t") + " °C", if (d.optInt("gpu_t") >= 85) Pal.RED else Pal.MUTED))
                    if (!d.isNull("disk_used") && d.has("disk_used")) sensors.addView(a.badge("Disque " + d.optInt("disk_used") + " %", if (d.optInt("disk_used") >= 90) Pal.RED else Pal.MUTED))
                    val dr = d.optJSONArray("drives")
                    if (dr != null) for (i in 0 until dr.length()) {
                        val o = dr.optJSONObject(i) ?: continue
                        val t = if (o.isNull("temp")) "" else " " + o.optInt("temp") + " °C"
                        val l = if (o.isNull("life")) "" else " · santé " + o.optInt("life") + " %"
                        sensors.addView(a.badge(o.str("name").take(16) + t + l, Pal.MUTED))
                    }
                } else if (!d.optBoolean("online")) {
                    big.text = "0 W"
                    big.setTextColor(Pal.MUTED)
                    setStatus("Éteint", Pal.MUTED)
                    detail.text = "PC éteint"
                } else {
                    big.text = "—"
                    big.setTextColor(Pal.MUTED)
                    setStatus("Allumé", Pal.GREEN)
                    detail.text = "Capteurs injoignables (LibreHardwareMonitor non lancé ?)"
                }

                // Session : en cours si le PC est allumé, sinon la dernière terminée
                val live = d.has("session_s") && !d.isNull("session_s")
                if (live) {
                    sSession.label.text = "Session en cours"
                    val wh = d.optDouble("session_wh")
                    sSession.value.text = kwhS(wh)
                    sSession.sub.text = dur(d.optInt("session_s")) + " · " + eur(wh, p)
                } else if (d.has("last_sess_s") && !d.isNull("last_sess_s")) {
                    sSession.label.text = "Dernière session"
                    val wh = d.optDouble("last_sess_wh", 0.0)
                    sSession.value.text = kwhS(wh)
                    sSession.sub.text = dur(d.optInt("last_sess_s")) + " · " + eur(wh, p)
                } else {
                    sSession.label.text = "Session"
                    sSession.value.text = "—"
                    sSession.sub.text = "après mise à jour"
                }
                sToday.label.text = "Aujourd'hui"
                sToday.value.text = kwhS(d.optDouble("today_wh"))
                sToday.sub.text = eur(d.optDouble("today_wh"), p) + (if (d.has("hours_today")) String.format(Locale.FRANCE, " · %.1f h", d.optDouble("hours_today")) else "")
                sMonth.label.text = "Ce mois-ci"
                sMonth.value.text = kwhS(d.optDouble("month_wh"))
                sMonth.sub.text = eur(d.optDouble("month_wh"), p) + (if (d.has("hours_month")) String.format(Locale.FRANCE, " · %.0f h", d.optDouble("hours_month")) else "")
                val cal = Calendar.getInstance()
                val elapsed = (cal.get(Calendar.DAY_OF_MONTH) - 1) + cal.get(Calendar.HOUR_OF_DAY) / 24.0 + cal.get(Calendar.MINUTE) / 1440.0
                forecast.text = if (elapsed >= 1.0 && d.optDouble("month_wh") > 0)
                    "Prévision du mois : " + eur(d.optDouble("month_wh") / elapsed * cal.getActualMaximum(Calendar.DAY_OF_MONTH), p) else ""
                drawChart(d)
            }
        }
    }
}

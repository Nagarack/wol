package com.example.wolremote

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

// Client qBittorrent partagé (session SID conservée, anti-bannissement : pas de re-login en boucle).
class QbClient(private val st: Settings) {
    private var sid = ""
    private var failKey = ""
    private var failAt = 0L
    private var failMsg = ""

    var onComplete: (() -> Unit)? = null
    private var seen: HashMap<String, Boolean>? = null

    class Result(val err: String, val data: JSONArray)

    private fun req(method: String, path: String, form: String? = null): Resp {
        val base = st.base(st.qbPort())
        val c = URL(base + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 3000
        c.readTimeout = 8000
        c.setRequestProperty("Referer", base)
        c.setRequestProperty("Origin", base)
        if (sid.isNotEmpty()) c.setRequestProperty("Cookie", sid)
        try {
            if (form != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                c.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            for (e in c.headerFields.entries) {
                val k = e.key
                if (k != null && k.equals("set-cookie", ignoreCase = true)) {
                    for (v in e.value) {
                        // qBittorrent 4 : "SID=…", qBittorrent 5 : "QBT_SID_<port>=…"
                        val pair = v.substringBefore(";").trim()
                        if (pair.substringBefore("=").contains("SID")) sid = pair
                    }
                }
            }
            val stream = if (code < 400) c.inputStream else c.errorStream
            return Resp(code, stream?.bufferedReader(Charsets.UTF_8)?.readText() ?: "")
        } finally {
            c.disconnect()
        }
    }

    @Synchronized
    fun torrents(): Result {
        val empty = JSONArray()
        try {
            if (st.active.isEmpty() && !st.reach(st.qbPort())) return Result("qBittorrent injoignable (PC éteint ou hors réseau).", empty)
            var r = try {
                req("GET", "/api/v2/torrents/info")
            } catch (e: java.io.IOException) {
                st.active = ""
                if (!st.reach(st.qbPort())) return Result("qBittorrent injoignable (PC éteint ou hors réseau).", empty)
                req("GET", "/api/v2/torrents/info")
            }
            if (r.code == 401 || r.code == 403) {
                val u = st.get("kQbUser").trim()
                val p = st.get("kQbPass").trim()
                if (u.isEmpty()) return Result("qBittorrent demande un identifiant : renseigne-le dans Réglages.", empty)
                val key = u + "\n" + p
                if (key == failKey && System.currentTimeMillis() - failAt < 60000L) return Result(failMsg, empty)
                val l = req("POST", "/api/v2/auth/login", "username=" + Net.enc(u) + "&password=" + Net.enc(p))
                if (!(l.ok && !l.body.trim().startsWith("Fails"))) {
                    failKey = key
                    failAt = System.currentTimeMillis()
                    failMsg = "Connexion qBittorrent refusée (HTTP " + l.code + ") : " + l.body.trim().take(150) +
                        "\nNouvel essai dans 1 min (pour éviter le bannissement de l'IP)."
                    return Result(failMsg, empty)
                }
                failKey = ""
                r = req("GET", "/api/v2/torrents/info")
            }
            if (!r.ok) return Result("Erreur qBittorrent (HTTP " + r.code + ") : " + r.body.take(100), empty)
            val arr = JSONArray(r.body)
            val now = HashMap<String, Boolean>()
            var finished = false
            val prev = seen
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val h = o.optString("hash")
                val done = o.optDouble("progress", 0.0) >= 1.0
                now[h] = done
                if (prev != null && done && prev[h] == false) finished = true
            }
            seen = now
            if (finished) onComplete?.invoke()
            return Result("", arr)
        } catch (e: Exception) {
            return Result("Erreur qBittorrent : " + e.message, empty)
        }
    }
}

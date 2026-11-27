package com.example.wolremote

import org.json.JSONObject

// Mise à jour de la tablette (wol_app.py) et de l'agent du PC depuis GitHub, sans copie manuelle.
object ServerUpdate {
    const val RAW = "https://raw.githubusercontent.com/Nagarack/wol/main/server/"

    class Status(val latest: Int, val tablet: Int?, val agent: Int?) {
        val tabletOld: Boolean get() = tablet == null || tablet < latest
        val agentOld: Boolean get() = agent == null || agent < latest
    }

    private fun ver(url: String): Int? = try {
        val r = Net.call("GET", url, timeout = 6000)
        if (!r.ok) null else JSONObject(r.body).let { if (it.isNull("version")) null else it.optInt("version") }
    } catch (e: Exception) { null }

    fun check(st: Settings): Status {
        val latest = Net.call("GET", RAW + "VERSION", timeout = 10000).body.trim().toInt()
        return Status(latest, ver(st.tablet() + "/version"), ver(st.tablet() + "/agent-version"))
    }

    private fun post(st: Settings, path: String): String = try {
        val r = Net.call("POST", st.tablet() + path, json = JSONObject().put("pin", st.pin()).toString(), timeout = 70000)
        JSONObject(r.body).optString("message", "OK")
    } catch (e: Exception) { "Échec : " + e.message }

    fun updateTablet(st: Settings) = post(st, "/self-update")
    fun updateAgent(st: Settings) = post(st, "/agent-update")
}

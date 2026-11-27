package com.example.wolremote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

class Resp(val code: Int, val body: String) {
    val ok: Boolean get() = code in 200..299
}

object Net {
    fun call(method: String, url: String, key: String = "", json: String? = null, timeout: Int = 20000, headers: Map<String, String> = emptyMap()): Resp {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = if (timeout < 5000) timeout else 5000
        c.readTimeout = timeout
        if (key.isNotEmpty()) c.setRequestProperty("X-Api-Key", key)
        for ((hk, hv) in headers) c.setRequestProperty(hk, hv)
        try {
            if (json != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code < 400) c.inputStream else c.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
            return Resp(code, body)
        } finally {
            c.disconnect()
        }
    }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    fun pingHost(host: String, port: Int): Boolean {
        return try {
            val r = call("GET", "http://$host:$port/ping", timeout = 3000)
            r.ok && JSONObject(r.body).optString("status") == "OK"
        } catch (e: Exception) {
            false
        }
    }
}

// Réglages stockés uniquement sur le téléphone (aucune clé n'est écrite dans le code).
class Settings(ctx: Context) {
    private val p = ctx.getSharedPreferences("wol", Context.MODE_PRIVATE)

    companion object {
        val DEFAULTS = mapOf(
            "url" to "http://100.124.61.8:8080",   // serveur Wake-on-LAN (tablette, via Tailscale)
            "pin" to "",
            "kTarifKwh" to "0.20",
            "pQb" to "8080",
            "host" to "192.168.1.19",              // PC, réseau local
            "host2" to "100.77.121.120"            // PC, Tailscale
        )
    }

    // Hôte du PC qui a répondu en dernier (local ou Tailscale)
    var active: String = ""

    fun get(k: String): String = p.getString(k, null) ?: DEFAULTS[k] ?: ""
    fun put(k: String, v: String) { p.edit().putString(k, v).apply() }
    fun all(): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        for ((k, v) in p.all) if (v is String) m[k] = v
        return m
    }
    fun replaceAll(m: Map<String, String>) {
        val e = p.edit()
        for ((k, v) in m) e.putString(k, v)
        e.apply()
    }

    fun qbPort(): Int = get("pQb").trim().toIntOrNull() ?: 8080

    fun tablet(): String = get("url").trim().trimEnd('/')
    fun pin(): String = get("pin")

    private fun clean(h: String): String =
        h.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')

    fun hosts(): List<String> =
        listOf(clean(get("host")), clean(get("host2"))).filter { it.isNotEmpty() }.distinct()

    fun base(port: Int): String {
        val h = if (active.isNotEmpty()) active else (hosts().firstOrNull() ?: "localhost")
        return "http://$h:$port"
    }

    // Cherche le PC (local puis Tailscale) via Prowlarr ; retient l'hôte qui répond.
    fun probe(): Boolean {
        for (h in hosts()) {
            if (Net.pingHost(h, 9696)) { active = h; return true }
        }
        return false
    }

    // Teste si un port répond sur le PC (réseau local puis Tailscale) ; retient l'hôte qui répond.
    fun reach(port: Int): Boolean {
        for (h in hosts()) {
            try {
                val s = java.net.Socket()
                s.connect(java.net.InetSocketAddress(h, port), 2500)
                s.close()
                active = h
                return true
            } catch (e: Exception) {
            }
        }
        return false
    }

    fun ping(port: Int): Boolean = Net.pingHost(if (active.isNotEmpty()) active else (hosts().firstOrNull() ?: ""), port)
}

class Rel(
    val guid: String, val indexerId: Int, val indexer: String, val title: String,
    val size: Long, val seeders: Int, val date: String, val downloadUrl: String, val magnetUrl: String,
    val cats: List<Pair<Int, String>>
) {
    // Vrai pour films, séries, animés (et documentaires). Faux pour jeux, musique, logiciels, livres, adulte…
    fun isVideo(): Boolean {
        if (cats.isEmpty()) return true   // catégorie inconnue : on garde
        for (c in cats) {
            val n = c.second.lowercase()
            if (c.first in 6000..6999 || n.contains("xxx") || n.contains("adult") || n.contains("porn")) continue
            if (c.first in 2000..2999 || c.first in 5000..5999) return true
            if (n.contains("movie") || n.contains("film") || n.contains("tv") || n.contains("séri") ||
                n.contains("serie") || n.contains("anim") || n.contains("video") || n.contains("vidéo") ||
                n.contains("doc")) return true
        }
        return false
    }

    companion object {
        fun from(o: JSONObject): Rel {
            val cl = ArrayList<Pair<Int, String>>()
            val ca = o.optJSONArray("categories")
            if (ca != null) {
                for (i in 0 until ca.length()) {
                    val c = ca.optJSONObject(i) ?: continue
                    cl.add(Pair(c.optInt("id", 0), c.str("name")))
                }
            }
            return Rel(
                o.str("guid"), o.optInt("indexerId", 0), o.str("indexer"), o.str("title"),
                o.optLong("size", 0L), o.optInt("seeders", 0), o.str("publishDate"),
                o.str("downloadUrl"), o.str("magnetUrl"), cl
            )
        }
    }
}

fun posterUrl(o: JSONObject): String {
    val rp = o.str("remotePoster")
    if (rp.startsWith("http")) return rp
    val imgs = o.optJSONArray("images") ?: return ""
    for (i in 0 until imgs.length()) {
        val im = imgs.optJSONObject(i) ?: continue
        if (im.str("coverType") == "poster") {
            val u = im.str("remoteUrl")
            if (u.startsWith("http")) return u
        }
    }
    return ""
}

object Img {
    private val cache = LruCache<String, Bitmap>(40)
    private val ui = Handler(Looper.getMainLooper())

    fun load(url: String, v: ImageView) {
        if (url.isEmpty()) return
        v.tag = url
        val hit = cache.get(url)
        if (hit != null) { v.setImageBitmap(hit); return }
        thread {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 5000
                c.readTimeout = 10000
                val bmp = c.inputStream.use { BitmapFactory.decodeStream(it) }
                if (bmp != null) {
                    cache.put(url, bmp)
                    ui.post { if (v.tag == url) v.setImageBitmap(bmp) }
                }
            } catch (e: Exception) {
            }
        }
    }
}

package com.example.wolremote

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlin.concurrent.thread

class LinksTab(private val a: Activity, private val st: Settings) {
    val view = ScrollView(a)
    private val info = a.tv("", 13f, Pal.MUTED)

    init {
        val col = LinearLayout(a)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(a.dp(16), a.dp(16), a.dp(16), a.dp(16))
        view.addView(col)
        col.addView(a.title("Raccourcis"))
        col.addView(a.tv("Ouvre l'interface web dans le navigateur du téléphone (réseau local ou Tailscale, selon ce qui répond).", 13f, Pal.MUTED))
        col.addView(link("Prowlarr", 9696, Pal.PURPLE))
        col.addView(link("Radarr", 7878, Pal.AMBER))
        col.addView(link("Sonarr", 8989, Pal.BLUE))
        col.addView(link("qBittorrent", 8080, Pal.GREEN))
        info.setPadding(0, a.dp(14), 0, 0)
        col.addView(info)
    }

    private fun link(label: String, port: Int, color: Int): View =
        a.btn(label + "\nport " + port, color, 76) { open(label, port) }

    private fun open(label: String, port: Int) {
        info.text = "Recherche du PC…"
        thread {
            val ok = st.reach(port)
            a.runOnUiThread {
                if (!ok) {
                    info.text = "$label ne répond pas : PC éteint ou service arrêté. Utilise « Réveiller et chercher » dans l'onglet Recherche."
                } else {
                    val url = st.base(port)
                    info.text = "$label : $url"
                    try {
                        a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (e: Exception) {
                        info.text = "Impossible d'ouvrir le navigateur."
                    }
                }
            }
        }
    }
}

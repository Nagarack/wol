package com.example.wolremote

import android.app.Activity
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

class SettingsTab(private val a: Activity, private val st: Settings) {
    val view = ScrollView(a)

    init {
        val col = LinearLayout(a)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(a.dp(16), a.dp(16), a.dp(16), a.dp(24))
        view.addView(col)
        col.addView(a.title("Réglages"))
        col.addView(a.tv("Tout est stocké uniquement sur ce téléphone.", 13f, Pal.MUTED))

        val fields = LinkedHashMap<String, EditText>()
        fun add(label: String, key: String, type: Int) {
            val l = a.tv(label, 13f, Pal.MUTED)
            l.setPadding(0, a.dp(16), 0, 0)
            col.addView(l)
            val f = a.field("", st.get(key), type)
            fields[key] = f
            col.addView(f)
        }
        val uri = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        val pw = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val num = InputType.TYPE_CLASS_NUMBER

        col.addView(a.section("Wake-on-LAN"))
        add("Serveur Wake-on-LAN (tablette)", "url", uri)
        add("Code PIN", "pin", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        col.addView(a.section("Accès au PC"))
        add("PC : adresse sur le réseau local", "host", uri)
        add("PC : adresse Tailscale (IP ou nom)", "host2", uri)
        col.addView(a.section("Clés API"))
        add("Clé API Prowlarr", "kProwlarr", pw)
        add("Clé API Radarr", "kRadarr", pw)
        add("Clé API Sonarr", "kSonarr", pw)
        col.addView(a.section("Qualité"))
        add("Profil de qualité Radarr (ID, vide = le premier)", "pRadarr", num)
        add("Profil de qualité Sonarr (ID, vide = le premier)", "pSonarr", num)
        col.addView(a.section("qBittorrent"))
        add("qBittorrent : utilisateur", "kQbUser", InputType.TYPE_CLASS_TEXT)
        add("qBittorrent : mot de passe", "kQbPass", pw)
        col.addView(a.section("Consommation"))
        add("Prix du kWh en € (par défaut : tarif réglementé EDF Base)", "kTarifKwh", InputType.TYPE_CLASS_TEXT)

        col.addView(a.btn("Enregistrer", Pal.GREEN) {
            for ((k, f) in fields) st.put(k, f.text.toString().trim())
            st.active = ""
            Toast.makeText(a, "Réglages enregistrés", Toast.LENGTH_SHORT).show()
        })
    }
}

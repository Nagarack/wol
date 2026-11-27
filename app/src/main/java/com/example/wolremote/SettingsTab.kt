package com.example.wolremote

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import org.json.JSONObject
import kotlin.concurrent.thread

class SettingsTab(private val a: Activity, private val st: Settings) {
    val view = ScrollView(a)
    private val fields = LinkedHashMap<String, EditText>()
    private var pendingBytes: ByteArray? = null

    init {
        val col = LinearLayout(a)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(a.dp(16), a.dp(16), a.dp(16), a.dp(24))
        view.addView(col)
        col.addView(a.title("Réglages"))
        col.addView(a.tv("Tout est stocké uniquement sur ce téléphone.", 13f, Pal.MUTED))

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
        add("qBittorrent : port (8080 par défaut)", "pQb", num)
        add("qBittorrent : utilisateur", "kQbUser", InputType.TYPE_CLASS_TEXT)
        add("qBittorrent : mot de passe", "kQbPass", pw)
        col.addView(a.section("Jellyfin"))
        add("Clé API Jellyfin (Tableau de bord, Clés API)", "kJellyfin", pw)
        col.addView(a.section("Consommation"))
        add("Prix du kWh en € (par défaut : tarif réglementé EDF Base)", "kTarifKwh", InputType.TYPE_CLASS_TEXT)

        col.addView(a.btn("Enregistrer", Pal.GREEN) {
            saveAll()
            Toast.makeText(a, "Réglages enregistrés", Toast.LENGTH_SHORT).show()
        })

        col.addView(a.section("Notifications"))
        col.addView(a.tv("Prévient quand un téléchargement est terminé, même appli fermée, et actualise Jellyfin. Une notification discrète reste affichée tant que c'est actif.", 13f, Pal.MUTED))
        val watch = a.btn("", Pal.BLUE) {}
        fun label() { watch.text = if (st.get("kWatch") == "1") "Activées (touche pour désactiver)" else "Désactivées (touche pour activer)" }
        label()
        watch.setOnClickListener {
            if (st.get("kWatch") == "1") {
                st.put("kWatch", "0")
                WatchService.stop(a)
            } else {
                st.put("kWatch", "1")
                if (Build.VERSION.SDK_INT >= 33 && a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    a.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 5)
                }
                saveAll()
                WatchService.start(a)
            }
            label()
        }
        col.addView(watch)

        col.addView(a.section("Maison"))
        col.addView(a.tv("Allume le PC quand ton téléphone rejoint le Wi-Fi de la maison, et te propose de l'éteindre quand tu en pars. Demande la surveillance en arrière-plan (Notifications).", 13f, Pal.MUTED))
        col.addView(a.btn2("Définir le Wi-Fi actuel comme maison") {
            val g = wifiGateway(a)
            if (g.isEmpty()) Toast.makeText(a, "Connecte-toi d'abord au Wi-Fi de la maison", Toast.LENGTH_LONG).show()
            else { st.put("kHomeGw", g); Toast.makeText(a, "Wi-Fi maison enregistré", Toast.LENGTH_SHORT).show() }
        })
        val geo = a.btn("", Pal.BLUE) {}
        fun geoLabel() { geo.text = if (st.get("kGeo") == "1") "Arrivée / départ : activé" else "Arrivée / départ : désactivé" }
        geoLabel()
        geo.setOnClickListener {
            if (st.get("kGeo") == "1") st.put("kGeo", "0")
            else if (st.get("kHomeGw").isEmpty()) { Toast.makeText(a, "Définis d'abord le Wi-Fi maison", Toast.LENGTH_LONG).show(); return@setOnClickListener }
            else { st.put("kGeo", "1"); st.put("kWatch", "1"); label() }
            WatchService.stop(a)
            if (st.get("kWatch") == "1" && st.get("kVac") != "1") WatchService.start(a)
            geoLabel()
        }
        col.addView(geo)

        col.addView(a.section("Mode vacances"))
        col.addView(a.tv("Coupe la surveillance en arrière-plan et les alertes de la tablette (températures, disque, PC allumé la nuit).", 13f, Pal.MUTED))
        val vacB = a.btn2("") {}
        fun vacLabel() { vacB.text = if (st.get("kVac") == "1") "Mode vacances : activé" else "Mode vacances : désactivé" }
        vacLabel()
        vacB.setOnClickListener {
            val on = st.get("kVac") != "1"
            st.put("kVac", if (on) "1" else "0")
            if (on) WatchService.stop(a) else if (st.get("kWatch") == "1") WatchService.start(a)
            thread {
                try { Net.call("POST", st.tablet() + "/vacation", json = JSONObject().put("pin", st.pin()).put("on", on).toString(), timeout = 10000) } catch (e: Exception) { }
            }
            vacLabel()
        }
        col.addView(vacB)

        col.addView(a.section("Sauvegarde"))
        col.addView(a.tv("Exporte tes réglages (clés API, PIN…) dans un fichier protégé par un mot de passe, pour les retrouver après une réinstallation.", 13f, Pal.MUTED))
        col.addView(a.btn("Exporter mes réglages", Pal.GRAY) {
            saveAll()
            askPass("Protège la sauvegarde par un mot de passe (à retenir).") { pass ->
                pendingBytes = Backup.export(st, pass)
                val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
                i.addCategory(Intent.CATEGORY_OPENABLE)
                i.type = "application/octet-stream"
                i.putExtra(Intent.EXTRA_TITLE, "monpc-reglages.bin")
                a.startActivityForResult(i, 71)
            }
        })
        col.addView(a.btn("Importer des réglages", Pal.GRAY) {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "*/*"
            a.startActivityForResult(i, 72)
        })
    }

    private fun saveAll() {
        for ((k, f) in fields) st.put(k, f.text.toString().trim())
        st.active = ""
    }

    private fun askPass(msg: String, ok: (String) -> Unit) {
        val f = a.field("Mot de passe", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        a.dialogBuilder().setTitle("Mot de passe").setMessage(msg).setView(f)
            .setPositiveButton("OK") { _, _ ->
                val p = f.text.toString()
                if (p.length < 4) Toast.makeText(a, "4 caractères minimum", Toast.LENGTH_SHORT).show() else ok(p)
            }
            .setNegativeButton("Annuler", null).show()
    }

    // Appelé par MainActivity.onActivityResult
    fun onResult(req: Int, res: Int, data: Intent?) {
        val uri = data?.data
        if (res != Activity.RESULT_OK || uri == null) return
        try {
            if (req == 71) {
                val b = pendingBytes ?: return
                a.contentResolver.openOutputStream(uri)?.use { it.write(b) }
                pendingBytes = null
                Toast.makeText(a, "Sauvegarde enregistrée", Toast.LENGTH_LONG).show()
            } else if (req == 72) {
                val bytes = a.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return
                askPass("Mot de passe de la sauvegarde.") { pass ->
                    val err = Backup.import(st, bytes, pass)
                    if (err.isEmpty()) {
                        Toast.makeText(a, "Réglages importés", Toast.LENGTH_LONG).show()
                        a.recreate()
                    } else {
                        Toast.makeText(a, err, Toast.LENGTH_LONG).show()
                    }
                }
            }
        } catch (e: Exception) {
            Toast.makeText(a, "Erreur : " + e.message, Toast.LENGTH_LONG).show()
        }
    }
}

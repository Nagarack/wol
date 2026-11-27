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
        val root = LinearLayout(a)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(a.dp(16), a.dp(16), a.dp(16), a.dp(24))
        view.addView(root)
        var col = root
        // Chaque rubrique est un groupe repliable : l'écran reste court
        fun grp(title: String) {
            val body = LinearLayout(a)
            body.orientation = LinearLayout.VERTICAL
            body.visibility = android.view.View.GONE
            val head = a.rowItem(title, "", "+") {}
            val mark = head.getChildAt(1) as android.widget.TextView
            head.setOnClickListener {
                val open = body.visibility == android.view.View.GONE
                body.visibility = if (open) android.view.View.VISIBLE else android.view.View.GONE
                mark.text = if (open) "−" else "+"
            }
            root.addView(head)
            root.addView(body)
            col = body
        }
        col.addView(a.title("Réglages"))
        col.addView(a.tv("Tout est stocké uniquement sur ce téléphone. Les modifications sont enregistrées en quittant l'onglet.", 13f, Pal.MUTED))

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

        grp("Wake-on-LAN")
        add("Serveur Wake-on-LAN (tablette)", "url", uri)
        add("Code PIN", "pin", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        grp("Accès au PC")
        add("PC : adresse sur le réseau local", "host", uri)
        add("PC : adresse Tailscale (IP ou nom)", "host2", uri)
        grp("Clés API")
        add("Clé API Prowlarr", "kProwlarr", pw)
        add("Clé API Radarr", "kRadarr", pw)
        add("Clé API Sonarr", "kSonarr", pw)
        grp("Qualité")
        add("Profil de qualité Radarr (ID, vide = le premier)", "pRadarr", num)
        add("Profil de qualité Sonarr (ID, vide = le premier)", "pSonarr", num)
        grp("qBittorrent")
        add("qBittorrent : port (8080 par défaut)", "pQb", num)
        add("qBittorrent : utilisateur", "kQbUser", InputType.TYPE_CLASS_TEXT)
        add("qBittorrent : mot de passe", "kQbPass", pw)
        grp("Jellyfin")
        add("Clé API Jellyfin (Tableau de bord, Clés API)", "kJellyfin", pw)
        grp("Consommation")
        add("Prix du kWh en € (par défaut : tarif réglementé EDF Base)", "kTarifKwh", InputType.TYPE_CLASS_TEXT)

        col = root
        col.addView(a.btn("Enregistrer", Pal.GREEN) {
            saveAll()
            Toast.makeText(a, "Réglages enregistrés", Toast.LENGTH_SHORT).show()
        })

        grp("Notifications")
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

        grp("Maison")
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

        grp("Mode vacances")
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

        grp("Serveur (tablette et PC)")
        col.addView(a.tv("Met à jour le programme de la tablette et l'agent du PC depuis GitHub, sans copier de fichier.", 13f, Pal.MUTED))
        val srv = a.tv("", 14f, Pal.FG)
        srv.setPadding(0, a.dp(10), 0, 0)
        col.addView(srv)
        fun vtxt(v: Int?) = if (v == null) "ancienne version" else "v$v"
        fun checkSrv() {
            srv.text = "Vérification…"
            thread {
                val t = try {
                    val s = ServerUpdate.check(st)
                    "Dernière : v" + s.latest + "\nTablette : " + vtxt(s.tablet) + (if (s.tabletOld) "  (à mettre à jour)" else "  (à jour)") +
                        "\nAgent du PC : " + vtxt(s.agent) + (if (s.agentOld) "  (à mettre à jour)" else "  (à jour)")
                } catch (e: Exception) { "Vérification impossible : " + e.message }
                a.runOnUiThread { srv.text = t }
            }
        }
        col.addView(a.btn2("Vérifier les versions") { checkSrv() })
        col.addView(a.btn2("Mettre à jour la tablette") {
            srv.text = "Mise à jour de la tablette…"
            thread {
                val m = ServerUpdate.updateTablet(st)
                a.runOnUiThread { srv.text = m }
            }
        })
        col.addView(a.btn2("Mettre à jour l'agent du PC") {
            srv.text = "Mise à jour de l'agent…"
            thread {
                val m = ServerUpdate.updateAgent(st)
                a.runOnUiThread { srv.text = m }
            }
        })

        grp("Sauvegarde")
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

    fun persist() = saveAll()

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

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
        root.setPadding(a.dp(20), a.dp(20), a.dp(20), a.dp(28))
        view.addView(root)
        var col = root
        val bodies = ArrayList<LinearLayout>()
        val refreshers = ArrayList<() -> Unit>()
        fun refreshAll() = refreshers.forEach { it() }

        // Chaque rubrique est une carte repliable avec son état en sous-titre
        fun grp(title: String, keys: List<String> = emptyList(), status: (() -> Pair<String, Int>)? = null) {
            val card = LinearLayout(a)
            card.orientation = LinearLayout.VERTICAL
            card.background = a.rounded(Pal.CARD, 16, Pal.BORDER)
            card.layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).also { it.topMargin = a.dp(10) }
            val head = LinearLayout(a)
            head.orientation = LinearLayout.HORIZONTAL
            head.gravity = android.view.Gravity.CENTER_VERTICAL
            head.setPadding(a.dp(16), a.dp(14), a.dp(16), a.dp(14))
            head.background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(android.graphics.Color.argb(40, 255, 255, 255)), null, a.rounded(Pal.CARD, 16))
            val tcol = LinearLayout(a)
            tcol.orientation = LinearLayout.VERTICAL
            val tt = a.tv(title, 16f, Pal.FG)
            tt.typeface = android.graphics.Typeface.DEFAULT_BOLD
            val subTv = a.tv("", 13f, Pal.MUTED)
            tcol.addView(tt)
            tcol.addView(subTv)
            val mark = a.tv("+", 22f, Pal.MUTED)
            head.addView(tcol, LinearLayout.LayoutParams(0, WRAP, 1f))
            head.addView(mark)
            val body = LinearLayout(a)
            body.orientation = LinearLayout.VERTICAL
            body.setPadding(a.dp(16), 0, a.dp(16), a.dp(16))
            body.visibility = android.view.View.GONE
            card.addView(head)
            card.addView(body)
            val upd: () -> Unit = {
                if (keys.isNotEmpty()) {
                    val n = keys.count { (fields[it]?.text?.toString() ?: st.get(it)).trim().isNotEmpty() }
                    subTv.text = if (n == keys.size) "Renseigné" else if (n == 0) "À renseigner" else "$n / ${keys.size} renseignés"
                    subTv.setTextColor(if (n == keys.size) Pal.GREEN else Pal.MUTED)
                } else if (status != null) {
                    val (t, c) = status()
                    subTv.text = t
                    subTv.setTextColor(c)
                } else subTv.visibility = android.view.View.GONE
            }
            refreshers.add(upd)
            upd()
            head.setOnClickListener {
                val open = body.visibility == android.view.View.GONE
                body.visibility = if (open) android.view.View.VISIBLE else android.view.View.GONE
                mark.text = if (open) "−" else "+"
                refreshAll()
            }
            root.addView(card)
            bodies.add(body)
            col = body
        }
        fun heading(t: String) {
            val h = a.section(t)
            (h.layoutParams as? LinearLayout.LayoutParams)?.topMargin = a.dp(20)
            root.addView(h)
        }
        fun note(t: String) {
            val n = a.tv(t, 13f, Pal.MUTED)
            n.setPadding(0, a.dp(2), 0, a.dp(4))
            col.addView(n)
        }
        fun add(label: String, key: String, type: Int) {
            val l = a.tv(label, 12f, Pal.MUTED)
            l.setPadding(a.dp(2), a.dp(14), 0, 0)
            col.addView(l)
            val f = a.field("", st.get(key), type)
            f.setOnFocusChangeListener { _, has -> if (!has) { saveAll(); refreshAll() } }
            fields[key] = f
            col.addView(f)
        }
        // Interrupteur avec titre et explication ; onChange renvoie false pour refuser le changement
        fun toggle(title: String, desc: String, on: Boolean, onChange: (Boolean) -> Boolean) {
            val r = LinearLayout(a)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = android.view.Gravity.CENTER_VERTICAL
            r.setPadding(0, a.dp(14), 0, 0)
            val t = LinearLayout(a)
            t.orientation = LinearLayout.VERTICAL
            t.addView(a.tv(title, 15f, Pal.FG))
            if (desc.isNotEmpty()) t.addView(a.tv(desc, 12f, Pal.MUTED))
            r.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
            val sw = android.widget.Switch(a)
            sw.isChecked = on
            sw.trackTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Pal.BLUE, Pal.BORDER))
            sw.thumbTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
            var guard = false
            sw.setOnCheckedChangeListener { _, v ->
                if (guard) return@setOnCheckedChangeListener
                if (!onChange(v)) { guard = true; sw.isChecked = !v; guard = false }
                refreshAll()
            }
            r.addView(sw)
            col.addView(r)
        }
        fun action(label: String, primary: Boolean = false, f: () -> Unit) {
            col.addView(if (primary) a.btn(label, Pal.BLUE, 48) { f() } else a.btn2(label, Pal.FG, 48) { f() })
        }

        col.addView(a.title("Réglages"))
        col.addView(a.tv("Tout est enregistré automatiquement, uniquement sur ce téléphone.", 13f, Pal.MUTED))

        val uri = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        val pw = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val num = InputType.TYPE_CLASS_NUMBER

        heading("Connexion")
        grp("Wake-on-LAN", listOf("url", "pin"))
        add("Serveur Wake-on-LAN (tablette)", "url", uri)
        add("Code PIN", "pin", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        grp("Accès au PC", listOf("host", "host2"))
        add("Adresse sur le réseau local", "host", uri)
        add("Adresse Tailscale (IP ou nom)", "host2", uri)

        grp("Télévision Samsung", listOf("kTvIp"))
        note("Permet de régler le volume, le muet et d'éteindre la télé depuis la télécommande. Sur la télé : Réglages, Général, Gestionnaire de périphériques externes, Gestionnaire de connexion des appareils, mets « Notification d'accès » sur « Première fois seulement ». Au premier envoi, accepte la demande qui s'affiche à l'écran. Pour pouvoir la rallumer, active aussi « Mise sous tension via réseau » (Réglages, Général, Réseau, Paramètres expert) : l'adresse MAC est retenue automatiquement après une première commande.")
        add("Adresse IP de la télé (ex : 192.168.1.40)", "kTvIp", uri)
        add("Adresse MAC de la télé (facultative, pour la rallumer)", "kTvMac", InputType.TYPE_CLASS_TEXT)

        heading("Services")
        grp("Clés API", listOf("kProwlarr", "kRadarr", "kSonarr", "kTmdb", "kJellyfin"))
        add("Prowlarr", "kProwlarr", pw)
        add("Radarr", "kRadarr", pw)
        add("Sonarr", "kSonarr", pw)
        add("Jellyfin (Tableau de bord, Clés API)", "kJellyfin", pw)
        add("TMDb (facultative, pour Découvrir)", "kTmdb", pw)
        grp("qBittorrent", listOf("kQbUser", "kQbPass"))
        add("Port (8080 par défaut)", "pQb", num)
        add("Utilisateur", "kQbUser", InputType.TYPE_CLASS_TEXT)
        add("Mot de passe", "kQbPass", pw)
        grp("Qualité", status = { Pair(if (st.get("pRadarr").isEmpty() && st.get("pSonarr").isEmpty()) "Profil par défaut" else "Personnalisée", Pal.MUTED) })
        note("Identifiant du profil de qualité à utiliser. Vide : le premier profil.")
        add("Profil Radarr (ID)", "pRadarr", num)
        add("Profil Sonarr (ID)", "pSonarr", num)
        grp("Consommation", status = { Pair((st.get("kTarifKwh").ifEmpty { "0,20" }) + " € / kWh", Pal.MUTED) })
        add("Prix du kWh en € (par défaut : tarif EDF Base)", "kTarifKwh", InputType.TYPE_CLASS_TEXT)

        heading("Automatisations")
        grp("Notifications", status = { Pair(if (st.get("kWatch") == "1") "Activées" else "Désactivées", if (st.get("kWatch") == "1") Pal.GREEN else Pal.MUTED) })
        note("Prévient quand un téléchargement est terminé, même appli fermée, et actualise Jellyfin. Une notification discrète reste affichée tant que c'est actif.")
        toggle("Surveillance en arrière-plan", "", st.get("kWatch") == "1") { on ->
            if (on) {
                st.put("kWatch", "1")
                if (Build.VERSION.SDK_INT >= 33 && a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    a.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 5)
                }
                saveAll()
                WatchService.start(a)
            } else {
                st.put("kWatch", "0")
                WatchService.stop(a)
            }
            true
        }
        grp("Maison", status = { Pair(if (st.get("kGeo") == "1") "Arrivée / départ activé" else if (st.get("kHomeGw").isNotEmpty()) "Wi-Fi défini" else "À configurer", if (st.get("kGeo") == "1") Pal.GREEN else Pal.MUTED) })
        note("Allume le PC quand ton téléphone rejoint le Wi-Fi de la maison, et te propose de l'éteindre quand tu en pars. Demande la surveillance en arrière-plan.")
        action("Définir le Wi-Fi actuel comme maison") {
            val g = wifiGateway(a)
            if (g.isEmpty()) Toast.makeText(a, "Connecte-toi d'abord au Wi-Fi de la maison", Toast.LENGTH_LONG).show()
            else { st.put("kHomeGw", g); refreshAll(); Toast.makeText(a, "Wi-Fi maison enregistré", Toast.LENGTH_SHORT).show() }
        }
        toggle("Arrivée / départ", "Allumer et éteindre selon le Wi-Fi", st.get("kGeo") == "1") { on ->
            if (on && st.get("kHomeGw").isEmpty()) {
                Toast.makeText(a, "Définis d'abord le Wi-Fi maison", Toast.LENGTH_LONG).show()
                false
            } else {
                st.put("kGeo", if (on) "1" else "0")
                if (on) st.put("kWatch", "1")
                WatchService.stop(a)
                if (st.get("kWatch") == "1" && st.get("kVac") != "1") WatchService.start(a)
                true
            }
        }
        grp("Mode vacances", status = { Pair(if (st.get("kVac") == "1") "Activé" else "Désactivé", if (st.get("kVac") == "1") Pal.AMBER else Pal.MUTED) })
        note("Coupe la surveillance en arrière-plan et les alertes de la tablette (températures, disque, PC allumé la nuit).")
        toggle("Mode vacances", "", st.get("kVac") == "1") { on ->
            st.put("kVac", if (on) "1" else "0")
            if (on) WatchService.stop(a) else if (st.get("kWatch") == "1") WatchService.start(a)
            thread {
                try { Net.call("POST", st.tablet() + "/vacation", json = JSONObject().put("pin", st.pin()).put("on", on).toString(), timeout = 10000) } catch (e: Exception) { }
            }
            true
        }

        heading("Maintenance")
        grp("Serveur (tablette et PC)", status = { Pair("Mises à jour depuis GitHub", Pal.MUTED) })
        note("Met à jour le programme de la tablette et l'agent du PC, sans copier de fichier.")
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
        action("Vérifier les versions") { checkSrv() }
        action("Mettre à jour la tablette") {
            srv.text = "Mise à jour de la tablette…"
            thread {
                val m = ServerUpdate.updateTablet(st)
                a.runOnUiThread { srv.text = m }
            }
        }
        action("Mettre à jour l'agent du PC") {
            srv.text = "Mise à jour de l'agent…"
            thread {
                val m = ServerUpdate.updateAgent(st)
                a.runOnUiThread { srv.text = m }
            }
        }

        grp("Sauvegarde", status = { Pair("Fichier protégé par mot de passe", Pal.MUTED) })
        note("Exporte tes réglages (clés API, PIN…) pour les retrouver après une réinstallation.")
        action("Exporter mes réglages") {
            saveAll()
            askPass("Protège la sauvegarde par un mot de passe (à retenir).") { pass ->
                pendingBytes = Backup.export(st, pass)
                val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
                i.addCategory(Intent.CATEGORY_OPENABLE)
                i.type = "application/octet-stream"
                i.putExtra(Intent.EXTRA_TITLE, "monpc-reglages.bin")
                a.startActivityForResult(i, 71)
            }
        }
        action("Importer des réglages") {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "*/*"
            a.startActivityForResult(i, 72)
        }
        for (b in bodies) a.compactButtons(b)
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

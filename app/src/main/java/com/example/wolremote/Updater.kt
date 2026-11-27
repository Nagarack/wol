package com.example.wolremote

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

// Mise à jour automatique depuis les Releases GitHub du dépôt (public).
class Updater(private val a: Activity) {
    private val repo = "Nagarack/wol"
    private val action = "com.example.wolremote.INSTALL_DONE"
    private var checked = false
    private var registered = false

    private val rx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                @Suppress("DEPRECATION")
                val next = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (next != null) {
                    next.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    a.startActivity(next)
                }
            } else if (st != PackageInstaller.STATUS_SUCCESS) {
                val m = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                a.runOnUiThread { toast("Échec de l'installation : $m") }
            }
        }
    }

    private fun toast(t: String) = android.widget.Toast.makeText(a, t, android.widget.Toast.LENGTH_LONG).show()

    @Suppress("DEPRECATION")
    private fun myVersion(): Int = a.packageManager.getPackageInfo(a.packageName, 0).versionCode

    fun check() {
        if (checked) return
        checked = true
        thread {
            try {
                val r = Net.call("GET", "https://api.github.com/repos/$repo/releases/latest", timeout = 10000)
                if (!r.ok) return@thread
                val o = JSONObject(r.body)
                val v = o.optString("tag_name").removePrefix("v").toIntOrNull() ?: return@thread
                if (v <= myVersion()) return@thread
                val assets = o.optJSONArray("assets") ?: return@thread
                var url = ""
                for (i in 0 until assets.length()) {
                    val x = assets.optJSONObject(i) ?: continue
                    if (x.optString("name").endsWith(".apk")) url = x.optString("browser_download_url")
                }
                if (url.isEmpty()) return@thread
                a.runOnUiThread {
                    a.dialogBuilder()
                        .setTitle("Mise à jour disponible")
                        .setMessage("Une nouvelle version de Mon PC (v$v) est disponible. Tes réglages sont conservés.")
                        .setPositiveButton("Mettre à jour") { _, _ -> download(url) }
                        .setNegativeButton("Plus tard", null)
                        .show()
                }
            } catch (e: Exception) {
            }
        }
    }

    private fun download(url: String) {
        if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) {
            toast("Autorise « Installer des applis inconnues » pour Mon PC, puis reviens et relance la mise à jour.")
            a.startActivity(Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.packageName)))
            checked = false
            return
        }
        toast("Téléchargement…")
        thread {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 10000
                c.readTimeout = 30000
                c.instanceFollowRedirects = true
                val pi = a.packageManager.packageInstaller
                val id = pi.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
                val s = pi.openSession(id)
                s.openWrite("MonPC", 0, -1).use { out ->
                    c.inputStream.use { it.copyTo(out) }
                    s.fsync(out)
                }
                c.disconnect()
                a.runOnUiThread {
                    if (!registered) {
                        val f = IntentFilter(action)
                        if (Build.VERSION.SDK_INT >= 33) a.registerReceiver(rx, f, Context.RECEIVER_NOT_EXPORTED) else a.registerReceiver(rx, f)
                        registered = true
                    }
                    val pend = PendingIntent.getBroadcast(
                        a, id, Intent(action).setPackage(a.packageName),
                        PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    )
                    s.commit(pend.intentSender)
                    s.close()
                }
            } catch (e: Exception) {
                a.runOnUiThread { toast("Téléchargement impossible : " + e.message) }
            }
        }
    }
}

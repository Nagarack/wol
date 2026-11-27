package com.example.wolremote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.IBinder
import org.json.JSONObject
import kotlin.concurrent.thread

// Surveille qBittorrent en arrière-plan : notification à chaque téléchargement terminé,
// puis actualisation forcée de Jellyfin.
class WatchService : Service() {
    @Volatile private var running = false
    private var counter = 0
    private var cb: ConnectivityManager.NetworkCallback? = null
    @Volatile private var atHome = false
    private var lastArrive = 0L

    override fun onBind(i: Intent?): IBinder? = null

    private fun builder(channel: String): Notification.Builder =
        if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channel)
        else @Suppress("DEPRECATION") Notification.Builder(this)

    private fun openApp(): PendingIntent =
        PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("watch", "Surveillance en arrière-plan", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel("done", "Téléchargements terminés", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val n = builder("watch").setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Mon PC").setContentText("Surveillance des téléchargements active")
            .setContentIntent(openApp()).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(1, n)
        if (cb == null && Settings(this).get("kGeo") == "1") registerHome()
        if (!running) {
            running = true
            thread { loop() }
        }
        return START_STICKY
    }

    private fun loop() {
        val st = Settings(this)
        val client = QbClient(st)
        client.onComplete = { names ->
            val nm = getSystemService(NotificationManager::class.java)
            for (name in names) {
                counter++
                val n = builder("done").setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("Téléchargement terminé").setContentText(name)
                    .setContentIntent(openApp()).setAutoCancel(true).build()
                nm.notify(1000 + counter, n)
            }
            // Laisse à Radarr/Sonarr le temps d'importer, puis force Jellyfin
            thread {
                Thread.sleep(90000)
                Jellyfin.refresh(st)
                Thread.sleep(120000)
                Jellyfin.refresh(st)
                Subs.autoRecent(st)
            }
        }
        var lastDisk = 0L
        client.onStalled = { names ->
            val nm = getSystemService(NotificationManager::class.java)
            for (name in names) {
                counter++
                val n = builder("done").setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle("Téléchargement bloqué").setContentText(name + " n'avance plus depuis 30 min")
                    .setContentIntent(openApp()).setAutoCancel(true).build()
                nm.notify(1000 + counter, n)
            }
        }
        while (running) {
            val now = System.currentTimeMillis()
            if (now - lastDisk > 1800000L) {
                lastDisk = now
                val msg = try { Storage.lowAlert(st) } catch (e: Exception) { null }
                if (msg != null && now - st.get("kDiskNotif").toLongOrNull().let { it ?: 0L } > 86400000L) {
                    st.put("kDiskNotif", now.toString())
                    val n = builder("done").setSmallIcon(android.R.drawable.stat_notify_error)
                        .setContentTitle("Espace disque faible").setContentText(msg)
                        .setContentIntent(openApp()).setAutoCancel(true).build()
                    getSystemService(NotificationManager::class.java).notify(900, n)
                }
            }
            val r = client.torrents()
            try {
                Thread.sleep(if (r.err.isEmpty()) 15000L else 60000L)
            } catch (e: InterruptedException) {
            }
        }
    }

    private fun pcOnline(st: Settings): Boolean = try {
        JSONObject(Net.call("GET", st.tablet() + "/status", timeout = 6000).body).getBoolean("online")
    } catch (e: Exception) { false }

    private fun say(title: String, text: String, action: String? = null) {
        counter++
        val pi = if (action == null) openApp() else PendingIntent.getActivity(this, counter,
            Intent(this, MainActivity::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = builder("done").setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle(title).setContentText(text)
            .setContentIntent(pi).setAutoCancel(true).build()
        getSystemService(NotificationManager::class.java).notify(2000 + counter, n)
    }

    // Arrivée / départ de la maison, d'après le Wi-Fi (pas besoin de localisation)
    private fun registerHome() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val c = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) {
                thread {
                    Thread.sleep(3000)
                    val st = Settings(this@WatchService)
                    val home = st.get("kHomeGw")
                    if (home.isEmpty() || wifiGateway(this@WatchService) != home) return@thread
                    atHome = true
                    val now = System.currentTimeMillis()
                    if (now - lastArrive < 3600000L) return@thread
                    lastArrive = now
                    if (!pcOnline(st)) {
                        try {
                            Net.call("POST", st.tablet() + "/wake", json = JSONObject().put("pin", st.pin()).toString(), timeout = 15000)
                            say("Bienvenue", "Le PC est en train de s'allumer.")
                        } catch (e: Exception) { }
                    }
                }
            }
            override fun onLost(n: Network) {
                thread {
                    if (!atHome) return@thread
                    atHome = false
                    Thread.sleep(120000)
                    if (atHome) return@thread
                    val st = Settings(this@WatchService)
                    if (pcOnline(st)) say("Tu es parti", "Le PC est toujours allumé. Touche pour l'éteindre.", "com.example.wolremote.SHUTDOWN")
                }
            }
        }
        cb = c
        try {
            cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), c)
        } catch (e: Exception) { cb = null }
    }

    override fun onDestroy() {
        try { cb?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } } catch (e: Exception) { }
        cb = null
        running = false
        super.onDestroy()
    }

    companion object {
        fun start(c: Context) {
            val i = Intent(c, WatchService::class.java)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, WatchService::class.java))
        }
    }
}

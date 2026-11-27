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
import android.os.IBinder
import kotlin.concurrent.thread

// Surveille qBittorrent en arrière-plan : notification à chaque téléchargement terminé,
// puis actualisation forcée de Jellyfin.
class WatchService : Service() {
    @Volatile private var running = false
    private var counter = 0

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
            }
        }
        var lastDisk = 0L
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

    override fun onDestroy() {
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

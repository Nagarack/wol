package com.example.wolremote

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var msg: TextView
    private lateinit var game: TextView
    private lateinit var urlInput: EditText
    private lateinit var pinInput: EditText
    private val handler = Handler(Looper.getMainLooper())
    // Thème sombre : toujours actif
    private val dark = true
    private fun dialog() = AlertDialog.Builder(
        this,
        if (dark) AlertDialog.THEME_DEVICE_DEFAULT_DARK else AlertDialog.THEME_DEVICE_DEFAULT_LIGHT
    )
    private val prefs by lazy { getSharedPreferences("wol", Context.MODE_PRIVATE) }

    private val poll = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 4000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(px(28), px(28), px(28), px(28))
            setBackgroundColor(Color.parseColor(if (dark) "#1C1917" else "#F5F5F4"))
        }

        root.addView(TextView(this).apply {
            text = "Mon PC"; textSize = 28f; gravity = Gravity.CENTER
            setTextColor(Color.parseColor(if (dark) "#FAFAF9" else "#1C1917"))
        })
        status = TextView(this).apply {
            text = "Vérification…"; textSize = 16f; gravity = Gravity.CENTER
            setPadding(0, px(6), 0, px(4))
        }
        root.addView(status)
        game = TextView(this).apply {
            text = "● Chroniques …"; textSize = 16f; gravity = Gravity.CENTER
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, px(24))
        }
        root.addView(game)

        urlInput = EditText(this).apply {
            hint = "Serveur (ex : http://100.64.0.5:8080)"
            setText(prefs.getString("url", "http://100.124.61.8:8080"))
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            setTextColor(Color.parseColor(if (dark) "#FAFAF9" else "#1C1917"))
            setHintTextColor(Color.parseColor(if (dark) "#A8A29E" else "#78716C"))
        }
        pinInput = EditText(this).apply {
            hint = "Code PIN"
            setText(prefs.getString("pin", "395474"))
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setTextColor(Color.parseColor(if (dark) "#FAFAF9" else "#1C1917"))
            setHintTextColor(Color.parseColor(if (dark) "#A8A29E" else "#78716C"))
        }
        root.addView(urlInput)
        root.addView(pinInput)

        fun bigButton(label: String, color: String, onClick: () -> Unit) = Button(this).apply {
            text = label; textSize = 18f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor(color))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, px(64)
            ).apply { topMargin = px(14) }
        }
        root.addView(bigButton("⏻  Allumer", "#16A34A") { act("/wake") })
        root.addView(bigButton("Éteindre", "#DC2626") {
            dialog()
                .setMessage("Éteindre le PC ?")
                .setPositiveButton("Éteindre") { _, _ -> act("/shutdown") }
                .setNegativeButton("Annuler", null)
                .show()
        })

        root.addView(bigButton("Lancer Chroniques", "#7C3AED") { act("/game/start") })
        root.addView(bigButton("Arrêter Chroniques", "#52525B") {
            dialog()
                .setMessage("Arrêter le serveur Chroniques ?")
                .setPositiveButton("Arrêter") { _, _ -> act("/game/stop") }
                .setNegativeButton("Annuler", null)
                .show()
        })

        msg = TextView(this).apply {
            gravity = Gravity.CENTER; setPadding(0, px(18), 0, 0)
            setTextColor(Color.GRAY)
        }
        root.addView(msg)
        setContentView(root)
        if (dark) {
            window.statusBarColor = Color.parseColor("#1C1917")
            window.navigationBarColor = Color.parseColor("#1C1917")
        }
    }

    override fun onResume() { super.onResume(); handler.post(poll) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(poll) }

    private fun base() = urlInput.text.toString().trim().trimEnd('/')

    private fun request(path: String, post: Boolean, pin: String = ""): JSONObject {
        val conn = URL(base() + path).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 8000
        try {
            if (post) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.outputStream.use { it.write(JSONObject().put("pin", pin).toString().toByteArray()) }
            }
            val stream = if (conn.responseCode < 400) conn.inputStream else conn.errorStream
            return JSONObject(stream.bufferedReader().readText())
        } finally {
            conn.disconnect()
        }
    }

    private fun refresh() {
        if (base().isEmpty()) { status.text = "Renseigne l'adresse du serveur"; return }
        thread {
            try {
                val online = request("/status", false).getBoolean("online")
                runOnUiThread {
                    status.text = if (online) "● Allumé" else "● Éteint"
                    status.setTextColor(Color.parseColor(if (online) "#16A34A" else "#DC2626"))
                }
                val running = try { request("/game/status", false).getBoolean("running") } catch (e: Exception) { false }
                runOnUiThread {
                    game.text = if (running) "● Chroniques allumé" else "● Chroniques éteint"
                    game.setTextColor(Color.parseColor(if (running) "#16A34A" else "#DC2626"))
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Serveur injoignable"
                    status.setTextColor(Color.GRAY)
                    game.text = "● Chroniques …"
                    game.setTextColor(Color.GRAY)
                }
            }
        }
    }

    private fun act(path: String) {
        prefs.edit().putString("url", base()).putString("pin", pinInput.text.toString()).apply()
        msg.text = "…"
        thread {
            val text = try {
                request(path, true, pinInput.text.toString()).optString("message", "OK")
            } catch (e: Exception) {
                "Erreur : ${e.message}"
            }
            runOnUiThread { msg.text = text }
            Thread.sleep(2000)
            runOnUiThread { refresh() }
        }
    }
}

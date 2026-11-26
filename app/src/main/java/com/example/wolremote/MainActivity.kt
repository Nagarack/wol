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
    private lateinit var urlInput: EditText
    private lateinit var pinInput: EditText
    private val handler = Handler(Looper.getMainLooper())
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
            setBackgroundColor(Color.parseColor("#F5F5F4"))
        }

        root.addView(TextView(this).apply {
            text = "Mon PC"; textSize = 28f; gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#1C1917"))
        })
        status = TextView(this).apply {
            text = "Vérification…"; textSize = 16f; gravity = Gravity.CENTER
            setPadding(0, px(6), 0, px(24))
        }
        root.addView(status)

        urlInput = EditText(this).apply {
            hint = "Serveur (ex : http://100.64.0.5:8080)"
            setText(prefs.getString("url", ""))
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        pinInput = EditText(this).apply {
            hint = "Code PIN"
            setText(prefs.getString("pin", "395474"))
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
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
            AlertDialog.Builder(this)
                .setMessage("Éteindre le PC ?")
                .setPositiveButton("Éteindre") { _, _ -> act("/shutdown") }
                .setNegativeButton("Annuler", null)
                .show()
        })

        msg = TextView(this).apply {
            gravity = Gravity.CENTER; setPadding(0, px(18), 0, 0)
            setTextColor(Color.GRAY)
        }
        root.addView(msg)
        setContentView(root)
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
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Serveur injoignable"
                    status.setTextColor(Color.GRAY)
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

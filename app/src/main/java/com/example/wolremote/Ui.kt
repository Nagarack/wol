package com.example.wolremote

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.util.concurrent.CountDownLatch

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

object Pal {
    val BG = Color.parseColor("#1C1917")
    val CARD = Color.parseColor("#292524")
    val FG = Color.parseColor("#FAFAF9")
    val MUTED = Color.parseColor("#A8A29E")
    val GREEN = Color.parseColor("#16A34A")
    val RED = Color.parseColor("#DC2626")
    val PURPLE = Color.parseColor("#7C3AED")
    val BLUE = Color.parseColor("#2563EB")
    val AMBER = Color.parseColor("#D97706")
    val GRAY = Color.parseColor("#52525B")
}

// Lecture sûre d'une chaîne JSON (optString renvoie "null" pour un null JSON)
fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k, "")

fun Activity.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Activity.tv(t: String, size: Float = 15f, color: Int = Pal.FG): TextView {
    val v = TextView(this)
    v.text = t
    v.textSize = size
    v.setTextColor(color)
    return v
}

fun Activity.btn(label: String, color: Int, h: Int = 52, onClick: () -> Unit): Button {
    val b = Button(this)
    b.text = label
    b.textSize = 16f
    b.setTextColor(Color.WHITE)
    b.setAllCaps(false)
    b.setBackgroundColor(color)
    b.setOnClickListener { onClick() }
    val lp = LinearLayout.LayoutParams(MATCH, dp(h))
    lp.topMargin = dp(10)
    b.layoutParams = lp
    return b
}

fun Activity.field(hint: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText {
    val e = EditText(this)
    e.hint = hint
    e.setText(value)
    e.setSingleLine()
    e.setTextColor(Pal.FG)
    e.setHintTextColor(Pal.MUTED)
    e.inputType = type
    return e
}

fun Activity.card(): LinearLayout {
    val c = LinearLayout(this)
    c.orientation = LinearLayout.VERTICAL
    c.setPadding(dp(14), dp(12), dp(14), dp(12))
    val bg = GradientDrawable()
    bg.setColor(Pal.CARD)
    bg.cornerRadius = dp(14).toFloat()
    c.background = bg
    val lp = LinearLayout.LayoutParams(MATCH, WRAP)
    lp.topMargin = dp(10)
    c.layoutParams = lp
    return c
}

fun Activity.dialogBuilder(): AlertDialog.Builder =
    AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)

// À appeler depuis un thread de fond : affiche une confirmation et attend la réponse.
fun Activity.confirmWait(title: String, msg: String, yes: String): Boolean {
    val latch = CountDownLatch(1)
    var res = false
    runOnUiThread {
        dialogBuilder().setTitle(title).setMessage(msg).setCancelable(false)
            .setPositiveButton(yes) { _, _ -> res = true; latch.countDown() }
            .setNegativeButton("Annuler") { _, _ -> latch.countDown() }
            .show()
    }
    latch.await()
    return res
}

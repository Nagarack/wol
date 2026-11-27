package com.example.wolremote

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONObject
import java.util.concurrent.CountDownLatch

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

object Pal {
    val BG = Color.parseColor("#0F0F12")
    val CARD = Color.parseColor("#1A1A1F")
    val CARD2 = Color.parseColor("#24242B")
    val BORDER = Color.parseColor("#2E2E36")
    val FG = Color.parseColor("#F4F4F5")
    val MUTED = Color.parseColor("#9A9AA6")
    val GREEN = Color.parseColor("#22C55E")
    val RED = Color.parseColor("#EF4444")
    val PURPLE = Color.parseColor("#8B5CF6")
    val BLUE = Color.parseColor("#3B82F6")
    val AMBER = Color.parseColor("#F59E0B")
    val GRAY = Color.parseColor("#3F3F46")
}

// Lecture sûre d'une chaîne JSON (optString renvoie "null" pour un null JSON)
fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k, "")

fun Activity.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Activity.rounded(color: Int, radiusDp: Int = 14, stroke: Int = 0): GradientDrawable {
    val g = GradientDrawable()
    g.setColor(color)
    g.cornerRadius = dp(radiusDp).toFloat()
    if (stroke != 0) g.setStroke(dp(1), stroke)
    return g
}

fun Activity.tv(t: String, size: Float = 15f, color: Int = Pal.FG): TextView {
    val v = TextView(this)
    v.text = t
    v.textSize = size
    v.setTextColor(color)
    return v
}

fun Activity.title(t: String): TextView {
    val v = tv(t, 28f, Pal.FG)
    v.typeface = Typeface.DEFAULT_BOLD
    v.setPadding(0, 0, 0, dp(6))
    return v
}

fun Activity.section(t: String): TextView {
    val v = tv(t, 14f, Pal.MUTED)
    v.typeface = Typeface.DEFAULT_BOLD
    v.setPadding(dp(2), dp(22), 0, dp(2))
    return v
}

fun Activity.badge(t: String, color: Int): TextView {
    val v = tv(t, 12f, color)
    v.typeface = Typeface.DEFAULT_BOLD
    v.setPadding(dp(9), dp(3), dp(9), dp(3))
    val bg = GradientDrawable()
    bg.setColor((color and 0x00FFFFFF) or 0x30000000)
    bg.cornerRadius = dp(10).toFloat()
    v.background = bg
    return v
}

fun Activity.btn(label: String, color: Int, h: Int = 52, onClick: () -> Unit): Button {
    val b = Button(this)
    b.text = label
    b.textSize = 16f
    b.setTextColor(Color.WHITE)
    b.setAllCaps(false)
    b.typeface = Typeface.DEFAULT_BOLD
    b.stateListAnimator = null
    b.background = RippleDrawable(ColorStateList.valueOf(Color.argb(70, 255, 255, 255)), rounded(color, 14), null)
    b.setOnClickListener {
        b.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        onClick()
    }
    // hauteur minimale (et non fixe) : un texte long passe à la ligne sans être coupé
    b.minHeight = dp(h)
    b.minimumHeight = dp(h)
    b.setPadding(dp(14), dp(10), dp(14), dp(10))
    val lp = LinearLayout.LayoutParams(MATCH, WRAP)
    lp.topMargin = dp(10)
    b.layoutParams = lp
    return b
}

// Ligne de liste : titre, détail optionnel et repère à droite (touche pour agir)
fun Activity.rowItem(label: String, sub: String = "", marker: String = "›", onClick: () -> Unit): LinearLayout {
    val r = LinearLayout(this)
    r.orientation = LinearLayout.HORIZONTAL
    r.gravity = android.view.Gravity.CENTER_VERTICAL
    r.setPadding(dp(16), dp(12), dp(16), dp(12))
    r.minimumHeight = dp(56)
    r.background = RippleDrawable(ColorStateList.valueOf(Color.argb(40, 255, 255, 255)), rounded(Pal.CARD, 14, Pal.BORDER), null)
    val t = LinearLayout(this)
    t.orientation = LinearLayout.VERTICAL
    t.addView(tv(label, 16f, Pal.FG))
    if (sub.isNotEmpty()) t.addView(tv(sub, 13f, Pal.MUTED))
    r.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
    val m = tv(marker, 20f, Pal.MUTED)
    r.addView(m)
    r.setOnClickListener {
        r.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        onClick()
    }
    val lp = LinearLayout.LayoutParams(MATCH, WRAP)
    lp.topMargin = dp(8)
    r.layoutParams = lp
    return r
}

// Rubrique repliable ; l'état (ouvert / fermé) est mémorisé
fun Activity.foldable(col: LinearLayout, title: String, st: Settings, key: String, openDefault: Boolean): LinearLayout {
    val body = LinearLayout(this)
    body.orientation = LinearLayout.VERTICAL
    val saved = st.get("fold_$key")
    val open0 = if (saved.isEmpty()) openDefault else saved == "1"
    body.visibility = if (open0) android.view.View.VISIBLE else android.view.View.GONE
    val head = rowItem(title, "", if (open0) "−" else "+") {}
    (head.layoutParams as LinearLayout.LayoutParams).topMargin = dp(16)
    val mark = head.getChildAt(1) as TextView
    head.setOnClickListener {
        val open = body.visibility == android.view.View.GONE
        body.visibility = if (open) android.view.View.VISIBLE else android.view.View.GONE
        mark.text = if (open) "−" else "+"
        st.put("fold_$key", if (open) "1" else "0")
    }
    col.addView(head)
    col.addView(body)
    return body
}

// Bouton secondaire : contour discret, pas de remplissage (un seul bouton plein par écran)
fun Activity.btn2(label: String, textColor: Int = Pal.FG, h: Int = 52, onClick: () -> Unit): Button {
    val b = btn(label, Pal.CARD, h, onClick)
    b.setTextColor(textColor)
    b.background = RippleDrawable(ColorStateList.valueOf(Color.argb(40, 255, 255, 255)), rounded(Pal.CARD, 14, Pal.BORDER), null)
    return b
}

fun Activity.field(hint: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText {
    val e = EditText(this)
    e.hint = hint
    e.setText(value)
    e.setSingleLine()
    e.textSize = 15f
    e.setTextColor(Pal.FG)
    e.setHintTextColor(Pal.MUTED)
    e.background = rounded(Pal.CARD2, 12, Pal.BORDER)
    e.setPadding(dp(14), dp(12), dp(14), dp(12))
    e.inputType = type
    val lp = LinearLayout.LayoutParams(MATCH, WRAP)
    lp.topMargin = dp(8)
    e.layoutParams = lp
    return e
}

fun Activity.card(): LinearLayout {
    val c = LinearLayout(this)
    c.orientation = LinearLayout.VERTICAL
    c.setPadding(dp(16), dp(14), dp(16), dp(14))
    c.background = rounded(Pal.CARD, 16, Pal.BORDER)
    val lp = LinearLayout.LayoutParams(MATCH, WRAP)
    lp.topMargin = dp(10)
    c.layoutParams = lp
    return c
}

fun Activity.progress(pct: Int): ProgressBar {
    val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
    pb.max = 100
    pb.progress = pct
    pb.progressTintList = ColorStateList.valueOf(Pal.GREEN)
    pb.progressBackgroundTintList = ColorStateList.valueOf(Pal.BORDER)
    val lp = LinearLayout.LayoutParams(MATCH, dp(8))
    lp.topMargin = dp(8)
    lp.bottomMargin = dp(4)
    pb.layoutParams = lp
    return pb
}

fun Activity.hideKeyboard() {
    val v = currentFocus ?: return
    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    imm.hideSoftInputFromWindow(v.windowToken, 0)
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

// ScrollView qui mémorise le dernier contact : on évite de reconstruire l'écran sous le doigt.
class TouchScroll(ctx: android.content.Context) : android.widget.ScrollView(ctx) {
    var lastTouch = 0L
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        lastTouch = android.os.SystemClock.uptimeMillis()
        return super.dispatchTouchEvent(ev)
    }
}

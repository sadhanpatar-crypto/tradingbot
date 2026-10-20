package com.tradingbot

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private val navy = 0xFF0B1220.toInt()
    private val gold = 0xFFF5B301.toInt()
    private val muted = 0xFF9FB0C8.toInt()
    private val panel = 0xFF16233A.toInt()
    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

    private lateinit var prefs: SharedPreferences
    private lateinit var priceText: TextView
    private lateinit var statusText: TextView
    private lateinit var card: LinearLayout
    private lateinit var headText: TextView
    private lateinit var bodyText: TextView
    private lateinit var framesText: TextView
    private lateinit var chart: ChartView
    private lateinit var levelsBox: LinearLayout
    private lateinit var analyzeBtn: Button
    private lateinit var offsetEdit: EditText

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var hindi = false
    private var voiceOn = true
    private var autoOn = false
    private var lastKey = ""

    @Volatile
    private var busy = false

    private val handler = Handler(Looper.getMainLooper())
    private val autoRun = object : Runnable {
        override fun run() {
            if (autoOn) {
                analyze(false)
                handler.postDelayed(this, 30000)
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, r: Int = 16): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(r).toFloat()
        return g
    }

    private fun tv(text: String, size: Float, color: Int, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.typeface = Typeface.DEFAULT_BOLD
        return t
    }

    private fun lpTop(top: Int): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(match, wrap)
        lp.topMargin = dp(top)
        return lp
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("tb", Context.MODE_PRIVATE)
        voiceOn = prefs.getBoolean("voice", true)
        tts = TextToSpeech(this, this)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(16), dp(16), dp(24))
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(navy)
        scroll.fitsSystemWindows = true
        scroll.addView(root)
        setContentView(scroll)

        // Header: logo + naam
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        val logo = ImageView(this)
        logo.setImageResource(R.drawable.logo)
        header.addView(logo, LinearLayout.LayoutParams(dp(56), dp(56)))
        val titles = LinearLayout(this)
        titles.orientation = LinearLayout.VERTICAL
        titles.setPadding(dp(12), 0, 0, 0)
        titles.addView(tv("Trading Bot", 22f, gold, true))
        titles.addView(tv("XAUUSD  |  Sadhan ka salahkaar", 12f, muted))
        header.addView(titles)
        root.addView(header)

        priceText = tv("--", 38f, Color.WHITE, true)
        priceText.setPadding(0, dp(12), 0, 0)
        root.addView(priceText)
        statusText = tv("ANALYZE dabao, Sadhan.", 12f, muted)
        root.addView(statusText)

        // Salah ka card
        card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(16), dp(14), dp(16), dp(14))
        card.background = rounded(0xFF37474F.toInt())
        headText = tv("Sadhan, ANALYZE dabao", 20f, Color.WHITE, true)
        bodyText = tv("", 15f, Color.WHITE)
        bodyText.setPadding(0, dp(8), 0, 0)
        bodyText.setLineSpacing(0f, 1.15f)
        framesText = tv("", 14f, 0xFFFFE082.toInt())
        framesText.setPadding(0, dp(10), 0, 0)
        card.addView(headText)
        card.addView(bodyText)
        card.addView(framesText)
        root.addView(card, lpTop(12))

        analyzeBtn = Button(this)
        analyzeBtn.text = "ANALYZE + BOLO"
        analyzeBtn.setOnClickListener { analyze(true) }
        root.addView(analyzeBtn, lpTop(12))

        val voiceChk = CheckBox(this)
        voiceChk.text = "Awaaz (voice) ON"
        voiceChk.setTextColor(Color.WHITE)
        voiceChk.isChecked = voiceOn
        voiceChk.setOnCheckedChangeListener { _, on ->
            voiceOn = on
            prefs.edit().putBoolean("voice", on).apply()
            if (!on) tts?.stop()
        }
        root.addView(voiceChk)

        val autoChk = CheckBox(this)
        autoChk.text = "Auto-refresh har 30 sec (salah badle tabhi bolega)"
        autoChk.setTextColor(Color.WHITE)
        autoChk.setOnCheckedChangeListener { _, on ->
            autoOn = on
            handler.removeCallbacks(autoRun)
            if (on) handler.post(autoRun)
        }
        root.addView(autoChk)

        // Chart
        chart = ChartView(this)
        root.addView(chart, LinearLayout.LayoutParams(match, dp(260)).also { it.topMargin = dp(12) })

        // Levels ki list
        levelsBox = LinearLayout(this)
        levelsBox.orientation = LinearLayout.VERTICAL
        levelsBox.setPadding(dp(14), dp(10), dp(14), dp(10))
        levelsBox.background = rounded(panel)
        levelsBox.addView(tv("Aapke levels", 14f, gold, true))
        root.addView(levelsBox, lpTop(12))

        // Broker offset
        val offBox = LinearLayout(this)
        offBox.orientation = LinearLayout.VERTICAL
        offBox.setPadding(dp(14), dp(10), dp(14), dp(10))
        offBox.background = rounded(panel)
        offBox.addView(tv("Broker price offset (\$)", 14f, gold, true))
        offBox.addView(
            tv(
                "Apne broker ke chart par XAUUSD ka price dekho aur app ke price se milao. " +
                    "Farq (broker minus app) yahan likho, jaise 1.5 ya -2.3. Levels bhi usi hisaab se khisak jayenge.",
                12f, muted
            )
        )
        val offRow = LinearLayout(this)
        offRow.orientation = LinearLayout.HORIZONTAL
        offsetEdit = EditText(this)
        offsetEdit.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        offsetEdit.setTextColor(Color.WHITE)
        offsetEdit.setText(prefs.getFloat("offset", 0f).toString())
        offRow.addView(offsetEdit, LinearLayout.LayoutParams(0, wrap, 1f))
        val saveBtn = Button(this)
        saveBtn.text = "Save"
        saveBtn.setOnClickListener {
            val v = offsetEdit.text.toString().trim().toFloatOrNull()
            if (v == null) {
                Toast.makeText(this, "Sahi number likho (jaise 1.5)", Toast.LENGTH_SHORT).show()
            } else {
                prefs.edit().putFloat("offset", v).apply()
                Toast.makeText(this, "Save ho gaya", Toast.LENGTH_SHORT).show()
                analyze(false)
            }
        }
        offRow.addView(saveBtn)
        offBox.addView(offRow)
        root.addView(offBox, lpTop(12))

        val foot = tv(
            "Ye bot sirf salah deta hai, guarantee nahi. Data free feed se aata hai (broker se thoda alag ho sakta hai). " +
                "News ke time (NFP, CPI, FOMC) trade avoid karo. Apna stop loss hamesha lagao. Final faisla aapka.",
            11f, muted
        )
        root.addView(foot, lpTop(14))

        analyze(false)
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            ttsReady = false
            return
        }
        val t = tts ?: return
        val r = t.setLanguage(Locale.forLanguageTag("hi-IN"))
        hindi = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
        if (!hindi) {
            t.setLanguage(Locale.US)
            runOnUiThread {
                Toast.makeText(
                    this,
                    "Hindi awaaz nahi mili. Phone Settings > Text-to-speech mein Hindi voice data install karo. Abhi English awaaz chalegi.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        ttsReady = true
    }

    private fun analyze(speak: Boolean) {
        if (busy) return
        busy = true
        statusText.text = "Data la raha hoon..."
        analyzeBtn.isEnabled = false
        val off = prefs.getFloat("offset", 0f).toDouble()
        Thread {
            try {
                val data = Feed.load(off)
                val adv = Analyzer.analyze(data.h1, data.m5, System.currentTimeMillis() / 1000)
                runOnUiThread {
                    try {
                        render(adv, data, speak)
                    } catch (e: Exception) {
                        statusText.text = "Screen error: ${e.message}"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "Data nahi mila: ${e.message}. Internet check karo, phir dobara dabao."
                }
            } finally {
                busy = false
                runOnUiThread { analyzeBtn.isEnabled = true }
            }
        }.start()
    }

    private fun render(adv: Advice, data: Data, speak: Boolean) {
        priceText.text = String.format(Locale.US, "%.2f", adv.price)
        val now = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        statusText.text = "Update: $now  |  Feed: ${data.symbol}" +
            (if (data.symbol != "XAUUSD=X") " (futures, broker se alag ho sakta hai)" else "")

        val color = when (adv.action) {
            Action.BUY -> 0xFF1B5E20.toInt()
            Action.SELL -> 0xFFB71C1C.toInt()
            Action.WAIT -> 0xFF8A6D00.toInt()
            Action.NO_TRADE -> 0xFF37474F.toInt()
        }
        card.background = rounded(color)
        headText.text = "Sadhan, " + adv.headline.show
        bodyText.text = adv.details.joinToString("\n\n") { it.show }
        framesText.text = "Chart: " + adv.frames.show

        chart.set(data.m5.takeLast(72), adv.levels, adv.price)

        while (levelsBox.childCount > 1) levelsBox.removeViewAt(1)
        for (lv in adv.levels.sortedByDescending { it.price }) {
            val diff = lv.price - adv.price
            val sign = if (diff >= 0) "+" else "-"
            val line = tv(
                "● " + lv.show + "   " + String.format(Locale.US, "%.2f", lv.price) +
                    "   (" + sign + String.format(Locale.US, "%.1f", abs(diff)) + ")",
                13f, lv.color
            )
            line.setPadding(0, dp(4), 0, 0)
            levelsBox.addView(line)
        }

        if (voiceOn && (speak || adv.key != lastKey)) speakOut(adv)
        lastKey = adv.key
    }

    private fun speakOut(adv: Advice) {
        val t = tts
        if (t == null || !ttsReady) {
            Toast.makeText(this, "Awaaz abhi ready nahi hai", Toast.LENGTH_SHORT).show()
            return
        }
        val text = if (hindi) adv.spoken() else adv.spokenRoman()
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "advice")
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(autoRun)
    }

    override fun onResume() {
        super.onResume()
        if (autoOn) {
            handler.removeCallbacks(autoRun)
            handler.post(autoRun)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoRun)
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}

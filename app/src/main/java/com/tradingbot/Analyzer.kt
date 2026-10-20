package com.tradingbot

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private fun f1(x: Double): String = String.format(Locale.US, "%.1f", x)
private fun f2(x: Double): String = String.format(Locale.US, "%.2f", x)

/**
 * Candles ko din / hafta / 4H mein jodta hai, broker ki tarah:
 * trading din New York ke 17:00 se shuru hota hai, hafta Sunday 17:00 se.
 */
object Agg {
    private val NY: ZoneId = ZoneId.of("America/New_York")

    private fun shifted(t: Long) =
        Instant.ofEpochSecond(t).atZone(NY).toLocalDateTime().minusHours(17)

    fun dayKey(t: Long): Long = shifted(t).toLocalDate().toEpochDay()

    fun weekKey(t: Long): Long =
        shifted(t).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY)).toEpochDay()

    fun h4Key(t: Long): Long {
        val s = shifted(t)
        return s.toLocalDate().toEpochDay() * 6 + s.hour / 4
    }

    fun m15Key(t: Long): Long = t / 900

    fun group(src: List<Candle>, key: (Long) -> Long): List<Candle> {
        val out = ArrayList<Candle>()
        var curKey = Long.MIN_VALUE
        for (c in src) {
            val k = key(c.t)
            if (out.isEmpty() || k != curKey) {
                out.add(Candle(c.t, c.o, c.h, c.l, c.c))
                curKey = k
            } else {
                val p = out[out.size - 1]
                out[out.size - 1] = Candle(p.t, p.o, max(p.h, c.h), min(p.l, c.l), c.c)
            }
        }
        return out
    }
}

object Analyzer {
    private const val BUF = 0.5

    private enum class Type { SWEEP, RETEST, BREAK_WAIT, BREAK_FAR, TIMEPASS, TOUCH, NEAR }

    /**
     * Ek level ka haal. Candles ko "mirror" karke hamesha ek hi sthiti socha jata hai:
     * level upar hai aur price neeche se aa raha hai (resistance). s = +1 asli, s = -1 mirror (support).
     */
    private class Ev(
        val level: Level,
        val type: Type,
        val s: Int,
        val dirM: Int,
        val rank: Int,
        val slM: Double,
        val strong: Boolean,
        val fresh: Boolean,
        val late: Boolean,
        val dist: Double,
        val barsAgo: Int
    ) {
        /** Asli direction: +1 BUY, -1 SELL. */
        val dir: Int get() = s * dirM
    }

    fun atr(c: List<Candle>, n: Int = 14): Double {
        if (c.size < 2) return 3.0
        val trs = ArrayList<Double>()
        for (i in 1 until c.size) {
            val p = c[i - 1].c
            trs.add(max(c[i].h - c[i].l, max(abs(c[i].h - p), abs(c[i].l - p))))
        }
        return trs.takeLast(n).average()
    }

    fun ema(v: List<Double>, n: Int): Double {
        if (v.isEmpty()) return 0.0
        val k = 2.0 / (n + 1)
        var e = v[0]
        for (i in 1 until v.size) e = v[i] * k + e * (1 - k)
        return e
    }

    /** Aapki 4 tarah ki lines: pichhla weekly H/L, pichhla daily H/L, pichhli 4H H/L, aaj ka open. */
    fun buildLevels(h1: List<Candle>): List<Level> {
        val days = Agg.group(h1) { Agg.dayKey(it) }
        val weeks = Agg.group(h1) { Agg.weekKey(it) }
        val h4 = Agg.group(h1) { Agg.h4Key(it) }
        val out = ArrayList<Level>()
        if (weeks.size >= 2) {
            val w = weeks[weeks.size - 2]
            out.add(Level(Kind.WEEKLY, true, w.h))
            out.add(Level(Kind.WEEKLY, false, w.l))
        }
        if (days.size >= 2) {
            val d = days[days.size - 2]
            out.add(Level(Kind.DAILY, true, d.h))
            out.add(Level(Kind.DAILY, false, d.l))
        }
        if (h4.size >= 2) {
            val b = h4[h4.size - 2]
            out.add(Level(Kind.H4, true, b.h))
            out.add(Level(Kind.H4, false, b.l))
        }
        if (days.isNotEmpty()) out.add(Level(Kind.OPEN, null, days[days.size - 1].o))
        return out
    }

    private fun mir(c: Candle, s: Int): Candle =
        if (s == 1) c else Candle(c.t, -c.o, -c.l, -c.h, -c.c)

    private fun eval(lv: Level, cl0: List<Candle>, live0: Candle, zone: Double, atr15: Double): Ev? {
        if (cl0.size < 6) return null
        val level0 = lv.price
        // Ek ghante pehle price level ke neeche tha to level resistance hai, warna support.
        val head = cl0.take(4).map { it.c }.average()
        val s = if (head <= level0) 1 else -1
        val lvl = s * level0
        val cl = cl0.map { mir(it, s) }
        val live = mir(live0, s)
        val dist = abs(live.c - lvl)

        // 1) SWEEP: candle level ke upar wick maar kar wapas neeche band hui
        var sw = -1
        for (i in max(0, cl.size - 8) until cl.size) {
            val c = cl[i]
            if (c.h >= lvl + 0.2 && c.c < lvl) {
                val cameFromBelow = i == 0 || (max(0, i - 3) until i).any { cl[it].c < lvl }
                if (cameFromBelow) sw = i
            }
        }
        if (sw >= 0 && live.c < lvl + 0.25 * zone) {
            val c = cl[sw]
            var ext = live.h
            for (j in sw until cl.size) ext = max(ext, cl[j].h)
            val body = abs(c.c - c.o)
            val wick = c.h - max(c.o, c.c)
            val strong = wick >= max(body, 0.5 * zone)
            val barsAgo = cl.size - 1 - sw
            return Ev(
                lv, Type.SWEEP, s, -1, 5, ext + BUF, strong,
                barsAgo <= 3, (lvl - live.c) > 3 * atr15, dist, barsAgo
            )
        }

        // 2) BREAK: do candle level ke upar band hui
        val last2 = cl.takeLast(2)
        val broke = last2.size == 2 && last2.all { it.c > lvl + 0.3 * zone } && cl.any { it.c < lvl }
        if (broke && live.c > lvl) {
            val rec = cl.takeLast(3) + live
            val touched = rec.any { it.l <= lvl + 0.3 * zone }
            val lc = cl[cl.size - 1]
            val lowWick = min(lc.o, lc.c) - lc.l
            // Hold = pichhli candle level ke upar band hui aur (green ya neeche lamba wick), price abhi bhi upar
            val hold = lc.c > lvl + 0.2 * zone && (lc.c > lc.o || lowWick >= abs(lc.c - lc.o)) &&
                live.c > lvl + 0.2 * zone
            if (touched && hold && (live.c - lvl) <= 3.5 * zone) {
                val lowest = rec.minOf { it.l }
                return Ev(lv, Type.RETEST, s, 1, 5, lowest - BUF, false, true, false, dist, 0)
            }
            val far = (live.c - lvl) > 3.5 * zone
            return Ev(lv, if (far) Type.BREAK_FAR else Type.BREAK_WAIT, s, 0, 3, 0.0, false, true, false, dist, 0)
        }

        // 3) TIME-PASS: pichhle ghante mein zyadatar candles level ke paas hi ghoom rahi hain
        val inZone = cl.count { it.l <= lvl + zone && it.h >= lvl - zone }
        val closes = cl.map { it.c }
        if (inZone >= 6 && (closes.max() - closes.min()) <= 2.5 * zone + 1.0 && dist <= 1.5 * zone) {
            return Ev(lv, Type.TIMEPASS, s, 0, 3, 0.0, false, true, false, dist, inZone)
        }

        // 4) TOUCH aur 5) NEAR
        if (live.h >= lvl - zone && live.c < lvl + zone) {
            return Ev(lv, Type.TOUCH, s, 0, 2, 0.0, false, true, false, dist, 0)
        }
        if (live.c < lvl && dist <= 4 * zone) {
            return Ev(lv, Type.NEAR, s, 0, 1, 0.0, false, true, false, dist, 0)
        }
        return null
    }

    /**
     * h1 = 1 ghante ki candles (kam se kam ~3 hafte), m5 = 5 minute ki candles (aakhri candle chal rahi hai).
     * nowSec = abhi ka waqt (epoch seconds).
     */
    fun analyze(h1: List<Candle>, m5: List<Candle>, nowSec: Long): Advice {
        val levels = buildLevels(h1)
        val price = m5.lastOrNull()?.c ?: 0.0

        if (m5.size < 30 || h1.size < 30 || levels.size < 6) {
            return Advice(
                Action.NO_TRADE,
                Say("Data kam hai", "डेटा कम है"),
                listOf(Say("Poora data nahi mila, isliye analysis nahi ho sakta.", "पूरा डेटा नहीं मिला, इसलिए विश्लेषण नहीं हो सकता।")),
                Say("Thodi der baad dobara try karo.", "थोड़ी देर बाद फिर कोशिश करो।"),
                price, levels, "nodata"
            )
        }
        val live = m5[m5.size - 1]
        if (nowSec - live.t > 40 * 60) {
            return Advice(
                Action.NO_TRADE,
                Say("Market band lag raha hai", "बाज़ार बंद लग रहा है"),
                listOf(Say("Data purana hai (weekend ya break). Abhi trade mat lo.", "डेटा पुराना है, वीकेंड या ब्रेक हो सकता है। अभी ट्रेड मत लो।")),
                Say("Market khulne par app dobara kholo.", "बाज़ार खुलने पर ऐप फिर से खोलना।"),
                price, levels, "closed"
            )
        }

        val m15 = Agg.group(m5) { Agg.m15Key(it) }
        val atr15 = atr(m15.dropLast(1))
        val zone = (0.35 * atr15).coerceIn(0.8, 3.0)
        val cl = m5.dropLast(1).takeLast(12)

        val h1c = h1.map { it.c }
        val ema20 = ema(h1c.takeLast(80), 20)
        val openP = levels.firstOrNull { it.kind == Kind.OPEN }?.price ?: price
        val trend = if (price > openP && h1c[h1c.size - 1] > ema20) 1
        else if (price < openP && h1c[h1c.size - 1] < ema20) -1 else 0

        val evs = levels.mapNotNull { eval(it, cl, live, zone, atr15) }

        if (evs.isEmpty()) {
            val near = levels.minByOrNull { abs(it.price - price) }!!
            val d = abs(near.price - price)
            val up = near.price > price
            return Advice(
                Action.NO_TRADE,
                Say("Abhi trade nahi, koi level paas nahi", "अभी ट्रेड नहीं, कोई लेवल पास नहीं है"),
                listOf(
                    Say(
                        "Sabse paas: ${near.show} ${f2(near.price)}, ${f1(d)}\$ ${if (up) "upar" else "neeche"}.",
                        "सबसे पास ${near.say}, ${f1(d)} डॉलर ${if (up) "ऊपर" else "नीचे"} है।"
                    )
                ),
                Say(
                    "1 ghante ka chart dekho. Price level ke paas aaye tab 15 min aur 5 min kholo.",
                    "एक घंटे का चार्ट देखो। भाव लेवल के पास आए तब पंद्रह मिनट और पाँच मिनट खोलना।"
                ),
                price, levels, "none"
            )
        }

        val best = evs.sortedWith(
            compareByDescending<Ev> { it.rank }.thenByDescending { it.level.weight }.thenBy { it.dist }
        )[0]
        val lv = best.level
        val nm = lv.show
        val snm = lv.say
        val above = best.s == 1
        val key0 = "${best.type}:${lv.kind}:${lv.high}"
        val side = if (above) "upar" else "neeche"
        val sideS = if (above) "ऊपर" else "नीचे"

        return when (best.type) {
            Type.NEAR -> Advice(
                Action.WAIT,
                Say("WAIT: price $nm ke paas aa raha hai", "रुको, भाव $snm के पास आ रहा है"),
                listOf(
                    Say(
                        "Level ${f1(best.dist)}\$ $side hai. Abhi entry nahi, level touch hone ka wait karo.",
                        "लेवल ${f1(best.dist)} डॉलर $sideS है। अभी एंट्री नहीं, लेवल छूने का इंतज़ार करो।"
                    )
                ),
                Say(
                    "15 min chart par nazar rakho; entry ke liye 5 min kholo.",
                    "पंद्रह मिनट का चार्ट देखते रहो; एंट्री के लिए पाँच मिनट खोलना।"
                ),
                price, levels, key0
            )

            Type.TOUCH -> Advice(
                Action.WAIT,
                Say("WAIT: price $nm ko touch kar raha hai", "रुको, भाव $snm को छू रहा है"),
                listOf(
                    if (above) Say(
                        "Ab 5 min par red rejection candle ka wait karo (upar wick, neeche close). Mil jaye to SELL. Bina confirmation entry mat lo.",
                        "अब पाँच मिनट पर लाल रिजेक्शन कैंडल का इंतज़ार करो, ऊपर विक और नीचे क्लोज़। मिल जाए तो सेल। बिना कन्फर्मेशन एंट्री मत लेना।"
                    ) else Say(
                        "Ab 5 min par green rejection candle ka wait karo (neeche wick, upar close). Mil jaye to BUY. Bina confirmation entry mat lo.",
                        "अब पाँच मिनट पर हरी रिजेक्शन कैंडल का इंतज़ार करो, नीचे विक और ऊपर क्लोज़। मिल जाए तो बाय। बिना कन्फर्मेशन एंट्री मत लेना।"
                    )
                ),
                Say(
                    "5 min chart kholo (entry candle), 15 min se confirm karo, 1 ghante par trend dekho.",
                    "पाँच मिनट का चार्ट खोलो, पंद्रह मिनट से कन्फर्म करो, और एक घंटे पर ट्रेंड देखो।"
                ),
                price, levels, key0
            )

            Type.TIMEPASS -> Advice(
                Action.WAIT,
                Say("WAIT: price $nm par time-pass kar raha hai", "रुको, भाव $snm पर टाइम-पास कर रहा है"),
                listOf(
                    Say(
                        "Pichhle 1 ghante ki ${best.barsAgo} candles level ke paas hi hain. Ya to sweep karke palatega ya level todega. Direction clear hone tak trade nahi.",
                        "पिछले एक घंटे की ${best.barsAgo} कैंडल लेवल के पास ही हैं। या तो स्वीप करके पलटेगा या लेवल तोड़ेगा। दिशा साफ़ होने तक ट्रेड नहीं।"
                    )
                ),
                Say(
                    "15 min candle ka close dekho; 5 min ke fake move se bacho.",
                    "पंद्रह मिनट की कैंडल का क्लोज़ देखो; पाँच मिनट की नकली चाल से बचना।"
                ),
                price, levels, key0
            )

            Type.BREAK_WAIT, Type.BREAK_FAR -> {
                val brk = if (above) "upar" else "neeche"
                val brkS = if (above) "ऊपर" else "नीचे"
                val buyWord = if (above) "BUY" else "SELL"
                val buyWordS = if (above) "बाय" else "सेल"
                val far = best.type == Type.BREAK_FAR
                Advice(
                    Action.WAIT,
                    if (far) Say("WAIT: $nm toot kar door nikal gaya", "रुको, $snm टूटकर दूर निकल गया")
                    else Say("WAIT: $nm toot gaya hai", "रुको, $snm टूट गया है"),
                    listOf(
                        Say(
                            "Price level ke $brk close hua (${f1(best.dist)}\$ door). Peeche se chase mat karo. Retest par candle hold kare tabhi $buyWord.",
                            "भाव लेवल के $brkS बंद हुआ, ${f1(best.dist)} डॉलर दूर। पीछे से मत भागो। रीटेस्ट पर कैंडल टिके तभी $buyWordS।"
                        )
                    ),
                    Say(
                        "5 min par retest dekho, 15 min close se confirm karo.",
                        "पाँच मिनट पर रीटेस्ट देखो, पंद्रह मिनट के क्लोज़ से कन्फर्म करो।"
                    ),
                    price, levels, key0
                )
            }

            Type.SWEEP, Type.RETEST -> signal(best, levels, price, zone, atr15, trend, key0)
        }
    }

    private fun signal(
        b: Ev, levels: List<Level>, price: Double, zone: Double, atr15: Double, trend: Int, key0: String
    ): Advice {
        val lv = b.level
        val nm = lv.show
        val snm = lv.say
        val dir = b.dir
        val isBuy = dir > 0
        val word = if (isBuy) "BUY" else "SELL"
        val wordS = if (isBuy) "बाय" else "सेल"
        val isSweep = b.type == Type.SWEEP
        val above = b.s == 1
        val side = if (above) "upar" else "neeche"
        val sideS = if (above) "ऊपर" else "नीचे"

        // Score: level ki taqat + confluence + wick + trend
        val conf = levels.count { it !== lv && abs(it.price - lv.price) <= 2 * zone }
        var score = lv.weight + min(conf, 2)
        if (b.strong) score += 1
        val withTrend = trend == dir
        val counter = trend == -dir && trend != 0
        if (withTrend) score += 1 else if (counter) score -= 1
        if (!isSweep) score += 1
        val label = if (score >= 5) "Strong" else if (score >= 3) "Medium" else "Kamzor"
        val labelS = if (score >= 5) "मज़बूत" else if (score >= 3) "मध्यम" else "कमज़ोर"

        // Entry / SL / TP
        val entry = price
        var sl = b.s * b.slM
        if ((entry - sl) * dir <= 0 || abs(entry - sl) < 1.0) sl = entry - dir * 1.0
        val risk = abs(entry - sl)
        val ahead = levels.map { it.price }
            .filter { (it - entry) * dir >= risk }
            .sortedBy { abs(it - entry) }
        val tp1 = ahead.firstOrNull { abs(it - entry) <= 4 * risk } ?: (entry + dir * 1.5 * risk)
        val tp2 = ahead.firstOrNull { abs(it - entry) > abs(tp1 - entry) + 0.5 && abs(it - entry) <= 6 * risk }
            ?: (entry + dir * max(2.5 * risk, abs(tp1 - entry) + risk))
        val rr = abs(tp1 - entry) / risk

        if (isSweep && (!b.fresh || b.late)) {
            val mins = (b.barsAgo + 1) * 5
            return Advice(
                Action.WAIT,
                Say("WAIT: reaction ho chuka hai", "रुको, रिएक्शन हो चुका है"),
                listOf(
                    Say(
                        "$nm par sweep lagbhag $mins min pehle hua, price ab ${f1(b.dist)}\$ door hai. Late entry mat lo; pullback ya naye setup ka wait karo.",
                        "$snm पर स्वीप लगभग $mins मिनट पहले हुआ, भाव अब ${f1(b.dist)} डॉलर दूर है। देर से एंट्री मत लो; पुलबैक या नए सेटअप का इंतज़ार करो।"
                    )
                ),
                Say(
                    "15 min par dekho ki level ab kaise behave kar raha hai; pullback par hi socho.",
                    "पंद्रह मिनट पर देखो कि लेवल अब कैसा व्यवहार कर रहा है; पुलबैक पर ही सोचना।"
                ),
                price, levels, "$key0:late"
            )
        }
        if (score < 3) {
            return Advice(
                Action.WAIT,
                Say("WAIT: setup kamzor hai", "रुको, सेटअप कमज़ोर है"),
                listOf(
                    Say(
                        "$nm par ${if (isSweep) "sweep" else "retest"} dikha, par setup kamzor hai (score $score${if (counter) ", trend ke against" else ""}). Skip karo.",
                        "$snm पर ${if (isSweep) "स्वीप" else "रीटेस्ट"} दिखा, पर सेटअप कमज़ोर है${if (counter) " और ट्रेंड के ख़िलाफ़ है" else ""}। स्किप करो।"
                    )
                ),
                Say(
                    "Is trade ko chhod do. 15 min par agle level ka wait karo.",
                    "इस ट्रेड को छोड़ दो। पंद्रह मिनट पर अगले लेवल का इंतज़ार करो।"
                ),
                price, levels, "$key0:weak"
            )
        }
        if (risk > 4 * atr15) {
            return Advice(
                Action.WAIT,
                Say("WAIT: stop loss bahut door hai", "रुको, स्टॉप लॉस बहुत दूर है"),
                listOf(
                    Say(
                        "Is setup mein SL ${f1(risk)}\$ door aa raha hai. Risk bada hai, skip karo.",
                        "इस सेटअप में स्टॉप लॉस ${f1(risk)} डॉलर दूर आ रहा है। रिस्क बड़ा है, स्किप करो।"
                    )
                ),
                Say(
                    "Pullback par chhote SL wala entry dhundo (5 min).",
                    "पुलबैक पर छोटे स्टॉप लॉस वाली एंट्री ढूँढो, पाँच मिनट पर।"
                ),
                price, levels, "$key0:risk"
            )
        }

        val notes = ArrayList<String>()
        val notesS = ArrayList<String>()
        if (b.strong) { notes.add("lamba wick"); notesS.add("लंबा विक") }
        if (withTrend) { notes.add("trend saath hai"); notesS.add("ट्रेंड साथ है") }
        else if (counter) { notes.add("trend ke against"); notesS.add("ट्रेंड के ख़िलाफ़") }
        if (conf > 0) { notes.add("${conf + 1} lines ek saath"); notesS.add("${conf + 1} लाइनें साथ में") }
        if (!isSweep) { notes.add("break ke baad hold"); notesS.add("ब्रेक के बाद टिका") }
        var extra = ""
        var extraS = ""
        if (score < 5) { extra += " Chhota lot rakho."; extraS += " छोटा लॉट रखना।" }
        if (counter) { extra += " Trend ke against hai, jaldi profit book karo."; extraS += " ट्रेंड के ख़िलाफ़ है, जल्दी प्रॉफिट लेना।" }
        if (risk > 2.5 * atr15) { extra += " SL thoda door hai."; extraS += " स्टॉप लॉस थोड़ा दूर है।" }

        val what = if (isSweep) {
            Say(
                "Price ne $nm ke $side wick maara aur wapas andar close hua. Ye sweep hai, reversal ka signal.",
                "भाव ने $snm के $sideS विक मारा और वापस अंदर बंद हुआ। यह स्वीप है, पलटने का इशारा।"
            )
        } else {
            val brk = if (above) "upar" else "neeche"
            val brkS = if (above) "ऊपर" else "नीचे"
            Say(
                "Price ne $nm ko $brk se tod diya aur retest par tik gaya.",
                "भाव ने $snm को $brkS की तरफ़ तोड़ा और रीटेस्ट पर टिका हुआ है।"
            )
        }
        val plan = Say(
            "Entry ${f2(entry)}, SL ${f2(sl)}, TP1 ${f2(tp1)}, TP2 ${f2(tp2)} (RR 1:${f1(rr)}).",
            "एंट्री ${f1(entry)}, स्टॉप लॉस ${f1(sl)}, टारगेट एक ${f1(tp1)}, टारगेट दो ${f1(tp2)}।"
        )
        val quality = Say(
            "Setup $label (score $score): ${notes.joinToString(", ")}.$extra",
            "सेटअप $labelS है।$extraS"
        )
        val big = lv.kind == Kind.WEEKLY || lv.kind == Kind.DAILY
        val frames = Say(
            "Entry 5 min par lo, 15 min ka close confirm karo, aur 1 ghante par trend dekho." +
                if (big) " Ye bada level hai, 1 ghante par bhi dikhta hoga." else "",
            "एंट्री पाँच मिनट पर लो, पंद्रह मिनट के क्लोज़ से कन्फर्म करो, और एक घंटे पर ट्रेंड देखो।" +
                if (big) " यह बड़ा लेवल है, एक घंटे पर भी दिखेगा।" else ""
        )
        val action = if (isBuy) Action.BUY else Action.SELL
        return Advice(
            action,
            Say(
                "$word ka mauka: $nm par ${if (isSweep) "sweep ke baad reversal" else "break ke baad retest hold"}",
                "$wordS का मौका है, $snm पर ${if (isSweep) "स्वीप के बाद रिवर्सल" else "ब्रेक के बाद रीटेस्ट होल्ड"}"
            ),
            listOf(what, plan, quality),
            frames, price, levels, "$key0:$word"
        )
    }
}

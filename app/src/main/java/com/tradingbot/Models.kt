package com.tradingbot

/** Ek candle. t = epoch seconds (candle ka shuru). */
data class Candle(val t: Long, val o: Double, val h: Double, val l: Double, val c: Double)

/** Ek hi baat do roop mein: show = screen par (Hinglish), say = awaaz ke liye (Hindi). */
data class Say(val show: String, val say: String)

enum class Kind { WEEKLY, DAILY, H4, OPEN }

enum class Action { BUY, SELL, WAIT, NO_TRADE }

/** Ek level (line). high = true/false ya null (Open ke liye). */
data class Level(val kind: Kind, val high: Boolean?, val price: Double) {

    /** Level kitna mazboot hai (weekly > daily > 4H = open). */
    val weight: Int
        get() = when (kind) {
            Kind.WEEKLY -> 4
            Kind.DAILY -> 3
            Kind.H4 -> 2
            Kind.OPEN -> 2
        }

    /** Line ka rang: daily peeli, weekly lal, 4H neeli, open hari. */
    val color: Int
        get() = when (kind) {
            Kind.WEEKLY -> 0xFFE53935.toInt()
            Kind.DAILY -> 0xFFFFC107.toInt()
            Kind.H4 -> 0xFF42A5F5.toInt()
            Kind.OPEN -> 0xFF43A047.toInt()
        }

    val show: String
        get() = when (kind) {
            Kind.WEEKLY -> if (high == true) "Weekly High (lal)" else "Weekly Low (lal)"
            Kind.DAILY -> if (high == true) "Daily High (peeli)" else "Daily Low (peeli)"
            Kind.H4 -> if (high == true) "4H High (neeli)" else "4H Low (neeli)"
            Kind.OPEN -> "Aaj ka Open (hari)"
        }

    val say: String
        get() = when (kind) {
            Kind.WEEKLY -> if (high == true) "वीकली हाई की लाल लाइन" else "वीकली लो की लाल लाइन"
            Kind.DAILY -> if (high == true) "डेली हाई की पीली लाइन" else "डेली लो की पीली लाइन"
            Kind.H4 -> if (high == true) "फोर आवर हाई की नीली लाइन" else "फोर आवर लो की नीली लाइन"
            Kind.OPEN -> "आज के ओपन की हरी लाइन"
        }

    /** Chart par chhota naam. */
    val tag: String
        get() = when (kind) {
            Kind.WEEKLY -> if (high == true) "W-H" else "W-L"
            Kind.DAILY -> if (high == true) "D-H" else "D-L"
            Kind.H4 -> if (high == true) "4H-H" else "4H-L"
            Kind.OPEN -> "Open"
        }
}

/** Bot ki salah. */
data class Advice(
    val action: Action,
    val headline: Say,
    val details: List<Say>,
    val frames: Say,
    val price: Double,
    val levels: List<Level>,
    /** Salah badli ya nahi, ye pehchanne ke liye (auto mode mein sirf badlav par bolta hai). */
    val key: String
) {
    /** Hindi voice ke liye poora text. */
    fun spoken(): String =
        "साधन, " + headline.say + "। " + details.joinToString(" ") { it.say } + " " + frames.say

    /** Hindi voice na ho to Hinglish text. */
    fun spokenRoman(): String =
        "Sadhan, " + headline.show + ". " + details.joinToString(" ") { it.show } + " " + frames.show
}

package com.tradingbot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** 5 minute ke candles aur upar aapki rang-birangi lines. */
class ChartView(ctx: Context) : View(ctx) {
    private var candles: List<Candle> = emptyList()
    private var levels: List<Level> = emptyList()
    private var price = 0.0
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    fun set(c: List<Candle>, l: List<Level>, pr: Double) {
        candles = c
        levels = l
        price = pr
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = resources.displayMetrics.density
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawColor(0xFF0F1A2B.toInt())
        if (candles.size < 2) return

        var lo = candles.minOf { it.l }
        var hi = candles.maxOf { it.h }
        val span0 = max(hi - lo, 4.0)
        val vis = levels.filter { it.price > lo - span0 * 0.6 && it.price < hi + span0 * 0.6 }
        for (lv in vis) {
            lo = min(lo, lv.price)
            hi = max(hi, lv.price)
        }
        val pad = (hi - lo) * 0.06
        lo -= pad
        hi += pad
        val plotW = w - 70f * d

        fun yOf(v: Double): Float = (h - (v - lo) / (hi - lo) * h).toFloat()

        // Candles
        val n = candles.size
        val step = plotW / n
        val bw = step * 0.6f
        for (i in 0 until n) {
            val c = candles[i]
            val x = step * (i + 0.5f)
            p.color = if (c.c >= c.o) 0xFF26A69A.toInt() else 0xFFEF5350.toInt()
            p.strokeWidth = 1f * d
            canvas.drawLine(x, yOf(c.h), x, yOf(c.l), p)
            val top = yOf(max(c.o, c.c))
            val bot = yOf(min(c.o, c.c))
            canvas.drawRect(x - bw / 2, top, x + bw / 2, max(bot, top + 1f), p)
        }

        // Levels (aapki lines)
        p.textSize = 10f * d
        for (lv in vis) {
            p.color = lv.color
            p.strokeWidth = 1.6f * d
            val y = yOf(lv.price)
            canvas.drawLine(0f, y, plotW, y, p)
            canvas.drawText(lv.tag + " " + String.format(Locale.US, "%.1f", lv.price), plotW + 4f * d, y + 3f * d, p)
        }

        // Abhi ka price
        p.color = 0xFFFFFFFF.toInt()
        p.strokeWidth = 1f * d
        val py = yOf(price)
        canvas.drawLine(0f, py, plotW, py, p)
        canvas.drawText(String.format(Locale.US, "%.1f", price), plotW + 4f * d, py + 3f * d, p)
    }
}

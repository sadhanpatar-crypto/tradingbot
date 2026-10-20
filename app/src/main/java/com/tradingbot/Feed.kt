package com.tradingbot

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class Data(val h1: List<Candle>, val m5: List<Candle>, val symbol: String)

/** Yahoo Finance se free price data (XAUUSD spot, na mile to GC=F gold futures). */
object Feed {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    /** offset = (aapke broker ka price) - (feed ka price). Sab candles mein jod diya jata hai. */
    fun load(offset: Double): Data {
        var err: Exception? = null
        for (sym in listOf("XAUUSD=X", "GC=F")) {
            try {
                val h1 = fetch(sym, "1h", "1mo")
                val m5 = fetch(sym, "5m", "5d")
                if (h1.size >= 60 && m5.size >= 40) {
                    return Data(h1.map { shift(it, offset) }, m5.map { shift(it, offset) }, sym)
                }
                err = Exception("$sym: data kam mila")
            } catch (e: Exception) {
                err = e
            }
        }
        throw err ?: Exception("data nahi mila")
    }

    private fun shift(c: Candle, o: Double): Candle =
        if (o == 0.0) c else Candle(c.t, c.o + o, c.h + o, c.l + o, c.c + o)

    private fun fetch(symbol: String, interval: String, range: String): List<Candle> {
        val url = URL("https://query1.finance.yahoo.com/v8/finance/chart/$symbol?interval=$interval&range=$range&includePrePost=false")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code != 200) throw Exception("$symbol HTTP $code")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return parse(body)
        } finally {
            conn.disconnect()
        }
    }

    fun parse(body: String): List<Candle> {
        val results = JSONObject(body).getJSONObject("chart").optJSONArray("result") ?: return emptyList()
        if (results.length() == 0) return emptyList()
        val r = results.getJSONObject(0)
        val ts = r.optJSONArray("timestamp") ?: return emptyList()
        val q = r.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0)
        val o = q.getJSONArray("open")
        val h = q.getJSONArray("high")
        val l = q.getJSONArray("low")
        val c = q.getJSONArray("close")
        val out = ArrayList<Candle>()
        for (i in 0 until ts.length()) {
            if (o.isNull(i) || h.isNull(i) || l.isNull(i) || c.isNull(i)) continue
            out.add(Candle(ts.getLong(i), o.getDouble(i), h.getDouble(i), l.getDouble(i), c.getDouble(i)))
        }
        return out
    }
}

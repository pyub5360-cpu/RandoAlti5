package com.randoalti

import kotlin.math.exp

object Tracker {
    private val lock = Any()
    private val times = ArrayList<Long>()
    private val alts = ArrayList<Double>()
    private var ref: Double? = null
    private var ema: Double? = null
    private var lastT = 0L

    @Volatile var gain = 0.0
        private set
    @Volatile var loss = 0.0
        private set
    @Volatile var running = false
    @Volatile var source = ""
    @Volatile var offset: Double? = null

    /** Décale toute la courbe d'une constante : les vitesses ne changent pas. */
    fun shift(d: Double) {
        synchronized(lock) {
            for (i in alts.indices) alts[i] = alts[i] + d
            ema = ema?.let { it + d }
            ref = ref?.let { it + d }
        }
    }

    fun reset() {
        synchronized(lock) {
            times.clear(); alts.clear()
            gain = 0.0; loss = 0.0; ref = null; ema = null; lastT = 0L
        }
    }

    fun add(t: Long, raw: Double) {
        synchronized(lock) {
            val e = ema
            ema = if (e == null || t - lastT > 120000) raw
                  else e + (1 - exp(-(t - lastT) / 10000.0)) * (raw - e)
            lastT = t
            val n = times.size
            if (n > 0 && t - times[n - 1] < 3000) return
            val v = ema ?: raw
            times.add(t); alts.add(v)
            val r = ref
            if (r == null) {
                ref = v
            } else {
                val d = v - r
                if (d >= 3) { gain += d; ref = v }
                else if (d <= -3) { loss -= d; ref = v }
            }
        }
    }

    private fun altAt(t: Long): Double? {
        val n = times.size
        if (n == 0 || t < times[0] || t > times[n - 1]) return null
        var lo = 0
        var hi = n - 1
        while (hi - lo > 1) {
            val m = (lo + hi) ushr 1
            if (times[m] <= t) lo = m else hi = m
        }
        val dt = times[hi] - times[lo]
        if (dt == 0L) return alts[lo]
        return alts[lo] + (alts[hi] - alts[lo]) * (t - times[lo]).toDouble() / dt
    }

    fun current(): Double? = synchronized(lock) { if (alts.isEmpty()) null else alts[alts.size - 1] }

    fun elapsedSec(): Long = synchronized(lock) {
        if (times.size < 2) 0L else (times[times.size - 1] - times[0]) / 1000
    }

    /** Vitesse ascensionnelle nette en m/h. minutes = 0 : globale. */
    fun rate(minutes: Int): Double? = synchronized(lock) { rateLocked(minutes) }

    private fun rateLocked(minutes: Int): Double? {
        val n = times.size
        if (n < 2) return null
        val now = times[n - 1]
        val cur = alts[n - 1]
        if (minutes == 0) {
            val h = (now - times[0]) / 3600000.0
            return if (h < 0.01) null else (cur - alts[0]) / h
        }
        val a = altAt(now - minutes * 60000L) ?: return null
        return (cur - a) / (minutes / 60.0)
    }
}

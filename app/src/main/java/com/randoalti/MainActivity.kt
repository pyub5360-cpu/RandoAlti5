package com.randoalti

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowInsets
import android.widget.*

class MainActivity : Activity() {
    private val bg = Color.parseColor("#101A14")
    private val ink = Color.parseColor("#E8F0E6")
    private val muted = Color.parseColor("#98A99C")
    private val up = Color.parseColor("#5FD08B")
    private val down = Color.parseColor("#F09A5B")

    private lateinit var tvAlt: TextView
    private lateinit var tvInfo: TextView
    private lateinit var btnGo: Button
    private val rows = LinkedHashMap<Int, TextView>()
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 1000) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tv(size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(null, Typeface.BOLD)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setOnApplyWindowInsetsListener { v, ins ->
                val b = ins.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(dp(16), b.top + dp(16), dp(16), b.bottom + dp(16))
                ins
            }
        }
        tvAlt = tv(56f, ink, true)
        tvInfo = tv(16f, muted)
        root.addView(tvAlt)
        root.addView(tvInfo)

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (m in listOf(2, 5, 10, 20, 30, 60, 0)) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
            }
            val label = tv(20f, muted).apply { text = if (m == 0) "Globale" else "$m min" }
            val value = tv(30f, ink, true).apply { gravity = Gravity.END }
            row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(value, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))
            list.addView(row)
            rows[m] = value
        }
        val scroll = ScrollView(this)
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        btnGo = Button(this).apply {
            textSize = 20f
            setOnClickListener { toggle() }
        }
        val btnReset = Button(this).apply {
            text = "Remise à zéro"
            setOnClickListener { Tracker.reset(); refresh() }
        }
        root.addView(btnGo, LinearLayout.LayoutParams(-1, -2))
        root.addView(btnReset, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
    }

    private fun toggle() {
        if (Tracker.running) {
            stopService(Intent(this, TrackerService::class.java))
            Tracker.running = false
        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startTracking()
        } else {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        refresh()
    }

    private fun startTracking() {
        startForegroundService(Intent(this, TrackerService::class.java))
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startTracking()
        } else {
            Toast.makeText(this, "Autorisez la localisation pour démarrer", Toast.LENGTH_LONG).show()
        }
    }

    private fun refresh() {
        val alt = Tracker.current()
        tvAlt.text = if (alt == null) "— m" else "${Math.round(alt)} m"
        val s = Tracker.elapsedSec()
        val src = if (Tracker.source.isEmpty()) "" else "   ${Tracker.source}"
        tvInfo.text = "D+ ${Math.round(Tracker.gain)} m   D− ${Math.round(Tracker.loss)} m   " +
            "${s / 3600}:${"%02d".format(s % 3600 / 60)}$src"
        for ((m, view) in rows) {
            val r = Tracker.rate(m)
            if (r == null) {
                view.text = "—"
                view.setTextColor(muted)
            } else {
                val v = (Math.round(r / 5.0) * 5).toInt()
                view.text = (if (v > 0) "+" else "") + "$v m/h"
                view.setTextColor(if (v > 30) up else if (v < -30) down else ink)
            }
        }
        btnGo.text = if (Tracker.running) "Arrêter" else "Démarrer"
    }

    override fun onResume() { super.onResume(); handler.post(tick) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(tick) }
}

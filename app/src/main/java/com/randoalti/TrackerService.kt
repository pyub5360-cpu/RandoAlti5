package com.randoalti

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.IBinder
import kotlin.math.exp

/**
 * Croisement baromètre + GPS (filtre complémentaire) :
 *  - le baromètre donne les variations d'altitude, précises à court terme ;
 *  - le GPS recale lentement l'altitude absolue, ce qui corrige la dérive due à la météo.
 * La correction est plafonnée à 20 m/h pour ne pas fausser la vitesse ascensionnelle.
 */
class TrackerService : Service(), SensorEventListener, LocationListener {
    private var sm: SensorManager? = null
    private var lm: LocationManager? = null
    private var hasBaro = false
    private var baroAlt: Double? = null
    private var lastFixT = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channel = "rando"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(channel, "Suivi", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notif = Notification.Builder(this, channel)
            .setContentTitle("Suivi de la montée")
            .setContentText("Mesure en cours")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)

        if (!Tracker.running) {
            Tracker.running = true
            val s = getSystemService(SENSOR_SERVICE) as SensorManager
            sm = s
            val p = s.getDefaultSensor(Sensor.TYPE_PRESSURE)
            hasBaro = p != null
            if (p != null) {
                s.registerListener(this, p, 1_000_000)
                Tracker.source = if (Tracker.offset != null) "Baromètre + GPS" else "Baromètre"
            } else {
                Tracker.source = "GPS seul"
            }
            try {
                val l = getSystemService(LOCATION_SERVICE) as LocationManager
                lm = l
                l.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 0f, this)
            } catch (e: SecurityException) {
            }
        }
        return START_STICKY
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type == Sensor.TYPE_PRESSURE) {
            val raw = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, e.values[0]).toDouble()
            baroAlt = raw
            Tracker.add(System.currentTimeMillis(), raw + (Tracker.offset ?: 0.0))
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onLocationChanged(l: Location) {
        if (!l.hasAltitude()) return
        val now = System.currentTimeMillis()
        if (!hasBaro) {
            Tracker.add(now, l.altitude)
            return
        }
        val b = baroAlt ?: return
        val acc = if (l.hasVerticalAccuracy()) l.verticalAccuracyMeters.toDouble() else 20.0
        if (acc > 30.0) return
        val err = l.altitude - b
        val o = Tracker.offset
        if (o == null) {
            // Premier recalage : toute la courbe est décalée, les vitesses ne changent pas.
            Tracker.shift(err)
            Tracker.offset = err
        } else {
            val dt = (now - lastFixT) / 1000.0
            val k = (1 - exp(-dt / 1800.0)) * minOf(1.0, 10.0 / maxOf(acc, 5.0))
            val maxStep = 20.0 / 3600.0 * dt
            val step = (k * (err - o)).coerceIn(-maxStep, maxStep)
            Tracker.offset = o + step
        }
        lastFixT = now
        Tracker.source = "Baromètre + GPS"
    }

    override fun onDestroy() {
        sm?.unregisterListener(this)
        try { lm?.removeUpdates(this) } catch (e: SecurityException) {}
        Tracker.running = false
        super.onDestroy()
    }
}

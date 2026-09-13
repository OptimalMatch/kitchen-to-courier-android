package ie.unidatum.courier

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * The unidatum node as a foreground service. Android runs a native executable
 * an app owns as long as it sits in the app's native-library directory, so the
 * engine ships as lib/arm64-v8a/libunidatum.so and is exec'd from there; the
 * repo lives in filesDir/node. DuckDB — the reads — is its musl CLI, run through
 * musl's loader (libmusl.so) by the libduckwrap.so script, with libstdcpp6.so
 * and libgccs1.so beside it.
 */
class NodeService : Service() {
    companion object {
        const val TAG = "unidatum"
        const val CHANNEL = "node"
        const val UI_PORT = 7480
        const val SYNC_PORT = 47800
        const val DHT_PORT = 47801
        const val LIBRARY = "chain-platform-shared"
        val process = AtomicReference<Process?>(null)
        val log = ArrayDeque<String>()
        fun logLine(s: String) { synchronized(log) { log.addLast(s); while (log.size > 200) log.removeFirst() } }
        fun running() = process.get()?.isAlive == true
        fun start(ctx: Context) { ctx.startForegroundService(Intent(ctx, NodeService::class.java)) }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, NodeService::class.java)) }
        fun nodeDir(ctx: Context) = File(ctx.filesDir, "node")
        fun bin(ctx: Context, name: String) = File(ctx.applicationInfo.nativeLibraryDir, name)
        fun nodeName(): String = "courier-" + android.os.Build.MODEL.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Data-sync always; the location type only once the person has granted the permission (Android 14+ refuses it otherwise).
        val hasLoc = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val type = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
            (if (hasLoc) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        startForeground(1, notification(), type)
        if (!running()) Thread { run() }.start()
        if (!positionLoop) { positionLoop = true; Thread { positions() }.start() }
        return START_STICKY
    }

    @Volatile private var positionLoop = false
    private var gps: LocationListener? = null

    /** Every 5 s: where the courier is, onto the couriers document. Simulated by default — the ride moves toward the order's
     *  next point — or the phone's GPS fixes when the mode says so. Runs as long as the service does, screen off included. */
    private fun positions() {
        val c = Courier.get(this)
        Keys.warm()
        var mode = ""
        while (positionLoop) {
            try {
                if (c.locationMode != mode) { mode = c.locationMode; setGps(mode == "gps", c) ; logLine("position: $mode") }
                if (c.joined) try { c.myOrders() } catch (_: Exception) {}
                try { c.refreshRoute() } catch (e: Exception) { logLine("route: ${e.message}") }
                if (mode == "sim") c.rideStep(5.0)
                if (c.registered) c.publishLocation()
            } catch (e: Exception) { logLine("position: ${e.message}") }
            Thread.sleep(5000)
        }
    }

    private fun setGps(on: Boolean, c: Courier) {
        val lm = getSystemService(LocationManager::class.java)
        gps?.let { lm.removeUpdates(it); gps = null }
        if (!on) return
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            logLine("position: gps mode needs the location permission; staying where the last fix or the ride left off"); return
        }
        val l = object : LocationListener {
            override fun onLocationChanged(loc: Location) { c.fix(loc.longitude, loc.latitude) }
            @Deprecated("") override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
        }
        gps = l
        Handler(Looper.getMainLooper()).post {
            try { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000L, 0f, l) } catch (e: Exception) { logLine("position: gps: ${e.message}") }
        }
    }

    private fun run() {
        try {
            val dir = nodeDir(this); dir.mkdirs()
            val engine = bin(this, "libunidatum.so")
            if (!File(dir, ".p2pfs").exists()) {
                logLine("init: library $LIBRARY as ${nodeName()}")
                exec(dir, engine.path, "init", "--library", LIBRARY, "--node-name", nodeName())
            }
            val pb = ProcessBuilder(engine.path, "ui", "--port", "$SYNC_PORT", "--dht-port", "$DHT_PORT", "--ui-port", "$UI_PORT",
                "--bind", "0.0.0.0", "--sql", "--no-mdns", "--sync-every", "5", "--seed-open")
                .directory(dir).redirectErrorStream(true)
            pb.environment()["P2PFS_DUCKDB"] = bin(this, "libduckwrap.so").path
            pb.environment()["HOME"] = dir.path
            val p = pb.start(); process.set(p)
            logLine("node: started")
            p.inputStream.bufferedReader().forEachLine { logLine(it); Log.i(TAG, it) }
            logLine("node: exited ${p.waitFor()}")
        } catch (e: Exception) { logLine("node: $e"); Log.e(TAG, "node", e) }
        process.set(null)
        stopSelf()
    }

    private fun exec(dir: File, vararg cmd: String): String {
        val p = ProcessBuilder(*cmd).directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText(); p.waitFor()
        out.lines().filter { it.isNotBlank() }.forEach { logLine(it) }
        return out
    }

    override fun onDestroy() {
        positionLoop = false
        gps?.let { getSystemService(LocationManager::class.java).removeUpdates(it) }
        process.get()?.destroy(); process.set(null)
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "unidatum node", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setContentTitle("unidatum courier node")
            .setContentText("library $LIBRARY · http://127.0.0.1:$UI_PORT").setSmallIcon(R.drawable.ic_node).setContentIntent(pi).setOngoing(true).build()
    }
}

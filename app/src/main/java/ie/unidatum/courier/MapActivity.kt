package ie.unidatum.courier

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.util.concurrent.Executors

/**
 * The map the courier rides with: where they are, where the food is, where it
 * is going, and the cycling route between them, drawn on OpenStreetMap tiles.
 * Everything on it comes from the two documents the phone already holds — the
 * order from its own node, the position it writes itself — so the map is a
 * renderer, not another source of truth. Collect and deliver are here too, so
 * the courier never has to go back to the list.
 */
class MapActivity : AppCompatActivity() {
    private lateinit var map: MapView
    private lateinit var courier: Courier
    private val ui = Handler(Looper.getMainLooper())
    private val work = Executors.newSingleThreadExecutor()
    private var orderId: String? = null
    private var follow = false   // the whole leg first; Follow me is one tap
    private var me: Marker? = null
    private var line: Polyline? = null
    private var fitted = ""
    @Volatile private var alive = true

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Configuration.getInstance().let {
            it.load(this, PreferenceManager.getDefaultSharedPreferences(this))
            it.userAgentValue = packageName   // OpenStreetMap's tiles ask for one; an app that hides is an app they block
        }
        setContentView(R.layout.activity_map)
        courier = Courier.get(this)
        orderId = intent.getStringExtra("order")
        map = findViewById(R.id.map)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller.setZoom(15.0)
        map.controller.setCenter(GeoPoint(courier.lat, courier.lon))
        map.setOnTouchListener { _, _ -> follow = false; findViewById<Button>(R.id.recentre).text = "Follow me"; false }
        findViewById<Button>(R.id.recentre).setOnClickListener {
            follow = true; (it as Button).text = "Following"; map.controller.animateTo(GeoPoint(courier.lat, courier.lon))
        }
        findViewById<Button>(R.id.navigate).setOnClickListener { openMapsApp() }
        findViewById<Button>(R.id.mapAction).setOnClickListener { act() }
        tick()
    }

    private fun order(): JSONObject? = courier.lastOrders.firstOrNull { it.optString("_id") == orderId } ?: courier.lastOrders.firstOrNull()

    private fun tick() {
        if (!alive) return
        val o = order()
        val here = GeoPoint(courier.lat, courier.lon)
        if (me == null) {
            me = Marker(map).apply { title = courier.id; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER); icon = getDrawable(R.drawable.pin_me) }
            map.overlays.add(me)
        }
        me!!.position = here
        me!!.rotation = -courier.heading.toFloat()   // osmdroid turns the icon anticlockwise; a bearing turns clockwise
        if (o != null) {
            markOnce("pickup", o.optJSONObject("pickup"), R.drawable.pin_pickup)
            markOnce("delivery", o.optJSONObject("delivery"), R.drawable.pin_delivery)
            val leg = courier.route
            if (leg != null) {
                if (line == null) { line = Polyline(map).apply { outlinePaint.color = Color.parseColor("#C8541F"); outlinePaint.strokeWidth = 12f }; map.overlays.add(0, line) }
                val pts = leg.points.map { GeoPoint(it[1], it[0]) }
                line!!.setPoints(pts)
                // A new leg: show the whole of it once, so the courier sees where they are going before it starts
                // following them. Panning afterwards is theirs to do.
                val key = o.optString("_id") + o.optString("status")
                if (key != fitted) {
                    fitted = key
                    follow = false   // a fit that follow-me immediately re-centres is no fit at all
                    findViewById<Button>(R.id.recentre).text = "Follow me"
                    val lats = pts.map { it.latitude } + here.latitude; val lons = pts.map { it.longitude } + here.longitude
                    map.post { map.zoomToBoundingBox(BoundingBox(lats.max() + 0.004, lons.max() + 0.004, lats.min() - 0.004, lons.min() - 0.004), false, 60) }
                }
            }
            val status = o.optString("status")
            val d = courier.metresToTarget(o)
            val eta = courier.route?.let { if (it.seconds > 0) " · ${Math.round(it.seconds / 60)} min by bike" else "" } ?: ""
            findViewById<TextView>(R.id.mapTitle).text = "${o.optString("_id")} · $status"
            findViewById<TextView>(R.id.mapWhere).text =
                (if (status == "ready") "to the pickup · " + (o.optJSONObject("pickup")?.optString("address") ?: "")
                 else "to the customer · " + (o.optJSONObject("delivery")?.optString("address") ?: "")) +
                (if (d == null) "" else "\n" + (if (d >= 1000) "%.1f km".format(d / 1000) else "${d.toInt()} m") + eta)
            findViewById<Button>(R.id.mapAction).text = if (status == "ready") "Collected" else "Delivered"
            findViewById<Button>(R.id.mapAction).isEnabled = true
        } else {
            findViewById<TextView>(R.id.mapTitle).text = courier.id
            findViewById<TextView>(R.id.mapWhere).text = "No order in hand. This is where you are; the hub sees it every 5 seconds."
            findViewById<Button>(R.id.mapAction).isEnabled = false
        }
        if (follow) map.controller.setCenter(here)
        map.invalidate()
        ui.postDelayed({ tick() }, 2000)
    }

    private val marks = HashMap<String, Marker>()
    private fun markOnce(key: String, place: JSONObject?, icon: Int) {
        val c = place?.optJSONObject("location")?.optJSONArray("coordinates") ?: return
        val p = GeoPoint(c.optDouble(1), c.optDouble(0))
        val m = marks.getOrPut(key) {
            Marker(map).apply { title = place.optString("address"); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM); setIcon(getDrawable(icon)); map.overlays.add(this) }
        }
        m.position = p
    }

    private fun openMapsApp() {
        val o = order() ?: return
        val to = if (o.optString("status") == "ready") o.optJSONObject("pickup") else o.optJSONObject("delivery")
        val c = to?.optJSONObject("location")?.optJSONArray("coordinates") ?: return
        val url = "https://www.google.com/maps/dir/?api=1&origin=${courier.lat},${courier.lon}" +
            "&destination=${c.optDouble(1)},${c.optDouble(0)}&travelmode=bicycling"
        try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
        catch (e: Exception) { android.widget.Toast.makeText(this, "no maps app", android.widget.Toast.LENGTH_SHORT).show() }
    }

    private fun act() {
        val o = order() ?: return
        val id = o.optString("_id"); val ready = o.optString("status") == "ready"
        findViewById<Button>(R.id.mapAction).isEnabled = false
        work.execute {
            try {
                if (ready) courier.collected(id) else courier.delivered(id)
                courier.myOrders()
                ui.post { android.widget.Toast.makeText(this, if (ready) "collected $id" else "delivered $id", android.widget.Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) { ui.post { android.widget.Toast.makeText(this, "failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show() } }
        }
    }

    override fun onResume() { super.onResume(); map.onResume() }
    override fun onPause() { super.onPause(); map.onPause() }
    override fun onDestroy() { alive = false; work.shutdownNow(); super.onDestroy() }
}

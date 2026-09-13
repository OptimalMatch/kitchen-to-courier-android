package ie.unidatum.courier

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * The courier's side of the process, the calls the MVP's sims/courier-app.mjs
 * makes — but from a phone that IS a node of the shared library, so the
 * reads and writes are local and the hub sees them when the phone syncs.
 *   join      /api/sync to hub-1's shared node, then follow platform_orders
 *   register  couriers doc on platform-eu (hub-1's API) near hub-1's pickup
 *   step 4    /api/doc/find {courier_id: mine, status ready|collected}   locally
 *   step 5    /api/doc/update $set collected / delivered                  locally
 *             couriers $set state available on platform-eu               at the hub
 */
data class MenuItem(val name: String, val priceCents: Long)

class Courier private constructor(ctx: Context) {
    companion object {
        @Volatile private var one: Courier? = null
        /** One courier per process: the activity and the service share its state (position, the orders last read). */
        fun get(ctx: Context): Courier = one ?: synchronized(this) { one ?: Courier(ctx.applicationContext).also { one = it } }
        /** Where the app starts the courier in the demo: hub-1's pickup area, so dispatch's $near finds it first. */
        val HOME = doubleArrayOf(-6.32, 53.35)
        const val SIM_SPEED_MPS = 8.0   // about 29 km/h, a brisk e-bike; the demo's clock
    }
    val prefs = ctx.getSharedPreferences("courier", Context.MODE_PRIVATE)
    var hubHost: String
        get() = prefs.getString("hubHost", "100.67.6.34")!!
        set(v) { prefs.edit().putString("hubHost", v).apply() }
    val hubSyncPort get() = prefs.getInt("hubSyncPort", 17811)
    val hubEuPort get() = prefs.getInt("hubEuPort", 17520)
    val hubId = "hub-1"
    /** "sim" (default: the ride is simulated at SIM_SPEED_MPS toward the pickup, then the customer) or "gps" (the phone's fixes). */
    var locationMode: String
        get() = prefs.getString("locMode", "sim")!!
        set(v) { prefs.edit().putString("locMode", v).apply() }
    /** The courier's id: app-<model>. The MVP's simulated couriers are hub-N-cNN; the sim leaves other ids' orders alone. */
    val id: String = "app-" + android.os.Build.MODEL.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
    val local = Api("http://127.0.0.1:${NodeService.UI_PORT}")
    val hubEu get() = Api("http://$hubHost:$hubEuPort")

    @Volatile var joined = false
    @Volatile var registered = false
    @Volatile var lastError: String? = null

    fun now(): String = Instant.now().toString()

    /** Where the courier is now, [lon, lat]: the simulated ride's point or the last GPS fix. Kept across restarts,
     *  so reinstalling the app mid-ride does not teleport the courier back to the hub. */
    @Volatile var lon = prefs.getFloat("lon", HOME[0].toFloat()).toDouble()
    @Volatile var lat = prefs.getFloat("lat", HOME[1].toFloat()).toDouble()
    private fun remember() { prefs.edit().putFloat("lon", lon.toFloat()).putFloat("lat", lat.toFloat()).apply() }
    val location: JSONObject get() = JSONObject().put("type", "Point").put("coordinates", JSONArray().put(lon).put(lat))
    /** The orders as last read, for the ride to know where it is going. */
    @Volatile var lastOrders: List<JSONObject> = emptyList()
    @Volatile var lastPublished = 0L
    @Volatile var publishError: String? = null

    /** A GPS fix from the phone (gps mode only). */
    fun fix(newLon: Double, newLat: Double) { lon = newLon; lat = newLat; remember() }

    /** The simulated ride: one 5-second step toward where the order says to go — the pickup while ready, the customer once
     *  collected, nowhere when idle. Straight line at SIM_SPEED_MPS; a courier's route is the maps app's business. */
    fun rideStep(seconds: Double) {
        val o = lastOrders.sortedBy { it.optString("ready_at") }.firstOrNull()
        // With an order: the pickup while it is ready, the customer once collected. Without one: back toward the hub's
        // area, which is where a courier waits between deliveries — and where dispatch looks for the nearest one.
        val to = o?.let { (if (it.optString("status") == "ready") it.optJSONObject("pickup") else it.optJSONObject("delivery"))?.optJSONObject("location")?.optJSONArray("coordinates") }
        val tLon = to?.optDouble(0) ?: HOME[0]; val tLat = to?.optDouble(1) ?: HOME[1]
        val mPerDegLat = 111_320.0; val mPerDegLon = mPerDegLat * Math.cos(Math.toRadians(lat))
        val dx = (tLon - lon) * mPerDegLon; val dy = (tLat - lat) * mPerDegLat
        val dist = Math.hypot(dx, dy)
        val step = SIM_SPEED_MPS * seconds
        if (dist <= step) { lon = tLon; lat = tLat } else {
            lon += dx / dist * step / mPerDegLon
            lat += dy / dist * step / mPerDegLat
        }
        remember()
    }

    /** The position onto the couriers document on platform-eu: what dispatch queries and what a customer app reads to
     *  draw the courier. Latest wins; a missed write is a missed position, nothing to queue. */
    fun publishLocation() {
        try {
            hubEu.update("couriers", JSONObject().put("_id", id), JSONObject().put("location", location).put("updated_at", now()))
            lastPublished = System.currentTimeMillis(); publishError = null
        } catch (e: Exception) { publishError = e.message }
    }

    /** Metres from the courier to the point on the order it is heading for, for the card. */
    fun metresToTarget(o: JSONObject): Double? {
        val to = (if (o.optString("status") == "ready") o.optJSONObject("pickup") else o.optJSONObject("delivery"))
            ?.optJSONObject("location")?.optJSONArray("coordinates") ?: return null
        val mPerDegLat = 111_320.0; val mPerDegLon = mPerDegLat * Math.cos(Math.toRadians(lat))
        return Math.hypot((to.optDouble(0) - lon) * mPerDegLon, (to.optDouble(1) - lat) * mPerDegLat)
    }

    /** Peer with the hub's shared node and follow the orders collection. Idempotent. */
    fun join(): String {
        val s = local.sync(hubHost, hubSyncPort)
        val r = local.replicate("platform_orders")
        // The chain publishes its menu into the shared library (signed, the menu-publish pipeline);
        // holding it locally is what lets the card name the items at a pickup with no network.
        val m = local.replicate("menu_published")
        joined = true
        return "sync: ${s.optString("msg", s.toString())}; replicate: ${r.optString("msg")}; menu: ${m.optString("msg")}"
    }

    /** One couriers document on platform-eu, near hub-1's pickup so dispatch's $near finds it. */
    fun register(): String {
        val eu = hubEu
        val mine = eu.find("couriers", JSONObject().put("_id", id), 1).firstOrNull()
        if (mine == null) {
            eu.put("couriers", JSONObject().put("_id", id).put("courier_id", id).put("hub_id", hubId).put("state", "available")
                .put("location", location)
                .put("current_order", JSONObject.NULL).put("updated_at", now()))
            registered = true
            return "registered $id on platform-eu"
        }
        if (mine.optString("state") == "off") eu.update("couriers", JSONObject().put("_id", id), JSONObject().put("state", "available").put("updated_at", now()))
        registered = true
        return "$id already on platform-eu (${mine.optString("state")})"
    }

    /** Orders dispatched to me that are not delivered yet, read from the phone's own copy. */
    fun myOrders(): List<JSONObject> {
        local.ensureLocal("platform_orders.")
        val o = local.find("platform_orders", JSONObject().put("courier_id", id).put("status", JSONObject().put("\$in", JSONArray().put("ready").put("collected"))), 20)
        lastOrders = o
        return o
    }

    fun collected(orderId: String) {
        local.ensureLocal("platform_orders.")
        local.update("platform_orders", JSONObject().put("_id", orderId).put("status", "ready"), JSONObject().put("status", "collected").put("collected_at", now()))
    }

    fun delivered(orderId: String) {
        local.ensureLocal("platform_orders.")
        local.update("platform_orders", JSONObject().put("_id", orderId).put("status", "collected"), JSONObject().put("status", "delivered").put("delivered_at", now()))
        hubEu.update("couriers", JSONObject().put("_id", id), JSONObject().put("state", "available").put("current_order", JSONObject.NULL).put("updated_at", now()))
    }

    /** restaurant:item -> (name, price) from the chain's published menu, read from the phone's node. Refreshed every minute; empty until the table has arrived. */
    @Volatile private var menu: Map<String, MenuItem> = emptyMap()
    @Volatile private var menuAt = 0L
    fun menu(): Map<String, MenuItem> {
        if (menu.isEmpty() || System.currentTimeMillis() - menuAt > 60_000) {
            try {
                val (local_, rows) = local.sqlFull("SELECT restaurant_id, item_id, name, price_cents FROM menu_published")
                menu = rows.associate { "${it.optString("restaurant_id")}:${it.optString("item_id")}" to MenuItem(it.optString("name"), it.optLong("price_cents")) }
                menuAt = System.currentTimeMillis()
                // Answered through the hub: the members are not here yet. Ask again for them; it is idempotent
                // and the names will keep coming from the hub until they land — after which the pickup needs no network.
                if (!local_) NodeService.logLine("menu: from the hub; replicate: " + local.replicate("menu_published").optString("msg"))
            } catch (e: Exception) { NodeService.logLine("menu: ${e.message}") }
        }
        return menu
    }

    /** What the hub sees of me — for the node tab; from platform-eu. */
    fun hubView(): String = try {
        val c = hubEu.find("couriers", JSONObject().put("_id", id), 1).firstOrNull()
        if (c == null) "not registered" else "${c.optString("state")}" + (c.optString("current_order").takeIf { it.isNotEmpty() && it != "null" }?.let { ", on $it" } ?: "")
    } catch (e: Exception) { "hub unreachable: ${e.message}" }
}

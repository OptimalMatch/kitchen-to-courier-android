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
        /** Only a fallback: the hub's real address comes from the platform (the hubs collection), and the courier
         *  waits at it between deliveries. This constant is what to do when the platform has not been asked yet. */
        val HOME = doubleArrayOf(-6.2783, 53.3372)
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
    /** Where this courier's hub is, as the platform publishes it. Learnt once and kept, so a courier who loses the
     *  network still knows where to wait. */
    var homeLon: Double
        get() = prefs.getFloat("homeLon", HOME[0].toFloat()).toDouble()
        private set(v) { prefs.edit().putFloat("homeLon", v.toFloat()).apply() }
    var homeLat: Double
        get() = prefs.getFloat("homeLat", HOME[1].toFloat()).toDouble()
        private set(v) { prefs.edit().putFloat("homeLat", v.toFloat()).apply() }
    @Volatile var homeAddress: String = ""
    fun home() = doubleArrayOf(homeLon, homeLat)

    /** Ask the platform where the hub is. Cheap, and only on the way in. */
    fun refreshHub() {
        try {
            val h = hubEu.find("hubs", JSONObject().put("_id", hubId), 1).firstOrNull() ?: return
            val c = h.optJSONObject("location")?.optJSONArray("coordinates") ?: return
            homeLon = c.optDouble(0); homeLat = c.optDouble(1); homeAddress = h.optString("address")
            NodeService.logLine("hub $hubId is at ${h.optString("address")}")
        } catch (e: Exception) { NodeService.logLine("hub: ${e.message}") }
    }

    /** Which way the courier is facing, degrees clockwise from north. From the phone's own bearing in gps mode when it
     *  has one, otherwise from the direction they just moved in — a courier is facing the way they are riding. */
    @Volatile var heading = prefs.getFloat("heading", 0f).toDouble()
    private fun remember() { prefs.edit().putFloat("lon", lon.toFloat()).putFloat("lat", lat.toFloat()).putFloat("heading", heading.toFloat()).apply() }

    /** The bearing from where the courier was to where they are now, ignored below a metre so a standing courier does
     *  not spin on rounding. */
    private fun face(fromLon: Double, fromLat: Double) {
        val mPerDegLat = 111_320.0; val mPerDegLon = mPerDegLat * Math.cos(Math.toRadians(lat))
        val dx = (lon - fromLon) * mPerDegLon; val dy = (lat - fromLat) * mPerDegLat
        val d = Math.hypot(dx, dy)
        if (d > 0.4) lastMoveAt = System.currentTimeMillis()
        if (d < 1.0) return
        heading = (Math.toDegrees(Math.atan2(dx, dy)) + 360.0) % 360.0
    }

    /** When the courier last actually moved, and whether that was recent enough to call them under way. A courier
     *  waiting at a door, or one whose phone has stopped reporting, is not moving and should not look like it. */
    @Volatile var lastMoveAt = 0L
    fun moving(): Boolean = System.currentTimeMillis() - lastMoveAt < 8000

    /** Put the courier at the end of the leg they are riding — the pickup, the customer, or the hub area when there is
     *  no order. A simulation control, and only useful because the ride is simulated: a real courier arrives by
     *  riding. It exists so the flow can be shown without waiting out eight minutes of Dublin traffic. */
    fun skipRide(): String {
        val t = target() ?: home()
        val was = metresTo(t[0], t[1])
        val fromLon = lon; val fromLat = lat
        lon = t[0]; lat = t[1]
        face(fromLon, fromLat)
        route = null; routeKey = ""; routeIdx = 0
        lastMoveAt = System.currentTimeMillis()
        remember()
        return "skipped ${Math.round(was)} m to ${if (target() == null) "the hub area" else "the destination"}"
    }

    /** The heading as a compass point, for a person to read. */
    fun compass(): String = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((heading + 22.5) % 360) / 45).toInt()]
    val location: JSONObject get() = JSONObject().put("type", "Point").put("coordinates", JSONArray().put(lon).put(lat))
    /** The cycling route to the point the courier is heading for: what the map draws and what the simulated ride follows,
     *  so the courier moves along streets. Refetched when the target changes; null when the router cannot be reached,
     *  and then the ride goes straight, as it did before there was a route. */
    @Volatile var route: Route.Leg? = null
    @Volatile private var routeKey = ""
    @Volatile private var routeIdx = 0

    /** The point the order says to head for now, or null when there is no order. */
    fun target(): DoubleArray? {
        val o = lastOrders.sortedBy { it.optString("ready_at") }.firstOrNull() ?: return null
        val c = (if (o.optString("status") == "ready") o.optJSONObject("pickup") else o.optJSONObject("delivery"))
            ?.optJSONObject("location")?.optJSONArray("coordinates") ?: return null
        return doubleArrayOf(c.optDouble(0), c.optDouble(1))
    }

    /** Fetch the route when the order, its status or a big jump in position has made the old one wrong. Blocking; the
     *  position loop calls it, never the main thread. */
    fun refreshRoute() {
        val o = lastOrders.sortedBy { it.optString("ready_at") }.firstOrNull()
        val t = target()
        // No order: the courier rides back to the hub's area, and that leg needs a route as much as a delivery does.
        // Without one the ride went straight and crossed the city through buildings and the river — a courier flying.
        val to = t ?: home()
        val key = if (o == null || t == null) "home" else "${o.optString("_id")}|${o.optString("status")}"
        if (metresTo(to[0], to[1]) < 30) { route = null; routeKey = "$key|arrived"; routeIdx = 0; return }
        if (key == routeKey && route != null) return
        val leg = Route.cycling(lon, lat, to[0], to[1])
        route = leg; routeKey = key; routeIdx = 0
        NodeService.logLine(if (leg == null) "route: none, riding straight" else "route to ${if (key == "home") "the hub" else "the order"}: ${Math.round(leg.metres)} m, ${Math.round(leg.seconds / 60)} min by bike")
    }

    /** The orders as last read, for the ride to know where it is going. */
    @Volatile var lastOrders: List<JSONObject> = emptyList()
    @Volatile var lastPublished = 0L
    @Volatile var publishError: String? = null

    /** A GPS fix from the phone (gps mode only). */
    fun fix(newLon: Double, newLat: Double, bearing: Double? = null) {
        val wasLon = lon; val wasLat = lat
        lon = newLon; lat = newLat
        if (bearing != null) heading = (bearing + 360.0) % 360.0 else face(wasLon, wasLat)
        remember()
    }

    /** The simulated ride: one 5-second step toward where the order says to go — the pickup while ready, the customer once
     *  collected, nowhere when idle. Straight line at SIM_SPEED_MPS; a courier's route is the maps app's business. */
    fun rideStep(seconds: Double) {
        val wasLon = lon; val wasLat = lat
        var budget = SIM_SPEED_MPS * seconds
        val pts = route?.points
        // Along the route when there is one: consume its points in order until the budget runs out, so the track on
        // the map follows streets. Without a route (no order, or the router unreachable) head straight for the target
        // — the order's next point, or the hub's area, which is where a courier waits between deliveries and where
        // dispatch looks for the nearest one.
        if (pts != null && routeIdx >= pts.size) { routeKey = ""; route = null }   // consumed: ask for the next leg
        if (pts != null && routeIdx < pts.size) {
            while (routeIdx < pts.size && budget > 0) {
                val p = pts[routeIdx]
                val d = metresTo(p[0], p[1])
                if (d <= budget) { lon = p[0]; lat = p[1]; budget -= d; routeIdx++ }
                else { stepToward(p[0], p[1], budget); budget = 0.0 }
            }
            face(wasLon, wasLat); remember(); return
        }
        val t = target()
        val tLon = t?.get(0) ?: homeLon; val tLat = t?.get(1) ?: homeLat
        val d = metresTo(tLon, tLat)
        if (d <= budget) { lon = tLon; lat = tLat } else stepToward(tLon, tLat, budget)
        face(wasLon, wasLat); remember()
    }

    private fun metresTo(toLon: Double, toLat: Double): Double {
        val mPerDegLat = 111_320.0; val mPerDegLon = mPerDegLat * Math.cos(Math.toRadians(lat))
        return Math.hypot((toLon - lon) * mPerDegLon, (toLat - lat) * mPerDegLat)
    }

    private fun stepToward(toLon: Double, toLat: Double, metres: Double) {
        val mPerDegLat = 111_320.0; val mPerDegLon = mPerDegLat * Math.cos(Math.toRadians(lat))
        val dx = (toLon - lon) * mPerDegLon; val dy = (toLat - lat) * mPerDegLat
        val d = Math.hypot(dx, dy)
        if (d < 0.01) return
        lon += dx / d * metres / mPerDegLon
        lat += dy / d * metres / mPerDegLat
    }

    /** What the courier asserts, in the exact bytes it signs. The hub rebuilds this string from the document's own
     *  fields and checks the signature over it, so the claim cannot be re-pointed at another courier, another place
     *  or another moment without breaking. Six decimal places is about 10 cm, past what any fix is worth. */
    fun positionClaim(at: String): String =
        "unidatum-courier-position/v2|$id|${"%.6f".format(java.util.Locale.ROOT, lon)}|${"%.6f".format(java.util.Locale.ROOT, lat)}|${"%.1f".format(java.util.Locale.ROOT, heading)}|$at"

    /** The position onto the couriers document on platform-eu: what dispatch queries and what a customer app reads to
     *  draw the courier — signed by the key in the phone's keystore, so the hub can tell the courier's own claim from
     *  anyone else who can write the collection. Latest wins; a missed write is a missed position, nothing to queue. */
    fun publishLocation() {
        try {
            val at = now()
            val claim = positionClaim(at)
            hubEu.update("couriers", JSONObject().put("_id", id), JSONObject()
                .put("location", location).put("heading", Math.round(heading * 10) / 10.0).put("updated_at", at)
                .put("position_claim", claim).put("position_sig", Keys.sign(claim)).put("key_alg", Keys.ALGORITHM))
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
        refreshHub()
        val eu = hubEu
        val mine = eu.find("couriers", JSONObject().put("_id", id), 1).firstOrNull()
        if (mine == null) {
            eu.put("couriers", JSONObject().put("_id", id).put("courier_id", id).put("hub_id", hubId).put("state", "available")
                .put("location", location)
                .put("current_order", JSONObject.NULL).put("updated_at", now())
                .put("public_key", Keys.publicKeyB64()).put("key_alg", Keys.ALGORITHM))
            registered = true
            return "registered $id on platform-eu, key ${Keys.backing}"
        }
        // The public key goes up whenever it is missing or has changed — a reinstall makes a new key, and the hub must
        // learn the new one or every position it signs reads as forged.
        val pk = Keys.publicKeyB64()
        if (mine.optString("state") == "off" || mine.optString("public_key") != pk)
            eu.update("couriers", JSONObject().put("_id", id), JSONObject().put("state", "available")
                .put("public_key", pk).put("key_alg", Keys.ALGORITHM).put("updated_at", now()))
        registered = true
        return "$id already on platform-eu (${mine.optString("state")})"
    }

    /** Orders dispatched to me that are not delivered yet, read from the phone's own copy. */
    fun myOrders(): List<JSONObject> {
        val o = local.find("platform_orders", JSONObject().put("courier_id", id).put("status", JSONObject().put("\$in", JSONArray().put("ready").put("collected"))), 20)
        lastOrders = o
        return o
    }

    fun collected(orderId: String) {
        local.update("platform_orders", JSONObject().put("_id", orderId).put("status", "ready"), JSONObject().put("status", "collected").put("collected_at", now()))
    }

    fun delivered(orderId: String) {
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

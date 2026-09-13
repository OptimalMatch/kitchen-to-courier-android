package ie.unidatum.courier

import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * A cycling route between two points, from OSRM's public router. The app draws
 * it, and the simulated ride follows it, so the courier moves along streets
 * rather than through buildings. No key and no account; when the router cannot
 * be reached the caller falls back to the straight line, which is what the
 * ride did before this existed.
 */
object Route {
    data class Leg(val points: List<DoubleArray>, val metres: Double, val seconds: Double)

    fun cycling(fromLon: Double, fromLat: Double, toLon: Double, toLat: Double): Leg? {
        return try {
            val u = "https://router.project-osrm.org/route/v1/cycling/" +
                "%.6f,%.6f;%.6f,%.6f".format(java.util.Locale.ROOT, fromLon, fromLat, toLon, toLat) +
                "?overview=full&geometries=polyline"
            val c = (URL(u).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000; readTimeout = 15000
                setRequestProperty("User-Agent", "kitchen-to-courier-android (unidatum demo)")
            }
            if (c.responseCode >= 400) return null
            val j = JSONObject(c.inputStream.bufferedReader().use(BufferedReader::readText))
            val r = j.optJSONArray("routes")?.optJSONObject(0) ?: return null
            val pts = decode(r.optString("geometry"))
            if (pts.size < 2) null else Leg(pts, r.optDouble("distance"), r.optDouble("duration"))
        } catch (e: Exception) { NodeService.logLine("route: ${e.message}"); null }
    }

    /** Google's encoded polyline, precision 5, which is what OSRM returns by default. */
    fun decode(s: String): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        var i = 0; var lat = 0; var lon = 0
        while (i < s.length) {
            var shift = 0; var result = 0; var b: Int
            do { b = s[i++].code - 63; result = result or ((b and 0x1f) shl shift); shift += 5 } while (b >= 0x20)
            lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            shift = 0; result = 0
            do { b = s[i++].code - 63; result = result or ((b and 0x1f) shl shift); shift += 5 } while (b >= 0x20)
            lon += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            out.add(doubleArrayOf(lon / 1e5, lat / 1e5))
        }
        return out
    }
}

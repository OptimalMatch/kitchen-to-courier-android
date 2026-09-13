package ie.unidatum.courier

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/** The engine's HTTP API, the calls the build sheet lists: put, find, count, update, status, peers, sync, replicate. */
class Api(val base: String) {
    internal fun call(method: String, path: String, body: JSONObject? = null, timeoutMs: Int = 20000): JSONObject {
        val c = URL(base + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        val text = (if (c.responseCode < 400) c.inputStream else c.errorStream).bufferedReader().use(BufferedReader::readText)
        val j = if (text.trimStart().startsWith("[")) JSONObject().put("list", JSONArray(text)) else JSONObject(text)
        if (j.has("error")) throw ApiError(j.getString("error"))
        return j
    }
    fun status(): JSONObject = call("GET", "/api/status")
    fun files(): List<JSONObject> { val a = call("GET", "/api/files").optJSONArray("list") ?: JSONArray(); return (0 until a.length()).map { a.getJSONObject(it) } }
    fun fetch(hash: String): JSONObject = call("POST", "/api/fetch", JSONObject().put("hash", hash))
    fun peers(): JSONArray = call("GET", "/api/peers").optJSONArray("list") ?: JSONArray()
    fun put(collection: String, doc: JSONObject): List<String> {
        val ids = call("POST", "/api/doc/put", JSONObject().put("collection", collection).put("document", doc)).optJSONArray("ids") ?: JSONArray()
        return (0 until ids.length()).map { ids.getString(it) }
    }
    fun find(collection: String, filter: JSONObject, limit: Int = 0): List<JSONObject> {
        val b = JSONObject().put("collection", collection).put("filter", filter); if (limit > 0) b.put("limit", limit)
        val docs = call("POST", "/api/doc/find", b).optJSONArray("documents") ?: JSONArray()
        return (0 until docs.length()).map { docs.getJSONObject(it) }
    }
    fun count(collection: String, filter: JSONObject): Int = call("POST", "/api/doc/count", JSONObject().put("collection", collection).put("filter", filter)).optInt("count")
    fun update(collection: String, filter: JSONObject, set: JSONObject): JSONObject =
        call("POST", "/api/doc/update", JSONObject().put("collection", collection).put("filter", filter).put("update", JSONObject().put("\$set", set)))
    /** Register a peer: a sync proves the node is real, speaks this library and is reachable; it records both ways. */
    fun sync(host: String, port: Int): JSONObject = call("POST", "/api/sync", JSONObject().put("host", host).put("port", port), 60000)
    /** Follow a collection: fetch every member this node lacks, then keep fetching new ones as they appear. */
    fun replicate(table: String): JSONObject = call("POST", "/api/table/replicate", JSONObject().put("table", table), 120000)
}

class ApiError(msg: String) : Exception(msg)

/** A node writes a collection only when every member is local; fetch what is still missing first (the MVP's lib/api.mjs ensureLocal). */
fun Api.ensureLocal(prefix: String): Int {
    fun missing() = files().filter { !it.optBoolean("local") && it.optString("name").startsWith(prefix) && !it.optString("name").contains(".cursors.") }
    val m = missing()
    for (f in m) try { fetch(f.getString("hash")) } catch (_: Exception) {}
    if (m.isNotEmpty()) for (i in 0 until 20) { if (missing().isEmpty()) break; Thread.sleep(500) }
    return m.size
}

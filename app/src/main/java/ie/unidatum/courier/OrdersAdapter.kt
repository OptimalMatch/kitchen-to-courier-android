package ie.unidatum.courier

import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject

class OrdersAdapter(val onCollected: (String) -> Unit, val onDelivered: (String) -> Unit, val myLocation: () -> JSONObject?) : RecyclerView.Adapter<OrdersAdapter.VH>() {
    private var items: List<JSONObject> = emptyList()
    private var menu: Map<String, MenuItem> = emptyMap()
    fun submit(o: List<JSONObject>, m: Map<String, MenuItem>) { items = o.sortedBy { it.optString("ready_at") }; menu = m; notifyDataSetChanged() }

    /** The order's lines folded by item (the same item twice is one line, qty 2), named from the published menu when the phone holds it. */
    data class Line(val itemId: String, val name: String, val qty: Int, val cents: Long)
    fun lines(o: JSONObject): List<Line> {
        val arr = o.optJSONArray("items") ?: return emptyList()
        val rid = o.optString("restaurant_id")
        val byItem = LinkedHashMap<String, Line>()
        for (k in 0 until arr.length()) {
            val it = arr.optJSONObject(k) ?: continue
            val id = it.optString("item_id"); val qty = it.optInt("qty", 1)
            val name = menu["$rid:$id"]?.name ?: it.optString("name", id)
            val prev = byItem[id]
            byItem[id] = Line(id, name, (prev?.qty ?: 0) + qty, (prev?.cents ?: 0) + qty * it.optLong("price_cents"))
        }
        return byItem.values.toList()
    }
    class VH(v: View) : RecyclerView.ViewHolder(v)

    /** A universal maps-directions link: any maps app takes it, the browser otherwise. Bicycle mode, a courier's. */
    private fun directionsUrl(from: JSONObject?, to: JSONObject): String {
        fun pt(p: JSONObject): String? = p.optJSONObject("location")?.optJSONArray("coordinates")?.let { "${it.optDouble(1)},${it.optDouble(0)}" }
        val dest = pt(to) ?: Uri.encode(to.optString("address"))
        val origin = from?.let { pt(it) ?: Uri.encode(it.optString("address")) }
        return "https://www.google.com/maps/dir/?api=1&destination=$dest&travelmode=bicycling" + (if (origin != null) "&origin=$origin" else "")
    }
    override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(LayoutInflater.from(p.context).inflate(R.layout.item_order, p, false))
    override fun getItemCount() = items.size
    override fun onBindViewHolder(h: VH, i: Int) {
        val o = items[i]; val id = o.optString("_id"); val status = o.optString("status")
        val ls = lines(o); val n = ls.sumOf { it.qty }
        h.itemView.findViewById<TextView>(R.id.orderId).text = id
        h.itemView.findViewById<TextView>(R.id.orderMeta).text =
            "$n item${if (n == 1) "" else "s"} from ${o.optString("restaurant_id")} · ${"%.2f".format(o.optLong("total_cents") / 100.0)} € · ready ${o.optString("ready_at").takeLast(13).dropLast(5)}"
        h.itemView.findViewById<TextView>(R.id.orderItems).text = ls.joinToString("\n") { "${it.qty}×  ${it.name}" + (if (it.name == it.itemId) "" else "   (${it.itemId})") }
        // Where to: the pickup until it is collected, the delivery after. Both come on the order document
        // (the platform writes the restaurant's address, the customer the delivery one).
        val pickup = o.optJSONObject("pickup"); val delivery = o.optJSONObject("delivery")
        h.itemView.findViewById<TextView>(R.id.pickup).text = "Pick up   " + (pickup?.optString("address")?.takeIf { it.isNotEmpty() } ?: o.optString("restaurant_id"))
        h.itemView.findViewById<TextView>(R.id.deliverTo).text = "Deliver to   " + (delivery?.optString("address")?.takeIf { it.isNotEmpty() } ?: "no address on the order")
        val route = h.itemView.findViewById<Button>(R.id.route)
        val to = if (status == "ready") pickup else delivery
        // To the pickup from where the courier is (the position the app registered; a real app would use GPS), then to the customer from the pickup.
        val from = if (status == "ready") myLocation() else pickup
        route.text = if (status == "ready") "Route to pickup" else "Route to customer"
        route.isEnabled = to != null
        route.setOnClickListener {
            val ctx = h.itemView.context
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(directionsUrl(from, to!!)))
            try { ctx.startActivity(i) } catch (e: Exception) { android.widget.Toast.makeText(ctx, "no maps app: ${e.message}", android.widget.Toast.LENGTH_SHORT).show() }
        }
        val pill = h.itemView.findViewById<TextView>(R.id.status); pill.text = status
        pill.setBackgroundResource(if (status == "ready") R.drawable.pill_ready else R.drawable.pill_collected)
        val b = h.itemView.findViewById<Button>(R.id.action)
        b.text = if (status == "ready") "Collected" else "Delivered"
        b.setOnClickListener { if (status == "ready") onCollected(id) else onDelivered(id) }
    }
}

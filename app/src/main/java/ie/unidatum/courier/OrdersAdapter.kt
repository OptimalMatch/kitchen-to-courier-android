package ie.unidatum.courier

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject

class OrdersAdapter(val onCollected: (String) -> Unit, val onDelivered: (String) -> Unit) : RecyclerView.Adapter<OrdersAdapter.VH>() {
    private var items: List<JSONObject> = emptyList()
    fun submit(o: List<JSONObject>) { items = o.sortedBy { it.optString("ready_at") }; notifyDataSetChanged() }
    class VH(v: View) : RecyclerView.ViewHolder(v)
    override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(LayoutInflater.from(p.context).inflate(R.layout.item_order, p, false))
    override fun getItemCount() = items.size
    override fun onBindViewHolder(h: VH, i: Int) {
        val o = items[i]; val id = o.optString("_id"); val status = o.optString("status")
        val items = o.optJSONArray("items")
        val lines = if (items == null) "" else (0 until items.length()).joinToString(", ") { k ->
            val it = items.optJSONObject(k); if (it == null) items.optString(k) else "${it.optInt("qty", 1)}× ${it.optString("item_id", it.optString("name"))}" }
        h.itemView.findViewById<TextView>(R.id.orderId).text = id
        h.itemView.findViewById<TextView>(R.id.orderMeta).text = "${o.optString("restaurant_id")} · ${"%.2f".format(o.optLong("total_cents") / 100.0)} € · ready ${o.optString("ready_at").takeLast(13).dropLast(5)}"
        h.itemView.findViewById<TextView>(R.id.orderItems).text = lines
        val pill = h.itemView.findViewById<TextView>(R.id.status); pill.text = status
        pill.setBackgroundResource(if (status == "ready") R.drawable.pill_ready else R.drawable.pill_collected)
        val b = h.itemView.findViewById<Button>(R.id.action)
        b.text = if (status == "ready") "Collected" else "Delivered"
        b.setOnClickListener { if (status == "ready") onCollected(id) else onDelivered(id) }
    }
}

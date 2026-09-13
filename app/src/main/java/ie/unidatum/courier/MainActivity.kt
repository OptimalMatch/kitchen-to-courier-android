package ie.unidatum.courier

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var courier: Courier
    private val ui = Handler(Looper.getMainLooper())
    private val work = Executors.newSingleThreadExecutor()
    private lateinit var adapter: OrdersAdapter
    @Volatile private var polling = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        courier = Courier.get(this)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        findViewById<TextView>(R.id.courierId).text = courier.id
        findViewById<EditText>(R.id.hubHost).setText(courier.hubHost)
        adapter = OrdersAdapter(onCollected = { act("collected ${it}") { courier.collected(it) } },
                                onDelivered = { act("delivered ${it}") { courier.delivered(it) } },
                                myLocation = { JSONObject().put("location", courier.location) },
                                distance = { courier.metresToTarget(it) })
        findViewById<Switch>(R.id.gpsMode).apply {
            isChecked = courier.locationMode == "gps"
            setOnCheckedChangeListener { _, on ->
                if (on && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 2)
                courier.locationMode = if (on) "gps" else "sim"
            }
        }
        findViewById<RecyclerView>(R.id.orders).apply { layoutManager = LinearLayoutManager(this@MainActivity); adapter = this@MainActivity.adapter }
        findViewById<Button>(R.id.saveHub).setOnClickListener {
            courier.hubHost = findViewById<EditText>(R.id.hubHost).text.toString().trim()
            courier.joined = false; courier.registered = false
            toast("hub set to ${courier.hubHost}; rejoining")
        }
        // The map is worth reaching with no order in hand too: it is where the ride back to the hub area is visible.
        findViewById<Button>(R.id.openMap).setOnClickListener { startActivity(android.content.Intent(this, MapActivity::class.java)) }
        findViewById<Button>(R.id.restartNode).setOnClickListener {
            NodeService.stop(this); courier.joined = false; courier.registered = false
            ui.postDelayed({ NodeService.start(this) }, 1500)
        }
        findViewById<Button>(R.id.toggleLog).setOnClickListener {
            val v = findViewById<View>(R.id.logBox); v.visibility = if (v.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        NodeService.start(this)
        ui.post(pollTick)
    }

    private val pollTick = object : Runnable {
        override fun run() {
            if (!polling) return
            work.execute { poll() }
            ui.postDelayed(this, 3000)
        }
    }

    /** One tick: node up? joined? registered? then my orders. Every failure lands in the status line, not a crash. */
    private fun poll() {
        val status = StringBuilder()
        var orders: List<JSONObject> = emptyList()
        var menu: Map<String, MenuItem> = emptyMap()
        var hubSeen = ""
        try {
            if (!NodeService.running()) { status.append("node: starting"); render(status.toString(), orders, menu, hubSeen); return }
            val st = courier.local.status()
            status.append("node ${st.optString("nodeName")} · ${st.optString("engineVersion")} · ${st.optString("library")} · ${st.optInt("files")} files · ${courier.local.peers().length()} peers")
            if (courier.hubHost.isEmpty()) { status.append("\nSet the hub's address below, then Rejoin."); render(status.toString(), orders, menu, hubSeen); return }
            if (!courier.joined) { NodeService.logLine(courier.join()); NodeService.logLine("joined ${courier.hubHost}") }
            if (!courier.registered) NodeService.logLine(courier.register())
            orders = courier.myOrders()
            if (orders.isNotEmpty()) menu = courier.menu()
            hubSeen = courier.hubView()
            courier.lastError = null
        } catch (e: Exception) {
            courier.lastError = e.message
            status.append("\n${e.message}")
            NodeService.logLine("tick: ${e.message}")
        }
        render(status.toString(), orders, menu, hubSeen)
    }

    private fun render(status: String, orders: List<JSONObject>, menu: Map<String, MenuItem>, hubSeen: String) = ui.post {
        findViewById<TextView>(R.id.nodeStatus).text = status
        findViewById<TextView>(R.id.hubSeen).text = if (hubSeen.isEmpty()) "" else "hub-1 sees me: $hubSeen"
        val age = if (courier.lastPublished == 0L) "not yet" else "${(System.currentTimeMillis() - courier.lastPublished) / 1000}s ago"
        findViewById<TextView>(R.id.position).text = "position ${"%.5f".format(courier.lat)}, ${"%.5f".format(courier.lon)} · facing ${courier.compass()} · ${courier.locationMode} · signed ${Keys.backing} · sent $age" + (courier.publishError?.let { " · $it" } ?: "")
        findViewById<TextView>(R.id.empty).visibility = if (orders.isEmpty()) View.VISIBLE else View.GONE
        adapter.submit(orders, menu)
        findViewById<TextView>(R.id.log).text = synchronized(NodeService.log) { NodeService.log.takeLast(60).joinToString("\n") }
    }

    private fun act(what: String, f: () -> Unit) = work.execute {
        try { f(); NodeService.logLine(what); ui.post { toast(what) } } catch (e: Exception) { NodeService.logLine("$what failed: ${e.message}"); ui.post { toast("failed: ${e.message}") } }
        poll()
    }

    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()

    override fun onDestroy() { polling = false; work.shutdownNow(); super.onDestroy() }
}

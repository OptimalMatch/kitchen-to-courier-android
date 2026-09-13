# Kitchen to Courier — the courier's phone as a node

An Android courier app for the
[kitchen-to-courier](https://www.unidatum.ie/en/blog/architecture-kitchen-to-courier)
architecture, built to show one thing: **the phone is not a client of a
server. It is a node.**

## What it is for

In the architecture, a restaurant chain and a delivery platform share one
library, `chain-platform-shared`, in which `platform_orders` lives. The
chain's kitchens write to it (accepted, ready), the platform's hubs write to
it (which courier), and the couriers write to it (collected, delivered). No
one owns the database; every party runs a node and holds a copy of what it
needs. The [MVP](https://github.com/OptimalMatch/kitchen-to-courier-mvp)
runs that as seven nodes in Docker with the apps simulated in JavaScript.

This app replaces one of the simulated couriers with a real phone. It
bundles the unidatum engine and runs a node of the shared library inside
the app. When the courier taps Collected, that is a write to the copy of
`platform_orders` on the phone. The hub learns about it on the next sync,
and from the hub the kitchen, the head office and the analytics node learn
about it in turn. If the phone loses the network, the courier still sees
the orders and the taps still land; they reach the hub when it is back.

Dispatch on the hub does not know the phone is different. The app registers
a `couriers` document on the platform's own library at hub-1's pickup point,
and the hub's dispatch rule, a geo query for the nearest available courier,
finds it the same way it finds the simulated ones.

## In action

The run below is against the MVP fleet (branch `main` after its PR #1) on
unidatum v2.368.0, with the phone, a Galaxy S23 Ultra, on the same Tailscale
network as the machine running compose.

### 1. Joined and waiting

<img src="docs/screenshots/1-idle.png" width="360" alt="The app idle: node status, position, hub-1 sees me available, no orders">

The node is up inside the app: `courier-sm-s918u`, engine v2.368.0, a member
of `chain-platform-shared`, holding 350 files (the members of
`platform_orders` and `menu_published` it has replicated) and peered with 7
nodes (hub-1 and, through it, the rest of the fleet). "hub-1 sees me:
available" is read back from the platform's `couriers` collection on hub-1's
other library, and the line under it is the position this app wrote there 2
seconds ago. Nothing has been dispatched.

### 2. Dispatched

<img src="docs/screenshots/2-ready.png" width="360" alt="An order dispatched to the phone: named items, pickup and delivery addresses, distance to the pickup, Route to pickup and Collected buttons">

A customer placed an order (`sims/customer.mjs`), restaurant r2's kitchen
accepted it and marked it ready, and hub-1's dispatch assigned it to the
nearest available courier: the phone. The hub wrote `courier_id` on the
order and `state: assigned` on the courier document. The phone's node pulled
the new member on its 5-second sync, the app's 3-second poll of its own node
found an order with its id, and the card appeared. The hub's log line for it
was `hub-1: o-live-mtz5pqk0-0 -> app-sm-s918u`.

The card is what a courier checks the bag against at the counter: the line
count and total first, then each line by name. The order document itself
carries only item ids and prices; the names come from `menu_published`,
the chain's signed menu that its menu-publish pipeline lands in the shared
library. The phone replicates that table too, so the lookup is local. A
line that appears twice on the order is folded into one line with the
quantity summed. Below the lines are the two addresses the order carries:
`pickup` (the restaurant's, written by the platform when it created the
order) and `delivery` (the customer's), and how far the courier still is
from the one it is heading for.

### 3. Collected

<img src="docs/screenshots/3-collected.png" width="360" alt="The order after Collected: status collected, 0 m to the customer, Route to customer and Delivered buttons">

The courier tapped Collected. The app ran one update on the node in the
phone: `{_id, status: ready} $set {status: collected, collected_at}`. The
status pill changed from the phone's own read, the route button turned to
the customer, and the simulated ride turned with it — the shot above is the
courier at the customer's door, 0 m to go. hub-1 saw the collect 1.4 to 2.0 s
later across the runs, measured by polling hub-1's API from the host until
the document changed. Delivered is the same shape and also sets the courier
document back to `available` on the platform; it reached hub-1 in 1.5 s, and
the courier then rode back toward the hub area on its own.

### The route

<img src="docs/screenshots/5-route-pickup.jpg" width="300" alt="Google Maps: bicycle route from the courier's position to the pickup, 13 min, 4.0 km"> <img src="docs/screenshots/6-route-customer.jpg" width="300" alt="Google Maps: bicycle route from the pickup to the customer, 3 min, 750 m">

Route hands the phone's maps app a directions link built from the points
on the order document, bicycle mode. Before the order is collected the
route runs from the courier's position to the pickup (left: 13 min, 4.0 km
to Capel Street); after, from the pickup to the customer (right: 3 min,
750 m to Smithfield). The app draws no map of its own and needs no maps
API key; any maps app that takes a directions URL will do, and the browser
otherwise. In this demo the courier's position is the fixed point the app
registered at hub-1's pickup area, since the phone was not in Dublin.

### The courier on the map

Every 5 seconds the app writes where the courier is onto its `couriers`
document on platform-eu — the field the build sheet already specifies as
"a geo point, set by courier app". That is the same document dispatch runs
its nearest-courier query on, so the position is not a side channel: it
feeds the assignment as well as the tracking.

A customer app reads the order, sees `courier_id`, reads that courier
document and draws the point. The MVP's `sims/track.mjs` does exactly that
from the command line:

```
01:58:20  ready   app-sm-s918u at 53.33629, -6.27028 (5s old)  423 m to the pickup
01:58:25  ready   app-sm-s918u at 53.33614, -6.26973 (5s old)  383 m to the pickup
01:58:30  ready   app-sm-s918u at 53.33599, -6.26918 (5s old)  343 m to the pickup
01:58:35  ready   app-sm-s918u at 53.33584, -6.26864 (5s old)  303 m to the pickup
```

40 metres every 5 seconds, and every fix 5 seconds old: the write cadence
end to end, phone to platform.

**Two modes**, the switch at the bottom of the screen:

- **Simulated (the default).** The courier rides at 8 m/s (about 29 km/h)
  along the route toward whatever the order says is next: the pickup while
  it is ready, the customer once collected, and back to the hub when there
  is no order, which is where dispatch looks for the nearest courier. The
  hub's address comes from the platform's own `hubs` collection, so the app
  does not carry a copy of where the hub is. The position survives a restart
  of the app, and **Skip** on the map puts the courier at the end of the leg
  — a simulation control, for showing the flow without riding out eight
  minutes of Dublin traffic.
- **GPS.** The phone's own fixes, through the foreground service, once the
  location permission is granted. This is the product path. It is off by
  default because the demo fleet's addresses are in Dublin and a phone
  anywhere else would never be the nearest courier to hub-1.

The card shows the distance to whichever point the courier is heading for,
computed from the same position.

### The map

<img src="docs/screenshots/7-map.jpg" width="360" alt="The in-app map: OpenStreetMap tiles of Dublin, the courier's chevron with its facing cone on Charlemont Street, a cycling route to the customer in Leeson Street Upper, 466 m and 4 minutes, the title reading collected and moving SE">

Everything on this screen comes from two documents the phone already holds:
the order, from its own node, and the position it writes itself. The map is
a renderer, not another source of truth. The tiles are OpenStreetMap's
through osmdroid, so the app needs no map key and no Play Services, for the
same reason it bundles the engine rather than calling a server.

The line is a real cycling route from OSRM's public router, redrawn when the
target changes: the courier's position to the pickup while the order is
ready, the pickup to the customer once it is collected. It opens showing the
whole leg; Follow me tracks the courier from there, Navigate hands the leg to
the phone's maps app for turn-by-turn, and Collected and Delivered are here
too, so nobody has to go back to the list mid-ride.

The simulated ride follows the same polyline, which is why the track on the
map runs along streets rather than through buildings — including the leg with
no order on it. When a delivery is done the courier rides back toward the hub
area, which is where dispatch looks for the nearest courier, and that leg is
routed like any other. Before it was, the courier went in a straight line and
crossed the city through buildings and the river.

<img src="docs/screenshots/9-home.jpg" width="360" alt="The map with no order in hand: the courier at Portobello riding back to the hub area, the route running through the Liberties and along the quays past Islandbridge">

The Orders button goes back to the list, and the map is reachable from the
list with no order in hand too, which is the only way to watch that leg.

The courier is a chevron with a cone, not a dot, because the position carries
a heading: the phone's own bearing in GPS mode, and the direction of travel
otherwise. The shape is deliberately not a teardrop — the pickup and the
customer are teardrops, and a courier standing at a door has to still read as
two things and not one blob. A customer app can point its courier icon the
same way, and know the direction came from the courier rather than from
whoever last wrote the row, because the heading is inside the signed claim.

<img src="docs/screenshots/8-pulse.jpg" width="700" alt="Four consecutive frames of the courier riding: a green ring expands and fades from under the chevron">

Four consecutive frames while the courier rides down Harcourt Road. The ring
expands from under the chevron and fades, and it runs only while the courier
is actually moving — not on a timer. A courier waiting at a door, or one
whose phone has gone quiet, sits still and the title says `stopped` instead
of `moving SE`. The animation carries information rather than decorating the
screen.

### Positions the hub can trust

The build sheet asks for `location` and `state` to be "signed with the
courier app's key, held by courier app, verified by hub". Held here means
held: the key is generated inside the phone's keystore and never leaves it.
On this hardware the keystore put it in the secure element — the app's own
status line reads `signed strongbox` — so the private half is not readable
by this app, by a backup, or by anyone who later roots the phone. The app
can ask it to sign; it cannot copy it.

Each position write carries the exact string it signed:

```
unidatum-courier-position/v2|app-sm-s918u|-6.269742|53.341630|133.9|2026-09-13T02:44:15.361314Z
```

and the public key sits on the courier's own document, so the hub verifies
without a key exchange. The MVP's `sims/hub-verify.mjs` rebuilds that string
from the document's own fields and checks the signature over it, which is
what makes it worth signing: the claim cannot be re-pointed at another
courier, another place or another moment without breaking.

Both tampering cases, run against the live fleet:

| what was done to the courier document | what the hub said |
|---|---|
| another writer moved the position 2 km | `claim is 53.345997, -6.302071; document is 53.36, -6.32` |
| a well-formed claim with a signature the key did not make | `the signature is not this key's over this claim` |
| another writer turned the heading to 271° | `claim faces 81.3°, document says 271°` |

Anyone who can write the collection can still write a row. Only the courier
can sign one. `checks/run.mjs` check 22 in the MVP verifies every courier
that carries a public key, and skips rather than passes when no app has
joined.

Not covered: `state`. Two parties write it — the hub sets `assigned`, the app
sets `available` — so a signature over it would break every time the hub
touched the document. And a signed position could in principle be replayed
verbatim; the hub sees how old each one is, which is how the tracker shows
"5s old", but nothing rejects a stale one yet.

### 4. What the node saw

<img src="docs/screenshots/4-node-log.png" width="360" alt="The node log: the local write, the sync push, then hub-1 and other nodes fetching the phone's new member">

The app's log panel shows the engine's own output. Reading down from
`collected o-live-mtz4n4iz-0`:

- `sync 100.67.6.34:17811: sent 1, got 0` — the phone pushed the one
  operation (its commit) to hub-1's shared node.
- `seeding platform_orders.collection.delta.parquet to 100.67.6.34:…` — the
  hub, and then five other nodes of the fleet (the different node ids in the
  `secure connection from` lines), came to the phone for the member that
  commit added. The phone is serving the fleet, not only consuming.
- `merged 1 op(s), rejected 0` — a commit from the hub side arriving on the
  phone, the dispatch's write.
- `punch: probing restaurant-1 at 100.67.6.34:47810` — the phone learned the
  other nodes' addresses from the hub, but only hub-1's shared port is
  published on the host, so those probes get no answer. The data still
  flows, through hub-1.

The order as hub-1 holds it afterwards, every field written by a different
party:

```
status:        delivered
courier_id:    app-sm-s918u
ready_at:      2026-09-13T01:20:29.330Z     kitchen r1
dispatched_at: 2026-09-13T01:20:30.088Z     hub-1 dispatch
collected_at:  2026-09-13T01:20:51.460954Z  the phone
delivered_at:  2026-09-13T01:21:18.396424Z  the phone
```

and hub-1's peer table lists the phone by the address it really has:
`courier-sm-s918u 100.107.235.10 47800`.

## What is in the APK

| file in `lib/arm64-v8a/` | what |
|---|---|
| `libunidatum.so` | the engine, the `android/arm64` release archive's `unidatum` |
| `libduckdb.so` | DuckDB's musl arm64 CLI, the engine's reader |
| `libmusl.so` | musl's loader, which runs it |
| `libstdcpp6.so`, `libgccs1.so` | its two libraries, from Alpine |
| `libduckwrap.so` | a shell script the engine gets as `P2PFS_DUCKDB` |

Android runs a native executable an app ships only from the app's own
native-library directory, and the packager takes only `lib*.so` names —
hence the names, and `patchelf` rewriting the DT_NEEDED entries to match,
including the transitive ones (libstdc++ asks for libgcc_s by name).
`tools/fetch-natives.sh` produces all six (gh access to the release repo,
`patchelf`, `curl`). They are 106 MB and stay out of git.

The node runs as a foreground service (`NodeService`): `init` once into
`filesDir/node` as `courier-<model>`, then `ui --port 47800 --ui-port 7480
--dht-port 47801 --bind 0.0.0.0 --sql --no-mdns --seed-open --sync-every 5`.
The app talks to it at `http://127.0.0.1:7480` with the same calls the MVP's
`lib/api.mjs` makes.

## The courier's calls (`Courier.kt`)

| step | call | where |
|---|---|---|
| join | `POST /api/sync {host, port: 17811}` then `POST /api/table/replicate` for `platform_orders` and `menu_published` | the phone's node |
| register | `POST /api/doc/put couriers {_id: app-<model>, hub_id: hub-1, state: available, location near hub-1's pickup}` | platform-eu, hub-1's API (17520) |
| 4 | `POST /api/doc/find platform_orders {courier_id: mine, status: {$in: [ready, collected]}}` every 3 s | the phone's node |
| 4 | `POST /api/sql SELECT restaurant_id, item_id, name, price_cents FROM menu_published`, cached a minute, to name the lines | the phone's node |
| 5 | `POST /api/doc/update {_id, status: ready} $set {status: collected, collected_at}` | the phone's node |
| 5 | `$set {status: delivered, delivered_at}`, then `couriers $set {state: available, current_order: null}` | the phone's node; platform-eu |
| every 5 s | `couriers $set {location, heading, updated_at, position_claim, position_sig}` — the claim signed by the keystore key | platform-eu, hub-1's API |

Before a write the app fetches any member of the collection the node does not
hold yet (`/api/files` + `/api/fetch`), as the MVP's `ensureLocal` does.

## Build and run

```sh
tools/fetch-natives.sh v2.368.0          # once; needs gh auth for the release repo
JAVA_HOME=~/jdk/jdk17 ANDROID_HOME=~/android-sdk ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or install the APK from the [releases](../../releases).

On the MVP side: `bin/demo-up.sh`, then set the hub host in the app to the
machine running compose (a Tailscale or LAN address the phone reaches).
Place orders with `docker compose run --rm tools node sims/customer.mjs`;
the nearest available courier to hub-1's pickup is the phone, so dispatch
assigns them to it and they appear on the phone with a Collected button.

Ports on the phone: 7480 (API, bound on all interfaces so a laptop on the
same network can query the phone's copy), 47800 sync, 47801 DHT.

## Limits

- arm64 only, Android 10+; the engine is a static Go binary, DuckDB is the musl build.
- Position defaults to a simulated ride, because the demo's addresses are in Dublin. GPS mode is a switch away and uses the phone's real fixes.
- The signature covers the position, not `state`: the hub writes `state` too, so a signature over it would break on every dispatch. Nothing rejects a replayed position yet, though its age is visible.
- The map's route comes from OSRM's public router; with no network it falls back to a straight line. Tiles are OpenStreetMap's, fine for a demo and not for a fleet of couriers.
- Only the latest position is kept, on the courier document. A position history for analytics would be an event stream in its own table.
- Until the menu table's members have landed, the names come through hub-1 (the node answers the query by asking its peers), and a pickup with no network would show item ids. Landing them needs unidatum v2.368.0 or later: before it, the phone saw head-office and hub-1 at one address on two ports and kept only the first, unpublished one.
- One hub (`hub-1`) and the MVP's port numbers are constants in `Courier.kt`; only the host is editable in the app.
- The phone reaches the rest of the fleet only through hub-1, since only hub-1's shared port is published by compose.
- The debug build only. A release build needs a signing key; no ProGuard rules are written.

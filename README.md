# Kitchen to Courier — the courier's phone as a node

An Android courier app for the [kitchen-to-courier](https://www.unidatum.ie/en/blog/architecture-kitchen-to-courier)
architecture. The phone is not a client of a server: it runs a unidatum node
of the platform's shared library (`chain-platform-shared`), holds its own copy
of `platform_orders`, reads and writes it locally, and syncs with the hub.
Dispatch on the hub assigns it orders the same way it assigns the simulated
couriers; Collected and Delivered are local writes that reach the hub on the
next sync (measured: under two seconds).

It joins the fleet of
[kitchen-to-courier-mvp](https://github.com/OptimalMatch/kitchen-to-courier-mvp)
(branch `phone-courier` publishes hub-1's shared sync port and keeps the
simulated couriers off the phone's orders).

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
hence the names, and `patchelf` rewriting the DT_NEEDED entries to match.
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
| join | `POST /api/sync {host, port: 17811}` then `POST /api/table/replicate {table: platform_orders}` | the phone's node |
| register | `POST /api/doc/put couriers {_id: app-<model>, hub_id: hub-1, state: available, location near hub-1's pickup}` | platform-eu, hub-1's API (17520) |
| 4 | `POST /api/doc/find platform_orders {courier_id: mine, status: {$in: [ready, collected]}}` every 3 s | the phone's node |
| 5 | `POST /api/doc/update {_id, status: ready} $set {status: collected, collected_at}` | the phone's node |
| 5 | `$set {status: delivered, delivered_at}`, then `couriers $set {state: available, current_order: null}` | the phone's node; platform-eu |

Before a write the app fetches any member of the collection the node does not
hold yet (`/api/files` + `/api/fetch`), as the MVP's `ensureLocal` does.

## Build and run

```sh
tools/fetch-natives.sh v2.367.0          # once; needs gh auth for the release repo
JAVA_HOME=~/jdk/jdk17 ANDROID_HOME=~/android-sdk ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On the MVP side, on branch `phone-courier`: `bin/demo-up.sh`, then set the
hub host in the app to the machine running compose (a Tailscale or LAN
address the phone reaches). Place orders with
`docker compose run --rm tools node sims/customer.mjs`; the nearest available
courier to hub-1's pickup is the phone, so dispatch assigns them to it and
they appear on the phone with a Collected button.

Ports on the phone: 7480 (API, bound on all interfaces so a laptop on the
same network can query the phone's copy), 47800 sync, 47801 DHT.

## Limits

- arm64 only, Android 10+; the engine is a static Go binary, DuckDB is the musl build.
- The courier's location is fixed at hub-1's pickup; a real app would write GPS fixes to its `couriers` document.
- One hub (`hub-1`) and the MVP's port numbers are constants in `Courier.kt`; only the host is editable in the app.
- The debug build only. A release build needs a signing key and `minifyEnabled false` is assumed (no ProGuard rules written).

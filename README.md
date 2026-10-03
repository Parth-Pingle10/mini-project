# MultiHopRelay

An Android app demonstrating **multi-hop Bluetooth Low Energy (BLE) mesh networking** with
**end-to-end encrypted file transfer**, built as a university/college networking project.

```
Phone A                 Phone B                 Phone C
   |                       |                       |
   |---- BLE ------------->|---- BLE ------------->|
   |                       |                       |
 Sender                  Relay                  Receiver
```

A and C are outside direct Bluetooth range of each other. B is within range of both. The app
discovers the path `A -> B -> C`, and A sends an encrypted file to C. **B relays the encrypted
bytes but can never decrypt, inspect, open, or execute the file's contents.**

For the full protocol/architecture design, see [`ARCHITECTURE.md`](ARCHITECTURE.md). For the
threat model, cryptographic design, and honestly-stated limitations, see
[`SECURITY.md`](SECURITY.md).

## 1. Project overview

MultiHopRelay lets several phones running this app discover each other over BLE, form an
ad-hoc multi-hop network, and transfer a file from a source device to a destination device
even when they cannot communicate directly — routing the encrypted data through one or more
intermediate "relay" devices. The core requirement driving the whole design: **a relay must be
blind** — it may know it is relaying traffic (source, destination, size, hop count) but must
never be able to read the file itself.

Highlights:
- Hybrid end-to-end encryption: AES-256-GCM per chunk + RSA-OAEP key wrapping, RSA-PSS signed
  transfer metadata, all backed by Android Keystore identity keys.
- A simple reactive (AODV-inspired) multi-hop routing protocol with loop prevention, route
  expiration, and automatic rediscovery when a relay disappears.
- Store-and-forward relaying with configurable storage/age/hop/bandwidth limits, persisted in
  Room so a temporary connection loss doesn't lose in-flight packets.
- Streaming file chunking (never loads a whole file into RAM), with resumable, out-of-order-
  tolerant reassembly and missing-chunk retransmission.
- A full Jetpack Compose UI: Home, Nearby Devices, Send, Transfers, Relay, Settings, Logs.
- An in-app **Test/Simulation Mode** network layer (`simulation/`) that runs the exact same
  routing/relay/transfer code without any BLE hardware, for fast, deterministic testing.

```
Sender
  |
  v
Encryption -> Chunking -> Routing -> BLE -> Relay -> BLE -> Receiver -> Reassembly -> Decryption
```

## 2. Architecture

See [`ARCHITECTURE.md`](ARCHITECTURE.md) for the full package layout, wire protocol byte
layout, BLE GATT profile, routing protocol details, and the transfer state machine. Short
version of the package structure:

```
app/
 +-- data/            Room database, DAOs, DataStore settings, file storage
 +-- bluetooth/        BleScanner, BleAdvertiser, BleGattServer/Client, BleConnectionManager
 +-- networking/        Packet, PacketCodec, RoutingManager, RelayManager, TransferManager, ChunkManager
 +-- crypto/            KeyManager, EncryptionManager, SignatureManager, HashManager
 +-- model/             Packet/NodeId/Route/Transfer data classes
 +-- simulation/        In-memory mesh network for testing without BLE hardware
 +-- ui/                Jetpack Compose screens
 +-- MainActivity.kt
```

## 3. Technology

- Kotlin, Jetpack Compose (Material 3), Coroutines/Flow
- Android BLE APIs (`android.bluetooth.le.*`, GATT client + server)
- Room (relay queue + transfer persistence), Jetpack DataStore (settings)
- Android Keystore (RSA-2048 identity, StrongBox-backed where available)
- `androidx.security-crypto` (`EncryptedSharedPreferences` for the small amount of non-Keystore
  identity metadata such as the cached NodeId string)
- `kotlinx-serialization-json` (declared as an available dependency per the project brief's
  "Kotlin serialization or another reliable serialization mechanism" requirement; the core BLE
  wire protocol itself uses a hand-written fixed-layout binary codec — `PacketCodec` — instead,
  since precise control over byte layout is what makes BLE MTU fragmentation and the relay's
  header-only parsing tractable; see `ARCHITECTURE.md` §3 for the rationale)
- Gradle Kotlin DSL, AGP 8.5.2, Kotlin 1.9.24, compileSdk/targetSdk 34, **minSdk 26**

minSdk 26 (Android 8.0) was chosen because it's the practical floor for reliable BLE GATT
server + peripheral-mode support and modern `KeyGenParameterSpec` Keystore features used here,
while still covering the large majority of active devices.

## 4. Installation — opening and building the project

1. Install **Android Studio** (Koala/2024.1 or newer recommended) with Android SDK Platform 34
   and a JDK 17 toolchain (Android Studio bundles a compatible JDK by default).
2. Unzip `MultiHopRelay.zip` and choose **File → Open** in Android Studio, selecting the
   unzipped `MultiHopRelay/` folder (the one containing `settings.gradle.kts`).
3. Let Gradle sync. The project includes `gradlew`/`gradlew.bat` and a
   `gradle/wrapper/gradle-wrapper.properties` pinned to Gradle 8.7 (matching AGP 8.5.2). If
   Android Studio reports the wrapper JAR is missing or asks to "repair" the project, run
   **File → Sync Project with Gradle Files**, or from a terminal in the project root run
   `gradle wrapper --gradle-version 8.7` once (using any locally installed Gradle) to
   regenerate `gradle/wrapper/gradle-wrapper.jar` — this environment's build sandbox does not
   have general internet access to Gradle's distribution service, so the wrapper jar bundled
   in this ZIP was fetched from a public mirror; if your network blocks that mirror too,
   Android Studio's bundled Gradle can build the project directly without the wrapper script.
4. Build → Make Project, or run on a device via the green ▶ Run button.
5. **A real device is required for anything Bluetooth-related** — BLE central + peripheral
   (GATT server) concurrency is not reliably supported on the Android Studio emulator. Use at
   least two, ideally three, physical Android phones (API 26+) for the full multi-hop demo.

## 5. Permissions

| Permission | Why it's needed |
|---|---|
| `BLUETOOTH_SCAN` (API 31+, `neverForLocation`) | Discover nearby nodes advertising the app's BLE service |
| `BLUETOOTH_ADVERTISE` (API 31+) | Advertise this device's presence to peers |
| `BLUETOOTH_CONNECT` (API 31+) | Open GATT client connections, run the GATT server |
| `BLUETOOTH` / `BLUETOOTH_ADMIN` (API ≤30) | Legacy equivalents of the three permissions above |
| `ACCESS_FINE_LOCATION` (API ≤30 only) | Pre-Android-12 platforms tie BLE scan results to location permission; not required on API 31+ since we declare `neverForLocation` |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Run `MeshForegroundService`, which keeps BLE scanning/advertising/GATT alive with a visible notification while a transfer or Relay Mode session is active (see §8, Background Limitations) |
| `POST_NOTIFICATIONS` (API 33+) | Show the required foreground-service notification |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Only used to let the app *inform* the user that battery optimization may throttle background relaying — never used to silently bypass it |

The app requests **only** these permissions, and only when actually needed (on first launch,
before starting BLE operations). No location data is ever read or used by the app logic — the
`ACCESS_FINE_LOCATION` permission on older Android versions exists purely because the OS
requires it to return BLE scan results, a well-documented Android platform quirk.

## 6. Running the three-phone demo

**Phone A (Sender), Phone B (Relay), Phone C (Receiver)** — three physical Android phones,
API 26+, Bluetooth enabled.

1. **Install the app on all three phones** (`Run` from Android Studio to each device in turn,
   or build a debug APK and sideload it).
2. **Grant Bluetooth permissions** on each phone when prompted on first launch.
3. **Phone B**: open **Settings** (or the **Relay** tab) and turn **Relay Mode** ON.
4. **Physical placement**: put B roughly in the middle, within BLE range (a few meters,
   depending on the phones) of both A and C, but move A and C far enough apart (or place an
   obstruction) that they are *not* in direct range of each other. A quick way to confirm this:
   open **Nearby Devices** on A — it should list B but not C, and vice versa on C.
5. **Phone A**: go to **Send File**, choose a file (e.g. `test.pdf`), select C's Node ID as the
   destination. The screen shows the discovered route once found:
   ```
   Route:
   A -> B -> C
   Encrypted: YES
   ```
6. Tap **Send**. Watch progress update on A's Send/Transfers screen, and on B's **Relay**
   screen (packets forwarded count, storage used, bandwidth ticking up).
7. **Phone C**: open **Transfers** — the incoming transfer appears, progresses, and finishes:
   ```
   Transfer complete
   From: A
   To: C
   Route: A -> B -> C
   Encrypted: YES
   Status: COMPLETE
   ```
8. The received file is saved to C's app-private storage (never auto-opened — see
   `SECURITY.md` §8). It is *not* visible in B's storage or logs in any decrypted form; B's
   **Relay** screen only ever shows aggregate stats (packets forwarded, bytes, bandwidth),
   never file names or content.

### Optional: exercising failure/rediscovery without moving phones

Turn **Relay Mode off on B mid-transfer**, or physically move B out of range of A or C, to see
`NO_ROUTE`/retry behavior on A's Transfers screen and the route-invalidation logging on B's
**Logs** screen (`ROUTE` tag entries). Turning B's Relay Mode back on / bringing it back in
range should let a fresh transfer attempt find a route again (existing in-flight ones may need
to be retried/re-sent, since this project intentionally uses a simple routing protocol — see
`SECURITY.md` §10 for the documented limitation around mid-transfer relay failure).

### Testing without three phones: Test/Simulation Mode

The `simulation/` package (see `ARCHITECTURE.md` §7) runs the exact same `RoutingManager` /
`RelayManager` / `TransferManager` logic against an in-memory, explicit-adjacency-graph network
instead of real BLE — useful for iterating on routing/relay logic on one machine, or for the
automated test suite (`app/src/test/...`), which includes an end-to-end
`A -> B -> C` encrypted-transfer test (`TransferManagerTest`) run entirely without any BLE
hardware.

## 7. Security model summary

Full details in [`SECURITY.md`](SECURITY.md). In short:

- **End-to-end encryption**: AES-256-GCM per chunk (fresh key per transfer, fresh nonce per
  chunk) with the file key RSA-OAEP-wrapped for the specific recipient. Only the recipient's
  Android-Keystore-protected private key can unwrap it.
- **The relay cannot decrypt the payload** — it only ever parses the fixed `Packet` header
  (source/destination NodeId, packet/transfer id, hop count, payload length) and treats the
  payload as opaque bytes, by construction (`RelayManager` never calls into `EncryptionManager`
  or parses `TransferInitPayload`/`ChunkDataPayload`).
- **The relay can observe network-level metadata**: that traffic exists, roughly how much, and
  basic routing headers — this is inherent to any hop-by-hop routed network and is stated
  plainly rather than hidden.
- **Node identity**: RSA-2048 keys generated in Android Keystore; the public NodeId is a
  one-way hash of the public key, never a phone number or MAC address.
- **Authentication & integrity**: RSA-PSS signed transfer metadata, AES-GCM authenticated
  encryption per chunk, a final whole-file SHA-256 check.
- **Replay protection**: per-transfer UUIDs, per-chunk sequence numbers bound into the AEAD's
  associated data, and dedup caches in both the routing and relay layers.
- **Limitations honestly documented**: no forward secrecy beyond the per-transfer key, no
  traffic-analysis resistance, no defense against a relay that silently drops (vs. tampers
  with) packets, chunk sizes are inferable from ciphertext length. See `SECURITY.md` §10 for
  the complete list.

## 8. Android background execution limitations

Android aggressively restricts background BLE scanning, advertising, and GATT activity for
battery and privacy reasons. This app does **not** claim to run BLE indefinitely in the
background undetected:
- `MeshForegroundService` runs while a transfer is active or Relay Mode is enabled, showing a
  **persistent, user-visible notification** ("MultiHopRelay is active...") — this is the
  documented, transparent Android mechanism for sustained background work, not a workaround.
- If the user force-stops the app, denies notification permission (which would prevent the
  foreground service from starting cleanly on API 33+), or the OS otherwise reclaims the
  process, BLE operations stop — the app does not attempt to evade these platform protections.
- Even with a foreground service, individual OEM battery-management skins (especially on some
  Chinese Android vendors) may still throttle background BLE more aggressively than stock
  Android; this is a known, real-world Android fragmentation issue outside this app's control.

## 9. Known limitations & what requires physical-device testing

This project was developed and its **networking/crypto/protocol core was verified to compile
cleanly** using a real Kotlin compiler in a sandboxed Linux environment without Android Studio
or an Android SDK available (see the development notes below). The following genuinely require
a physical Android device (or at minimum a real Android emulator with BLE support, which is
unreliable for peripheral/GATT-server mode) to verify:

- Real BLE central+peripheral role concurrency, actual GATT connection limits (Android
  typically supports roughly 4-7 simultaneous GATT connections depending on chipset/vendor),
  and simultaneous advertising+scanning+GATT-server operation on one radio.
- Real-world BLE range, RSSI behavior, and MTU negotiation outcomes (this varies significantly
  by phone chipset/vendor).
- Foreground service behavior under real OEM battery-management restrictions.
- Runtime permission dialogs and the exact API 26-30 vs. 31+ permission branching.
- Android Keystore StrongBox availability (varies by device; the app gracefully proceeds
  without it if unavailable).

**Development note on verification**: the Kotlin sources for the entire crypto/protocol/
routing/relay/transfer/simulation core (everything in `model/`, `crypto/`, `networking/`,
`simulation/`, plus the corresponding test suite in `app/src/test/`) were compiled with a real
`kotlinc` 1.9.24 against a hand-written stub of the small `kotlinx.coroutines`/`Flow`/
`android.util.Log` API surface actually used (since this sandboxed environment had no network
access to Maven Central or the Android SDK to fetch the real dependencies) and compiled with
**zero errors and zero warnings**. The Android-UI-specific files (Jetpack Compose screens,
Room's generated code, the BLE GATT classes' exact Android API usage) were written carefully
against documented, stable Android APIs and reviewed by hand, but were **not** compiler-
verified in this environment — please build the project in Android Studio (which will run the
real Android Gradle Plugin, Room's annotation processor via KSP, and the real Android SDK
stubs) as the authoritative build check, and report any issues found.

## 10. What this app deliberately does *not* do

- Does not use anyone's Bluetooth MAC address, phone number, or any other personally
  identifying information as a node address.
- Does not automatically execute, open, or launch a received file (see `SECURITY.md` §8).
- Does not attempt to hide network activity from the device's own owner — Relay Mode is an
  explicit, visible, user-controlled toggle, and a persistent notification shows whenever the
  mesh stack is active in the background.
- Does not install anything on, or otherwise use, a device without its owner's knowledge and
  explicit action (installing/running the app, granting permissions, and opting into Relay
  Mode are all separate, explicit user actions).

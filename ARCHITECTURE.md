# ARCHITECTURE.md — MultiHopRelay System Design

## 1. Layered overview

```
Sender
  |
  v
Encryption   (crypto/: EncryptionManager, SignatureManager, KeyManager)
  |
  v
Chunking     (networking/ChunkManager)
  |
  v
Routing      (networking/RoutingManager)
  |
  v
BLE          (bluetooth/: BleConnectionManager, BleGattClient/Server, BleScanner/Advertiser)
  |
  v
Relay        (networking/RelayManager)   <-- only touches encrypted bytes
  |
  v
BLE
  |
  v
Receiver
  |
  v
Reassembly   (networking/ChunkManager + TransferManager)
  |
  v
Decryption   (crypto/EncryptionManager)
```

Every arrow above is an actual class boundary in the code, not just a conceptual layer — the
`networking/` package is written entirely against the [`MeshTransport`](app/src/main/java/com/example/multihoprelay/networking/MeshTransport.kt)
interface and knows nothing about BLE, GATT, or Android at all. This is what lets
`RoutingManager`, `RelayManager`, and `TransferManager` run *unmodified* against either:
- **real BLE** via [`BleConnectionManager`](app/src/main/java/com/example/multihoprelay/bluetooth/BleConnectionManager.kt), or
- **an in-memory simulated network** via [`SimulatedMeshTransport`](app/src/main/java/com/example/multihoprelay/simulation/SimulatedMeshTransport.kt) (see §7, Test/Simulation Mode).

## 2. Package layout

```
app/src/main/java/com/example/multihoprelay/
 |
 +-- model/            Packet, NodeId, Route, Neighbor, Transfer, enums - pure data
 |
 +-- crypto/           KeyManager (Keystore identity), EncryptionManager (AES-GCM + RSA-OAEP),
 |                      SignatureManager (RSA-PSS), HashManager (SHA-256), IdentityProvider
 |                      (abstraction letting routing/transfer code run without Android Keystore)
 |
 +-- networking/        Packet.kt (model), PacketCodec (wire format), FragmentAssembler (BLE
 |                      MTU fragmentation), RoutingPayloads/TransferPayloads (message bodies),
 |                      MeshTransport (transport abstraction), RoutingManager, RelayManager,
 |                      TransferManager, ChunkManager
 |
 +-- bluetooth/         GattProfile (UUIDs), BlePermissions, BleAdvertiser, BleScanner,
 |                      BleGattServer, BleGattClient, BleConnectionManager (implements
 |                      MeshTransport over real BLE)
 |
 +-- data/
 |    +-- database/     Room: AppDatabase, entities (RelayPacketEntity, TransferEntity,
 |    |                  TransferChunkEntity), DAOs, TypeConverters
 |    +-- repository/   (transfer/relay state is read directly from Room Flows by the
 |    |                  ViewModel; a thin repository layer is inlined in AppViewModel for a
 |    |                  project this size, see AppViewModel.toTransferInfo())
 |    +-- settings/     SettingsRepository (DataStore-backed RelaySettings)
 |    +-- storage/      FileStorage / TransferFileStore (app-private file management)
 |
 +-- simulation/        SimulatedIdentityProvider, SimulatedMeshTransport, SimulatedNetwork,
 |                      SimulatedMeshNode - Test/Simulation Mode, kept import-isolated from
 |                      the bluetooth/ package in both directions
 |
 +-- ui/                Jetpack Compose screens (home, nearby, send, transfers, relay,
 |                      settings, logs) + AppViewModel + theme
 |
 +-- service/           MeshForegroundService (keeps BLE alive per Android background limits)
 |
 +-- util/              MeshLogger (in-memory ring-buffer debug log)
 |
 +-- AppContainer.kt     Hand-rolled DI container wiring identity -> transport -> routing ->
 |                       relay -> transfer for production use
 +-- MultiHopRelayApp.kt Application class
 +-- MainActivity.kt     Permission flow + Compose NavHost
```

## 3. Wire protocol

### 3.1 Packet header

Every message between two directly-connected nodes is a `Packet`, encoded by `PacketCodec`
into this fixed binary layout (all multi-byte integers big-endian):

```
Offset  Field                   Size
0       version                 1 byte
1       packetType              1 byte
2-17    packetId (UUID)         16 bytes
18-33   transferId (UUID)       16 bytes  (all-zero sentinel if not part of a transfer)
34-41   sourceNodeId            8 bytes (ASCII hex)
42-49   destinationNodeId       8 bytes (ASCII hex)
50      hopCount                1 byte
51      maxHopCount             1 byte
52-59   sequenceNumber          8 bytes (long, big-endian)
60-63   payloadLength           4 bytes (int, big-endian)
64..    payload                 [payloadLength] bytes
..      crc32                   4 bytes (corruption check only - see SECURITY.md §4)
```

A manual, fixed-layout binary codec (rather than a general-purpose serialization library) was
used deliberately: it keeps every header byte accounted for, makes the BLE fragmentation
boundary simple to reason about, and lets a relay parse *only* the small routing header without
ever touching the payload bytes at all — this is central to the blind-relay guarantee.

### 3.2 Packet types

| Type | Code | Direction | Purpose |
|---|---|---|---|
| `HELLO` | 1 | 1-hop only | Periodic neighbor announcement (never forwarded) |
| `ROUTE_REQUEST` | 2 | Flooded | Route discovery (RREQ) |
| `ROUTE_REPLY` | 3 | Unicast, reverse path | Route discovery reply (RREP), carries target's public key |
| `ROUTE_ERROR` | 4 | Flooded | Announces a broken next hop (RERR) |
| `TRANSFER_INIT` | 10 | Sender -> Receiver | Signed, encrypted-key transfer metadata |
| `TRANSFER_ACCEPT` | 11 | Receiver -> Sender | Ready to receive |
| `TRANSFER_REJECT` | 12 | Receiver -> Sender | Declined (bad signature, invalid metadata) |
| `CHUNK_DATA` | 13 | Sender -> Receiver | One AES-GCM sealed chunk |
| `CHUNK_ACK` | 14 | Receiver -> Sender | Chunk received + authenticated |
| `TRANSFER_COMPLETE` | 15 | Sender -> Receiver | All chunks sent |
| `TRANSFER_CANCEL` | 16 | Either | Abort |
| `CHUNK_RESEND_REQUEST` | 17 | Receiver -> Sender | Requests retransmission of specific missing chunks |

### 3.3 BLE link-layer fragmentation

A BLE ATT payload is small (20 bytes at the default 23-byte MTU; up to ~500 bytes after MTU
negotiation). `FragmentAssembler` splits an encoded `Packet` into fragments that fit the
negotiated per-link MTU:

```
Offset  Field              Size
0       fragmentFlags      1 byte   (bit 0: isFirst, bit 1: isLast)
1-4     logicalMessageId   4 bytes  (random id grouping fragments of one Packet)
5-6     fragmentIndex      2 bytes
7-8     totalFragments     2 bytes
9..     fragmentPayload    remaining bytes
```

`LinkReassemblyBuffer` (one instance per BLE link) reassembles fragments back into a complete
`Packet` byte array, tolerating out-of-order arrival and multiple interleaved logical messages
on the same link, and evicting stale in-progress reassembly state if a peer vanishes mid-message.

## 4. BLE transport (GATT profile)

Every device runs **both** roles simultaneously — a GATT server (peripheral) via
`BleGattServer`, and, for peers it decides to initiate toward, a GATT client (central) via
`BleGattClient` — because two arbitrary phones need to be able to talk regardless of who
happened to be scanning vs. advertising when they found each other.

- **Advertisement** (`BleAdvertiser`): a custom 128-bit `SERVICE_UUID`, plus service data
  containing only the protocol version byte and the 8-character NodeId — nothing else.
- **Scanning** (`BleScanner`): filtered to `SERVICE_UUID` so only protocol-compatible peers
  show up, decoding the NodeId from the advertisement's service data without needing to
  connect first.
- **GATT characteristics**: `CHARACTERISTIC_RX` (client writes fragments here) and
  `CHARACTERISTIC_TX` (server notifies fragments here, Nordic-UART-style duplex over one
  connection).
- **Connection symmetry-breaking**: when two nodes discover each other, both *could* try to
  open a GATT client connection to the other. `BleConnectionManager` breaks this tie
  deterministically — a node only initiates if its own NodeId sorts lexicographically lower
  than the peer's — avoiding redundant double connections without any extra negotiation.
- **MTU negotiation**: the client requests `REQUESTED_MTU = 517` on connect; whatever MTU is
  actually negotiated becomes that link's fragment size for `FragmentAssembler`.

## 5. Routing (`RoutingManager`)

A deliberately simple, reactive (on-demand) protocol loosely inspired by AODV — appropriate
for a small, course-project-scale mesh rather than internet-scale routing:

```
        A
        |
        +-- B
        |   \
        |    C
        |
        +-- D
```

- **Discovery**: to reach an unknown destination, a node floods `ROUTE_REQUEST` to all current
  neighbors. Each relay that has not already seen `(originNode, requestId)` re-broadcasts it,
  appending itself to the visited path, until `maxHopCount` is hit or the destination replies.
- **Reply**: the destination (or any node with a fresh cached route to it) sends a unicast
  `ROUTE_REPLY` back along the reverse of the visited path, also carrying the destination's
  public key (piggybacked so the sender can learn a multi-hop peer's public key without ever
  connecting to it directly — see SECURITY.md §2).
- **Loop prevention**: every RREQ carries its visited-node list; a node refuses to
  forward/accept an RREQ that already contains its own NodeId. A duplicate-packet cache (by
  `packetId`) additionally drops repeated floods regardless of path.
- **Expiration & rediscovery**: routes older than `Route.DEFAULT_ROUTE_TTL_MILLIS` (2 minutes)
  are pruned, forcing rediscovery. If a neighbor disconnects, every route whose `nextHop` was
  that neighbor is immediately invalidated (`onNeighborLost`) and a `ROUTE_ERROR` is broadcast
  — this is exactly the "B disappears -> A discovers an alternate route" scenario from the
  requirements, and is covered by `RoutingManagerTest`.

## 6. Relay / store-and-forward (`RelayManager`)

```
A ---> B ---> C          (B forwards immediately, next hop known)

A ---> B     C (out of range)
       |
       +-- store encrypted packet, retry every 5s

...later, C comes into range...

A     B ---------- C
      |
      +-- forward stored packet, then delete it
```

1. A packet not addressed to this node is only even considered if **Relay Mode is on**.
2. Validate: hop count vs. `maxHopCount`/configured `maxHops`, duplicate/replay check
   (in-memory recently-forwarded set + persisted-queue existence check), storage-limit check.
3. **Persist to Room** (`RelayPacketEntity`, via `RelayQueueDao`) *before* attempting to
   forward — this is what makes it store-and-forward rather than best-effort: a temporary next
   -hop disconnection does not lose the packet.
4. Attempt immediate forward if a next hop is already known (direct neighbor or routing
   table); otherwise it stays `QUEUED` and a 5-second background loop retries.
5. On successful forward: increment stats, add to the replay-guard set, **delete from the
   persisted queue**.
6. A background sweep purges packets past `maxPacketAgeMillis` and recomputes storage/bandwidth
   stats surfaced on the Relay screen.

The relay logic only ever touches `Packet`'s fixed header fields — it decodes `Packet` via
`PacketCodec` (header + opaque payload bytes) and re-encodes it unchanged (only `hopCount`
incremented) for the next hop. It never attempts to interpret `TransferInitPayload`,
`ChunkDataPayload`, or any other application-layer payload structure.

## 7. Test/Simulation Mode

`simulation/SimulatedNetwork` is an explicit adjacency graph (`connect(a, b)` /
`disconnect(a, b)`) with a `SimulatedMeshTransport` per node implementing the exact same
`MeshTransport` interface `BleConnectionManager` implements. `SimulatedMeshNode` wires the
*production* `RoutingManager` / `RelayManager` / `TransferManager` classes against it, each
with its own in-memory Room database and software (non-Keystore) identity. This lets the full
`A -> B -> C` topology, route failure/rediscovery, and blind-relay-with-store-and-forward
behavior be exercised deterministically in unit tests (see `app/src/test/...`) with zero BLE
hardware — and is architecturally isolated: `simulation/` never imports `android.bluetooth.*`,
and `bluetooth/` never imports anything from `simulation/`.

## 8. Transfer state machine (`TransferManager`)

```
DISCOVERING -> ROUTE_FOUND -> PREPARING -> ENCRYPTING -> TRANSFERRING -> RECEIVING -> VERIFYING -> COMPLETED
                                                                 \
                                                                  -> FAILED / CANCELLED / EXPIRED / NO_ROUTE
```

- **Sender**: discovers a route, chunks the source file (streamed via `RandomAccessFile`,
  never loading the whole file into memory), generates a random AES file key, wraps it for the
  recipient, signs the metadata, sends `TRANSFER_INIT`, waits for `TRANSFER_ACCEPT`, then
  streams `CHUNK_DATA` packets (each independently AEAD-sealed), sends `TRANSFER_COMPLETE`,
  and responds to any `CHUNK_RESEND_REQUEST` before finishing.
- **Receiver**: verifies `TRANSFER_INIT`'s self-certifying identity + RSA-PSS signature,
  pre-allocates a staging file sized to the declared file size, unwraps the file key with its
  Keystore private key, decrypts+authenticates each `CHUNK_DATA` (rejecting outright on AEAD
  failure), writes each chunk at its correct offset (supporting out-of-order arrival), ACKs
  each chunk, and on `TRANSFER_COMPLETE` checks for any still-missing chunk indices (requesting
  resend if so) before doing a final whole-file SHA-256 check and moving the file to
  `TransferFileStore`'s app-private "received" directory.
- All progress (`chunksDone`/`totalChunks`, `state`, `route`) is persisted to Room
  (`TransferEntity`) so the UI (`TransfersScreen`) reflects live progress via a `Flow`, and so
  an interrupted transfer's state survives process death.

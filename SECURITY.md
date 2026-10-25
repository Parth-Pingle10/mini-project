# SECURITY.md — MultiHopRelay Threat Model & Cryptographic Design

This document describes, honestly and specifically, what MultiHopRelay protects against, what
it does not, and why. It is written for a university networking/security project — the goal is
a correct and clearly-explained design, not a production-grade security product.

## 1. Actors and trust model

| Actor | Role | Trust level |
|---|---|---|
| Sender (A) | Originates a file transfer | Trusted to have the receiver's genuine public key (via route discovery) |
| Receiver (C) | Final destination of a file transfer | Trusted to hold its own private key securely (Android Keystore) |
| Relay (B) | Forwards packets it isn't the source or destination of | **Untrusted** with respect to file content; trusted only to route/deliver-or-drop packets |
| Passive local observer | Anyone within BLE range | Untrusted — assumed able to see all BLE traffic (advertisements, GATT writes/notifications) |

The central design goal: **a relay node must not be able to decrypt, inspect, open, execute,
or otherwise access the contents of a file it relays.** A relay *can* observe:
- that traffic exists between two other nodes,
- the source/destination NodeId, packet id, transfer id, hop count, and packet size,
- coarse timing and volume of traffic (how many packets, how often).

A relay *cannot*:
- decrypt the file content or file key,
- learn the file name in plaintext (it's inside the encrypted `TRANSFER_INIT` payload),
- learn the file's plaintext size beyond what ciphertext length already reveals (see §6),
- modify the payload without detection (AEAD authentication fails on the receiver).

## 2. Node identity

Every installation generates an RSA-2048 key pair inside the **Android Keystore**
(`KeyManager.generateIdentity()`), StrongBox-backed when the hardware supports it. The private
key is generated inside the secure element/TEE and **is never exported** — the app never calls
any API that would extract raw private key bytes, and the Keystore itself refuses to allow it.

The public **NodeId** is `SHA-256(publicKey.encoded)` truncated to the first 4 bytes, rendered
as 8 hex characters (e.g. `7F21A93C`). This is a one-way, non-invertible derivation: it reveals
nothing about the device, its owner, or any personal information — unlike using a phone number
or a stable Bluetooth MAC address (which we deliberately never use for node addressing).

**Self-certifying identity**: when node C receives a `TRANSFER_INIT`, it recomputes
`SHA-256(claimed sender's embedded public key)` and checks it equals the packet's
`sourceNodeId` header field. This means a malicious relay cannot make a `TRANSFER_INIT`
*appear* to come from a different NodeId than the public key it carries — any attempt to swap
either field independently is caught before the signature is even checked.

## 3. End-to-end encryption (hybrid scheme)

```
file
  |
  v
random AES-256 key (fresh per transfer, generated with SecureRandom)
  |
  +--> AES-256-GCM, one fresh random 96-bit nonce per chunk --> encrypted chunks (confidentiality + integrity)
  |
  +--> RSA-OAEP-SHA256, wrapped with recipient's Keystore public key --> wrapped file key
```

- **Why hybrid, not RSA-only**: RSA cannot encrypt arbitrary-length data efficiently (or at all
  beyond its modulus size), so only the small (32-byte) symmetric key is RSA-wrapped. The bulk
  file data is encrypted with a symmetric AEAD cipher, which is fast and has no message-size
  limitation in practice.
- **AES-256-GCM** is used for each chunk independently. Each chunk gets its own randomly
  generated 12-byte nonce (`SecureRandom`, never reused, never derived from a counter) and its
  own 128-bit authentication tag. GCM provides both confidentiality and integrity/tamper
  detection in one primitive — this satisfies the "authenticated encryption" requirement
  directly; we do not need (and do not use) a separate MAC.
- **RSA-OAEP with SHA-256/MGF1** wraps the file key for the recipient. OAEP (not raw RSA or
  PKCS#1 v1.5 padding) is the modern, IND-CCA2-secure padding scheme for RSA encryption.
- **Associated Authenticated Data (AAD)**: each chunk's GCM call binds `(transferId,
  chunkIndex)` as AAD. This means an attacker (including a malicious relay) cannot take a
  validly-encrypted chunk from position 5 and replay it as position 3, or move it into a
  different transfer — GCM authentication fails immediately if the AAD doesn't match what was
  used at encryption time.
- **Where keys live**: the random per-transfer AES file key is generated in memory on the
  sender, wrapped for the recipient, and (for resume support) persisted **only in the local,
  app-private Room database on the sender's own device** — never transmitted anywhere except
  as the RSA-wrapped blob addressed to the specific recipient. The receiver unwraps it using
  its Keystore-backed private key, which itself never leaves the Keystore.

## 4. Authentication and integrity

- **RSA-PSS (SHA-256/MGF1)** signs the `TRANSFER_INIT` metadata (file name, size, chunk count,
  wrapped key, file hash) using the sender's Keystore private key. The receiver verifies this
  signature against the sender's public key (which is embedded in the same message and
  cross-checked against the claimed NodeId, per §2) before accepting the transfer. This
  detects any relay tampering with transfer metadata in transit.
- **Per-chunk AEAD tags** (AES-GCM) detect any tampering with chunk ciphertext or its AAD. Any
  failure throws `AEADBadTagException`, which `TransferManager` treats as "reject this chunk
  outright" — it never falls back to accepting partial/unauthenticated output.
- **Final whole-file SHA-256** check: after all chunks are received and individually
  authenticated, the receiver hashes the fully reassembled plaintext file and compares it
  against the hash embedded (and signed) in `TRANSFER_INIT`. This catches any chunk-ordering
  or reassembly bug even though each chunk was already individually authenticated.
- **Link-layer CRC32**: every wire-format `Packet` also carries a CRC32 over its header+payload
  bytes. This is *not* a security mechanism (a relay could recompute a valid CRC over
  arbitrary bytes) — it exists purely to catch accidental corruption from BLE transport bugs,
  and is explicitly documented as such in `Packet`'s KDoc so it is never mistaken for
  cryptographic authentication.

## 5. Replay protection

- Every transfer has a random `transferId` (UUID). Every chunk carries a `chunkIndex`
  (sequence number) and its own random nonce.
- The AEAD's AAD binds `(transferId, chunkIndex)`, so a captured, valid ciphertext chunk
  cannot be replayed into a *different* transfer or at a *different* index and still pass
  authentication.
- `RelayManager` maintains a short-lived in-memory "recently forwarded" set plus checks
  against the persisted relay queue, so an identical packet re-injected by an attacker within
  the replay window is dropped rather than forwarded twice.
- `RoutingManager` keeps a `(originNode, requestId)` dedup table for route requests, so a
  captured RREQ cannot be re-flooded to cause a broadcast storm or to poison the route table
  with stale paths.

## 6. What the relay *can* see (and why that's an accepted, documented limitation)

Being fully transparent about the residual metadata leakage:

- **Traffic existence and volume**: a relay (or anyone passively observing BLE traffic) can
  tell that node A is communicating with node C, roughly how much data, and for how long. BLE
  itself is not designed to hide connection metadata, and this app makes no claim otherwise.
- **Ciphertext length ~= plaintext length**: AES-GCM does not pad, so the ciphertext size for a
  chunk is (plaintext chunk size + 16-byte tag). A relay could therefore infer the approximate
  total file size from the number and size of `CHUNK_DATA` packets, even though it cannot read
  a single byte of content. Padding to a fixed chunk size would mitigate this but was left out
  to keep the demo/protocol simple — this is called out explicitly here as a known limitation
  rather than silently ignored.
- **Routing metadata is inherently visible**: `sourceNodeId`, `destinationNodeId`, `packetId`,
  `transferId`, `hopCount`, and payload length are read by every relay along the path, because
  routing literally requires this information to decide where to forward a packet. No relay
  ever sees the *payload* itself in plaintext, but this header metadata is unavoidably visible
  to every hop by the nature of hop-by-hop routing (this is analogous to how any packet-switched
  network's routers see addressing headers).
- **Timing correlation**: a global passive adversary who watches every hop could in principle
  correlate timing to guess which traffic belongs to which transfer, even across multiple
  relays. This app does not implement traffic-analysis countermeasures (e.g. fixed-rate cover
  traffic, onion-routing-style layered encryption) — that is out of scope for a course project
  and is explicitly **not** claimed to be solved here.

We do **not** claim BLE or this application's encryption makes a transfer invisible or
untraceable — only that the **payload content is protected end-to-end**, which is the stated
requirement.

## 7. Relay Mode as a privacy/consent boundary

- Relaying is **strictly opt-in** (`Settings -> Relay Mode`, or the toggle on the Relay screen).
  With Relay Mode off, a device still sends/receives its own transfers but forwards nothing on
  behalf of other nodes — `RelayManager.onPacketReceived()` checks this flag first and drops
  (does not even queue) any non-self-addressed packet when it is off.
- When Relay Mode is on, the device may forward packets **automatically** without a per-packet
  prompt — the requirements explicitly call this out as acceptable ("once enabled, the device
  may automatically relay permitted encrypted packets without asking for every individual
  packet"), since asking for every packet would make relaying practically unusable.
- Configurable limits (`Settings` screen / `RelaySettings`) bound how much a relay exposes
  itself to: maximum storage, maximum packet age, maximum hop count, maximum concurrent
  relays, and a bandwidth cap. These protect the relaying device's own resources; they are not
  themselves security boundaries for the payload (which is protected by encryption regardless
  of these limits).

## 8. Untrusted received files

Per requirement #18, a decrypted, verified file is written only to **app-private storage**
(`context.filesDir/received/...`, never a shared/public directory) and the app **never
automatically opens, executes, or launches it**. `FileStorage`/`TransferFileStore` only ever
exposes a path; `TransfersScreen` shows the file's existence and metadata but does not invoke
any "open" action automatically. If a future UI adds an explicit "Open" button, it would go
through Android's standard `ACTION_VIEW`/chooser flow, which itself applies the OS's normal
app-sandboxing and permission model — this app does not attempt to bypass that model.

## 9. Logging discipline

`MeshLogger` is a development/demo aid only. By convention (documented in its KDoc and
enforced by code review across this codebase), it is never passed private keys, symmetric file
keys, decrypted file bytes, or raw file content — only protocol/routing metadata (node ids,
packet ids, chunk indices, sizes, states). You can audit this by grepping the codebase: no
`logger.*` call anywhere references a `SecretKey`, `PrivateKey`, or decrypted `ByteArray`
containing file content.

## 10. Known limitations (explicitly out of scope)

- **No forward secrecy per-message beyond the per-transfer random file key.** If a device's
  long-term RSA private key is ever compromised, every past `TRANSFER_INIT` wrapped-key blob
  addressed to that device that an attacker recorded could be decrypted retroactively. A
  production system would use an ephemeral key-agreement handshake (e.g. X3DH/Signal-style) per
  transfer; this was judged out of scope for a course project given the added protocol
  complexity, and is documented here rather than silently omitted.
- **No protection against a fully malicious relay silently dropping packets** (as opposed to
  tampering with them, which *is* detected). Store-and-forward with retries mitigates
  *accidental* loss (relay temporarily out of range), not deliberate censorship by a
  bad-faith relay — a real mesh network would need redundant multi-path routing to fully
  address this, which is beyond this project's scope.
- **No traffic-analysis resistance** (see §6).
- **Route discovery (RREQ flooding) has no admission control** beyond hop-count and duplicate
  suppression — a malicious node could flood RREQs to consume neighbors' bandwidth
  (denial-of-service), which this simple, course-appropriate routing protocol does not defend
  against.
- **Chunk plaintext length is inferable from ciphertext length** (see §6) since chunks are not
  padded to a fixed size.
- **BLE pairing/bonding is not used.** All confidentiality/integrity comes from the
  application-layer AEAD scheme described above, not from BLE's own link-layer security
  (which would require pairing UX we chose not to add, since the payload is already protected
  independently of the transport).
- This app has not undergone any third-party security review or formal verification. It is a
  learning project demonstrating correct use of standard, well-reviewed primitives (AES-GCM,
  RSA-OAEP, RSA-PSS via the Android/JCA providers) — it is **not** intended for protecting
  data with real-world sensitivity or adversarial stakes.

## 11. Cryptographic primitives summary

| Purpose | Primitive | Key size | Notes |
|---|---|---|---|
| Node identity / signing / key wrap | RSA (Keystore) | 2048-bit | One key pair serves both signing (PSS) and encryption (OAEP) roles — a simplification noted in `KeyManager`'s KDoc; a production system would use separate keys for each purpose |
| File encryption | AES-GCM | 256-bit | Fresh key per transfer, fresh nonce per chunk, 128-bit tag |
| Key transport | RSA-OAEP | — | SHA-256/MGF1, `RSA/ECB/OAEPWithSHA-256AndMGF1Padding` via JCA |
| Metadata signatures | RSA-PSS | — | SHA-256/MGF1, salt length 32 |
| Chunk/file integrity | SHA-256 | — | Per-chunk (informational) and whole-file (authoritative, checked) |
| Link-layer corruption check | CRC32 | — | Not a security mechanism — see §4 |

All cryptographic operations use the standard Java Cryptography Architecture (JCA) providers
bundled with Android — no custom/home-grown cryptographic algorithm is used anywhere in this
project.

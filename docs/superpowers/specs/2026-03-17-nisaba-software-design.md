# Nisaba Software Design Specification

**Date:** 2026-03-17
**Status:** Design approved, pending implementation planning
**Companion:** `nisaba-arch.md` (architecture document — problem, topology, schema, build phases)
**Stack:** Kotlin, Spring Boot 4 (latest), Arrow-kt, GraalVM Native, PostgreSQL + TimescaleDB

This document specifies the internal software design for Nisaba v1 — the download job distributor. It covers package structure, module boundaries, interfaces, data flow, error handling, and testing strategy. The architecture document covers the *what* and *why*; this document covers the *how*.

---

## Table of Contents

1. [Decisions Resolved](#1-decisions-resolved)
2. [Architecture Approach](#2-architecture-approach)
3. [Package Structure](#3-package-structure)
4. [Error Handling with Arrow-kt](#4-error-handling-with-arrow-kt)
5. [TorrentClient Interface & Adapter Pattern](#5-torrentclient-interface--adapter-pattern)
6. [State Machine](#6-state-machine)
7. [30s Sync Loop](#7-30s-sync-loop)
8. [Boot & Reconciliation Sequence](#8-boot--reconciliation-sequence)
9. [Configuration Design](#9-configuration-design)
10. [*arr-facing API — Response Mapping](#10-arr-facing-api--response-mapping)
11. [Testing Strategy](#11-testing-strategy)
12. [Future Considerations](#12-future-considerations)

---

## 1. Decisions Resolved

### Blocking decisions (from arch doc Section 9)

**Decision 1 — Node credential storage:** `nodes.yml` config file with restrictive file permissions (`chmod 600`). Sufficient for homelab. When a secret manager is added later, swap the config loader — the rest of the app is unaffected.

**Decision 2 — Category-to-save-path mapping:** Lives in `application.yml` under `nisaba.categories`. Separated from `nodes.yml` so that credentials are isolated. When credentials move to a secret manager, `nodes.yml` goes away entirely.

### Additional decisions made during brainstorming

**`*arr`-facing API auth:** Static username/password in `application.yml` under `nisaba.auth`. Mirrors real qBittorrent auth behavior (SID cookie).

**Spring Boot version:** Spring Boot 4 (latest).

**Multi-client support:** v1 supports qBittorrent only. The `TorrentClient` interface is the adapter boundary — adding Transmission, Deluge, or rTorrent later means implementing a new adapter. No code changes outside `client/`.

**Workflow orchestration:** Not in v1. The state machine + scheduler pattern is sufficient for the download distributor. When post-download pipeline work arrives (transcode, HLS conversion, transport), a workflow orchestrator (Conductor, Temporal) can be introduced. The `DONE` state becomes the handoff trigger.

---

## 2. Architecture Approach

**Layered architecture with a clean adapter boundary.** Four horizontal layers with strict dependency direction (each layer only calls the one below):

```
API Layer          → qBit-compatible REST controllers
    ↓
Service Layer      → Business logic, state machine, Arrow-kt Either
    ↓
Client Layer       → TorrentClient interface → QBittorrentClient adapter
    ↓
Persistence Layer  → Spring Data JDBC repositories, Flyway migrations
```

This is standard Spring Boot convention with one element borrowed from Hexagonal architecture: the `TorrentClient` interface at the client layer boundary. This gives us the adapter point for future torrent client support without the indirection cost of full ports-and-adapters.

**Architecture diagram:** `diagrams/06-software-architecture.svg`

---

## 3. Package Structure

```
com.nisaba
├── api/                          # API Layer — qBit-compatible REST surface
│   ├── controller/
│   │   ├── AuthController.kt         # POST /api/v2/auth/login
│   │   ├── AppController.kt          # GET /api/v2/app/{version,webapiVersion,preferences}
│   │   ├── TorrentController.kt      # /api/v2/torrents/{info,add,delete,properties,files,...}
│   │   └── CategoryController.kt     # /api/v2/torrents/{categories,createCategory,setCategory}
│   ├── dto/                          # Request/response DTOs matching qBit JSON contracts
│   │   ├── QBitTorrentInfo.kt
│   │   ├── QBitPreferences.kt
│   │   ├── QBitAddRequest.kt
│   │   └── ...
│   ├── auth/
│   │   └── SessionAuthFilter.kt      # SID cookie validation filter
│   └── mapper/
│       └── StateMapper.kt            # Internal state → qBit state string mapping
│
├── service/                      # Service Layer — all business logic
│   ├── RegistryService.kt            # Torrent CRUD, dedup check, state transitions
│   ├── RouterService.kt              # EMA-weighted node selection, round-robin tiebreak
│   ├── SyncService.kt                # 30s poll loop, progress diffing, stall detection
│   ├── ReassignmentService.kt        # Pause-on-old, re-add-to-new, failover orchestration
│   ├── ReconciliationService.kt      # Boot phases 2-4, crash recovery
│   ├── BandwidthService.kt           # EMA calculation, bandwidth sample writes
│   └── StateMachine.kt               # Torrent state transitions + validation + audit logging
│
├── client/                       # Client Layer — outbound to torrent nodes
│   ├── TorrentClient.kt              # Interface: add, remove, pause, list, getSpeed, probe
│   ├── TorrentClientFactory.kt       # Creates client by node type (qbit for v1, extensible)
│   └── qbittorrent/
│       ├── QBittorrentClient.kt       # TorrentClient impl using WebClient
│       ├── QBitAuthManager.kt         # SID cookie management per node
│       └── QBitDto.kt                 # Node-facing DTOs (raw qBit API responses)
│
├── persistence/                  # Persistence Layer — Spring Data JDBC
│   ├── entity/
│   │   ├── TorrentEntity.kt
│   │   ├── NodeEntity.kt
│   │   ├── StateTransitionEntity.kt
│   │   └── BandwidthSampleEntity.kt
│   ├── repository/
│   │   ├── TorrentRepository.kt
│   │   ├── NodeRepository.kt
│   │   ├── StateTransitionRepository.kt
│   │   └── BandwidthSampleRepository.kt
│   └── migration/                    # Flyway SQL migrations
│       ├── V1__create_nodes.sql
│       ├── V2__create_torrents.sql
│       ├── V3__create_state_transitions.sql
│       └── V4__create_bandwidth_samples.sql
│
├── config/                       # Configuration
│   ├── NisabaProperties.kt           # @ConfigurationProperties — all tunable settings
│   ├── NodeConfig.kt                 # nodes.yml loader
│   ├── WebClientConfig.kt            # WebClient beans for node communication
│   └── BootGate.kt                   # Blocks API until reconciliation completes
│
└── NisabaApplication.kt         # Spring Boot entry point
```

### Design rationale

- **Two separate DTO layers:** `api/dto/` for qBit-compatible JSON the `*arr` stack expects, `client/qbittorrent/QBitDto.kt` for raw responses from actual nodes. They look similar but serve different masters and must evolve independently.
- **`StateMachine.kt` is a standalone component** in the service layer. Every state transition in the system goes through it — it validates the transition is legal, writes the audit log, and updates the torrent record. No service can update `torrent.state` directly.
- **`TorrentClient.kt` interface** is the adapter boundary. v1 has only `QBittorrentClient`. Adding Transmission later means implementing `TorrentClient` and a new package under `client/`.
- **`BootGate.kt`** is a Spring `HandlerInterceptor` that returns 503 to all `/api/v2/**` requests until reconciliation completes.

---

## 4. Error Handling with Arrow-kt

### Sealed error hierarchy

```kotlin
sealed interface NisabaError {
    // Node communication errors
    data class NodeUnreachable(val nodeId: String, val cause: String) : NisabaError
    data class NodeRejected(val nodeId: String, val reason: String) : NisabaError
    data class AuthFailed(val nodeId: String) : NisabaError

    // Business logic errors
    data class AlreadyExists(val infohash: String) : NisabaError
    data class NoHealthyNodes(val reason: String) : NisabaError
    data class InvalidStateTransition(
        val infohash: String,
        val from: TorrentState,
        val to: TorrentState
    ) : NisabaError
    data class TorrentNotFound(val infohash: String) : NisabaError

    // Reassignment errors
    data class ReassignmentFailed(
        val infohash: String,
        val oldNode: String,
        val reason: String
    ) : NisabaError
}
```

### Flow through layers

- **Service Layer:** Every public method returns `Either<NisabaError, T>`. Uses `either { }` blocks with `raise()` for short-circuiting and `.bind()` for propagation.
- **Client Layer:** `TorrentClient` methods return `Either<NisabaError, T>`. Network failures caught and wrapped as `NodeUnreachable`. qBit API rejections wrapped as `NodeRejected`.
- **API Layer:** Controllers fold `Either` — `Right(value)` becomes 200 OK with qBit-compatible JSON, `Left(error)` maps to appropriate qBit error response.
- **Persistence Layer:** Plain Spring Data JDBC. Throws exceptions only on infrastructure failure. No `Either` here — DB errors are truly exceptional.

### Arrow-kt patterns used

- **`either { }` blocks** with `.bind()` for fail-fast chaining
- **`ensure()`** for guard conditions
- **`mapLeft`** for error transformation at layer boundaries
- **`parTraverse`** for concurrent node polling (sync loop, boot reconciliation)
- **`parZip`** for parallel independent operations
- **`recover`** for graceful degradation (node unreachable during sync — mark unhealthy, continue)

### Error-to-HTTP mapping

The `*arr` stack should never see an error it wouldn't see from a real qBittorrent instance. Internal failures are masked as qBit-compatible responses.

| NisabaError | HTTP Response | Rationale |
|---|---|---|
| `AlreadyExists` | 200 + `"Fails."` | Matches real qBit duplicate behavior |
| `NoHealthyNodes` | 200 + `"Fails."` | `*arr` retries on its own schedule |
| `TorrentNotFound` | 404 | Standard |
| `InvalidStateTransition` | 409 Conflict | Logged server-side, not exposed to `*arr` |
| `NodeUnreachable` | 200 + `"Fails."` | Transparent to `*arr` |

---

## 5. TorrentClient Interface & Adapter Pattern

### Interface

```kotlin
interface TorrentClient {
    // Connection & health
    suspend fun probe(node: NodeEntity): Either<NisabaError, NodeHealth>
    suspend fun authenticate(node: NodeEntity): Either<NisabaError, AuthSession>

    // Torrent operations
    suspend fun addTorrent(
        node: NodeEntity,
        magnetUri: String,
        savePath: String,
        category: String?,
        paused: Boolean = false
    ): Either<NisabaError, AddResult>
    suspend fun pauseTorrent(node: NodeEntity, infohash: String): Either<NisabaError, Unit>
    suspend fun removeTorrent(
        node: NodeEntity, infohash: String, deleteFiles: Boolean
    ): Either<NisabaError, Unit>

    // State queries
    suspend fun listTorrents(node: NodeEntity): Either<NisabaError, List<TorrentStatus>>
    suspend fun getTorrentProperties(
        node: NodeEntity, infohash: String
    ): Either<NisabaError, TorrentProperties>
    suspend fun getTorrentFiles(
        node: NodeEntity, infohash: String
    ): Either<NisabaError, List<TorrentFile>>

    // Telemetry
    suspend fun getTransferSpeed(node: NodeEntity): Either<NisabaError, TransferInfo>
}
```

### Client-agnostic DTOs

```kotlin
data class NodeHealth(val nodeId: String, val healthy: Boolean, val version: String? = null)
data class AuthSession(val nodeId: String, val token: String)
data class AddResult(val infohash: String, val accepted: Boolean)
data class TorrentStatus(
    val infohash: String,
    val name: String,
    val progress: Float,           // 0.0 to 1.0
    val speedBps: Long,
    val state: ClientTorrentState,
    val savePath: String,
    val contentPath: String?,
    val eta: Long?,
    val ratio: Float?,
    val seedingTime: Long?
)
data class TorrentProperties(val savePath: String, val seedingTime: Long?)
data class TorrentFile(val name: String, val size: Long)
data class TransferInfo(val downloadSpeedBps: Long, val uploadSpeedBps: Long)

enum class ClientTorrentState {
    QUEUED, DOWNLOADING, STALLED, PAUSED, COMPLETED, CHECKING, ERROR, METADATA
}
```

### Design rationale

- **All methods are `suspend`** — Arrow-kt's `parTraverse` and `parZip` work naturally with coroutines.
- **Client-agnostic DTOs** — the service layer never sees qBit-specific types. Each adapter maps raw responses into these common types.
- **`ClientTorrentState` enum is normalized** — qBit has ~15 states, Transmission uses numeric status, Deluge has its own strings. Each adapter maps to this common enum.
- **`NodeEntity` passed to every call** — the adapter reads `baseUrl` and credentials from it. No global state.

### Factory pattern

```kotlin
@Component
class TorrentClientFactory(
    private val qBitClient: QBittorrentClient
) {
    fun clientFor(node: NodeEntity): TorrentClient = when (node.clientType) {
        ClientType.QBITTORRENT -> qBitClient
        // future: ClientType.TRANSMISSION -> transmissionClient
    }
}
```

The `NodeEntity` has a `clientType` field (default `QBITTORRENT`). Adding a new client means implementing `TorrentClient`, adding an enum value, and wiring it in the factory.

---

## 6. State Machine

### Transition map

```kotlin
private val allowedTransitions: Map<TorrentState, Set<TorrentState>> = mapOf(
    QUEUED       to setOf(ASSIGNING),
    ASSIGNING    to setOf(DOWNLOADING, QUEUED),       // QUEUED = node rejected
    DOWNLOADING  to setOf(STALLED, PAUSED, DONE),
    STALLED      to setOf(REASSIGNING, QUEUED),       // QUEUED = manual retry
    REASSIGNING  to setOf(ASSIGNING, FAILED),         // FAILED = no healthy nodes
    PAUSED       to setOf(DOWNLOADING, QUEUED),       // DOWNLOADING = resume
    FAILED       to setOf(QUEUED),                    // manual retry only
    DONE         to setOf()                           // terminal
)
```

### Implementation

```kotlin
@Component
class StateMachine(
    private val torrentRepository: TorrentRepository,
    private val transitionRepository: StateTransitionRepository
) {
    fun transition(
        infohash: String,
        to: TorrentState,
        nodeId: String? = null,
        reason: String? = null
    ): Either<NisabaError, TorrentEntity> = either {
        val torrent = torrentRepository.findByInfohash(infohash)
            ?: raise(TorrentNotFound(infohash))

        val from = torrent.state
        ensure(to in (allowedTransitions[from] ?: emptySet())) {
            InvalidStateTransition(infohash, from, to)
        }

        transitionRepository.save(
            StateTransitionEntity(
                infohash = infohash,
                fromState = from,
                toState = to,
                nodeId = nodeId,
                reason = reason
            )
        )

        val updated = torrent.copy(state = to)
        torrentRepository.save(updated)
        updated
    }
}
```

### Design rationale

- **Single entry point** — every state change goes through `StateMachine.transition()`. No service updates `torrent.state` directly.
- **Declarative transition map** — easy to read, test, and extend.
- **Automatic audit logging** — every transition writes to `state_transitions`.
- **`ASSIGNING` timeout** — handled by the sync loop. If a torrent is in `ASSIGNING` for >90s, the sync loop transitions it back to `QUEUED`.
- **Delete is not a state transition** — it's record destruction. The delete endpoint bypasses the state machine, removes the DB record, and calls `removeTorrent` on the assigned node.
- **`DONE` is terminal for v1** — when pipeline orchestration is added later, `DONE` becomes the handoff trigger to the orchestrator.

---

## 7. 30s Sync Loop

The sync loop is Nisaba's heartbeat. It runs every 30 seconds via `@Scheduled` and performs three jobs.

### Job 1 — Bandwidth sampling

For each healthy node, poll `GET /api/v2/transfer/info` for `dl_info_speed`. Write sample to `bandwidth_samples` hypertable. Recalculate EMA: `new_weight = 0.3 * current_speed + 0.7 * previous_weight`. Update `nodes.ema_weight`.

### Job 2 — Torrent state sync

For each healthy node, poll `GET /api/v2/torrents/info` for all torrents on that node. For each torrent in our registry with state `DOWNLOADING`: if found on node, update `progress_pct`, `pieces_bitmap`, `last_synced_at`. If progress = 100%, transition to `DONE`. If not found on assigned node, transition to `STALLED`.

### Job 3 — Stall detection

For each torrent in state `DOWNLOADING`: if speed = 0 for 2 consecutive cycles, transition to `STALLED` and trigger reassignment. For each torrent in state `ASSIGNING`: if stuck for >90s, transition back to `QUEUED`.

### Implementation

```kotlin
@Service
class SyncService(
    private val nodeRepository: NodeRepository,
    private val torrentRepository: TorrentRepository,
    private val bandwidthService: BandwidthService,
    private val stateMachine: StateMachine,
    private val reassignmentService: ReassignmentService,
    private val clientFactory: TorrentClientFactory
) {
    private val stallCounters = ConcurrentHashMap<String, Int>()

    @Scheduled(fixedRate = 30_000)
    suspend fun syncLoop() {
        val healthyNodes = nodeRepository.findByHealthyTrue()

        // Poll all nodes in parallel (parTraverse)
        val nodeSnapshots = healthyNodes.parTraverse { node ->
            either {
                val client = clientFactory.clientFor(node)
                val speed = client.getTransferSpeed(node).bind()
                val torrents = client.listTorrents(node).bind()
                NodeSnapshot(node, speed, torrents)
            }.recover {
                markNodeUnhealthy(it, node)
                null
            }
        }.filterNotNull()

        // Write bandwidth samples + update EMA
        nodeSnapshots.forEach { snapshot ->
            bandwidthService.recordSample(snapshot.node.nodeId, snapshot.speed)
        }

        // Diff registry against live state
        val registryTorrents = torrentRepository.findByStateIn(DOWNLOADING, ASSIGNING)
        registryTorrents.forEach { torrent ->
            syncTorrent(torrent, nodeSnapshots)
        }
    }
}
```

### Design rationale

- **`parTraverse` for node polling** — all nodes polled concurrently, one failing doesn't block others.
- **`stallCounters` is in-memory** — doesn't survive restarts. On restart, reconciliation handles stale state. Counter rebuilds within 2 cycles (60s).
- **`recover` on node polling** — unreachable node is marked unhealthy, sync continues with remaining nodes.
- **Reassignment triggered inline** after stall detection, not on a separate schedule.

---

## 8. Boot & Reconciliation Sequence

Maps to the arch doc's 5-phase boot sequence. Runs via `@EventListener(ApplicationReadyEvent::class)`.

### Phase 1 — Connect and migrate
Handled by Spring Boot auto-configuration + Flyway. Fail fast if DB unreachable.

### Phase 2 — Probe all nodes
`parTraverse` all configured nodes, call `probe()`. Update `nodes.healthy` and `nodes.last_seen_at`. If no nodes respond, wait 15s and retry. Repeat until at least one node is healthy.

### Phase 3 — Fetch live torrent state
`parTraverse` all healthy nodes, call `listTorrents()`. Build in-memory map of `infohash -> (nodeId, TorrentStatus)`.

### Phase 4 — Reconcile DB vs live state
For every record in the `torrents` table:

| Condition | Action |
|---|---|
| Found on node, progress ahead of DB | Fast-forward: update `progress_pct`, `pieces_bitmap`, `last_synced_at` |
| Not found on any node, state was `DOWNLOADING` | Transition to `STALLED` |
| State is `REASSIGNING` | Crashed mid-handoff — transition to `QUEUED` |
| State is `DONE` | No action |
| Found on node, not in DB (orphan) | Log warning, do not touch |
| `bandwidth_samples` empty | Seed all `nodes.ema_weight` to 0.5 |

### Phase 5 — Open API
`BootGate.open()` — starts accepting `*arr` requests. Start 30s poll scheduler. Start node health check loop.

### BootGate implementation

```kotlin
@Component
class BootGate : WebMvcConfigurer {
    private val ready = AtomicBoolean(false)

    fun open() { ready.set(true) }
    fun isReady(): Boolean = ready.get()

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(object : HandlerInterceptor {
            override fun preHandle(
                request: HttpServletRequest,
                response: HttpServletResponse,
                handler: Any
            ): Boolean {
                if (!ready.get() && request.requestURI.startsWith("/api/v2")) {
                    response.status = 503
                    response.writer.write("Nisaba is reconciling, please retry")
                    return false
                }
                return true
            }
        }).addPathPatterns("/api/v2/**")
    }
}
```

---

## 9. Configuration Design

### `application.yml`

```yaml
nisaba:
  auth:
    username: admin
    password: changeme

  poll:
    interval-seconds: 30
    stall-threshold-cycles: 2
    assigning-timeout-seconds: 90

  ema:
    alpha: 0.3
    cold-start-weight: 0.5

  categories:
    tv-sonarr: /data/media/tv
    radarr: /data/media/movies
    lidarr: /data/media/music
    default: /data/media/misc

spring:
  datasource:
    url: jdbc:postgresql:///nisaba?socketFactory=...
  flyway:
    enabled: true
```

### Type-safe properties

```kotlin
@ConfigurationProperties(prefix = "nisaba")
data class NisabaProperties(
    val auth: AuthProperties,
    val poll: PollProperties,
    val ema: EmaProperties,
    val categories: Map<String, String>
) {
    data class AuthProperties(val username: String, val password: String)
    data class PollProperties(
        val intervalSeconds: Long = 30,
        val stallThresholdCycles: Int = 2,
        val assigningTimeoutSeconds: Long = 90
    )
    data class EmaProperties(
        val alpha: Float = 0.3f,
        val coldStartWeight: Float = 0.5f
    )
}
```

### `nodes.yml`

```yaml
nodes:
  - id: node-a
    url: http://node-a.mesh:8080
    username: admin
    password: secret
    label: "NFS Host - Primary"

  - id: node-b
    url: http://node-b.mesh:8080
    username: admin
    password: secret
    label: "Edge Node B"
```

Loaded by `NodeConfig` at startup, synced into the `nodes` DB table by `ReconciliationService`.

---

## 10. *arr-facing API — Response Mapping

### Endpoints Nisaba must expose

| Endpoint | Method | Purpose |
|---|---|---|
| `/api/v2/auth/login` | POST | Auth, return SID cookie |
| `/api/v2/app/webapiVersion` | GET | Return version string (e.g. `"2.9.3"`) |
| `/api/v2/app/version` | GET | Return app version string |
| `/api/v2/app/preferences` | GET | Save path, ratio limits, DHT, queueing settings |
| `/api/v2/torrents/info` | GET | List torrents (aggregated across nodes) |
| `/api/v2/torrents/properties` | GET | Single torrent details by hash |
| `/api/v2/torrents/files` | GET | File list for a torrent |
| `/api/v2/torrents/add` | POST | Add torrent (magnet or .torrent file) |
| `/api/v2/torrents/delete` | POST | Remove torrent |
| `/api/v2/torrents/setCategory` | POST | Set category on a torrent |
| `/api/v2/torrents/createCategory` | POST | Create a new category |
| `/api/v2/torrents/categories` | GET | List categories + save paths |
| `/api/v2/torrents/setShareLimits` | POST | Set ratio/seeding limits |
| `/api/v2/torrents/topPrio` | POST | Move to top of queue |
| `/api/v2/torrents/setForceStart` | POST | Force start a torrent |

### State mapping

```kotlin
object StateMapper {
    fun toQBitState(state: TorrentState): String = when (state) {
        QUEUED       -> "queuedDL"
        ASSIGNING    -> "checkingResumeData"
        DOWNLOADING  -> "downloading"
        STALLED      -> "stalledDL"
        REASSIGNING  -> "checkingResumeData"
        PAUSED       -> "pausedDL"
        DONE         -> "pausedUP"
        FAILED       -> "error"
    }
}
```

### Key endpoint behaviors

- **`POST /api/v2/auth/login`** — validates against `nisaba.auth.username/password`, returns `"Ok."` with `SID` cookie on success, `"Fails."` otherwise.
- **`GET /api/v2/torrents/info`** — reads all torrents from the registry (not from nodes), maps each to qBit JSON format using `StateMapper`. Optional `category` query param filters results.
- **`POST /api/v2/torrents/add`** — extracts infohash from magnet URI, resolves save path from category config, calls `RegistryService.addTorrent()`, returns `"Ok."` or `"Fails."`.
- **`POST /api/v2/torrents/delete`** — removes registry record and calls `removeTorrent` on the assigned node. Bypasses the state machine (deletion is not a state transition).
- **`GET /api/v2/app/preferences`** — returns static/configured values that the `*arr` stack checks (save_path, ratio settings, DHT enabled, queueing disabled).
- **`GET /api/v2/torrents/categories`** — returns the categories map from `application.yml`.

### Endpoints called on downstream nodes

| Endpoint | Purpose |
|---|---|
| `/api/v2/auth/login` | Authenticate to node |
| `/api/v2/app/version` | Health check / probe |
| `/api/v2/transfer/info` | Get `dl_info_speed` for bandwidth sampling |
| `/api/v2/torrents/info` | Fetch live torrent state for sync |
| `/api/v2/torrents/add` | Add torrent to a node |
| `/api/v2/torrents/delete` | Remove torrent from a node |
| `/api/v2/torrents/pause` | Pause torrent before reassignment |
| `/api/v2/torrents/properties` | Get detailed torrent state |

---

## 11. Testing Strategy

### Level 1 — Unit tests (no Spring context)

- **StateMachine** — every legal transition, every illegal transition, audit log writes
- **RouterService** — EMA calculation, highest-weight selection, round-robin tiebreak, empty-node handling
- **StateMapper** — every internal state maps to a valid qBit string
- **BandwidthService** — EMA math with edge cases (zero speed, cold start, weight bounds [0,1])
- Arrow-kt `Either` paths — verify `Left` and `Right` for each service method

### Level 2 — Integration tests (Testcontainers)

- `@ServiceConnection` with `PostgreSQLContainer` + TimescaleDB image
- Repository tests — CRUD, queries, hypertable writes, retention policy
- Full add flow — add torrent -> registry write -> state transitions
- Flyway migrations run cleanly on fresh DB

### Level 3 — Component tests (full Spring context, mocked nodes)

- Mock `TorrentClient` via MockK to simulate qBit responses
- Sync loop — feed mock data, verify progress updates, stall detection triggers
- Reassignment — simulate node offline, verify pause-on-old then add-to-new
- Boot reconciliation — seed DB with various states, verify correct recovery
- API contract tests — verify JSON responses match qBit format exactly

### Level 4 — End-to-end (real qBittorrent in Testcontainers)

- Spin up 2 qBittorrent containers
- Add torrent through Nisaba, verify it appears on a node
- Stop a container, verify reassignment to surviving node
- Restart Nisaba mid-download, verify reconciliation recovers

### Invariant tests (run at every level)

```kotlin
@Test fun `only one active assignment per infohash`()
@Test fun `EMA weight always in 0 to 1 range`()
@Test fun `API blocked during reconciliation`()
@Test fun `pause before re-add on reassignment`()
@Test fun `orphan torrents are never modified`()
```

### Test tooling

- **MockK** — Kotlin-native mocking, coroutine support
- **Kotest assertions** — expressive Kotlin-first assertions
- **Testcontainers** with `@ServiceConnection` — Postgres + TimescaleDB
- **WireMock** — simulating qBit API responses in component tests

---

## 12. Future Considerations

These are explicitly out of scope for v1 but the architecture accommodates them:

- **Additional torrent clients** — implement `TorrentClient` interface for Transmission, Deluge, rTorrent. Add `clientType` to node config. No changes outside `client/` package.
- **Post-download pipeline** — transcode, HLS conversion, transport. `DONE` state becomes handoff trigger to a workflow orchestrator (Conductor, Temporal). New states added to the state machine (`TRANSCODING`, `TRANSPORTING`, `PUBLISHING`).
- **Admin API** — runtime node registration, manual retry/reassign, registry inspection. New controllers in `api/controller/`.
- **Prometheus metrics** — active torrents per node, EMA weights, reassignment count, sync loop duration.
- **High availability** — multiple Nisaba instances need EMA state consensus. Requires distributed locking or leader election.

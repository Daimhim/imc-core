# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project shape

Multi-module Gradle (Kotlin DSL) project with three modules (`settings.gradle.kts`):

- **`:imc-core`** — pure Kotlin/JVM library (NOT an Android library). Published as `com.github.Daimhim:imc-core` via JitPack and to a local `../repo` Maven directory by the `maven-publish` plugin. Transitive dependencies (OkHttp, Java-WebSocket) are declared `compileOnly` — **consumers must supply them**, and the demo `:app` does so explicitly. Library is pre-release (no external consumers yet) — breaking changes are OK.
- **`:app`** — Android demo (`com.android.application`, namespace `org.daimhim.imc_core.demo`). The `org.jetbrains.compose` plugin is applied but the app uses View Binding, not Compose. **Launcher activity is `QgbWsTestActivity`** (per `AndroidManifest.xml`). Hosts the Android-side implementations injected into the library (AlarmManager schedulers, net monitor, SP cache, event-log bridge) plus several test/visualization activities.
- **`:weaknet`** — **standalone** Android app (`com.android.application`, namespace `org.daimhim.imc_core.weaknet`, own launcher `WeakNetMainActivity`). A weak-network simulator: a local `VpnService` (`WeakNetVpnService`) intercepts traffic and applies chaos (packet loss / delay / corruption via `ChaosBytePipe` + `IpPacketParser` + `PacketBuilder`) to test the SDK's reconnect/heartbeat behavior under degraded links. Does **not** depend on `:imc-core` — it's a test harness, not part of the SDK.

Toolchain (`gradle.properties`): Kotlin 1.8.0, AGP 7.2.1, Compose 1.3.0, JVM target 1.8. Repositories include Aliyun mirrors and JitPack (see `settings.gradle.kts` / `build.gradle.kts`).

## Build & debug workflow — remote compile, local install

**This repo is edited locally but built remotely.** The `M:` drive is an SSHFS mount of `/data/work/imc-core` on a remote Debian build machine (reached over a private VPN — actual host/user kept in the local dev-env doc, out of this repo); editing files under `M:` writes straight to the remote. **The local Windows box has no Android toolchain — do not run `gradlew` locally.** Compile on the remote over SSH, pull the APK back, install with local `adb`. Full environment notes (SSH key, ZeroTier troubleshooting, mount recovery) live in `C:\Users\Administrator\Desktop\RemoteDevelopmentEquipment\imc-core-开发环境说明.md`.

```powershell
# Compile on the remote (source the toolchain env first; JDK 17, ANDROID_HOME, GRADLE_USER_HOME live there)
ssh -i "<key>\id_ed25519" -o IdentitiesOnly=yes <user>@<remote-host> `
  "source /etc/profile.d/devenv.sh && cd /data/work/imc-core && ./gradlew :app:assembleDebug"
# APK lands at /data/work/imc-core/app/build/outputs/apk/debug/app-debug.apk — copy via the M: mount or scp

# Install & debug on the local device/emulator (local adb only)
adb install -r app-debug.apk
adb logcat
```

`gradlew` shows as permanently modified in git: CRLF→LF + `chmod +x` was applied for Linux — **do not commit it as a business change**. `local.properties` (`sdk.dir=/data/opt/android-sdk`) is gitignored.

## Gradle tasks

Task names below run **on the remote**. The PowerShell shell here does not support `&&` — chain with `;` or run commands separately.

A **Tencent-mirror wrapper (`gw.ps1` / `gw.cmd`)** exists as a project-level shim that runs before `gradlew`. It parses `gradle/wrapper/gradle-wrapper.properties`, computes the exact wrapper cache hash (`new BigInteger(1, MD5(distributionUrl)).toString(36)`), and — if `$GRADLE_USER_HOME\wrapper\dists\<distName>\<hash>\<zip>.ok` is missing — pre-downloads the zip from `https://mirrors.cloud.tencent.com/gradle/` into that exact location, extracts, and writes the `.ok` marker. Then it forwards args to the real wrapper, which finds a cache hit and skips its own download. Falls back silently to default behavior if Tencent is unreachable. Use it when you suspect a fresh dist download is needed; otherwise it's transparently a no-op.

```powershell
# Build everything
./gradlew build

# Library only
./gradlew :imc-core:build

# Publish library JAR to ../repo (Maven layout)
./gradlew :imc-core:publishReleaseJarPublicationToMavenRepository
# or simply
./gradlew :imc-core:publish

# Demo app
./gradlew :app:assembleDebug
./gradlew :app:installDebug

# Tests (imc-core has test sources under src/test/java; demo runs JUnit too)
./gradlew :imc-core:test
./gradlew :app:testDebugUnitTest
./gradlew :app:testDebugUnitTest --tests "org.daimhim.imc_core.demo.SomeTest.method"
```

There is no lint/format task configured beyond Gradle defaults.

## High-level architecture

### Engine layer — `IEngine` (`imc-core/src/main/java/org/daimhim/imc_core/IEngine.kt`)

The public contract for a long-lived socket session. **Only one implementation now**:

- **`V2JavaWebEngine`** — built on `org.java_websocket.WebSocketClient`. Internally manages state via an `EngineState` sealed class (`Idle` / `Connecting(key)` / `Connected(key)` / `Reconnecting(key)` / `Closing`) and wraps a `WebSocketClientImpl` that handles protocol / heartbeat / send cache / auto-reconnect inside one class with banner-separated sections.

Legacy `JavaWebEngine` / `OkhttpIEngine` / `UDPEngine` / `DefCustomHeartbeat` / `RapidResponseForce` / `RapidResponseForceV2` were all deleted — don't reference them in suggestions.

URL handling: `engineOn(key)` records the URL in `EngineState`, normalizes `http://` → `ws://` and `https://` → `wss://`, then transitions through Connecting → Connected. Calling `engineOn(newKey)` while connected triggers a clean close → Reconnecting(newKey) → spawn new client. The state machine prevents the historical "currentKey not initialized" bug.

Engine states reported via `IEngine.engineState()`: `ENGINE_OPEN` / `ENGINE_CONNECTING` / `ENGINE_FAILED` (= Reconnecting) / `ENGINE_CLOSED`.

**`autoConnect` is required** — `V2JavaWebEngine.Builder.build()` calls `error()` if not set, because `handleAfterClose` depends on `autoConnect.isActive()` to decide between "let auto-reconnect take over" and "restart immediately for URL switch". Without it the engine would busy-loop on close.

**Pull-mode URL via `IKeyProvider`** — `Builder.setKeyProvider { ... }` lets the engine ask for the latest URL on every reconnect attempt (typical use: JWT token refresh, first-attempt vs retry flag, primary/backup host swap). Without it the engine reuses the URL passed to the first `engineOn(key)` call.

A handshake watchdog (`RapidResponseForceV4`, 15 s) is armed per `spawnNewClient` to break stuck WS upgrades; on timeout it force-closes the client which lets `handleAfterClose` route to `Reconnecting`.

`engineOff()` is terminal: it closes the socket, clears the message cache, disposes the `autoConnect` (which unsubscribes from `NetSurveillance`), and emits a synthetic `connectionClosed(NORMAL, "engineOff")` so callers don't miss the signal.

### TLS pinning & DNS cache — `CertificatePinner` / `DnsCache`

Two optional hardening primitives in `imc-core` (Kotlin/JVM only, no Android deps):

- **`CertificatePinner`** (A5) — `Builder.setCertificatePinner { host, certs -> ... }`. Called after the WS handshake but **before** `onOpen`; returning `false` (or throwing) force-closes the socket and surfaces `ImcEvent.TlsFailure(stage=PIN_FAILURE)` instead of a later opaque SSL error. Purpose: catch MITM/DPI that presents a chain the system TrustManager accepts but whose leaf fingerprint differs from the real server. `certs` order is server→intermediate→root; do no long work in the hook (no OCSP/CRL — leave that to the TrustManager).
- **`DnsCache`** (A9) — TTL DNS cache with last-success fallback. Caches `InetAddress.getAllByName` results (default TTL 300s); on a fresh-resolve failure it falls back to the last good answer even if expired, up to `STALE_MAX_TTL_MS` (default 24h), to ride out transient DNS outages with zero user impact. Skips localhost / IP literals. Thread-safe; concurrent fresh resolves for the same host are not deduped (simplicity over precision).

### Heartbeat — `ILinkNative` + `ITimeoutScheduler`

`ILinkNative` is the heartbeat abstraction the engine consumes. Two built-in strategies:

- **`V2FixedHeartbeat`** — fixed interval with a tolerance window. Default 5s. After a missed tick it enters a `(toleranceFactor - 1) × interval` grace window before declaring failure (default factor 1.5).
- **`V2SmartHeartbeat`** — adaptive. Sends heartbeats, increments interval by `heartbeatStep` after each success, backs off and triggers reconnect on repeated failures. Probes upward until the network drops the connection, then locks in the working maximum.

Multiple heartbeat modes are registered per engine via `Builder.addHeartbeatMode(key, ILinkNative)` and switched at runtime with `engine.onChangeMode(key)`. Typical use: a "foreground" fixed/fast mode and a "background" smart/slow mode toggled from a lifecycle observer. The demo `EngineForegroundBinder` (in `:app`) does this via `ProcessLifecycleOwner` with a 30s debounce on the foreground→background transition.

Heartbeats fire via `ITimeoutScheduler`. The library default is `RRFTimeoutScheduler`, which delegates to a per-instance `RapidResponseForceV4`. In the demo app these are **replaced** with `HeartbeatAlarmTimeoutScheduler` and `AutoConnectAlarmTimeoutScheduler` (in `app/src/main/java/.../demo/`) — these use Android `AlarmManager.setExact` + a broadcast receiver to survive Doze mode. The receivers are declared in `AndroidManifest.xml` and the app requests `USE_EXACT_ALARM` / `SCHEDULE_EXACT_ALARM` permissions; any new scheduler tied to AlarmManager needs the matching `<receiver>` entry and action string registered.

### Auto-reconnect — `IAutoConnect`

`ProgressiveAutoConnect` implements exponential backoff: `reconnectDelay *= 2` up to `maxReconnectDelay` (default 128s). Triggered by `onClose` / `onError` inside `WebSocketClientImpl`. `engine.onNetworkChange(state)` and `engine.makeConnection()` both funnel into `resetStartAutoConnect()` to abort the current backoff and retry immediately.

The engine injects an `IReconnector` into `ProgressiveAutoConnect` via `setReconnector(...)` — when the alarm fires, the reconnector calls back into `engine.engineOn(resolvedKey)` (asking `IKeyProvider` first, then state cache) to rebuild the socket through `spawnNewClient`. This fixed the historical `webSocketClient.reconnect()` no-op bug when the old client had been nulled out.

If a `NetSurveillance` is passed to `ProgressiveAutoConnect.Builder.surveillance(...)`, backoff is replaced by `NetReport.recommend`:
- `IMMEDIATE` / `FAILOVER` → `IMMEDIATE_DELAY_MS` (1000ms, see `ProgressiveAutoConnect.kt` companion — short delays get coalesced by `AlarmManager` batching on some ROMs)
- `BACKOFF_LONG` → `maxReconnectDelay`
- `BACKOFF_NORMAL` → original exponential
- `WAIT_USER` → abort scheduling entirely

`maxImmediateRetries` (default 3) is a **hard floor**: even if surveillance keeps recommending `IMMEDIATE`, after N consecutive 1-second retries the action is forcibly downgraded to `BACKOFF_NORMAL` to avoid burning battery on a no-signal stretch. Counter resets in `stopAutoConnect()` (connection success / explicit stop).

A `Builder.onFailover(OnFailoverHandler)` hook fires when `FAILOVER` is recommended — the App typically calls `engine.engineOn(backupUrl)` and returns `true` to tell the SDK to stop retrying the current URL.

`IAutoConnect.dispose()` exists for resource release; `engine.engineOff()` calls it automatically. Once disposed, the autoConnect instance shouldn't be reused — surveillance is unsubscribed, and reusing it would leave it un-driven.

### Network surveillance — `NetSurveillance` (3-layer model)

Three layers fused into one `NetReport` for the auto-reconnect to consume:

1. **L1 declarative** — `NetStateMonitor` (`NetState.kt`). Android implementation `AndroidNetStateMonitor` (in `:app`) subscribes to `ConnectivityManager.NetworkCallback` (`onAvailable`/`onLost`/`onCapabilitiesChanged`/`onBlockedStatusChanged`/`onUnavailable`) and folds them into a `NetSnapshot` (verdict + `NetCap` set + `NetTransport` set + bandwidth + signal). `NetSnapshot.equals` ignores `timestamp` to suppress duplicate notifications. Cost ≈ 0; event-driven.
2. **L2 active probe** — `NetProber` + `DefaultNetProber` (`NetProbe.kt` / `DefaultNetProber.kt`). Multi-stage probe (DNS → TCP → TLS → optional HTTP `/healthz`) plus an optional `publicReference` target (e.g. `baidu.com:443`) used to distinguish "our server is down" from "user has no internet". Uses `InetAddress.getAllByName` with sequential TCP fallback for IPv6/IPv4. Default executor is a bounded `ThreadPoolExecutor(4)` with daemon threads — **DNS is uninterruptible**, so `getByName` calls keep occupying threads until the system-level DNS timeout. Pure JVM; no OkHttp dependency.
3. **L3 orchestration** — `NetSurveillance` + `DefaultNetSurveillance` (`NetSurveillance.kt`). Subscribes to L1, decides when to trigger L2 probes (immediate on `CONNECTED_NOT_VALIDATED`, debounced after other changes, rate-limited by `minProbeIntervalMs`), fuses results into `NetReport(overall, recommend)` and notifies listeners.

`NetReport.recommend` is a `ReconnectAction` enum (`IMMEDIATE` / `BACKOFF_NORMAL` / `BACKOFF_LONG` / `FAILOVER` / `WAIT_USER`) consumed by `ProgressiveAutoConnect`. `recoveryGraceUntilMs` (3s after OFFLINE→online) forces `IMMEDIATE` for soft verdicts to absorb DNS/TCP warm-up time; hard verdicts (`OFFLINE` / `CAPTIVE_PORTAL` / `BLOCKED`) keep their natural recommendation.

**`NetProbeProfile`** controls probe cadence and supports runtime switching via `surveillance.setProfile(NetProbeProfile.AGGRESSIVE | BALANCED | BACKGROUND | LOW_POWER)`. Each profile bundles `minProbeIntervalMs / debounceMs / probeTimeoutMs / burstEnabled / burstIntervalMs / burstAttempts / burstPerAttemptTimeoutMs`. Profile changes apply on the next `scheduleProbe`; `burstEnabled` flips re-arm or cancel the burst timer (and cancel any inflight `probeBurst`). `setBurstEnabled(on)` is sugar for `setProfile(currentProfile.copy(burstEnabled = on))`.

The `NetSurveillanceTestActivity` (in `:app`) is a visualization sandbox for the whole stack.

The older `NST` / `NetCheckDetect` / `NetCheckConfig` interfaces and `kongqw:NetworkMonitor` usage in `MainActivity`/`StartApp` are obsolete; the new 3-layer model replaces them, but `MainActivity` still wires the kongqw library — leave it alone unless explicitly migrating.

### Send-while-disconnected cache — `IMessageCache`

`WebSocketClientImpl` overrides `send(...)` to buffer outbound application-layer payloads (not WS frames) in an `IMessageCache` when the socket isn't open. The cache stores `CachedMessage.Text` / `CachedMessage.Binary` with id + timestamp; on `onOpen` / `onMessage` / `onWebsocketPong` the client `retryingSendingCache()` polls items and re-encodes them through the current `draft` before sending.

Three built-in implementations:
- **`InMemoryMessageCache`** — default. FIFO + 64 KB byte cap + optional TTL (default 5 s via per-instance `RapidResponseForceV4`).
- **`FileMessageCache`** — persists the whole queue to a binary blob (`<file>.tmp` → rename) on every mutation. Survives process restart. Has an `owner` field (typically current account ID) for cross-account isolation: a mismatch resets the file automatically.
- **`SharedPrefsMessageCache`** — in `:app`. Wraps `encodeMessageCacheBlob` / `decodeMessageCacheBlob` from the library, base64s the blob into a single SP key, default 32 KB cap.

`engineOff()` is the only place that calls `messageCache.clear()`. `WebSocketClientImpl.close()` deliberately does **not** clear — `dispatchEngineOn` uses `close()` to switch URLs, and dropping cached messages there would surprise the caller.

### Listener pipeline — `IMCListenerManager`

Two listener tiers:

1. **`V2IMCSocketListener`** — interceptor-style, returns `Boolean` (true = consumed, stops dispatch). Registered with a priority level via `addIMCSocketListener(level, listener)`. Lower numeric level fires first (TreeMap order). **Levels 0–10 are reserved**; default user level is `IMCListenerManager.DEFAULT_LEVEL` (15). Dispatch takes a snapshot under the lock and releases it before calling listeners, so a slow listener can't block the dispatch path.
2. **`V2IMCListener`** — terminal sink at the tail of the chain. The manager installs a single internal `V2RealRecipient` at the default level which fans out to every `V2IMCListener` added via `addIMCListener(...)`. The internal list is a `CopyOnWriteArrayList` — safe to add/remove during dispatch from another thread.

Both `onMessage(text: String)` and `onMessage(byteArray: ByteArray)` exist (recent commits unified the byte variant's parameter name to `byteArray` — preserve that when touching the interfaces).

Connection lifecycle is reported separately through `IMCStatusListener` (`connectionSucceeded` / `connectionClosed` / `connectionLost`), set via `setIMCStatusListener`.

### Timeout core — `RapidResponseForceV4`

`RapidResponseForceV4` is the only timeout/scheduler primitive left. **Per-instance state, no static sharing** (unlike the deleted V2). One daemon thread per instance monitors a `HashMap<id, Task>`, sleeps until the nearest deadline, fires the per-task lambda, and exits when the map empties. Used by:
- `RRFTimeoutScheduler` (the default `ITimeoutScheduler`)
- `V2JavaWebEngine` handshake watchdog
- `InMemoryMessageCache` TTL
- `DefaultNetSurveillance` probe / burst timers

### Structured events — `ImcEvents`

`imc-core` ships a structured event bus (`ImcEvent.kt` / `ImcEvents.kt`) used **instead of a text log facade** — there is no `IMCLog` anymore. Each meaningful state change emits a typed `ImcEvent` subclass; consumers subscribe via `ImcEvents.subscribe(sink)`:

- **6 categories** + 24 event types: `ENGINE` (state transitions, open/close/error, handshake watchdog), `HEARTBEAT` (sent/pong/failed/modeChange), `CACHE` (cached/flushed/evicted with reason), `AUTOCONNECT` (scheduled/fired/aborted/failover), `NETWORK` (snapshot/report/profile/lifecycle), `PROBE` (started/finished/burstFinished), plus `INTERNAL` (`ImcEvent.InternalError` for caught exceptions like persistence failures). TLS failures (incl. `CertificatePinner` rejections) surface as `ImcEvent.TlsFailure`.
- **`ImcEvents.emit(event)` is `internal`** — only imc-core can publish. Business consumers can only subscribe.
- All emit calls are made outside any internal lock to keep slow sinks from blocking the engine.
- `ImcEventRingBuffer(capacity)` is a ready-made sink for UI timeline views; `snapshot()` returns a copy, `snapshot(Category)` filters by category.
- `ImcEvents.enabled = false` globally short-circuits (no allocations) for hot-path benchmarks.
- The demo wires `ImcEventLogBridge` (in `:app`) on `StartApp.onCreate` — it renders every event into one human-readable line and pushes it to `LogStore` (visible in `FullLogActivity`) and `FileLogger` (on-disk file for `adb pull`).

If a new state change is worth surfacing to monitoring, add a new `ImcEvent` subclass + emit point instead of reaching for a text logger.

## Conventions worth knowing

- Library code is Kotlin/JVM only — **do not** add Android APIs to `:imc-core`. Android-specific helpers (AlarmManager schedulers, lifecycle handlers, network monitors, SP-backed cache) live in `:app` and are injected into the library via `Builder`.
- Mutable shared state in `V2JavaWebEngine` (`syncJWE`), `ProgressiveAutoConnect` (`syncConnectionLost`), `IMCListenerManager`, `InMemoryMessageCache`, `DefaultNetSurveillance` etc. is guarded by explicit `synchronized(...)` blocks on dedicated lock objects — keep that pattern when extending; do not introduce coroutines/Flow inside the core. **Event emit and listener callbacks must happen outside these locks** to avoid sink/listener implementations deadlocking the engine.
- Engine API methods (`engineOn`, `send`, etc.) may be called from any thread; the demo wraps `engineOn` in a `Thread { }` because `WebSocketClient.connect()` blocks.
- Comments and log strings are predominantly Chinese — preserve language when editing nearby code unless asked otherwise.
- The codebase is pre-release. **No need to preserve backwards compatibility** when refactoring; prefer breaking-clean over adding `@Deprecated` shims.

# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project shape

Multi-module Gradle (Kotlin DSL) project with two modules:

- **`:imc-core`** — pure Kotlin/JVM library (NOT an Android library). Published as `com.github.Daimhim:imc-core` via JitPack and to a local `../repo` Maven directory by the `maven-publish` plugin. All transitive dependencies (OkHttp, Java-WebSocket, timber-multiplatform) are declared `compileOnly` — **consumers must supply them**, and the demo `:app` does so explicitly.
- **`:app`** — Android demo (`com.android.application`, namespace `org.daimhim.imc_core.demo`). The `org.jetbrains.compose` plugin is applied but the app uses View Binding, not Compose. Launcher activity is `RRFTestActivity` (per `AndroidManifest.xml`).

Toolchain (`gradle.properties`): Kotlin 1.8.0, AGP 7.2.1, Compose 1.3.0, JVM target 1.8. Repositories include Aliyun mirrors and JitPack (see `settings.gradle.kts` / `build.gradle.kts`).

## Common commands

Use the wrapper (`gradlew.bat` on Windows, `./gradlew` elsewhere). The PowerShell shell here does not support `&&` — chain with `;` or run commands separately.

**Tencent-mirror wrapper (`gw.ps1` / `gw.cmd`)**: project-level shim that runs before `gradlew.bat`. It parses `gradle/wrapper/gradle-wrapper.properties`, computes the exact wrapper cache hash (`new BigInteger(1, MD5(distributionUrl)).toString(36)`), and — if `$GRADLE_USER_HOME\wrapper\dists\<distName>\<hash>\<zip>.ok` is missing — pre-downloads the zip from `https://mirrors.cloud.tencent.com/gradle/` into that exact location, extracts, and writes the `.ok` marker. Then it forwards args to the real `gradlew.bat`, which finds a cache hit and skips its own download. Falls back silently to default wrapper behavior if Tencent is unreachable. Use it instead of `gradlew` when you suspect a fresh dist download is needed; otherwise it's transparently a no-op.

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

# Tests (only the app module declares JUnit; the library has no test sources)
./gradlew :app:testDebugUnitTest
./gradlew :app:testDebugUnitTest --tests "org.daimhim.imc_core.demo.SomeTest.method"
```

There is no lint/format task configured beyond Gradle defaults.

## High-level architecture

### Engine layer — `IEngine` (`imc-core/src/main/java/org/daimhim/imc_core/IEngine.kt`)

The public contract for a long-lived socket session. Implementations:

- `V2JavaWebEngine` — **current/recommended** implementation built on `org.java_websocket.WebSocketClient`. Use this for new work. Internally manages state via an `EngineState` sealed class (Idle / Connecting / Connected / Reconnecting / Closing) and wraps a `WebSocketClientImpl` that handles protocol / heartbeat / cache / auto-reconnect inside one class with banner-separated sections.
- `OkhttpIEngine`, `JavaWebEngine` (V1), `UDPEngine` — **all `@Deprecated`**. No internal callers, kept only for source compatibility with external SDK consumers using legacy versions. Will be removed in a next major release. Don't suggest these to users; don't extend them.
- `DefCustomHeartbeat`, `RapidResponseForce` (V1) — also `@Deprecated`; tied to the legacy engines above.

URL handling: `engineOn(key)` records the URL in `EngineState`, normalizes `http://` → `ws://` and `https://` → `wss://`, then transitions through Connecting → Connected. Calling `engineOn(newKey)` while connected triggers a clean close → Reconnecting(newKey) → spawn new client. The state machine prevents the historical "currentKey not initialized" bug.

Engine states reported via `IEngine.engineState()`: `ENGINE_OPEN` / `ENGINE_CONNECTING` / `ENGINE_FAILED` (= Reconnecting) / `ENGINE_CLOSED`. All five `IEngineState` constants are reachable in V2 (V1 only returned OPEN/CLOSED).

### Heartbeat — `ILinkNative` + `ITimeoutScheduler`

`ILinkNative` is the heartbeat abstraction the engine consumes. Two built-in strategies:

- **`V2FixedHeartbeat`** — fixed interval. Default 5s, configurable via `Builder.setCurHeartbeat(seconds)`.
- **`V2SmartHeartbeat`** — adaptive. Sends heartbeats, increments interval by `heartbeatStep` after each success, backs off and triggers reconnect on repeated failures. Probes upward until the network drops the connection, then locks in the working maximum.

Multiple heartbeat modes are registered per engine via `Builder.addHeartbeatMode(key, ILinkNative)` and switched at runtime with `engine.onChangeMode(key)`. Typical use: a "foreground" fixed/fast mode and a "background" smart/slow mode toggled from a lifecycle observer.

Heartbeats fire via `ITimeoutScheduler`. The library default is `RRFTimeoutScheduler`, which delegates to `RapidResponseForceV2`. In the demo app these are **replaced** with `HeartbeatAlarmTimeoutScheduler` and `AutoConnectAlarmTimeoutScheduler` (in `app/src/main/java/.../demo/`) — these use Android `AlarmManager.setExact` + a broadcast receiver to survive Doze mode. The receivers are declared in `AndroidManifest.xml` and the app requests `USE_EXACT_ALARM` / `SCHEDULE_EXACT_ALARM` permissions; any new scheduler tied to AlarmManager needs the matching `<receiver>` entry and action string registered.

### Auto-reconnect — `IAutoConnect`

`ProgressiveAutoConnect` implements exponential backoff: `reconnectDelay *= 2` up to `maxReconnectDelay` (default 128s). Triggered by `onClose` / `onError` inside `WebSocketClientImpl`. `engine.onNetworkChange(state)` and `engine.makeConnection()` both funnel into `resetStartAutoConnect()` to abort the current backoff and retry immediately.

If a `NetSurveillance` is passed to `ProgressiveAutoConnect.Builder.surveillance(...)`, backoff is replaced by `NetReport.recommend` (see below): `IMMEDIATE`/`FAILOVER` → `initReconnectDelay`, `BACKOFF_LONG` → `maxReconnectDelay`, `BACKOFF_NORMAL` → original exponential, `WAIT_USER` → abort scheduling entirely. A `Builder.onFailover(OnFailoverHandler)` hook fires when `FAILOVER` is recommended — the App typically calls `engine.engineOn(backupUrl)` and returns `true` to tell the SDK to stop retrying the current URL.

### Network surveillance — `NetSurveillance` (3-layer model)

Three layers fused into one `NetReport` for the auto-reconnect to consume:

1. **L1 declarative** — `NetStateMonitor`. Android implementation `AndroidNetStateMonitor` (in `:app`) subscribes to `ConnectivityManager.NetworkCallback` (`onAvailable`/`onLost`/`onCapabilitiesChanged`/`onBlockedStatusChanged`/`onUnavailable`) and folds them into a `NetSnapshot` (verdict + `NetCap` set + `NetTransport` set + bandwidth + signal). Cost ≈ 0; event-driven.
2. **L2 active probe** — `NetProber` + `DefaultNetProber`. Multi-stage probe (DNS → TCP → TLS → optional HTTP `/healthz`) plus an optional `publicReference` target (e.g. `baidu.com:443`) used to distinguish "our server is down" from "user has no internet". Pure JVM; no OkHttp dependency.
3. **L3 orchestration** — `NetSurveillance` + `DefaultNetSurveillance`. Subscribes to L1, decides when to trigger L2 probes (immediate on `CONNECTED_NOT_VALIDATED`, debounced after other changes, rate-limited by `minProbeIntervalMs` defaulting to 10 s), fuses results into `NetReport(overall, recommend)` and notifies listeners.

`NetReport.recommend` is a `ReconnectAction` enum (`IMMEDIATE` / `BACKOFF_NORMAL` / `BACKOFF_LONG` / `FAILOVER` / `WAIT_USER`) consumed by `ProgressiveAutoConnect`. The `NetSurveillanceTestActivity` (in `:app`) is a visualization sandbox for the whole stack.

The older `NST` / `NetCheckDetect` / `NetCheckConfig` interfaces and `kongqw:NetworkMonitor` usage in `MainActivity`/`StartApp` are obsolete; the new 3-layer model replaces them, but `MainActivity` still wires the kongqw library — leave it alone unless explicitly migrating.

### Send-while-disconnected cache

`WebSocketClientImpl` overrides `send(...)` to buffer outbound frames in `cacheList` (8 KB cap, FIFO eviction) when the socket isn't open. Frames are flushed in `retryingSendingCache()` on `onOpen` / `onMessage` / `onWebsocketPong`. Each cached frame is also registered with a `RapidResponseForceV2` for TTL tracking.

### Listener pipeline — `IMCListenerManager`

Two listener tiers:

1. **`V2IMCSocketListener`** — interceptor-style, returns `Boolean` (true = consumed, stops dispatch). Registered with a priority level via `addIMCSocketListener(level, listener)`. Lower numeric level fires first (TreeMap order). **Levels 0–10 are reserved**; default user level is `IMCListenerManager.DEFAULT_LEVEL` (15).
2. **`V2IMCListener`** — terminal sink at the tail of the chain. The manager installs a single internal `V2RealRecipient` at the default level which fans out to every `V2IMCListener` added via `addIMCListener(...)`.

Both `onMessage(text: String)` and `onMessage(byteArray: ByteArray)` exist (recent commits unified the byte variant's parameter name to `byteArray` — preserve that when touching the interfaces).

Connection lifecycle is reported separately through `IMCStatusListener` (`connectionSucceeded` / `connectionClosed` / `connectionLost`), set via `setIMCStatusListener`.

### Timeout core — `RapidResponseForce` family

`RapidResponseForceV2` (used everywhere) and `RapidResponseForceV3` (newer, simpler) are shared-thread deadline managers: one daemon thread monitors many tagged tasks, sleeps until the next deadline, fires timeout callbacks, and exits when the task map empties. V2 uses an op-queue (`RRF_INCREASE` / `RRF_DELETE` / `RRF_DELETE_QUERY`) drained by `PowerTrainRunnable`; V3 keeps deadlines in a static map directly and notifies a `coreLock`. V2's static state (`operationTasksQueue`, `timeoutCallbackMap`, etc.) is **shared across all instances** — keep that in mind when reasoning about lifecycle.

### Logging

`IMCLog` is a thin facade. The library never logs directly to a concrete framework — instead, install an `IIMCLogFactory` via `Builder.setIMCLog(...)`. The demo passes `TimberIMCLog`, but the library itself is logger-agnostic (timber is `compileOnly`). Do not introduce hard dependencies on Timber inside `:imc-core`. Note that `RapidResponseForceV3.kt` currently imports `timber.multiplatform.log.Timber` directly — this is an existing inconsistency, prefer routing through `IMCLog` if editing it.

## Conventions worth knowing

- Library code is Kotlin/JVM only — **do not** add Android APIs to `:imc-core`. Android-specific helpers (AlarmManager schedulers, lifecycle handlers, network monitors) live in `:app` and are injected into the library via `Builder`.
- Mutable shared state in `RapidResponseForceV2`, `IMCListenerManager`, and `V2JavaWebEngine` is guarded by explicit `synchronized(...)` blocks on dedicated lock objects (`syncRRF`, `syncJWE`, `syncConnectionLost`, etc.) — keep that pattern when extending; do not introduce coroutines/Flow inside the core.
- Engine API methods (`engineOn`, `send`, etc.) may be called from any thread; the demo wraps `engineOn` in a `Thread { }` because `WebSocketClient.connect()` blocks.
- Comments and log strings are predominantly Chinese — preserve language when editing nearby code unless asked otherwise.

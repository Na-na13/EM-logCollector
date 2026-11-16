# Log Collector: Design Decisions

> **Status:** agreed design for the REST-based rewrite (v1). Not implemented yet.
> The code in `src/main` still reflects the previous, daemon-based design described in [Background](#1-background).

## Contents

1. [Background](#1-background)
2. [Goals and constraints](#2-goals-and-constraints)
3. [Architecture overview](#3-architecture-overview)
4. [Parser service and REST API](#4-parser-service-and-rest-api)
5. [Starting the parser with Testcontainers](#5-starting-the-parser-with-testcontainers)
6. [Collecting SUT logs](#6-collecting-sut-logs)
7. [Mapping logs to tests](#7-mapping-logs-to-tests)
8. [Fitness targets](#8-fitness-targets)
9. [Configuration](#9-configuration)
10. [Inspection output](#10-inspection-output)
11. [Error handling](#11-error-handling)
12. [Java version](#12-java-version)
13. [Changes outside this module](#13-changes-outside-this-module)
14. [Testing](#14-testing)
15. [Known limitations of v1](#15-known-limitations-of-v1)
16. [Rejected alternatives](#16-rejected-alternatives)
17. [Future work](#17-future-work)

---

## 1. Background

The log collector is an `AdditionalTargetCollector` that adds log-based fitness targets to EvoMaster. When a test makes the SUT emit a log message, the message is reduced to its *log template* with the [Drain](https://github.com/logpai/logparser) algorithm. For example, `User 42 logged in` and `User 7 logged in` both become `User <*> logged in`. Each distinct template becomes a coverage target, so the search is rewarded for tests that drive the SUT into new behaviour visible in its logs.

### Previous design

```
EvoMaster ──"startMs endMs" via stdin──► parser container ──/var/run/docker.sock──► Docker ──► SUT logs
                                          (fetches logs, runs Drain,
                                           writes templates/unique-templates.txt)
EvoMaster ◄──── reads file ─────────────┘
```

- `LogCollector` started the parser with `ProcessBuilder` running `docker run -i ...`, and kept it alive as a daemon.
- Communication used a custom line protocol over stdin/stdout (`"<startMs> <endMs>"` in, `OK` / `ERROR: ...` out).
- The parser container mounted the host Docker socket and fetched the SUT's container logs itself, for the wall-clock time window of each test.
- Results were exchanged through files in mounted host directories (`logs/`, `templates/`).
- Crash detection, restarts, timeouts and cleanup (a JVM shutdown hook) were hand-written.
- The Docker Compose project to monitor was given through the `LOG_COLLECTOR_PROJECT` environment variable.

### Review feedback

The initial PR was not accepted. The EvoMaster maintainers asked for:

1. the parser to be *"a simple REST API inside Docker, which would be started via TestContainers"*, and
2. no use of environment variables for configuration.

This document records the design that follows from that feedback, together with the further requirements listed below.

---

## 2. Goals and constraints

| # | Requirement | Consequence |
|---|---|---|
| G1 | Parser is a simple REST API inside Docker, started via Testcontainers | No `ProcessBuilder`, no stdin protocol, no hand-written lifecycle management |
| G2 | OS-agnostic (Linux, macOS, Windows) | No Docker socket mounts, no host volume mounts, no hard-coded paths |
| G3 | Works with an external SUT, as EvoMaster assumes | Cannot rely on the SUT running in-process or under EvoMaster's control |
| G4 | Accurate test-to-log mapping | No wall-clock time windows, since the SUT's clock may differ from EvoMaster's |
| G5 | No changes to the `AdditionalTargetCollector` interface | Everything must be done from the three existing hooks |
| G6 | No environment variables | All settings are `EMConfig` options |
| G7 | Java 8 compatible | Same as the other `core-extra` modules (`arazzo-parser`, `asyncapi-parser`, `solver`) |
| G8 | The search process stays easy to inspect | Per-test trace and template files, see [§10](#10-inspection-output) |
| G9 | v1 as simple as possible | Docker is the only log source; other sources are left for later |

---

## 3. Architecture overview

```
 SUT containers ──► Docker daemon ──log stream──► EvoMaster: LogCollector
 (Compose project)                                 │  buffer + A/B cursor
                                                   │
                                                   │  POST /parse {lines}
                                                   ▼
                                     parser container (Drain only)
                                     started via Testcontainers
                                                   │
                     LogCollector ◄─ JSON templates┘
                          │
                          ▼
                 TargetInfo("log:<source>:<id>", 1.0) ──► EvoMaster archive
```

The main change from the previous design is a **split of responsibilities**:

| Responsibility | Previous design | New design |
|---|---|---|
| Fetch SUT logs | parser (via mounted Docker socket) | **EvoMaster** (via Testcontainers' Docker client) |
| Decide which lines belong to which test | parser (timestamps) | **EvoMaster** (cursor, see [§7](#7-mapping-logs-to-tests)) |
| Turn lines into templates (Drain) | parser | parser |
| Exchange results | files in mounted folders | HTTP response body |

Rationale:

- **No socket mount.** Only the component that fetches logs needs Docker access. EvoMaster already has it, because Testcontainers needs it to start the parser. Moving fetching into EvoMaster leaves the parser container with nothing mounted (G2).
- **Accurate mapping.** Only the process that executes the tests knows exactly when a test starts and ends. The parser could only guess using timestamps (G4).
- **A simple API.** The parser becomes a pure "lines in, templates out" service (G1).
- **Extensible.** New log sources (files, push) only change the EvoMaster side. The parser and its API stay the same.

### Java-side components

| Class | Role |
|---|---|
| `LogCollector` | Implements `AdditionalTargetCollector`; starts the parser, owns the log source, maps results to `TargetInfo`, writes inspection files |
| `LogSource` | Interface: `drainNewLines()` returns all complete lines received since the previous call |
| `DockerLogSource` | `LogSource` that follows the log streams of the Compose project's containers |
| `ParserClient` | HTTP client for the parser API |
| `LogLine`, `TemplateHit` | Plain data classes for the API payloads |

---

## 4. Parser service and REST API

The parser (`parser.py`, maintained in a separate repository) only runs Drain behind an HTTP server. It knows nothing about Docker, the SUT or time windows.

### Endpoints

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/health` | Returns `200` once the service is ready. Used by Testcontainers' wait strategy. |
| `POST` | `/parse` | Parses a batch of log lines and returns the matched templates. Called once per test. |
| `POST` | `/reset` | *Optional.* Clears all Drain state. Only for testing the parser itself; EvoMaster does not call it. |

### `POST /parse`

Request:

```json
{
  "lines": [
    { "source": "oauth2-client", "text": "2026-11-16 10:00:01 INFO User 42 logged in" },
    { "source": "oauth2-token",  "text": "2026-11-16 10:00:01 WARN Token expired for client abc" }
  ]
}
```

Response (`200`):

```json
{
  "templates": [
    { "source": "oauth2-client", "id": "17", "template": "<*> <*> INFO User <*> logged in", "count": 1 },
    { "source": "oauth2-token",  "id": "3",  "template": "<*> <*> WARN Token expired for client <*>", "count": 1 }
  ]
}
```

Semantics:

- **One Drain model per `source`.** Template IDs are scoped by source.
- **State persists for the lifetime of the container.** Templates learned in one request are kept for later requests. This is why the parser runs as one container for the whole EvoMaster run, not one per test.
- **Stable IDs.** A template's `id` (its Drain cluster ID) never changes once assigned, even when its text becomes more general as more lines match it. `id` is the key; `template` is for humans only.
- **All matched templates are returned, not only new ones.** Deciding what counts as "new" is the job of EvoMaster's archive, which also needs repeat coverage to replace a test with a better one. *This may be revisited.*
- `count` is the number of lines in this request that matched the template.
- An empty `lines` array returns an empty `templates` array. In practice EvoMaster skips the call when there are no lines.
- Unknown fields are ignored on both sides, so the API can grow without breaking either side.
- Any non-`200` status is treated as a failed request (see [§11](#11-error-handling)).

`GET /templates` (list all known templates) was considered and **dropped**. EvoMaster can keep the full template list itself from the `/parse` responses. In Drain, a template's text only changes when a line matches it, and any matching line is in that request's response. So EvoMaster's copy is always up to date.

---

## 5. Starting the parser with Testcontainers

```java
GenericContainer<?> parser = new GenericContainer<>(DockerImageName.parse(parserImage))
    .withExposedPorts(8080)
    .waitingFor(Wait.forHttp("/health"))
    .withLogConsumer(new Slf4jLogConsumer(log));   // parser's own output → EvoMaster's log
parser.start();

String baseUrl = "http://" + parser.getHost() + ":" + parser.getMappedPort(8080);
```

- Testcontainers pulls the image if needed, creates and starts the container, maps the internal port to a random free host port, and only returns once `/health` answers.
- **No `docker` CLI is needed** on the PATH. Testcontainers talks to the Docker API directly and finds the daemon on Linux, macOS and Windows by itself.
- **No mounts.** The container needs no access to the host filesystem or the Docker socket.
- **Cleanup is automatic.** Testcontainers' Ryuk container removes the parser when the JVM exits, even if it is killed. No shutdown hook is needed.
- The parser's own stdout/stderr is forwarded to EvoMaster's log, replacing the previous `logs/processbuilder-log` file.
- The internal port (`8080`) is part of the image contract.

---

## 6. Collecting SUT logs

`DockerLogSource` reads the SUT's logs through the Docker client that Testcontainers already provides (`DockerClientFactory.instance().client()`).

1. **Find the containers once, at startup.** List running containers with the label `com.docker.compose.project=<logCollectorComposeProject>`. Containers belonging to this JVM's Testcontainers session (the parser and Ryuk) are skipped.
2. **Follow each container's log stream** from "now" (`follow=true`, `tail=0`): output written before attaching is ignored. A background thread receives the frames.
3. **Split frames into lines.** Docker frames do not line up with lines. A frame may hold several lines, part of a line, or half of a multi-byte UTF-8 character. Bytes are kept until a newline arrives, and only complete lines are decoded. stdout and stderr are buffered separately so their partial lines do not mix. `\r\n` is handled and blank lines are dropped.
4. **Name the source** after the Compose service label (`com.docker.compose.service`), falling back to the container name.
5. Complete lines are appended to an **in-memory buffer**, which the cursor logic in [§7](#7-mapping-logs-to-tests) empties.

The Compose project name is **required**. With an empty value, EvoMaster stops at startup with a clear error. Following "all running containers" was rejected because unrelated containers on the machine would add noise to the targets. If the project matches no running containers, startup fails as well, since this almost always means a typo or that the SUT is on a different Docker daemon (see [§15](#15-known-limitations-of-v1)).

Nothing is ever written to or deleted from Docker. The SUT's logs remain available through `docker logs` as usual.

---

## 7. Mapping logs to tests

### Why not timestamps

The previous design selected logs by wall-clock time window (`startMs`–`endMs`, taken on EvoMaster's machine). This breaks when the SUT's clock differs from EvoMaster's, for example with a remote SUT, and it depends on the precision of Docker's timestamps. Accuracy of the test-to-log mapping is critical for this approach, so timestamps are not used at all.

### Cursor-based mapping

EvoMaster executes tests strictly one after another. The buffer from [§6](#6-collecting-sut-logs) fills continuously, and `LogCollector` empties it at two points of every test evaluation:

| Point | Hook | Action |
|---|---|---|
| **A** | `goingToStartExecutingNewTest()` | Empty the buffer and **discard** its contents |
| **B** | `testFinishedCollectResult()` | Empty the buffer and **parse** its contents as this test's logs |

```
 time ─────────────────────────────────────────────────────────────►

        A1            B1                  A2             B2
        │  test 1     │   (gap)           │  test 2      │
        ▼             ▼                   ▼              ▼
 logs:  L1  L2        L3                  L4  L5
        └─ test 1 ────┘                   └─ test 2 ─────┘
                      └─ discarded ───┘
```

### Why discard at A

The lines in the gap (L3) were not caused by any test. In white-box mode, EvoMaster's REST fitness calls `rc.resetSUT()` *immediately before* `goingToStartExecutingNewTest()`, and the reset itself produces log output (clearing tables, re-seeding data). Background jobs in the SUT can also log at any time.

If those lines were kept, they would be credited to the next test. The archive might then keep that test as "the" test covering a template it does not actually trigger.

Discarding does **not** lose coverage history:

- **EvoMaster's archive** remembers which tests have covered which targets.
- **The parser's Drain state** remembers every template it has seen, with stable IDs.

The buffer only ever holds lines not yet assigned to a test. Discarding them only affects which lines get parsed; Docker's own logs are untouched.

### Trade-off: late lines

A log line caused by a test but delivered after that test's point B is discarded at the next A. Examples are asynchronous processing in the SUT, or a few milliseconds of Docker streaming delay. That test then misses that template for this evaluation. This is a deliberate choice: a missed target can still be found by a later test, while a wrongly credited target misleads the search. A short settle delay before collecting at B could reduce this, but is left out of v1 to keep it simple.

---

## 8. Fitness targets

Each template returned by `/parse` becomes:

```java
new TargetInfo("log:" + source + ":" + id, 1.0, -1)
```

- `descriptiveId`: `log:<source>:<drainId>`, e.g. `log:oauth2-client:17`. It is stable for the whole run, as `TargetInfo` requires (no timestamps, no template text, which can change).
- `value`: `1.0`, meaning covered. There is no partial heuristic for log templates in v1.
- `actionIndex`: `-1`, meaning not tracked. `reportActionIndex()` is a no-op in v1.

The targets reach the archive through the existing path `EnterpriseFitness.handleFurtherFitnessFunctions()` → `idMapper.handleLocalTarget()`. This path is already used by REST (white-box and black-box) and GraphQL fitness.

---

## 9. Configuration

All settings are `EMConfig` options (G6). `LOG_COLLECTOR_PROJECT` is removed. `core-extra` cannot depend on `core`, so `EnterpriseFitness` passes the values to the `LogCollector` constructor as plain parameters, and `LogCollector` never sees `EMConfig`.

The naming follows existing EvoMaster patterns: an on/off flag plus a file path, as in `exportCoveredTarget` + `coveredTargetFile` and `writeStatistics` + `statisticsFile`. All options are `@Experimental`.

| Option | Type | Default | Meaning |
|---|---|---|---|
| `enableLogCollector` | Boolean | `false` | Enable log-based targets (already exists; description to be updated) |
| `logCollectorComposeProject` | String | `""` | Docker Compose project whose containers are followed. **Required** when the collector is enabled. |
| `logCollectorParserImage` | String | `nanarei/evolog-parser:latest` | Parser image started via Testcontainers |
| `logCollectorTemplatesFile` | String | `logTemplates.txt` | Template ID → text file, see [§10](#10-inspection-output) |
| `logCollectorWriteTrace` | Boolean | `false` | Write the per-evaluation trace file |
| `logCollectorTraceFile` | String | `logCollectorTrace.jsonl` | Location of the trace file |

Which Docker daemon to use (local, or remote via `DOCKER_HOST` / `~/.testcontainers.properties`) is standard Docker/Testcontainers configuration, not an option of this module.

---

## 10. Inspection output

The search process should be as easy to inspect as with the previous design, where `unique-templates.txt` was rewritten after every test. With no new interface methods (G5), all output is written from point **B**, which runs after every test evaluation.

| File | When written | Content |
|---|---|---|
| `logTemplates.txt` | Rewritten at B **when something changed** (a new template, or an existing template's text generalised) | One line per known template: `log:<source>:<id>` and the current template text |
| Trace file (if `logCollectorWriteTrace`) | Appended at every B, flushed immediately | One JSON object per evaluation |

Example trace entry:

```json
{"eval": 1532,
 "lines": [{"source": "oauth2-client", "text": "2026-11-16 10:00:01 INFO User 42 logged in"}],
 "templates": [{"id": "log:oauth2-client:17", "template": "<*> <*> INFO User <*> logged in", "count": 1}]}
```

- Including raw lines makes it possible to check the test-to-log mapping by eye.
- Because the files are written as the search runs, they can be followed live (`tail -f`), and nothing is lost if EvoMaster is killed. The last state stays on disk, which is exactly what the previous design relied on.
- The trace is off by default because a run evaluates thousands of tests and the file can get large.
- `eval` counts **evaluations**, not final tests. Most evaluated candidates are discarded by the search, and the ones that end up in the generated suite are renamed. To see which log targets the final test suite covers, use EvoMaster's existing `exportCoveredTarget` / `coveredTargetFile`, which includes the `log:` targets.

The previous `logs/` and `templates/` directories in the working directory are no longer created.

---

## 11. Error handling

The collector must never break the search.

| Situation | Behaviour |
|---|---|
| Parser image cannot be pulled, or `/health` never answers | Startup fails with a clear error (the user enabled the feature, so silently running without it would be misleading) |
| Compose project empty, or matches no running containers | Startup fails with a clear error |
| `/parse` times out, cannot connect, or returns non-`200` | Warning logged; this test reports no log targets; the search continues |
| A container's log stream ends or fails | Warning logged; other containers continue to be followed |

There is no restart logic. The parser is a stateful HTTP service managed by Testcontainers; if it dies, its Drain state is lost anyway, so restarting it would change the template IDs.

---

## 12. Java version

EvoMaster's **driver** (`client-java`) must stay JDK 8 compatible, while **core** is built and run on JDK 17 (see `docs/for_developers.md`, *JDK VERSIONS*). This module is only used by `core`, so JDK 17 would technically be allowed. It is nevertheless compiled with `release 8`, like `arazzo-parser`, `asyncapi-parser` and `solver`, to stay consistent with the other `core-extra` modules and keep the option of reuse elsewhere open (G7).

In practice:

- `HttpURLConnection` instead of `java.net.http.HttpClient`
- plain classes instead of records
- no `List.of` / `Map.of` and other post-8 APIs
- Testcontainers 1.21.x and Jackson 2.14.x both support Java 8

New module dependencies are `org.testcontainers:testcontainers` (compile scope) and `com.fasterxml.jackson.core:jackson-databind`. Both versions are already managed in the root `pom.xml`, and `core` already depends on Testcontainers at compile scope.

---

## 13. Changes outside this module

| File | Change |
|---|---|
| `core/.../EMConfig.kt` | New options from [§9](#9-configuration); updated `enableLogCollector` description |
| `core/.../EnterpriseFitness.kt` | One line: pass the options to the `LogCollector` constructor |
| `docs/options.md` | Regenerated from `EMConfig` (checked by `ConfigToMarkdownTest`) |

The `AdditionalTargetCollector` interface, the search algorithm, the fitness functions and the archive are **not changed**.

---

## 14. Testing

| Test | Scope | Needs Docker |
|---|---|---|
| `LogCollector` unit tests | Cursor logic (discard at A, parse at B), mapping to `TargetInfo`, inspection files, failure → empty result. Uses a fake `LogSource` and a small fake parser built on the JDK's `com.sun.net.httpserver.HttpServer` | No |
| `ParserClient` unit tests | JSON request/response, non-`200` handling, unknown fields | No |
| Line splitting unit tests | Multiple lines per frame, partial lines across frames, split UTF-8 characters, `\r\n`, blank lines | No |
| `LogCollectorDockerManualTest` | End-to-end with the real parser image and a running Compose project. Not run in CI | Yes |

The Python test daemons (`replay_daemon.py`, `error_daemon.py`) are removed together with the stdin protocol. The sample logs under `src/test/resources/test-logs/` can still be used as input for the fake log source.

---

## 15. Known limitations of v1

1. **Docker only.** The SUT must run in Docker containers of one Docker Compose project. A SUT running as a plain process or a remote service is not supported yet.
2. **Same Docker daemon.** The SUT containers must run on the Docker daemon EvoMaster (Testcontainers) connects to, and the parser container is started on that same daemon. A remote daemon works if Testcontainers is pointed at it, but the SUT and the parser cannot be on different daemons.
3. **Containers are found once.** Containers that are restarted, recreated or added after startup are not followed.
4. **Late log lines are discarded.** Lines that arrive after their test's point B are lost (see [§7](#7-mapping-logs-to-tests)).
5. **No action-level attribution.** Targets are reported per test, not per action (`actionIndex = -1`).
6. **Binary coverage only.** Targets are either covered (`1.0`) or absent; there is no heuristic distance.
7. **Parser image location.** The image is hosted on a personal DockerHub account (`nanarei/evolog-parser`) and should move to an EvoMaster organisation account.
8. **Drain state is per run.** Template IDs are only stable within one EvoMaster run; they are not comparable across runs.

---

## 16. Rejected alternatives

| Alternative | Reason for rejection |
|---|---|
| Keep the stdin daemon started with `ProcessBuilder` | Rejected in review; requires the `docker` CLI, a custom protocol and hand-written lifecycle handling |
| Parser fetches logs itself via a mounted Docker socket | Not OS-agnostic (`/var/run/docker.sock` does not exist with native Docker on Windows); the parser cannot know test boundaries without timestamps |
| Wall-clock time windows for mapping logs to tests | Inaccurate when SUT and EvoMaster clocks differ; depends on timestamp precision |
| Keep between-test lines and credit them to the next test | Credits targets to tests that did not cause them (e.g. output of `resetSUT()`) |
| Return only new templates from `/parse` | Prevents the archive from replacing a covering test with a better one |
| Exchange results through files in mounted folders | Host paths differ between OSes; HTTP responses make the files unnecessary |
| Environment variables for configuration | Maintainers asked to avoid them; `EMConfig` is the standard mechanism |
| Follow all running containers when no project is set | Unrelated containers add noise to the targets |
| `GET /templates` plus a new "search finished" hook to write a final report | Would require changing `AdditionalTargetCollector`; EvoMaster can track templates itself and rewrite the file at B |
| A JVM shutdown hook to write final output | The pattern this rewrite removes; unnecessary when files are kept up to date at B |
| Re-discovering containers before each test | More complex; not needed for v1 |

---

## 17. Future work

- Additional log sources: tailing log files, or a push endpoint for remote SUTs.
- Re-discovering containers so restarted or recreated SUT containers keep being followed.
- An optional settle delay at point B to catch late log lines.
- Action-level attribution using `reportActionIndex()`, by collecting after each action.
- Revisiting whether `/parse` should return all templates or only new ones.
- Moving the parser image to an EvoMaster organisation account.

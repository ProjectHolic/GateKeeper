# GateKeeper — Architecture & Code Reference

**A JavaFX desktop simulator for API rate limiting and abuse detection.**

This document is a complete description of the project. It is written for a reader
who has never seen the codebase and knows no JavaFX: it explains what the program
does, what every concept means, how the pieces fit together, what every file and
every function is for, and why the non-obvious decisions were made. It also
includes a worked numeric example, a "how to change X" recipe section, and a
troubleshooting guide.

---

## Table of contents

1. [What the program does](#1-what-the-program-does)
2. [Building and running](#2-building-and-running)
3. [Repository layout](#3-repository-layout)
4. [JavaFX concepts primer](#4-javafx-concepts-primer)
5. [Domain concepts and vocabulary](#5-domain-concepts-and-vocabulary)
6. [How rate limiting works](#6-how-rate-limiting-works)
7. [Worked example: eight requests, end to end](#7-worked-example-eight-requests-end-to-end)
8. [Architecture overview](#8-architecture-overview)
9. [Threading model](#9-threading-model)
10. [`SimulationController` field reference](#10-simulationcontroller-field-reference)
11. [The `model` package, file by file](#11-the-model-package-file-by-file)
12. [The policy package, file by file](#12-policy-package-file-by-file)
13. [The `ui` package, file by file](#13-the-ui-package-file-by-file)
14. [The FXML layout, widget by widget](#14-the-fxml-layout-widget-by-widget)
15. [The stylesheet, rule by rule](#15-the-stylesheet-rule-by-rule)
16. [The bundled dataset](#16-the-bundled-dataset)
17. [Runtime flows, step by step](#17-runtime-flows-step-by-step)
18. [Performance engineering](#18-performance-engineering)
19. [Design decisions and their rationale](#19-design-decisions-and-their-rationale)
20. [Recipes: how to change things](#20-recipes-how-to-change-things)
21. [Troubleshooting](#21-troubleshooting)
22. [Known limitations](#22-known-limitations)
23. [Glossary](#23-glossary)
24. [Appendix A: complete function index](#appendix-a-complete-function-index)
25. [Appendix B: the three cell renderers in detail](#appendix-b-the-three-cell-renderers-in-detail)

---

## 1. What the program does

GateKeeper imitates the request-admission layer that sits in front of an API.
Real services use such a layer to decide, for each incoming request, whether to
serve it or refuse it, and to keep a score of how badly each caller has behaved.

GateKeeper is a **simulator**: there is no server, no network, and no real API.
It is a GUI over a synthetic model. You pick a client and a request type, press
a button, and the program decides whether that request would have been allowed.
It also replays a bundled half-million-row request history so the rate limits
can be derived from how each client has actually behaved.

### The eight things the user can do

| Control | Handler | What happens |
| --- | --- | --- |
| **+ (add client)** | `onAddClient` | Registers a new client by name. Duplicate names are refused. |
| **Single Request** | `onSingleRequest` | Evaluates one request. Logs `ACCEPTED` or `BLOCKED`. |
| **Burst (20x)** | `onBurst` | The same, twenty times back to back. |
| **Auto Simulate** | `onAutoSimulate` | A background timer picks a client and a type at random every 800 ms until pressed again. |
| **Generate Abuse Report** | `onGenerateAbuseReport` | Opens a modal dashboard of per-client risk. |
| **⚙ (settings)** | `onSettings` | Modal to change policy, window, thresholds, severity, decay, per-type limits. |
| **Clear** | `onClear` | Resets the whole simulation. |
| **Reload History** | `onReloadHistory` | Re-reads the bundled CSV. |

### What you see

A fixed 1000x600 window in three regions:

* **Top bar (73 px)** — logo and title on the left; "Generate Abuse Report", the
  settings gear, and a pulsing green status dot on the right.
* **Left column (250 px)** — the client registry (cards with a status badge) and
  below it the live request log with its Clear / Reload History buttons.
* **Centre (750 px)** — four KPI numbers across the top, a traffic line chart,
  and the request controls.

---

## 2. Building and running

### Requirements

* **JDK 21.** The code uses records, exhaustive `switch` expressions, and
  `List.removeFirst` (a Java 21 sequenced-collection method).
* **JavaFX.** The project declares **no dependencies in any build file, because
  it has no build file at all.** IntelliJ IDEA is configured to use the JavaFX
  jars already installed on the system.

### How the project is currently built

| File | Purpose |
| --- | --- |
| `GateKeeper.iml` | Declares `src` as the source root and `resources` as a *java-resource* root, and adds a library called `lib`. |
| `.idea/libraries/lib.xml` | Points that library at `/usr/share/openjfx/lib` — a **hard-coded absolute path**. |
| `.idea/misc.xml` | Sets `languageLevel = JDK_21`, `project-jdk-name = 21`, output to `./out`. |
| `.idea/vcs.xml` | Git integration. |

**Consequence:** on any machine where JavaFX lives elsewhere, the project will
not compile until that path is changed. From a shell you can compile and run
manually:

```bash
# 1. compile — JavaFX jars must be on the classpath
javac -d out/classes -cp "/usr/share/openjfx/lib/*" $(find src -name '*.java')

# 2. copy resources next to the classes so they land on the classpath
cp -r resources/com out/classes/

# 3. run
java -cp "/usr/share/openjfx/lib/*:out/classes" com.simulator.Main
```

### Entry point

`src/com/simulator/Main.java` is a nine-line shim that only calls
`MainApp.main(args)`. It exists because the IDE run configuration points at it.
`MainApp` is the real entry point.

### A note on resource loading

The classpath must contain **both**:

```
com/simulator/ui/dashboard.fxml
com/simulator/ui/style.css
com/simulator/ui/img/logo.png
com/simulator/files/logs.csv
```

`dashboard.fxml`, `style.css` and the logo come from the `resources` root.
`logs.csv` comes from `src` — it is not Java source, but IntelliJ copies
non-`.java` files from a source root onto the classpath, which is how the
application finds it. This is fragile by design of the current project layout: if
the CSV is not copied, the app reports the history as unavailable in the log
list's placeholder rather than crashing.

### Two known, harmless warnings at startup

```
WARNING: Loading FXML document with JavaFX API of version 25 by JavaFX runtime of version 11.0.11-internal
```

The FXML declares `xmlns="http://javafx.com/javafx/25"` (exported from a newer
Scene Builder) while the installed runtime is JavaFX 11. The document loads
correctly. The warning can be silenced by changing the FXML namespace to
`.../javafx/11`.

---

## 3. Repository layout

```
GateKeeper/
├── GateKeeper.iml                  IntelliJ module descriptor (the only build config)
├── README.md                       Original team work-split notes
├── ARCHITECTURE.md                 This document
├── .gitignore
├── .idea/                          IntelliJ settings (lib.xml, misc.xml, vcs.xml)
│
├── resources/                      "java-resource" source root → classpath
│   └── com/simulator/ui/
│       ├── dashboard.fxml          Main window layout, 226 lines (Scene Builder output)
│       ├── style.css               Dark theme, 346 lines, 58 rules
│       └── img/logo.png            500x500 logo, shown in the header
│
├── src/                            Java source root (also holds a runtime data file)
│   ├── com/simulator/
│   │   ├── Main.java               Shim entry point, 9 lines
│   │   ├── files/
│   │   │   └── logs.csv            Bundled request history: 516,544 rows, 24 MB
│   │   ├── model/                  Domain objects
│   │   │   ├── Client.java                     122 lines
│   │   │   ├── DailyAcceptedIndex.java          93 lines
│   │   │   ├── Log.java                         43 lines
│   │   │   ├── Request.java                     29 lines
│   │   │   ├── RequestType.java                  8 lines
│   │   │   └── ViolationLevel.java               8 lines
│   │   ├── policy/                 Rate-limiting algorithms
│   │   │   ├── RatePolicy.java                  40 lines
│   │   │   ├── AbstractWindowPolicy.java       129 lines
│   │   │   ├── FixedWindowPolicy.java           40 lines
│   │   │   └── SlidingWindowPolicy.java         28 lines
│   │   └── ui/                     JavaFX layer
│   │       ├── MainApp.java                    24 lines
│   │       └── SimulationController.java     1913 lines
│   │
└── out/                            IntelliJ build output (git-ignored)
```

### Why the CSV is a resource rather than generated data

Rate limits are *derived from history*. A client that has historically made up to
240 reads in a day gets a higher read limit than one that made 51. Without a real
history every client would look identical, and the "auto-calculate from
history" feature — a headline feature of the settings dialog — would have nothing
to work from.

---

## 4. JavaFX concepts primer

If JavaFX is unfamiliar, this section is the minimum you need to read the rest of
the document. Skip it if you already know JavaFX.

### The scene graph

Every visual element is a **`Node`**. Nodes are organised in a tree: a parent
*contains* children. The tree of the main window is declared in `dashboard.fxml`
and instantiated by `FXMLLoader`.

Common containers used here:

| Node | Purpose |
| --- | --- |
| `StackPane` | Stacks children on top of each other, all filling the space. |
| `VBox` | Vertical stack; each child gets its preferred height. |
| `HBox` | Horizontal stack; each child gets its preferred width. |
| `BorderPane` | Five regions: top, bottom, left, right, centre. |
| `ScrollPane` | Makes its content larger than itself scrollable. |
| `Region` | A generic, styleable rectangle used for spacers and meter bars. |

Controls used here: `Label`, `Button`, `TextField`, `ListView`, `ComboBox`,
`Spinner`, `LineChart`, `Circle`, `SVGPath`.

### Stage and Scene

* A **`Stage`** is a top-level operating-system window. It has a title bar and
  can be shown, hidden, and styled (`StageStyle.DECORATED` by default;
  `TRANSPARENT` and `UNDECORATED` are used by the modals).
* A **`Scene`** holds one scene graph and is attached to a `Stage`.

The application has four stages: the main window, the settings dialog, the
abuse-report popup, and a small validation-error dialog.

### The single UI thread

JavaFX allows exactly **one** thread to touch the scene graph: the *FX
Application Thread*. Any attempt from another thread throws
`IllegalStateException: Not on FX application thread`. To hand work across, use
`Platform.runLater(runnable)`. See [§9](#9-threading-model).

### Observable collections and properties

JavaFX ships observable versions of its data structures so the UI can update
itself:

* **`ObservableList<E>`** — a `List` that fires a `ListChangeListener` when
  elements are added, removed or replaced. `FXCollections.observableArrayList()`
  creates one.
* **`IntegerProperty` / `ObjectProperty<T>`** — observable single values, with
  `get()`, `set(value)` and `addListener(...)`.
* **`Bindings.createStringBinding(supplier, observables...)`** — recomputes a
  `String` automatically whenever any of the observed sources changes.

`ListView` and `ComboBox` are *observers*: hand them an `ObservableList` and they
refresh themselves. This is why the controller rarely calls a "refresh" method.

### The extractor idiom

`FXCollections.observableArrayList(extractor)` lets you declare which properties
of each element the list should watch:

```java
FXCollections.observableArrayList(
        client -> new Observable[]{
                client.totalRequestProperty(),
                client.violationCountProperty(),
                client.violationScoreProperty(),
                client.levelProperty()
        });
```

Without this, mutating a `Client`'s score would not notify the list and the badge
would go stale. With it, the list fires a change event and the affected cells
re-render. See [§8](#8-architecture-overview).

### Virtualised lists

`ListView` does **not** create one node per item. It renders only the rows
currently on screen, recycling nodes as you scroll ("virtual flow"). This is why
a `ListView` can hold 50,000 items cheaply, and why the *data* (not the widget
count) is what costs memory.

### ListCell

The renderer for one row. Subclass `ListCell<T>` and override
`updateItem(T item, boolean empty)`. The `empty` flag is true for the filler
cells that pad a partially filled list; when it is true you must clear the
graphic and text. `setGraphic(node)` installs your custom row. This project has
three such renderers — see [Appendix B](#appendix-b-the-three-cell-renderers-in-detail).

### FXML and `@FXML`

`FXMLLoader` parses `dashboard.fxml`, creates the nodes, and injects them into
the controller's fields annotated `@FXML` by matching `fx:id`. Two other
mechanisms are used here:

* `onAction="#methodName"` on a button calls a controller method.
* `fx:controller="fully.qualified.ClassName"` names the controller.

If a referenced handler does not exist, `FXMLLoader` throws during `load()` — so
a typo in the FXML fails loudly at startup rather than silently.

### Modality and nested event loops

`Stage.initModality(Modality.APPLICATION_MODAL)` blocks the rest of the
application while the stage is showing. `showAndWait()` additionally blocks the
*caller* until the stage closes, by starting a **nested event loop**. Animated
`Timeline`s and other timers keep running inside a nested loop, which is why the
auto-simulation must be explicitly paused when the settings dialog opens.

### Animation

* **`Timeline`** — fires an event on a schedule. Used for the 1-second traffic
  sampler and the 800 ms auto-simulation tick. `Timeline.INDEFINITE` repeats
  forever.
* **`KeyFrame`** — one scheduled moment with its handler.
* **`FadeTransition`** — animates a property between two values; used for the
  pulsing status dot.

### Styling

Two mechanisms, used together here:

1. **CSS** (`style.css`) targets *style classes*, applied via the `styleClass`
   attribute in FXML. Class names in this project are camelCase and occasionally
   misspelled — `.ClintLog` is the client registry, `.LogList` the log.
2. **Inline styles** set from Java with `setStyle("...")`, for anything that
   depends on runtime state (a badge colour that depends on the current violation
   level, for example).

Inline styles win over CSS, which is why dynamic badges look right and why
duplicating a rule in both places causes confusion.

---

## 5. Domain concepts and vocabulary

### Client

A caller of the API, identified by a **name** (`"Client-014"`). A `Client`
carries mutable telemetry:

| Field | Type | Meaning |
| --- | --- | --- |
| `name` | `String` (final) | The registry key. |
| `totalRequest` | `IntegerProperty` | Accepted requests this session. |
| `violationCount` | `IntegerProperty` | Blocked requests this session. |
| `violationScore` | `IntegerProperty` | Accumulated penalty; decays over time. |
| `level` | `ObjectProperty<ViolationLevel>` | Risk badge, derived from the score. |
| `lastViolationTime` | `LocalDateTime` | When the score last increased. Drives the cooldown. |

The first four are JavaFX properties, which is what lets the UI update itself
when a score changes.

### Request

One attempt by a client to perform an action of a given `RequestType`, stamped
with the moment it was made. Requests live only in a short in-memory window and
are never persisted.

### RequestType

The kind of action: `READ`, `WRITE`, `LOGIN`, `PAYMENT`. Each carries a
**severity weight** (default `READ` 1, `WRITE` 2, `LOGIN` 3, `PAYMENT` 2) — a
login storm is more concerning than a read flood, so violations of a severe type
cost more score.

### Log

A durable record of a request's outcome: the client, type, timestamp, and a
status of `ACCEPTED` or `BLOCKED`. Logs are what the user scrolls through, and
what rate limits are derived from.

### ViolationLevel

The four-step risk label shown on a client's badge, ordered by increasing
severity. **It is derived purely from `violationScore`** by descending comparison
against three configurable thresholds (default 20 / 50 / 100). Nothing else may
set it.

### Window, limit, and the two policies

A **window** is a length of time (default 10 s, configurable 1–3600 s). A
**limit** is how many requests of one type a client may make inside it. The two
policies differ only in where the window's edges fall:

* **Fixed** — snaps to a global grid of whole windows.
* **Sliding** — always ends at the newest matching request.

### Score, level, and cooldown — three different things

This distinction is the single most important thing to understand:

* **Allow/block** is the *per-request verdict*. It is what the policy returns and
  what decides whether a request is served.
* **Violation level** is the client's *standing risk*, derived only from the
  accumulated score.
* **Cooldown** is the lockout timer. A client at `CRITICAL` is refused outright
  until one full window passes without a fresh violation.

These are deliberately separate. An earlier version conflated the first two,
which made a client's badge contradict the score printed beside it. They are now
carried independently in `RatePolicy.Decision`.

---

## 6. How rate limiting works

This is the heart of the program. Everything else is presentation.

### Inputs

For each request the policy needs: the client, the request type, the list of
recent requests, the limit for that (client, type), the window length, the
severity weights, and the three score thresholds.

### The algorithm, step by step

```
evaluate(client, type, requests):
  1.  Re-derive the client's level from its current score.
  2.  If score >= CRITICAL threshold AND the client is still inside its
      cooldown window  ->  refuse immediately. No scoring, no counting.
  3.  Filter `requests` to those from THIS client AND of THIS type,
      sorted oldest to newest. (Other clients' requests, and this client's
      requests of OTHER types, do not count against this type's limit.)
  4.  If nothing matched  ->  allow, and decay the score if there is one.
  5.  Take `latest`, the timestamp of the newest matching request. Ask the
      policy where its window starts and ends.
  6.  count = how many matching requests fall inside that half-open range.
  7.  Compare count against the limit:
          count >  limit * 3  ->  penalty 30 * severity  ->  refuse
          count >  limit * 2  ->  penalty 15 * severity  ->  refuse
          count >  limit      ->  penalty  5 * severity  ->  refuse
          otherwise            ->  allow, and decay the score
  8.  Re-derive the level and return.
```

Note the comparisons are strict (`>`), so a client may make exactly `limit`
requests per window; the next one is refused.

### Where the window edges fall

This is the only thing that differs between the two policies.

**Sliding window** — always ends at the newest matching request:

```java
new Window(latest.minus(window), latest.plusNanos(1))
```

The `plusNanos(1)` makes the range half-open, matching every other window in the
codebase. A burst of 8 requests in 8 seconds is always seen in full, even if it
straddles what would be two fixed windows.

**Fixed window** — snaps to a global grid anchored to the Unix epoch:

```java
long windowSeconds = Math.max(1, window.getSeconds());
long epochSecond   = latest.toEpochSecond(ZoneOffset.UTC);
long windowStart   = Math.floorDiv(epochSecond, windowSeconds) * windowSeconds;
LocalDateTime start = LocalDateTime.ofEpochSecond(windowStart, 0, ZoneOffset.UTC);
return new Window(start, start.plusSeconds(windowSeconds));
```

`Math.max(1, ...)` guards against a zero-length window, which would otherwise
divide by zero.

The defining property of an aligned window is that the verdict depends only on
`offset mod windowLength`. The current implementation satisfies that for every
window length tested (7, 10, 30, 45, 90, 600, 3600 seconds).

The trade-off is inherent and deliberate: a burst straddling a boundary is split
across two windows, so a fixed window under-reports peak load. That is exactly
why the sliding window exists, and the settings dialog lets you switch between
them to see the difference.

Request timestamps are naive `LocalDateTime` values with no zone. They are
*t treated* as UTC purely to obtain a stable grid. The absolute offset is
irrelevant; what matters is that a given instant always falls in the same window.

### How the limit itself is chosen

`resolveThreshold(client, type)` is the single authority:

```
custom = customThresholds[type]           # user setting; 0 means "auto"
if custom > 0        ->  use custom
if client is null     ->  use the built-in default for the type
otherwise            ->  derive from that client's own history:
                             peak  = highest number of ACCEPTED requests of
                                     this type the client made on any single day
                             limit = clamp(ceil(peak * 1.5), default, 200)
```

Built-in defaults (the floor): `LOGIN` 5, `PAYMENT` 10, `WRITE` 20, `READ` 50.

**Both the dashboard label and the enforcing policy call this one function**, so
the number the user sees is by construction the number that is enforced.

> **Known subtlety:** because the peak includes *today*, a client that sends a
> great many requests in one sitting can ratchet its own limit upward. With the
> bundled data (busiest days of 10–240 requests) a normal session will not reach
> that, so it is latent rather than active.

### Score, level, and recovery

| Situation | Effect |
| --- | --- |
| Request refused | `violationScore += penalty`, `violationCount += 1`, `lastViolationTime = now` |
| Request allowed, score > 0 | `violationScore -= decayAmount`, floored at 0 |
| Any change | Level recomputed from the score |

The score-to-level mapping (with default thresholds):

| Score | Level | Badge text (registry / report) |
| --- | --- | --- |
| `>= 100` | `CRITICAL` | `BLOCKED` / `BLOCKED` |
| `>= 50` | `HIGH` | `SUSPICIOUS` / `SUSPICIOUS` |
| `>= 20` | `WARNING` | `WARNING` / `WARNING` |
| below 20 | `NONE` | `ACTIVE` / `NORMAL` |

A `CRITICAL` client is refused without further penalty until a full window passes
with no new violation. It can then be served again, and every accepted request
decays its score, eventually dropping it below the critical threshold.

---

## 7. Worked example: eight requests, end to end

To make the algorithm concrete, here is a full trace. Settings: a **custom LOGIN
limit of 3** (so the numbers are small), a 10-second window, LOGIN severity 3,
thresholds 20 / 50 / 100, decay 1. The client starts at score 0, level `NONE`.

| # | In-window count | Verdict | Score after | Level after | Client total | Log |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 1 | allowed | 0 (no decay, already 0) | `NONE` | 1 | ACCEPTED |
| 2 | 2 | allowed | 0 | `NONE` | 2 | ACCEPTED |
| 3 | 3 | allowed | 0 | `NONE` | 3 | ACCEPTED |
| 4 | 4 | **refused** | 0 + 5×3 = **15** | `NONE` | 3 | BLOCKED |
| 5 | 4 | **refused** | 15 + 15 = **30** | `WARNING` | 3 | BLOCKED |
| 6 | 4 | **refused** | 30 + 15 = 45 | `WARNING` | 3 | BLOCKED |
| 7 | 4 | **refused** | 45 + 15 = **60** | `HIGH` | 3 | BLOCKED |
| 8 | 4 | **refused** | 60 + 15 = **75** | `HIGH` | 3 | BLOCKED |

Observations worth internalising:

* **The in-window count stays at 4.** The request just refused is removed from
  the window again, so a client that keeps hammering stays exactly one over the
  limit rather than digging an ever-deeper hole. That is why it takes many
  requests to reach `CRITICAL`.
* **The badge lags the refusals.** Requests 4 to 7 were all refused, yet the
  badge only reached `WARNING` at request 5. At request 4 the score was 15,
  below the warning threshold of 20, so the client still read `NONE` — correctly,
  because 15 out of 100 is genuinely low. The badge reports *standing risk*, not
  "was the last request refused".
* **Refused requests never count as accepted**, so they do not raise the
  history-derived limit.

Continuing, if the client keeps going, the score climbs 15 points per refusal:
90, then 105, at which point it crosses the critical threshold. From then on:

| Phase | Behaviour |
| --- | --- |
| Inside the 10 s cooldown | Every request refused immediately, with **no further penalty** — the score is frozen. |
| After 10 s of quiet | The next request is evaluated normally. Count is 1 (≤ 3), so it is **allowed** and the score decays by 1. |
| Each further accepted request | Score decays by 1. From 105, six accepted requests bring it to 99 — below critical, so the badge drops to `HIGH` and the client is usable again. |
| Continuing | 56 accepted requests to fall below 50 (`WARNING`→ boundary), 86 to fall below 20 (back to `NONE`). |

The practical consequence: a client that misbehaves is locked out for one
window, then recovers gradually as it behaves.

---

## 8. Architecture overview

### Three layers

```
┌──────────────────────────────────────────────────────────────┐
│  ui                                                          │
│    MainApp               JavaFX Application, loads the FXML  │
│    SimulationController  ALL state, ALL wiring, ALL UI build  │
│      ├── model.*                                            │
│      └── policy.*                                           │
└──────────────────────────────────────────────────────────────┘
┌──────────────────────────────────────────────────────────────┐
│  policy                                                     │
│    RatePolicy              interface + Decision record       │
│    AbstractWindowPolicy    all scoring / decay / lockout      │
│    FixedWindowPolicy       grid-aligned window               │
│    SlidingWindowPolicy     trailing window                   │
└──────────────────────────────────────────────────────────────┘
┌──────────────────────────────────────────────────────────────┐
│  model                                                      │
│    Client, Request, Log, RequestType, ViolationLevel          │
│    DailyAcceptedIndex     incremental history tallies        │
└──────────────────────────────────────────────────────────────┘
```

Data flows strictly downward: `policy` depends on `model`, `ui` depends on both.
The `model` package depends on nothing in the project (though `Client` and
`RatePolicy` do import JavaFX types — see [§22](#22-known-limitations)).

### The five collections

`SimulationController` holds all mutable state in five `ObservableList`s.

| Field | Contents | Bound to |
| --- | --- | --- |
| `clients` | Registered clients | `ClientList`, `clientChoiceBox`, both KPI labels, the report's list |
| `requests` | Requests inside the current window | nothing (internal to the policy) |
| `logs` | Request history, newest first | `LogList` |
| `types` | The four `RequestType` values | `typeChoiceBox` |
| `trafficSeries` | Per-second accepted counts | `trafficChart` |

### Property-based auto-updating

`clients` is created with an **extractor** that declares which of each element's
properties the list should watch:

```java
private final ObservableList<Client> clients =
        FXCollections.observableArrayList(
                client -> new Observable[]{
                        client.totalRequestProperty(),
                        client.violationCountProperty(),
                        client.violationScoreProperty(),
                        client.levelProperty()
                }
        );
```

Because the list observes those four properties, mutating
`client.violationScore` fires a change event on the list, which refreshes the
affected `ListView` cell. That is why a badge updates the instant its score
changes, with no hand-written listener. The same list backs the abuse report's
`ListView`, so that dialog updates too.

The two KPI labels use `Bindings.createStringBinding`, recomputing whenever the
observed list changes.

### The history index

`DailyAcceptedIndex` maintains, for every (client, type, local date), how many
requests were **accepted**. It exists because deriving a limit needs "the
busiest day this client ever had", and rescanning 500,000 log rows to find that
on every single request is far too slow. See
[§18](#18-performance-engineering).

It is kept in step with `logs` by a `ListChangeListener`, so it is correct for
*any* mutation path rather than depending on scattered manual bookkeeping.

### Startup ordering

The order of statements in `initialize()` matters. In particular:

* `setItems(...)` is called **before** the selection listeners are added, so
  populating the lists does not trigger a cascade of label updates.
* `initializeDefaultSettings()` runs **before** `updatePolicyDisplay()`, so the
  first label render uses real values.
* The `logs` change listener is registered **before** `loadLogsFromCsv()`, so the
  index is populated when the history arrives.
* `setupTrafficChart()` and `startTrafficTimer()` run **before** the history load,
  so the chart is live while the data streams in.

---

## 9. Threading model

JavaFX permits only the FX Application Thread to touch the scene graph.

| Component | Thread | Notes |
| --- | --- | --- |
| `Main.main`, `Application.launch` | main → FX | `launch` hands over to the FX thread. |
| `MainApp.start(Stage)` | FX | |
| `FXMLLoader.load()` and `initialize()` | FX | Called from inside `start`. |
| All eight `@FXML` handlers | FX | Every one is invoked by the FX event loop. |
| Traffic chart `Timeline` (1 s) | FX | A `Pulse`/timeline handler. |
| Auto-simulation `Timeline` (800 ms) | FX | **Keeps running inside a modal's nested event loop** — hence the explicit pause. |
| Settings, abuse report, validation modals | FX | `showAndWait()` starts a nested event loop. |
| `readHistoryAsync` → `readHistoryRows` → `parseHistoryRow` | **`log-history-loader`** (daemon) | Pure I/O and string parsing; touches no UI and no JavaFX collection. |
| `mergeHistoryRows`, `onHistoryLoadFailed` | FX, via `Platform.runLater` | The hand-off point. |
| All `ObservableList` mutations | FX | Including `logs`, `clients`, `requests`. |
| `DailyAcceptedIndex` reads and writes | FX | |
| `Client` property mutations | FX | |

The loader thread is created as a daemon so it cannot keep the JVM alive if the
window is closed mid-load.

`readHistoryRows` deliberately creates no `Client` and no `Log`; it produces only
immutable `HistoryRow` records. That is what makes it safe off the FX thread.
Client creation — which mutates the `clients` list — happens in
`mergeHistoryRows` on the FX thread.

---

## 10. `SimulationController` field reference

### Injected from the FXML (`@FXML`)

| Field | FXML `fx:id` | Type | Role |
| --- | --- | --- | --- |
| `ClientBox` | `ClientBox` | `TextField` | Name entry for a new client. |
| `totalRequestLabel` | `totalRequestLabel` | `Label` | KPI: accepted requests across all clients. |
| `violationLabel` | `violationLabel` | `Label` | KPI: violations across all clients. |
| `ClientList` | `ClientList` | `ListView<Client>` | The client registry. |
| `LogList` | `LogList` | `ListView<Log>` | The request log. |
| `clientChoiceBox` | `clientChoiceBox` | `ComboBox<Client>` | Which client the next request belongs to. |
| `typeChoiceBox` | `typeChoiceBox` | `ComboBox<RequestType>` | Which type the next request is. |
| `settingsButton` | `settingsButton` | `Button` | Kept for completeness; the FXML wires the action. |
| `policyDisplayLabel` | `policyDisplayLabel` | `Label` | "Policy: Fixed Window". |
| `activeWindowLabel` | `activeWindowLabel` | `Label` | Window size, e.g. "10S". |
| `rateLimitLabel` | `rateLimitLabel` | `Label` | The enforced limit, e.g. "47 / window". |
| `autoSimButton` | `autoSimButton` | `Button` | Toggles the simulation; also restyled at runtime. |
| `trafficChart` | `trafficChart` | `LineChart<String,Number>` | Per-second accepted requests. |
| `trafficYAxis` | `trafficYAxis` | `NumberAxis` | Auto-ranging, forced to include zero. |
| `Blinking` | `Blinking` | `Circle` | The pulsing status dot. |

### Settings state (mutated only by Save or Reset)

| Field | Default | Meaning |
| --- | --- | --- |
| `selectedPolicy` | `FIXED_WINDOW` | Which policy class to build. |
| `windowSeconds` | 10 | Window length. |
| `customThresholds` | all 0 | Per type; 0 means "derive from history". |
| `severityMultipliers` | READ 1, WRITE 2, LOGIN 3, PAYMENT 2 | Penalty weights. |
| `warningThreshold` | 20 | Score at which level becomes `WARNING`. |
| `highThreshold` | 50 | …`HIGH`. |
| `criticalThreshold` | 100 | …`CRITICAL`, and the lockout trigger. |
| `decayAmount` | 1 | Score removed per accepted request. |

### Simulation state

| Field | Meaning |
| --- | --- |
| `autoSimTimeline` | The 800 ms `Timeline`; null when stopped. |
| `isAutoSimRunning` | Whether the simulation should be running. |
| `autoSimClientIndex` | Round-robin cursor; only ever read modulo the client count. |
| `random` | `Random` for picking a request type. |
| `suppressIndexSync` | Set while `logs` is changed in bulk, so the change listener stands down. |
| `historyLoading` | Guards against two concurrent history loads. |
| `validRequestsThisSecond` | Accepted requests since the last chart tick. |
| `secondCounter` | Monotonic tick number, used as the chart's category label. |

---

## 11. The `model` package, file by file

### `Client.java` (122 lines)

The mutable per-caller record. **Not** a JavaFX `Node`; it uses JavaFX property
wrappers so the UI can observe it.

| Member | Purpose |
| --- | --- |
| `String name` | `final`, set in the constructor. The registry key. |
| `IntegerProperty totalRequest` | Accepted-request counter, starts 0. |
| `IntegerProperty violationCount` | Blocked-request counter, starts 0. |
| `IntegerProperty violationScore` | Accumulated penalty, starts 0. |
| `ObjectProperty<ViolationLevel> level` | Risk badge, starts `NONE`. |
| `LocalDateTime lastViolationTime` | Plain field, **not** a property. Set by `addViolationScore`, read by the policy's cooldown check, nulled by `reset`. |
| `Client(String name)` | Assigns the name only. |
| `getName()` | The accessor. |
| `getTotalRequest()` / `totalRequestProperty()` | Plain read / observable read. |
| `increaseTotalRequest()` | `+1`. |
| `getViolationScore()` / `violationScoreProperty()` | Plain read / observable read. |
| `addViolationScore(int)` | Adds the penalty **and** stamps `lastViolationTime`. |
| `decayViolationScore(int)` | Subtracts, floored at 0. Does not touch the timestamp. |
| `getViolationCount()` / `violationCountProperty()` | Plain read / observable read. |
| `increaseViolationCount()` | `+1`. |
| `getLevel()` / `levelProperty()` | Plain read / observable read. |
| `getLastViolationTime()` | Read by the policy. |
| `reset()` | Returns the client to a pristine state; used by Clear and Reload. |
| `updateLevelFromScore(int,int,int)` | **The only writer of `level`.** Compares the score against critical → high → warning. |
| `toString()` | Returns the name, which is what `ComboBox<Client>` displays. |

There is deliberately **no setter for `level`**. Removing one is what guarantees
the badge can never disagree with the score.

`Client` does not override `equals`, so it has *identity* equality: two clients
with the same name are still different objects. That matters, and is discussed
in [§19](#19-design-decisions-and-their-rationale).

### `Request.java` (29 lines)

An immutable-in-practice value holder: `client`, `time`, `type`.

* `Request(Client, RequestType)` — stamps `time` with `LocalDateTime.now()`.
  Used for live traffic.
* `Request(Client, RequestType, LocalDateTime)` — explicit timestamp. Used only
  when replaying the CSV, which carries its own times from the past.

Plus `getClient()`, `getTime()`, `getType()`. No setters, no `equals`, no
`hashCode`. Because it uses identity equality, `requests.remove(request)` removes
exactly the instance just added.

### `Log.java` (43 lines)

A durable record of an outcome. Built **from a `Request`**, copying its client,
type and time, plus a status.

* `public static final String STATUS_ACCEPTED = "ACCEPTED"`
* `public static final String STATUS_BLOCKED = "BLOCKED"`

These constants exist because the literals were previously repeated in five
places, where a typo would silently change behaviour. `DailyAcceptedIndex` and
the log cell both compare against them.

Accessors: `getClient()`, `getTime()`, `getType()`, `getStatus()`. `toString()`
renders `"<client> <type> <status>"`.

### `RequestType.java` (8 lines)

`enum RequestType { READ, WRITE, LOGIN, PAYMENT }`. Ordinal order is not
meaningful; the display order in the combo box is set explicitly in
`initialize()`.

### `ViolationLevel.java` (8 lines)

`enum ViolationLevel { NONE, WARNING, HIGH, CRITICAL }`. Ordered by increasing
severity, which the report's switch expressions rely on.

### `DailyAcceptedIndex.java` (93 lines)

An incremental aggregate. Internal shape:

```
dailyCounts    : IdentityHashMap<Client, EnumMap<RequestType, TreeMap<LocalDate, Integer>>>
peakDailyCache : IdentityHashMap<Client, EnumMap<RequestType, Integer>>
```

Reading the nesting right to left: *for each client (by identity), for each
request type, a date-sorted map from local date to accepted count.*

| Member | Purpose |
| --- | --- |
| `add(Log)` | Ignores anything not `ACCEPTED`; otherwise `+1` on that day and drops that client's cached peak. |
| `remove(Log)` | The exact inverse, for when a log is genuinely removed. |
| `clear()` | Empties both maps. |
| `maxDailyAccepted(Client, RequestType)` | The busiest single day, or 0. Cached per client+type until that client's tallies change. |
| `tally(...)` *(private)* | `merge(day, delta, sum)`, then invalidates the cache entry. |
| `perType(...)` *(private)* | Safe lookup returning an empty map when absent. |

Two deliberate choices:

* **`IdentityHashMap`, not `HashMap`.** `Client` has no `equals` override, so
  `HashMap` would behave identically — but `IdentityHashMap` states the intent:
  the index is keyed on object identity, exactly as the policy filters requests.
* **The peak is cached, not recomputed.** Scanning 366 days per request would be
  wasteful; invalidating on write keeps it effectively constant time.

> The index is **cumulative**. It keeps counting accepted traffic even after the
> on-screen log has been trimmed, because a trimmed request still happened and
> still counts towards that client's busiest day. Only a full reset clears it.

---

## 12. policy package, file by file

### `RatePolicy.java` (40 lines)

The interface, plus one nested type.

```java
public interface RatePolicy {
    record Decision(boolean allowed, ViolationLevel level) {
        static Decision allowed(ViolationLevel level) { ... }
        static Decision blocked(ViolationLevel level) { ... }
    }
    Decision evaluate(Client client, RequestType type, ObservableList<Request> requests);
}
```

`Decision` keeps two questions apart. `allowed` answers *"may this request
through?"* and is the **only** thing the caller should use to decide
serve-versus-block. `level` is the client's standing risk, derived solely from
its score. A request can be blocked while the client is still low risk, and a
client can sit at `CRITICAL` purely because of its score.

The signature takes the `RequestType` explicitly, because the configured limit
is per client **per type**. Counting all of a client's traffic against one
type's limit would make the per-type limits meaningless.

### `AbstractWindowPolicy.java` (129 lines)

Package-private. Holds everything the two policies share, so they cannot drift
apart.

| Member | Purpose |
| --- | --- |
| `record Window(LocalDateTime start, LocalDateTime end)` | A **half-open** range `[start, end)`. |
| `WARNING_PENALTY = 5`, `HIGH_PENALTY = 15`, `CRITICAL_PENALTY = 30` | Base penalties, multiplied by the type's severity. |
| Constructor | Takes `maxRequests`, `window`, `severityMultipliers`, `int[] violationThresholds`, `decayAmount`. **Throws `IllegalArgumentException` if the threshold array is null or shorter than 3.** |
| `abstract Window windowFor(LocalDateTime latest)` | The single method subclasses implement. |
| `evaluate(...)` | The algorithm in [§6](#6-how-rate-limiting-works). |
| `penalise(Client, int)` *(private)* | Adds the penalty, bumps the count, re-derives the level, returns a blocked `Decision`. |
| `refreshLevel(Client)` *(private)* | One-line wrapper over `updateLevelFromScore`. |
| `isCoolingDown(Client)` *(private)* | True when `now - lastViolationTime < window`. |
| `getMaxRequests()` | Public accessor for the configured limit. |
| `getWindow()` | `protected`; only the subclasses need it. |

The filter inside `evaluate` uses `==` (identity) for both the client and the
type. The type filter is what makes per-type limits work.

### `FixedWindowPolicy.java` (40 lines)

Extends `AbstractWindowPolicy`. Its entire contribution is `windowFor` — the
epoch-anchored grid shown in [§6](#6-how-rate-limiting-works).

### `SlidingWindowPolicy.java` (28 lines)

Extends `AbstractWindowPolicy`. Also only implements `windowFor`:

```java
return new Window(latest.minus(getWindow()), latest.plusNanos(1));
```

---

## 13. The `ui` package, file by file

### `MainApp.java` (24 lines)

A standard JavaFX `Application`.

```java
FXMLLoader loader = new FXMLLoader(MainApp.class.getResource("dashboard.fxml"));
Scene scene = new Scene(loader.load(), 1000, 600);
stage.setTitle("GateKeeper Rate Limiter");
stage.setScene(scene);
stage.setMaximized(false);
stage.setResizable(false);
stage.show();
```

`FXMLLoader.load()` instantiates `SimulationController` (named by `fx:controller`)
and calls its `initialize()` before returning. The window is fixed at 1000x600
and not resizable.

### `SimulationController.java` (1913 lines)

The controller for the whole application. It owns all state, wires the FXML
controls, builds the two modals entirely in Java code, and implements three
custom `ListCell` renderers. It is by far the largest file in the project; the
field reference is [§10](#10-simulationcontroller-field-reference) and the
function index is [Appendix A](#appendix-a-complete-function-index).

#### Constants

| Constant | Value | Meaning |
| --- | --- | --- |
| `HISTORY_RESOURCE` | `"/com/simulator/files/logs.csv"` | Classpath location of the bundled history. |
| `HISTORY_READ_BUFFER` | `65536` | `BufferedReader` buffer size for the CSV. |
| `THRESHOLD_HEADROOM` | `1.5` | A client may exceed its busiest day by this factor. |
| `THRESHOLD_CEILING` | `200` | Upper bound on a history-derived limit. |
| `MAX_RETAINED_LOGS` | `50000` | Cap on resident log entries. |
| `DEFAULT_POLICY` | `FIXED_WINDOW` | Used by Reset Defaults. |
| `DEFAULT_WINDOW_SECONDS` | `10` | " |
| `DEFAULT_WARNING_THRESHOLD` | `20` | " |
| `DEFAULT_HIGH_THRESHOLD` | `50` | " |
| `DEFAULT_CRITICAL_THRESHOLD` | `100` | " |
| `DEFAULT_DECAY_AMOUNT` | `1` | " |
| `DEFAULT_CUSTOM_THRESHOLD` | `0` | " — 0 means "derive from history". |
| `DEFAULT_SEVERITY_MULTIPLIERS` | immutable map | READ 1, WRITE 2, LOGIN 3, PAYMENT 2. |
| `HISTORY_TIME_FORMAT` | `yyyy-MM-dd'T'HH:mm:ss` | CSV timestamp parser. |
| `CLOCK_FORMAT` | `HH:mm:ss` | Log cell display. |

The defaults are named constants rather than literals precisely so that
"Reset Defaults" can repopulate the form **without touching live state** — the
dialog's cross is a genuine cancel.

#### Nested types

* `enum PolicyType { FIXED_WINDOW("Fixed Window"), SLIDING_WINDOW("Sliding Window") }`
  with a `toString()` returning the display name, so the combo box shows
  friendly text.
* `private record HistoryRow(String clientName, RequestType type, LocalDateTime time, String status)`
  — one parsed CSV line, before it becomes a `Log`.
* `ClientReportCell`, `ClientCardCell`, `LogCell` — the three renderers, detailed
  in [Appendix B](#appendix-b-the-three-cell-renderers-in-detail).

#### The settings dialog, widget by widget

`buildAndShowSettingsDialog()` is 348 lines of imperative JavaFX. Structure:

```
Stage (TRANSPARENT, APPLICATION_MODAL)          500 x 680
└── StackPane root
    └── VBox card  (max/pref width 500, radius 12, drop shadow)
        ├── HBox headerBar   draggable; "SETTINGS" label, spacer, ✕ button
        └── ScrollPane (fitToWidth, no h-bar)
            └── VBox content (padding 20, spacing 16)
                ├── VBox policySection      "Rate Limit Policy"  + ComboBox<PolicyType>
                ├── VBox windowSection      "Window Size (seconds)"  + Spinner 1..3600
                ├── VBox thresholdsSection  "Violation Score Thresholds"
                │     └── HBox: [WARNING (≥) + spinner] [HIGH (≥) + spinner]
                │               [CRITICAL (≥) + spinner]      each 1..1000
                ├── VBox severitySection     "Request Type Severity Multipliers"
                │     └── HBox of 4 VBoxes, one per RequestType, each with a
                │         label and a Spinner 1..10
                ├── VBox decaySection        "Violation Score Decay (per accepted request)"
                │     └── Spinner 0..50
                ├── VBox customThresholdsSection
                │       "Custom Thresholds (per request type, overrides auto)"
                │     ├── HBox of 4 VBoxes, one per RequestType, Spinner 0..10000
                │     └── Label  "0 = auto-calculated from history"
                └── HBox buttonRow (right-aligned)
                      ├── Button "Reset Defaults"
                      └── Button "Save"
```

**13 spinners in total** (1 window + 3 thresholds + 4 severity + 1 decay +
4 custom). Initial values come from the live settings, except after a reset.

Three local helper factories keep the code readable:

* `createSection(Label)` — a titled `VBox` section.
* `createSpinner(int[] {min, max, initial})` — a dark-themed editable
  `Spinner<Integer>`, 110 px wide.
* `createPolicyCombo()` — a `ComboBox<PolicyType>` seeded with the live policy.

The dialog is draggable by its header bar: a mouse-press records the scene
offset, and mouse-drag sets the stage's screen position accordingly.

* **Reset Defaults** repopulates the form only. It does **not** touch live state.
* **✕** closes the dialog, discarding everything.
* **Save** validates `WARNING ≤ HIGH ≤ CRITICAL`; on a violation it shows an
  error modal and applies nothing. Otherwise it copies every control value into
  the controller's settings, refreshes the display, and closes.

#### The abuse report, block by block

`showAbuseReportPopUp(...)` is ~150 lines. Structure:

```
Stage (UNDECORATED, APPLICATION_MODAL)          560 x 560
└── VBox root (padding 18/22, spacing 14, border, drop shadow)
    ├── HBox headerBar   draggable
    │     ├── StackPane iconBadge   an SVGPath shield, tinted #38bdf8
    │     ├── Label "ABUSE REPORT"
    │     ├── Region spacer (grows)
    │     └── Button "✕"
    ├── Region headerSep            a 1 px divider
    ├── HBox kpiRow                 three createKpiCard(...) cards
    │     ├── "MONITORED CLIENTS"  / client count        / "Active in registry"
    │     ├── "TOTAL VIOLATIONS"   / sum of counts      / "Rate limit breaches"
    │     └── "FLAGGED THREATS"    / "n (m Blocked)"    / "Policy violations"
    ├── ListView<Client> listView   styleClass "report-list", ClientReportCell
    └── Button "CLOSE"
```

The KPI values:

```java
totalClientsCount     = clients.size();
flaggedCount          = clients with violationCount > 0
blockedCount          = clients whose level == CRITICAL
totalViolationsCount  = sum of violationCount
```

---

## 14. The FXML layout, widget by widget

`resources/com/simulator/ui/dashboard.fxml`, 226 lines, Scene Builder output.
Declared as `fx:controller="com.simulator.ui.SimulationController"`.

### Root

```
StackPane #root                styleClass "root", 1000 x 600
└── BorderPane #bp
```

### Top bar — `HBox #topBar`, height 73

| Node | fx:id | Geometry | Notes |
| --- | --- | --- | --- |
| `Pane` | `AppNamePanel` | 327 wide | `styleClass AppNamePanel` |
| `Image` | — | — | `@img/logo.png` |
| `Label` | `AppName` | x59 y7 | "GATEKEEPER", System Bold 26 |
| `Label` | — | x59 y37 | "API Rate-Limit & Abuse Detection Engine" |
| `Pane` | `ReportGenaratePanel` | 673 wide | `styleClass ReportGenaratePanel` |
| `Button` | `AbuseGenarateButton` | x429 y28 | → `#onGenerateAbuseReport` |
| `Button` | `settingsButton` | x617 y28, 36x36 | "⚙", `styleClass icon-btn`, → `#onSettings` |
| `Circle` | `Blinking` | x238 y46, r8 | Fill `#10b981`, stroke black; pulsed by a `FadeTransition` |

### Left column — `VBox`, 253 wide

| Node | fx:id | Geometry | Notes |
| --- | --- | --- | --- |
| `Pane` | `AddClintPanale` | 250 x 64 | `styleClass AddClintPanale` |
| `TextField` | `ClientBox` | x17 y29, 172x26 | `styleClass text-box`, prompt "Client Name ( i.g. Payment12)" |
| `Button` | `AddButton` | x200 y29, 27x26 | "+", `styleClass addbtn`, → `#onAddClient` |
| `ListView<Client>` | `ClientList` | 250 x 463 | `styleClass ClintLog`, `ClientCardCell` |
| `Pane` | `LogClearPanal` | 253 x 63 | `styleClass LogClearPanal` |
| `Label` | — | x14 y11 | "LIVE REQUEST LOG" |
| `Button` | `clearButton` | x182 y11 | "Clear", `styleClass clear-btn`, → `#onClear` |
| `Button` | `reloadHistoryButton` | x14 y36, 225x22 | "Reload History", → `#onReloadHistory` |
| `ListView<Log>` | `LogList` | 253 x 464 | `styleClass LogList`, `LogCell` |

### Centre — `VBox`

| Node | fx:id | Geometry | Notes |
| --- | --- | --- | --- |
| `Pane` | `RequestInfoPanel` | 497 x 66 | `styleClass RequestInfoPanel` |
| `Label` | `totalRequestLabel` | x60 y36 | Accepted total, bound. |
| `Label` | `violationLabel` | x178 y36 | Violations total, bound. |
| `Label` | `activeWindowLabel` | x282 y36 | e.g. "10S" |
| `Label` | `rateLimitLabel` | x359 y36 | e.g. "47 / window" |
| `LineChart` | `trafficChart` | x20 y65, 443x158 | `styleClass traffic-chart`, `animated=false`, legend hidden |
| `CategoryAxis` | `trafficXAxis` | — | `tickLabelsVisible=false` |
| `NumberAxis` | `trafficYAxis` | — | `forceZeroInRange=true`, side LEFT |
| `Pane` | `SimPanel` | 358 x 186 at (22,26) | `styleClass SimCard` |
| `Label` | `policyDisplayLabel` | x213 y25 | "Policy: Fixed Window" |
| `ComboBox<RequestType>` | `typeChoiceBox` | x226 y67, 120x26 | |
| `ComboBox<Client>` | `clientChoiceBox` | x34 y91, 199x26 | |
| `Button` | `Sendrequestbutton` | x34 y143 | "Single Request", `styleClass single-req-btn`, → `#onSingleRequest` |
| `Button` | `burstButton` | x151 y143 | "Burst (20x)", `styleClass burst-btn`, → `#onBurst` |
| `Button` | `autoSimButton` | x226 y116 | "Auto Simulate", `styleClass auto-sim-btn`, → `#onAutoSimulate` |

### Quirks worth knowing

* The client registry's style class is **`ClintLog`** — a typo that is now
  load-bearing, because it is what `style.css` targets. A dead `.ClintList`
  rule alongside it was removed.
* `stylesheets="@style.css"` is repeated on ten individual nodes rather than once
  at the root. Harmless but redundant; JavaFX de-duplicates the URL.
* All eight `onAction` handlers exist in the controller. A missing one makes
  `FXMLLoader` throw at startup, so the wiring fails loudly.

---

## 15. The stylesheet, rule by rule

`resources/com/simulator/ui/style.css` — 346 lines, 58 rules. Applied to the main
scene via the FXML, and to all three modals from code.

### Colour vocabulary

| Role | Values |
| --- | --- |
| Page background | `#020617` |
| Panel | `#0f172a`, `#1e293b` |
| Borders | `#334155`, `#475569` |
| Body text | `#e5e7eb`, `#94a3b8` |
| Muted text | `#64748b` |
| Accent blue | `#2563eb`, `#3b82f6`, `#38bdf8` |
| Green | `#22c55e`, `#4ade80`, `#10b981`, `#052e16` |
| Amber | `#f59e0b`, `#fbbf24`, `#d97706` |
| Orange | `#f97316`, `#fb923c` |
| Red | `#ef4444`, `#f87171`, `#450a0a` |

### All 58 selectors, by purpose

**Page and panel chrome (1–15)**

| Selector | Purpose |
| --- | --- |
| `.root` | Page background. |
| `.AppNamePanel`, `.ReportGenaratePanel`, `.LogClearPanal` | Dark panel fills. |
| `.AddClintPanale`, `.RequestInfoPanel` | Panel fill + `#334155` border. |
| `.SimCard` | Rounded (24 px) request-control card. |
| `.ClintLog` | Client registry container: fill + border. |
| `.LogList` | Log container: transparent fill + border. |

**List cells (5–10, 53–57)**

| Selector | Purpose |
| --- | --- |
| `.LogList .list-cell` | Transparent, 8 px radius, 14 px text, `8 12` padding. |
| `.LogList .list-cell:empty` | Filler cells collapse to nothing. |
| `.ClintLog .list-cell` | Transparent, `3 6` padding. |
| `.ClintLog .list-cell:empty` | Collapse. |
| `.ClintLog .list-cell:filled:hover` | Suppress the default hover fill. |
| `.ClintLog .list-cell:selected` | Suppress the default selected fill. |
| `.report-list`, `.report-list .list-cell`, `:empty` | The abuse report's list. |

**Traffic chart (16–24, 49–52)**

| Selector | Purpose |
| --- | --- |
| `.traffic-chart` | Translucent navy card, 16 px radius. |
| `.chart-plot-background`, `.chart-content` | Transparent. |
| `.chart-horizontal-grid-lines, .chart-vertical-grid-lines` | Faint dashed grid (rule 19) and, again at rule 49, forced transparent — **a duplicate that cancels itself out**. |
| `.axis`, `.axis-line` | Muted tick labels, invisible axis line. |
| `.default-color0.chart-series-line` | 2.8 px `#3b82f6` line with a `dropshadow` glow. |
| `.chart-series-line` | Rounded caps, transparent stroke. |
| `.chart-line-symbol` | Hides the data-point dots. |
| `.chart-legend` | Transparent. |
| `.chart-alternative-row-fill`, `-column-fill` | Transparent (the FXML also sets `alternativeRowFillVisible=false`). |

**Buttons (25–33)**

| Selector | Colour |
| --- | --- |
| `.addbtn` | `#94a3b8` |
| `.text-box` | `#334155` |
| `.single-req-btn` | `#10b981` green |
| `.burst-btn` | `#d97706` amber |
| `.auto-sim-btn` | `#3b82f6` blue |
| `.genarate-btn` | `#2563eb` blue |
| `.icon-btn` / `:hover` | `#1e293b` / `#334155` with a `#3b82f6` border |
| `.clear-btn` | transparent |

Note `.auto-sim-btn` is overridden **inline** at runtime when the simulation
starts (red) and stops (blue), because inline styles beat CSS.

**Combo boxes (34–41)**

`.combo-box` fill, `.list-cell` text, `.arrow-button` transparent,
`.arrow` white, and the popup list (`.combo-box-popup .list-view`,
`.list-cell`, `:hover`, `:selected`).

**Scroll bars (42–48, 56–57)**

A shared block applied to `.LogList`, `.ClintLog` and `.report-list`:
7 px wide, transparent track, `#475569` thumb (lightening to `#64748b` on
hover), and both increment and decrement arrow buttons removed via
`-fx-shape: ""`. Horizontal bars are collapsed entirely
(`-fx-pref-height: 0; -fx-max-height: 0; -fx-opacity: 0`).

**Report (53–58)**

`.report-list` container and cells, plus `.kpi-card`
(`#1e293b` fill, `#334155` border, rounded).

### A known inconsistency in the report's colours

The threat meter's *text* colour and its *bar* colour use different band
boundaries:

* Text (`scoreColor`): `<= 0.2` green, `<= 0.5` amber, `<= 0.8` orange, else red.
* Bar (`createFill`): `<= 0.35` green gradient, `<= 0.7` amber gradient, else red gradient.

So between score fractions 0.2 and 0.35 the label is amber while the bar is
still green. Cosmetic, but it is a genuine inconsistency rather than intent.

---

## 16. The bundled dataset

`src/com/simulator/files/logs.csv` — 24 MB, 516,544 rows.

### Format

```
client,type,time,status
Client-017,WRITE,2025-09-20T21:00:43,ACCEPTED
```

| Column | Type | Notes |
| --- | --- | --- |
| `client` | `String` | `Client-001` … `Client-020`. Resolved by name. |
| `type` | `RequestType` | Must match the enum exactly, or the row is skipped. |
| `time` | `LocalDateTime` | ISO-8601, no zone, parsed with `yyyy-MM-dd'T'HH:mm:ss`. |
| `status` | `String` | `ACCEPTED` or `BLOCKED`. |

Rows that are malformed — fewer than four fields, an empty field, an unknown
type, or an unparseable timestamp — are **skipped**, so one bad line cannot
abort the load and leave the application half-populated.

### Profile

| Property | Value |
| --- | --- |
| Rows | 516,544 |
| Distinct clients | 20 (`Client-001` … `Client-020`) |
| Distinct days | 366, from 2025-09-20 to 2026-09-20 |
| Statuses | **516,544 `ACCEPTED` — zero `BLOCKED`** |
| `READ` | 304,450 (58.9%) |
| `WRITE` | 133,415 (25.8%) |
| `LOGIN` | 52,795 (10.2%) |
| `PAYMENT` | 25,884 (5.0%) |
| Busiest client | `Client-017`, 52,145 rows |
| Quietest client | `Client-011`, 9,410 rows |

Peak single-day counts, by type — these are what the auto-calculated limits are
built from:

| Type | Peak day | `ceil(×1.5)` | Clamped limit |
| --- | --- | --- | --- |
| `READ` | 240 | 360 | **200** (hits the ceiling) |
| `WRITE` | 87 | 131 | 131 |
| `LOGIN` | 35 | 53 | 53 |
| `PAYMENT` | 20 | 30 | 30 |

### Two consequences worth knowing

**There is no blocked traffic in the dataset.** Every historical row was
accepted. So the "Abuse Rate" percentage in the report is always 0% until you
generate blocked traffic yourself, and the `BLOCKED` branch of the log cell is
only ever exercised by live simulation.

**The auto-calculated limits are considerably looser than the built-in
defaults**, because the defaults were written as small illustrative numbers
rather than as real thresholds. With history-derived limits, `READ` goes from a
default of 50 to up to 200. See [§7](#7-worked-example-eight-requests-end-to-end)
for how this plays out in practice.

---

## 17. Runtime flows, step by step

### Startup

1. `Main.main` → `MainApp.main` → `Application.launch` → `start(stage)`.
2. `FXMLLoader` parses `dashboard.fxml`, constructs `SimulationController`, calls
   `initialize()`.
3. `initialize()` wires everything, then calls `loadLogsFromCsv()`, which returns
   after starting a daemon thread.
4. `stage.show()` — the window appears, responsive, with an empty registry and a
   "Loading request history..." placeholder.
5. The background thread reads and parses 516,544 rows (~0.85 s).
6. `mergeHistoryRows` runs on the FX thread: resolves 20 clients, builds 516,544
   `Log` objects, sorts newest-first, populates the index from the **entire**
   dataset, and publishes the newest 50,000 to the list (~0.18 s, one change
   event). The placeholder is removed.

### A single request

See [§6](#6-how-rate-limiting-works) for the decision logic and
[§7](#7-worked-example-eight-requests-end-to-end) for a full numeric trace. The
surrounding bookkeeping in `onSingleRequest()`:

1. Read the selected client; return if none.
2. Read the selected type; return if none.
3. `evictRequestsOutsideWindow(now)` — drop requests older than `now - window`.
4. Build a `Request` stamped `now`, add it to `requests`.
5. Build a policy and evaluate.
6. If allowed: bump the client's total, bump the per-second counter, record an
   `ACCEPTED` log.
7. If blocked: record a `BLOCKED` log and **remove the request again**, so a
   refused attempt leaves no trace in the window.

### A burst

`onBurst()` calls `onSingleRequest()` twenty times in a row on the FX thread.
All twenty land in the same window, so the limit is exceeded partway through and
the remainder are blocked — the intended demonstration.

### Auto simulation

An 800 ms `Timeline`. Each tick round-robins a client by index, picks a random
type, **moves the on-screen selection to match**, and issues one request. It runs
until the button is pressed again, the registry becomes empty, or the settings
dialog opens (which pauses it and restores it afterwards).

There is deliberately no `CRITICAL` short-circuit in the tick: a locked-out
client must keep asking so the policy can lift the lockout once the cooldown
expires.

### Clear

`onClear()` returns if the log is already empty, else `resetSimulationState()`:
log, index, window state and every client's telemetry are wiped together, so no
client is left holding a score that nothing backs. The client **registry** is
preserved.

### Reload History

`onReloadHistory()` ignores re-entry during a load, resets, then re-reads the
CSV. Useful after clearing by accident.

### Settings dialog

Opened via `onSettings()`. The auto simulation is stopped first, because a modal
dialog runs a nested event loop that would otherwise keep generating traffic
behind it and shift the limits being edited. It is restarted in a `finally`.

See [§13](#13-the-ui-package-file-by-file) for the widget inventory.

### Abuse report

`onGenerateAbuseReport()` builds an undecorated application-modal window. Because
its list is bound to the same `clients` observable list, it reflects live state
as it changes.

---

## 18. Performance engineering

The bundled history is large enough that four things had to be designed
deliberately. Figures compare the original implementation with the current one,
measured on this machine against the real 516,544-row dataset.

### 1. Asynchronous history loading

Parsing half a million rows on the FX Application Thread froze the application
before its window was even shown, and appending row by row fired 516,544 list
change events.

Now the file read, splitting and timestamp parsing happen on a daemon thread;
only object construction and a single `setAll` touch the FX thread.

| | Before | After |
| --- | --- | --- |
| FX thread blocked inside `initialize()` | 2,816 ms | 1,282 ms |
| Worst stall after the window is up | — | 194 ms |

The residual 1,282 ms is JVM and JavaFX toolkit startup (measured at ~330 ms
bare) plus FXML/CSS construction; it is no longer the CSV.

### 2. Incremental history index

Deriving a limit needs the busiest day a client ever had. The original code
regrouped all 516,544 log entries on **every request and every selection
change**, with nested `Collectors.groupingBy` allocating maps of maps.

| | Before | After |
| --- | --- | --- |
| Threshold derivation, per request | 9.73 ms | 0.0028 ms |
| One "Burst (20x)" click | 194.7 ms | 0.06 ms |
| **Speed-up** | | **~3,900×** |

A `ListChangeListener` on `logs` keeps the index synchronised, so it is correct
for any mutation path rather than depending on manual bookkeeping scattered
around the controller.

### 3. Rate-limiter window eviction

The `requests` list was only ever pruned when a request was blocked, so accepted
requests accumulated for the whole session and every evaluation rescanned the
entire history — quadratic.

Both policies look back at most one window from the newest request, and the
request under evaluation is always the newest, so anything older than
`now - window` can never be counted again. `evictRequestsOutsideWindow` drops it.

| 20,000 accepted requests, 10 s window | Without eviction | With eviction |
| --- | --- | --- |
| Final list size | 20,000 | 34 |
| Total wall time | 7.22 s | 0.14 s |

This is provably lossless: **160 scenarios** (both policies × 8 window lengths ×
5 limits × bursty and even traffic, 4,000 requests each) produced identical
verdict sequences with and without it.

### 4. Log retention cap

Holding all 516,544 `Log` objects cost roughly 395 MB for a list the dashboard
only scrolls near the top of. `MAX_RETAINED_LOGS = 50_000` caps it.

| | Before | After |
| --- | --- |
| Log objects retained | 516,544 | 50,000 |
| Heap in use | ~395 MB | 38.2 MB |

**The subtlety:** the log list and the index are coupled through the change
listener, so trimming an `ACCEPTED` entry would decrement the index and silently
lower that client's derived limit. The index is therefore **cumulative** and
trimming runs with listener sync suppressed. Verified: after trimming, the index
still matches a from-scratch rebuild over all 516,544 rows for all 80
client × type pairs, and every enforced limit is unchanged.

### Cumulative effect

A 1,200-request burst through the real handlers, with the limit raised so that
every request is genuinely evaluated rather than short-circuiting on a lockout:

| | Before | After |
| --- | --- | --- |
| Wall time | 18,832 ms | 623 ms |
| Per request | 15.693 ms | 0.519 ms |

---

## 19. Design decisions and their rationale

**The `Decision` record exists because two questions were conflated.** The policy
originally returned a `ViolationLevel`, and the controller assigned it directly
to the client's badge. That made the badge report a per-request verdict while
the score printed next to it said something else. Separating *"may this
through?"* from *"how risky is this client?"* removed the possibility of
disagreement, and deleting `Client.setLevel` enforces it.

**`updateLevelFromScore` is the only writer of `level`.** Anything else would let
the badge drift from the score.

**`evaluate` takes the `RequestType`.** The limit is configured per client per
type, so the count must be per client per type. Counting all of a client's
traffic against one type's limit made the per-type feature inert.

**The fixed window is anchored to the epoch.** Aligning it to
`getSecond() % windowSeconds` only made sense for windows dividing 60, so a
7-, 45-, 90-, 600- or 3600-second window produced a grid that reset at every
minute boundary.

**One `resolveThreshold`, two callers.** The label and the policy used to compute
limits by two different routes, so the number on screen was frequently not the
number being enforced.

**Custom thresholds default to 0 (auto).** They were seeded to 50/20/5/10, which
meant the "custom" branch always won and the history-derived feature — a
headline feature of the settings dialog — was unreachable.

**The simulation is paused while the settings dialog is open.** A modal runs a
nested event loop, so the `Timeline` kept firing and shifting the limits under
the user's hands.

**Reset Defaults does not mutate live state, and ✕ is a real cancel.** Reset used
to write straight into the controller's fields, so *Reset → ✕* silently applied
a reset nobody saved.

**Duplicate client names are refused, case-insensitively.** The registry is keyed
by name and the history loader resolves names the same way, so a second entry
with an existing name would be invisible to its own history. Rather than a new
dialog, the existing client is **selected** — that selection *is* the feedback.

**`Clear` resets everything derived together.** Dropping only the log left
clients holding scores nothing backed, and because limits are derived from the
log it also changed every client's effective limit silently.

**The index is cumulative; the log list is not.** Trimming the display must not
change enforcement. This is the one genuinely subtle coupling in the codebase,
which is why it is commented in three places.

**`Client` uses identity equality, and so does everything keyed on it.**
`DailyAcceptedIndex` uses `IdentityHashMap` and the policy filters with `==`
precisely because `Client` has no `equals` override. Name-based lookups
(`findClientByName`, the CSV loader) are a separate, deliberate concern. The
consequence: two `Client` objects that happen to share a name are *not* the same
client, which is why the UI refuses to create one.

**Both policies share `AbstractWindowPolicy`.** They were 95% duplicated — 90 of
99 identical lines — which is exactly how the two drifted apart in the first
place. They now differ only in `windowFor`.

**`@FXML` methods are `private`.** JavaFX reaches them reflectively. It works,
and it keeps the controller's surface small.

**Dead code was deleted rather than kept "just in case":** the per-client UUID,
the unreachable counter wrap, and four CSS selectors for UI that was never built.

---

## 20. Recipes: how to change things

### Add a new `RequestType`

1. Add the constant to `RequestType.java`. **The name must be `UPPER_CASE`** — it
   is matched against the CSV with `valueOf`.
2. Add a severity default in **two** places: the `static` block in
   `SimulationController` (`DEFAULT_SEVERITY_MULTIPLIERS`) and the switch in
   `initializeDefaultSettings()` if you want a non-1 value.
3. Add the display order in `initialize()`'s `types.addAll(...)`.
4. Add a floor in `getDefaultThreshold()`'s switch — it is exhaustive, so the
   compiler will tell you.
5. If any switch over `RequestType` is exhaustive, it will now fail to compile;
   that is the intended safety net.

The settings dialog and the report pick up the new type automatically, because
both iterate `RequestType.values()`.

### Change the default window or thresholds

Edit `DEFAULT_WINDOW_SECONDS`, `DEFAULT_WARNING_THRESHOLD`,
`DEFAULT_HIGH_THRESHOLD`, `DEFAULT_CRITICAL_THRESHOLD` or `DEFAULT_DECAY_AMOUNT`
in `SimulationController`. The named constants exist so that Reset Defaults stays
in sync automatically.

### Change the retention cap

Edit `MAX_RETAINED_LOGS`. Nothing else needs to change: both the bulk load
(`recentOnly`) and live traffic (`recordLiveLog`) read it.

### Change how limits are derived from history

Edit `autoThresholdFor` — `THRESHOLD_HEADROOM` (currently 1.5) and
`THRESHOLD_CEILING` (currently 200) are the two knobs, and
`getDefaultThreshold` is the floor.

### Add a column to the CSV

1. Extend `HistoryRow` with the new field.
2. Parse it in `parseHistoryRow` (remember to `return null` on anything
   unparseable).
3. Propagate it in `mergeHistoryRows` when constructing the `Log`.
4. Extend `Log` with a matching field and accessor.
5. Update the `LogCell` renderer if it should be displayed.

### Add a genuinely new policy (e.g. token bucket)

1. Add a new `class XPolicy extends AbstractWindowPolicy`.
2. Implement `windowFor` — or, if the policy has no window at all, extend
   `RatePolicy` directly and implement `evaluate` yourself, returning
   `Decision.blocked(...)` / `Decision.allowed(...)`.
3. Add a constant to the `PolicyType` enum with a display name.
4. Add a branch in `getClientPolicy`.

The abstract base means a new window-based policy is about ten lines.

### Split the settings dialog into FXML

`buildAndShowSettingsDialog()` is 348 lines of imperative JavaFX and is the
single largest method in the project. To extract it: create
`settings.fxml` with an `fx:controller` of your choice, move the static
structure there, and keep only the dynamic parts (spinner ranges, initial
values, and the three button handlers) in Java.

### Add a JUnit test for the policy

`RatePolicy` is a pure function of its inputs and needs no JavaFX toolkit beyond
`javafx.base` for `ObservableList`, so it tests directly:

```java
var policy = new FixedWindowPolicy(5, Duration.ofSeconds(10), severity, new int[]{20,50,100}, 1);
var requests = FXCollections.observableArrayList();
requests.add(new Request(client, RequestType.LOGIN, LocalDateTime.now()));
var decision = policy.evaluate(client, RequestType.LOGIN, requests);
assertTrue(decision.allowed());
```

Useful cases, all of which caught real bugs: identical bursts shifted by one
window length must decide identically; two request types must not count against
each other; level must equal `updateLevelFromScore` of the score; a `CRITICAL`
client must recover after a cooldown.

### Add a build file

A minimal `pom.xml` needs `javafx-controls` and `javafx-fxml` (both pull
`javafx-graphics`), `maven.compiler.release` 21, and
`javafx-maven-plugin` for a runnable jar. That would remove the hard-coded
`/usr/share/openjfx/lib` dependency entirely.

---

## 21. Troubleshooting

**The log list says "Request history unavailable: Missing classpath resource
`/com/simulator/files/logs.csv`"**
`logs.csv` is not on the classpath. It lives under `src/`, so it only gets there
if your build copies non-`.java` files from the source root. With a manual build,
`cp -r resources/com out/classes/` is not enough — you also need
`cp src/com/simulator/files out/classes/com/simulator/`.

**The window is blank or the app throws on startup**
The FXML failed to load, almost always because a referenced `onAction` handler
does not exist, or because the controller class moved. The stack trace names the
offending attribute.

**The console shows `CSS Error parsing file:...`**
A syntax error in `style.css` — usually a selector list that lost a comma, or a
multi-line function value whose commas were stripped. The line and column are
given. Check brace balance and that every selector in a comma-separated group
ends with a comma except the last.

**Everything is blocked immediately**
Two likely causes. Either the client is `CRITICAL` and inside its cooldown (it
will be refused until a full window passes) — or you have set a custom threshold
of 1 or 2 for the type. Check the "Reload History" button to get a clean state,
and look at the Violations KPI.

**A client is stuck on `BLOCKED` and will not recover**
The cooldown is one full window long. With the default 10 s window, wait ten
seconds and try again. If the window is set to 3600 s, that is an hour by
design. Then the client needs enough *accepted* requests to decay its score below
the critical threshold — with the default decay of 1 per request, that is 6
requests from 105.

**The rate limit label does not change when I switch type**
It is per client **and** per type, so switching type legitimately changes it. It
also depends on which client is selected.

**Auto Simulate appears to do nothing**
Every client may be `CRITICAL` and locked out, in which case every generated
request is blocked. Check the client registry badges and the Violations KPI.
Note also that the simulation moves the visible selection every 800 ms.

**Two clients with the same name exist**
This was possible before the duplicate check was added. If you see it, one was
likely created before the fix; there is no merge, so delete by clearing and
reloading, which resets telemetry but keeps the registry.

**The settings dialog is taller than the main window**
It is 500×680 while the main window is a fixed 1000×600. On a display shorter
than ~700 px the dialog will overflow.

**"Loading FXML document with JavaFX API of version 25 by JavaFX runtime of
version 11"**
Expected and harmless. The FXML was exported from a newer Scene Builder. Change
the `xmlns` in the FXML to match your runtime to silence it.

**The FXML version warning turns into a real error after editing**
If you change `xmlns` to a version *newer* than your runtime, the loader will
actually fail. Match it exactly to the runtime.

---

## 22. Known limitations

**No build file.** `GateKeeper.iml` hard-codes `/usr/share/openjfx/lib`. The
project cannot be built by anyone else, on CI, or on a machine where JavaFX lives
elsewhere. A `pom.xml` with JavaFX 21 and JUnit 5 is the highest-value missing
piece.

**No tests and no CI.** Every behaviour described here was verified with
throwaway harnesses. There is no regression suite.

**The domain layer is not UI-free.** `RatePolicy.evaluate` takes an
`ObservableList`, and `Client` wraps JavaFX properties. That makes the core
untestable without JavaFX on the classpath and unusable from a server-side
context. Both were deliberate trade-offs for a simulator, but they are the main
thing to revisit if this code were ever to head anywhere real.

**No graceful shutdown.** `MainApp` does not override `stop()`, so neither
`Timeline` is explicitly stopped. The JVM exiting is what ends them.

**The main window is not resizable** and is shorter than the settings dialog.

**Drop shadows are clipped.** Both modals size their `Scene` exactly to the card
(`500×680` for a 500-wide card, `560×560` for a padded root), leaving no room
for the 25 px `dropshadow` blur to render, so the shadow is cut off at the edges.

**Auto-simulation hijacks the selection.** Every 800 ms tick moves the client and
type combo boxes, so clicking them while it runs is futile. A click landing
between ticks also routes to whichever client the tick last selected.

**Silent no-ops remain in three places.** `onSingleRequest` returns without
feedback when no client or type is selected, and `startAutoSimulation` returns
silently when the registry is empty. Nothing on screen explains why.

**The history-derived limit is self-referential.** Because the peak includes
today, a very heavy session can raise a client's own limit. Latent with the
bundled data.

**Editable spinners are not validated.** They are `setEditable(true)` with no
input filter, so committing a non-numeric or out-of-range value behaves
unpredictably.

**`logs.csv` is 24 MB and staged for commit.** It is required for the auto-calc
feature to be meaningful, but it dominates the repository.

**`SimulationController` is 1,913 lines** and mixes state, policy construction,
file I/O, two modals built in imperative Java, and three cell renderers. It is
the obvious next thing to split; the README's original plan described a
`services` layer that was never built.

---

## 23. Glossary

### Domain

| Term | Meaning |
| --- | --- |
| **Client** | A named API caller with accumulated telemetry. |
| **Request** | One attempt by a client, of one type, at one moment. |
| **Request type** | `READ`, `WRITE`, `LOGIN` or `PAYMENT`; each has a severity weight. |
| **Log** | A durable `ACCEPTED`/`BLOCKED` record, built from a request. |
| **Window** | The time span a rate limit applies over. |
| **Limit** | Requests of one type a client may make per window. |
| **Fixed window** | A window on a global grid; bursts across a boundary are split. |
| **Sliding window** | A window that always ends at the newest request. |
| **Violation score** | Accumulated penalty; decays on accepted requests. |
| **Violation level** | `NONE`/`WARNING`/`HIGH`/`CRITICAL`, derived from the score alone. |
| **Cooldown** | How long a `CRITICAL` client stays locked out. |
| **Decay** | How much score an accepted request removes. |
| **Severity** | Multiplier applied to a penalty, per request type. |
| **`Decision`** | A policy's answer: `allowed` (per request) plus `level` (client risk). |
| **Half-open range** | `[start, end)` — includes `start`, excludes `end`. Avoids double-counting at boundaries. |

### JavaFX

| Term | Meaning |
| --- | --- |
| **Node** | Anything in the scene graph. |
| **Region** | A styleable layout container / rectangle. |
| **Stage** | A top-level OS window. |
| **Scene** | The graph attached to a stage. |
| **Scene graph** | The tree of nodes. |
| **FXML** | JavaFX's XML layout format, loaded by `FXMLLoader`. |
| **`fx:id`** | The attribute that names a node for controller injection. |
| **`fx:controller`** | The attribute naming the controller class. |
| **FX Application Thread** | JavaFX's single UI thread; UI work must happen on it. |
| **`Platform.runLater`** | Schedules work onto the FX thread from another thread. |
| **ObservableList** | A `List` that fires change events. |
| **Property** | An observable value wrapper (`IntegerProperty` etc.). |
| **Binding** | An automatically recomputing expression over observables. |
| **Extractor** | The lambda declaring which element properties an `ObservableList` watches. |
| **ListView / ListCell** | A list widget, and the renderer for one row. |
| **Virtual flow** | `ListView`'s row-node recycling; why huge lists are cheap to display. |
| **Cell factory** | The supplier of `ListCell`s for a `ListView`. |
| **Placeholder** | The node a `ListView` shows when empty. |
| **`Spinner`** | A numeric field with increment/decrement arrows. |
| **`ComboBox`** | A dropdown. |
| **`Timeline` / `KeyFrame`** | A repeating timer, and one scheduled moment. |
| **`FadeTransition`** | An animation between two opacity values. |
| **Modality** | Whether a stage blocks the rest of the application. |
| **Nested event loop** | What `showAndWait()` starts; timers keep running inside it. |
| **`StageStyle`** | `DECORATED` (default), `UNDECORATED`, `TRANSPARENT`. |
| **Style class** | A named CSS hook applied via `styleClass`. |

### Java

| Term | Meaning |
| --- | --- |
| **Record** | An immutable data carrier (Java 16+). Used for `HistoryRow`, `Window`, `Decision`. |
| **Sealed / exhaustive switch** | A `switch` over an enum with no `default`; the compiler enforces completeness. |
| **Identity equality** | `==` or `IdentityHashMap`: same object, not merely equal. |
| **Method reference** | `acceptedIndex::add` as a `Consumer`. |

---

## Appendix A: complete function index

Every method in the project, by file.

**`Main.main(String[])`** — delegates to `MainApp.main`.

**`MainApp.start(Stage)`** — loads the FXML and shows the window.
**`MainApp.main(String[])`** — calls `Application.launch()`.

**`Client`** — constructor, `getName`, `getTotalRequest`,
`totalRequestProperty`, `increaseTotalRequest`, `getViolationScore`,
`violationScoreProperty`, `addViolationScore`, `decayViolationScore`,
`getViolationCount`, `violationCountProperty`, `increaseViolationCount`,
`getLevel`, `levelProperty`, `getLastViolationTime`, `reset`,
`updateLevelFromScore`, `toString`. See [§11](#11-the-model-package-file-by-file).

**`DailyAcceptedIndex`** — `add`, `remove`, `clear`, `maxDailyAccepted`, `tally`,
`perType`.

**`Log`** — constructor `Log(Request, String)`, `getClient`, `getTime`,
`getType`, `getStatus`, `toString`, plus `STATUS_ACCEPTED` / `STATUS_BLOCKED`.

**`Request`** — `Request(Client, RequestType)`,
`Request(Client, RequestType, LocalDateTime)`, `getClient`, `getTime`, `getType`.

**`RatePolicy`** — `evaluate`, and `Decision` with `allowed` / `blocked`.

**`AbstractWindowPolicy`** — constructor, `evaluate`, abstract `windowFor`,
`penalise`, `refreshLevel`, `isCoolingDown`, `getMaxRequests`, `getWindow`.

**`FixedWindowPolicy`** / **`SlidingWindowPolicy`** — constructor and `windowFor`.

**`SimulationController`** (declaration order):

| Line | Member |
| --- | --- |
| 64 | `enum PolicyType` |
| 119 | `record HistoryRow` |
| 200 | `initialize()` |
| 315 | `loadLogsFromCsv()` |
| 327 | `readHistoryAsync()` |
| 338 | `readHistoryRows()` |
| 365 | `parseHistoryRow(String)` |
| 398 | `mergeHistoryRows(List<HistoryRow>)` |
| 438 | `onHistoryLoadFailed(Exception)` |
| 444 | `recentOnly(List<Log>)` *(static)* |
| 457 | `recordLiveLog(Log)` |
| 470 | `setHistoryPlaceholder(String)` |
| 478 | `initializeDefaultSettings()` |
| 485 | `updatePolicyDisplay()` |
| 492 | `updateRateLimitDisplay(Client, RequestType)` |
| 509 | `resolveThreshold(Client, RequestType)` |
| 524 | `autoThresholdFor(Client, RequestType)` |
| 534 | `onSettings()` |
| 543 | `showSettingsDialog()` |
| 557 | `showValidationError(String)` |
| 596 | `buildAndShowSettingsDialog()` |
| 944 | `createTopPlaceholder(String)` |
| 961 | `onAddClient()` |
| 983 | `findClientByName(String)` |
| 992 | `getDefaultThreshold(RequestType)` |
| 1001 | `getClientPolicy(Client, RequestType)` |
| 1025 | `evictRequestsOutsideWindow(LocalDateTime)` |
| 1031 | `onSingleRequest()` |
| 1084 | `onBurst()` |
| 1092 | `onAutoSimulate()` |
| 1100 | `startAutoSimulation()` |
| 1120 | `stopAutoSimulation()` |
| 1132 | `runAutoSimulationStep()` |
| 1155 | `setupTrafficChart()` |
| 1165 | `startTrafficTimer()` |
| 1204 | `onClear()` |
| 1215 | `resetSimulationState()` |
| 1238 | `onReloadHistory()` |
| 1247 | `onGenerateAbuseReport()` |
| 1251 | `showAbuseReportPopUp(ObservableList<Client>)` |
| 1405 | `createKpiCard(String, String, String, String)` *(static)* |
| 1426 | `createListView(ObservableList<Client>)` *(static)* |
| 1448 | `class ClientReportCell` |
| 1645 | `createFill(double)` *(static)* |
| 1667 | `class ClientCardCell` |
| 1778 | `class LogCell` |

Line numbers refer to `SimulationController.java` as of this document and will
drift as the file changes; the method names will not.

---

## Appendix B: the three cell renderers in detail

A `ListCell` subclass overrides `updateItem(T item, boolean empty)`. The `empty`
flag marks filler cells; when true the graphic and text must be cleared or the
row will show stale content. `ListView` recycles cells, so `updateItem` is
called far more often than there are items.

### `ClientCardCell` — the registry row

`updateItem`:

1. Applies a transparent background and `3 6` padding to the cell itself.
2. If empty, clears the graphic and returns.
3. Builds an `HBox` card with `10 12` padding and full available width.
4. Calls `updateCardStyle(card, client)` for the current background and border.
5. Adds the client name in bold 13 px, a growing spacer, and a status badge in
   bold 9 px with `3 8` padding.

`updateSelected(boolean)` is overridden so that selection styling is reapplied
when the selection state changes, calling `updateCardStyle` with the current
item.

`updateCardStyle(HBox, Client)` computes:

```
background  = selected ? "#243248"
           : blocked ? "#1c1424"      (CRITICAL)
           : high    ? "#1a1a24"
           : warning ? "#1a241a"
           :          "#1e293b"
border      = selected ? "#3b82f6"
           : blocked ? "#ef4444"
           : high    ? "#f59e0b"
           : warning ? "#f97316"
           :          "#334155"
borderWidth = (selected || blocked) ? 1.5 : 1.0
```

Badge text and colours:

| Level | Text | Background | Border | Text fill |
| --- | --- | --- | --- | --- |
| `CRITICAL` | `BLOCKED` | `#450a0a` | `#ef4444` | `#f87171` |
| `HIGH` | `SUSPICIOUS` | `#451a03` | `#f59e0b` | `#fbbf24` |
| `WARNING` | `WARNING` | `#3a2400` | `#f97316` | `#fb923c` |
| `NONE` | `ACTIVE` | `#052e16` | `#22c55e` | `#4ade80` |

### `LogCell` — the request log row

`updateItem`:

1. Applies a transparent background and `5` padding to the cell.
2. If empty, clears and returns.
3. Builds a `VBox` card with `9 12` padding, full width, background `#1e293b`,
   8 px radius.
4. **Top row** — an `HBox` containing:
   * the timestamp, `log.getTime().format(CLOCK_FORMAT)`, monospaced 10 px,
     fill `#64748b`;
   * the client name, bold 13 px, white, set to grow so it pushes the rest right;
   * the status pill, bold 9 px, `4 7` padding, coloured by `setStatusStyle`.
5. **Second row** — a label reading `"Request: " + log.getType()`, 11 px,
   `#94a3b8`.

`setStatusStyle(Label, String)` compares case-insensitively against
`Log.STATUS_ACCEPTED` (`#22c55e` on `#052e16`) and `Log.STATUS_BLOCKED`
(`#ef4444` on `#450a0a`). A status matching neither is left unstyled.

### `ClientReportCell` — the abuse report row

The richest of the three. It receives the parent `ListView` in its constructor so
it can bind its card width to the list width.

`updateItem`:

1. Applies a transparent background and `4 0 6 0` padding to the cell.
2. If empty, clears and returns.
3. Builds a `VBox` card, binding both `maxWidthProperty` and `prefWidthProperty`
   to `parentListView.widthProperty().subtract(24)`.
4. Computes `isBlocked` / `isHigh` / `isWarning` from the client's level, then
   derives card background and border:
   `#1c1424`/`rgba(239,68,68,0.45)`, `#1a1a24`/`rgba(245,158,11,0.4)`,
   `#1a241a`/`rgba(249,115,22,0.4)`, or `#111a2e`/`#1e293b`.
5. Installs mouse-enter and mouse-exit handlers that lighten the background to
   `#18233c` and brighten the border to the level's accent colour.
6. **Row 1** — name in bold 14 px, spacer, and a status badge in bold 10 px with
   `3 10` padding. Note the badge text differs from the registry's: the
   un-flagged state reads `NORMAL` here versus `ACTIVE` there.
7. **Row 2** — three telemetry labels in 11 px:
   * `Total Requests: <n>`
   * `Violations: <n> (Score: <s>)`, coloured red if blocked, amber if there are
     violations, grey if none;
   * `Abuse Rate: <x.x>%`, computed as `violationCount * 100.0 / totalRequest`
     (0% when `totalRequest` is 0 to avoid dividing by zero), coloured red above
     50%, amber above 0%, green at 0%.
8. **Row 3** — the threat meter:
   * `score = min(violationScore / 100.0, 1.0)`, `scorePercent = round(score*100)`;
   * a header row: "Threat Level", spacer, and `"<n>% • <classification>"`
     coloured by `scoreColor`;
   * `riskClassification` is a switch on the level: `NONE` → `"LOW RISK"`,
     `WARNING` → `"WATCH LIST"`, `HIGH` → `"ELEVATED RISK"`,
     `CRITICAL` → `"CRITICAL RISK"`;
   * a `StackPane` bar containing a track `Region` and a fill `Region`, whose
     `maxWidthProperty` is bound to `bar.widthProperty().multiply(max(score, 0.02))`
     so even a zero score shows a visible sliver.

### The threat meter's two colour scales

`scoreColor` (the label) and `createFill` (the bar) use different bands:

| Score fraction | Label colour | Bar gradient |
| --- | --- | --- |
| `<= 0.2` | `#22c55e` | `linear-gradient(#10b981, #22c55e)` |
| `0.2 – 0.35` | `#f59e0b` | `linear-gradient(#10b981, #22c55e)` |
| `0.35 – 0.5` | `#f59e0b` | `linear-gradient(#f59e0b, #fbbf24)` |
| `0.5 – 0.7` | `#f97316` | `linear-gradient(#f59e0b, #fbbf24)` |
| `0.7 – 0.8` | `#f97316` | `linear-gradient(#ef4444, #f43f5e)` |
| `> 0.8` | `#ef4444` | `linear-gradient(#ef4444, #f43f5e)` |

In the `0.2 – 0.35` band the label reads amber while the bar is still green.
Cosmetic, and unintentional.

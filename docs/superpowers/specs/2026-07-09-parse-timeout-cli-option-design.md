# Design: first-class `--parseTimeout` CLI option

Date: 2026-07-09
Branch: `feat/parse-timeout-cli-option`

## Problem

Indexing a genuinely huge OST/PST can legitimately take longer than the
extractor's 24h parse timeout. When that timeout fires, the parse is cancelled
and the document is only partially indexed. Operators need an easy, per-run way
to raise (or disable) that timeout.

Today the timeout is only reachable through the `-s`/`--settings` properties
file (`parseTimeout=72h`), not as a real CLI flag. The JOpt parser does not
enable `allowsUnrecognizedOptions`, so an undeclared `--parseTimeout` is
rejected. This design promotes it to a first-class option.

## Background: current state (verified)

- The timeout is an extract-lib option:
  `@Option(name="parseTimeout", ..., "Set to 0 to disable. Defaults to 24h.")`,
  a duration. `0`/negative disables it. Extract self-defaults via
  `options.get("parseTimeout", "24h")`, so an absent key yields 24h inside
  extract. This is a released dependency (extract-lib >= 9.15.2) and is **not**
  modified by this work.
- Duration syntax is extract's `HumanDuration`: `^(\d+)(d|h|m|s|ms)?$`
  (e.g. `24h`, `30m`, `500ms`, `0`). A bare number is milliseconds; `0` is a
  zero duration and therefore disables the timeout.
- datashare's INDEX task already recognizes the option: `IndexTask` is
  `@OptionsClass(Extractor.class)` and builds the extractor from
  `Options.from(taskView.args)`. So a `parseTimeout` key in the task args flows
  straight into the `Extractor`, identically to `ocrTimeout`, `ocrLanguage`,
  `maxEmbedDepth`, etc. No task-side plumbing is required.
- There are **two** CLI surfaces, both of which declare extractor options:
  - Legacy JOpt Simple: `DatashareCliOptions` (option declarations) +
    `DatashareCli.createParser()` (registration). Options serialized to
    `Properties` by `asProperties`.
  - Picocli: `PipelineOptions` (used by `StageRunCommand` and server mode via
    `ServerOptions`; note `WorkerRunCommand` uses `WorkerOptions`, which carries
    no extractor options, so `worker run` does not surface `--parseTimeout` -
    identical to every sibling extractor option, and harmless because the
    worker executes tasks whose args already carry `parseTimeout`),
    serialized by `toProperties()`.
- Property merge: `CommonMode` builds
  `new PropertiesProvider(settingsFile).overrideWith(cliProperties)`, so CLI
  properties win over the `-s` settings file. `asProperties` emits any option
  whose value list is non-empty, which includes options declared with
  `.defaultsTo(...)`. Therefore a defaulted `--parseTimeout` is always emitted
  and overrides a `parseTimeout` in the settings file.

## Scope

A **dedicated flag** (`--parseTimeout`), matching the existing `ocrTimeout`
twin, added to **both** CLI surfaces. Rationale:

- Every extractor/pipeline option in the codebase (`ocrTimeout`, `ocrLanguage`,
  `ocrStrategy`, `maxEmbedDepth`, `parallelism`, ...) is a hand-declared
  dedicated flag. There is no general "surface any `@Option`" mechanism. A
  generic mechanism would be a divergent one-off; a dedicated flag matches the
  established pattern.
- The property key must be exactly `parseTimeout`, because that is the string
  the `Extractor` reads.

## Design decisions

1. **Default emission: `.defaultsTo("24h")`**, consistent with `ocrTimeout` and
   `indexTimeout`. The default shows in `--help`. Accepted trade-off: because
   `asProperties` always emits a defaulted option and CLI properties override
   the settings file, a run always carries `parseTimeout` and a CLI run's value
   (including the 24h default when the flag is omitted) overrides any
   `parseTimeout` set in the `-s` settings file.
2. **Validation: unvalidated `String`**, exactly like `ocrTimeout`
   (`.ofType(String.class)` / picocli `String` field). Garbage surfaces at
   extract parse time rather than at CLI parse time. Keeps the two timeout
   options consistent.
3. **Warn on `0`: yes.** When the resolved `parseTimeout` is zero/negative
   (timeout disabled, no safety cap), log a one-line WARN. Disabling a safety
   timeout is a foot-gun worth flagging.

## Components to change

### 1. `datashare-cli` — legacy JOpt path

`DatashareCliOptions.java`:
- Add constants `PARSE_TIMEOUT_OPT = "parseTimeout"` and
  `DEFAULT_PARSE_TIMEOUT = "24h"`.
- Add a `parseTimeout(OptionParser)` method: `String`, `.withRequiredArg()`,
  `.ofType(String.class)`, `.defaultsTo(DEFAULT_PARSE_TIMEOUT)`, no
  abbreviation (matching `ocrTimeout`/`indexTimeout`). Help text lifted from
  extract: *"Wall-clock timeout for a single document's parse and output, e.g.
  `30m` or `24h`. Set to 0 to disable. Defaults to 24h."*

`DatashareCli.java`:
- Register `DatashareCliOptions.parseTimeout(parser)` in `createParser()`.

### 2. `datashare-cli` — picocli path

`PipelineOptions.java`:
- Add field
  `@Option(names={"--parseTimeout"}, description=..., defaultValue=DEFAULT_PARSE_TIMEOUT) String parseTimeout;`
  mirroring the `ocrTimeout` field.
- Add `DatashareOptions.put(props, PARSE_TIMEOUT_OPT, parseTimeout)` in
  `toProperties()`.

### 3. `datashare-app` — warn-on-0

`IndexTask.java` (constructor):
- Read the resolved `parseTimeout` from `propertiesProvider`, parse with
  extract's `HumanDuration`, and if the duration is zero/negative,
  `logger.warn(...)` that the parse timeout is disabled and a pathological
  document can hang the worker indefinitely.
- Placed in `IndexTask` (the single chokepoint for CLI, picocli, API, and
  UI-launched index tasks) so the warning fires consistently regardless of
  entry point. When the key is absent (API/UI paths that do not set it), extract
  applies its own 24h default, the duration is non-zero, and no warning fires.

## Data flow

```
CLI (JOpt)      ─┐
CLI (picocli)   ─┤→ parseTimeout key in Properties → task args → IndexTask
API / UI args   ─┘   (@OptionsClass(Extractor.class))
                                                     → Options.from(args)
                                                     → new Extractor(...)
                                                     → setParseTimeout(...)
```

The plumbing from properties to the `Extractor` already exists and is shared by
all sibling extractor options; this work only adds `parseTimeout` to the two CLI
declaration surfaces plus the `IndexTask` warning.

## Testing (TDD)

- `DatashareCliTest` (datashare-cli): `--parseTimeout 48h` → `cli.properties`
  includes `entry("parseTimeout", "48h")`; with no flag, properties include the
  `24h` default.
- `StageRunCommandTest` (datashare-cli, picocli): `--parseTimeout 48h` →
  `toProperties()` includes `parseTimeout=48h`; default `24h` when absent.
- `IndexTaskTest` (datashare-app): the value reaches the `Extractor`
  (`48h` → `Duration.ofHours(48)`); constructing an `IndexTask` with
  `parseTimeout=0` emits the WARN.

## Out of scope

- extract-lib itself (released dependency; only wired through datashare).
- No new documentation file: there are no existing `parseTimeout` references in
  the datashare repo to update; the option's help text is the doc surface.
- No frontend/API schema change: index task args are a free-form map that
  already carries arbitrary extractor options, and `IndexTask` already reads
  `parseTimeout`.

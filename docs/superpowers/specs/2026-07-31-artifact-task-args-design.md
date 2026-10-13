# ARTIFACT reads its task args, and a settings file outranks a CLI default

Issue: [#2298](https://github.com/ICIJ/datashare/issues/2298). Spotted by @caro3801 while reviewing
[#2262](https://github.com/ICIJ/datashare/pull/2262).

## Problem

`ArtifactTask` is the only pipeline stage configured by the process-wide `PropertiesProvider` instead
of its own task args.

`ArtifactTask.java:59-64` takes the injected provider. Every sibling builds one from its task args:
`IndexTask.java:68`, `ScanTask.java:34`, `DeduplicateTask.java:33`, `CategorizeTask.java:48`,
`ExtractNlpTask.java:59`, `ScanIndexTask.java:63`, `EnqueueFromIndexTask.java:56`.

The injected provider folds the settings file in (`CommonMode.java:115`):

```java
new PropertiesProvider(properties.getProperty(PropertiesProvider.SETTINGS_OPT)).overrideWith(properties)
```

The CLI launcher does not. `CliApp.java:221` builds args from the raw CLI-parsed properties, so a
value set only in `datashare.properties` never reaches any stage's args. REST is unaffected: it folds
the file in before building args (`TaskResource.java:553-556`).

Net effect: with `artifacts=raw` in `datashare.properties`, `--stages ARTIFACT` produces artifacts and
`--stages INDEX` produces nothing.

A second bug sits behind the first. `DatashareCli.asProperties` emits a property whenever jopt-simple
holds a value for the option, including an unset `defaultsTo` value (`DatashareCli.java:228`):

```java
if (options.has(spec) || !entry.getValue().isEmpty()) {
```

`artifactsForce` defaults to `false` (`DatashareCliOptions.java:504-508`), so a settings-file
`artifactsForce=true` is overridden by a default the operator never typed. This happens inside the
process-wide provider too, so ARTIFACT does not honour it either. `artifacts` and `artifactDir`
(`DatashareCliOptions.java:490-502`) have no default, which is exactly why they do survive from the
settings file today. The same shape exists on the picocli surface, where 65 of 149 options carry a
`defaultValue`.

## Goal

One rule for every pipeline stage: a stage is configured by its task args, and those args carry the
resolved configuration.

Resolved means, highest wins:

1. explicit CLI flag
2. `DS_DOCKER_*` env vars
3. settings file
4. CLI option default

Layers 2 and 3 already resolve in that order inside `PropertiesProvider.getProperties()`, which loads
the file and then `putAll`s the env vars over it (`PropertiesProvider.java:80-101`). This spec treats
them as a single "resolved settings" layer and does not change their relative order.

## Changes

### A. `ArtifactTask` reads its task args

Replace the injected `PropertiesProvider` with `new PropertiesProvider(taskView.args)`, matching
`IndexTask.java:68`. The parameter leaves the constructor; the assisted-inject factory signature
(`DatashareTaskFactory.java:23`) does not change, because Guice injects the non-`@Assisted` parameters
and one fewer of them is not a signature change for callers.

`createSourceExtractor()` (`ArtifactTask.java:195-197`) becomes args-based with the rest. That is the
point of the change: OCR settings should reach ARTIFACT the way they reach INDEX.

No REST endpoint starts an `ArtifactTask` today, so this has no web-side effect. A future one must
build args like `TaskResource.applyProjectProperties` does.

### B. CLI task args come from the resolved provider

`CliApp.runTaskWorker` passes `mode.properties()` (`CommonMode.java:469`) to `runPipeline` instead of
the raw CLI properties. `runPipeline`'s signature and its existing behaviour are unchanged: it keeps
receiving a `Properties` and keeps calling `propertiesToMap` on it (`CliApp.java:221`).

`CommonMode.properties()` is the settings file plus env vars plus CLI properties, and is already the
source of truth for the rest of the app. This is what REST does at `TaskResource.java:554`.

This also closes a gap for keys with no CLI option on a given surface: `artifacts` and
`artifactsForce` exist only on jopt, so under picocli the settings file is the only way to set them,
and Change B is what carries them into the args.

The admin dispatch reads in `runTaskWorker` (`createIndex`, `genApiKey`, the user and project
subcommands) keep reading the raw properties. They describe an action to perform now, not
configuration, and nobody puts `createIndex=foo` in a settings file.

### C. A settings-file value outranks a CLI default

#### picocli

Set a default value provider on the `CommandLine` in `Main.runPicocli` (`Main.java:53-74`):

```java
commandLine.setDefaultValueProvider(new CommandLine.PropertiesDefaultProvider(resolvedSettings));
```

where `resolvedSettings` is `new PropertiesProvider(settingsPath).getProperties()`, so the file and
the `DS_DOCKER_*` env vars land in the same layer (`PropertiesProvider.java:80-101`).

The provider must be set before parsing, so the settings path is resolved from `args` by a small
private helper in `Main`, alongside `isLegacyInvocation` and `applyColorScheme`, handling `-s X`,
`--settings X`, `-s=X` and `--settings=X`. The option is declared at
`GlobalOptions.java:34` with `ScopeType.INHERIT`. A throwaway pre-parse would also work but leaves
field state and error reporting to reason about; a deterministic scan of `args` is simpler to test.

Verified empirically against picocli 4.7.7, the version in `datashare-cli/pom.xml:29`:

| case | result |
| --- | --- |
| no CLI args, key in settings | settings value wins over annotation `defaultValue` |
| explicit flag, key in settings | explicit flag wins |
| key absent from settings | annotation `defaultValue` applies |
| no provider set | today's behaviour, unchanged |

`PropertiesDefaultProvider` queries the option's longest name with the prefix stripped. A script over
every picocli command found 4 options whose long name differs from the property key they feed, and
only one of them carries a `defaultValue`: `@Option(names = {"--port", "--tcpListenPort"})` at
`ServerOptions.java:28`. Its longest name is `tcpListenPort`, which is the property key, so it
resolves correctly. Verified directly: a settings-file `tcpListenPort=9009` is picked up, `port=9009`
is not. The stock library class is therefore sufficient and no custom `IDefaultValueProvider` is
needed.

#### jopt-simple

In `DatashareCli.asProperties` (`DatashareCli.java:224-235`), when an option was not typed and the
resolved settings (the file with the env vars over it) define that key, emit **the settings value in
place of the default**. Verified against jopt-simple 6.0-alpha-3 (`datashare-cli/pom.xml:22`):
`asMap()` carries the `defaultsTo` value while `has(spec)` returns `false`, so the two cases are already
distinguishable at that line.

Substitute, never omit. Omitting the key looks equivalent, because `CommonMode` folds the settings file
in below the CLI properties, but that reasoning only covers consumers of the resolved provider. See the
Scope section for the three raw-`Properties` consumers it strands and the behaviour change accepted in
exchange. Substitution also makes this surface match picocli's, which substitutes by construction.

The settings file is resolved inside the method from the parsed options, via `OptionSet.has(String)`
and `OptionSet.valueOf(String)` keyed on `SETTINGS_OPT`. `DatashareCliOptions.settings`
(`:351-356`) returns `void`, so there is no spec to capture, and looking the value up by key avoids
changing that signature. When `-s` is absent, `new PropertiesProvider(null)` resolves the classpath
`datashare.properties` exactly as `CommonMode.java:115` does, keeping the two paths symmetric.

The file is read twice per run, once here and once in `CommonMode`. It is a small properties file read
at startup, so this is not worth caching.

### What this fixes

`artifacts=raw` in `datashare.properties`, `--stages INDEX,ARTIFACT`:

- before: ARTIFACT produces artifacts, INDEX produces nothing
- after: the resolved provider carries it (already true), Change B puts it in every stage's args,
  Change A makes ARTIFACT read args, so both stages see it

`artifactsForce=true` in `datashare.properties`:

- before: silently ignored, the jopt `defaultsTo(false)` wins even inside the process-wide provider
- after: Change C stops the default being emitted, the settings value survives into the resolved set
  and into the args

## Scope

**Revised 2026-07-31, after implementation review.** This section originally scoped `mode` and `logLevel`
out, on the plan that Change C would make the settings file win by *omitting* the CLI default and letting
`CommonMode` fold the file back in. Review showed that only works for consumers of the resolved provider.
Three consumers read the raw CLI `Properties` directly and would have been stranded on unrelated
fallbacks:

| Key | Consumer | Was | Would have become |
| --- | --- | --- | --- |
| `logLevel` | `Main.java:107` | `INFO` (`DatashareCliOptions.java:243`) | `DEBUG`, since `Level.toLevel(null)` is `DEBUG` |
| `pluginsDir` | `CliApp.java:79-81` then `PluginService.java:31` | `DEFAULT_PLUGIN_DIR` | `./plugins`, cwd-relative |
| `extensionsDir` | `CliApp.java:79` then `ExtensionService.java:24` | `DEFAULT_EXTENSIONS_DIR` | `./extensions`, cwd-relative |

`CliApp.java:79-81` uses `new PropertiesProvider(properties)`, the bare-`Properties` overload
(`PropertiesProvider.java:61-64`), which never loads the settings file, unlike `CommonMode.java:115`.

Change C therefore **substitutes** the settings value in place of the default rather than omitting the
key, which is also what the picocli half does by construction. Every key that had a value keeps one.

The accepted consequence, decided by the user once the stranding was known: `logLevel`, `pluginsDir`,
`extensionsDir` and `mode` set only in `datashare.properties` now take effect on the legacy CLI where the
option default previously won. A deployment whose settings file sets `mode` will honour it on upgrade.
That is a real behaviour change and belongs in the release notes.

`artifacts` and `artifactsForce` have no picocli option, so `datashare stage run ARTIFACT --artifacts
raw` remains impossible. Change B makes the settings file a working alternative. Adding the flags
belongs to #2262.

## Testing

TDD, each test red before the code that makes it pass.

### Change C, in `datashare-cli`, no infrastructure needed

`DatashareCliTest`:

- `-s <file>` with `artifactsForce=true` in the file: the resulting properties must not carry
  `artifactsForce=false`. Red today.
- explicit `--artifactsForce=false` with the same file: the properties carry `false`. Guards the
  precedence against inverting.
- no settings file: every default is still emitted. Guards against the change dropping defaults
  app-wide.

A picocli test under `datashare-cli/src/test/java/org/icij/datashare/cli/command/`:

- `--settings <file>` with `ocr=false`: the collected properties carry `ocr=false`. Red today, the
  annotation default wins.
- the explicit-beats-settings and no-settings-file counterparts.

### Change A, in `datashare-app`

`ArtifactTaskTest`:

- a new test asserting the task configures itself from `taskView.args`. Red today, args are ignored.
- the 17 `new ArtifactTask(...)` call sites across `ArtifactTaskTest`, `ArtifactChaosIntTest` and
  `ArtifactCoverageIntTest` move to one shared test helper that passes args, so the constructor shape
  lives in one place. The helper is a package-visible class in the `org.icij.datashare.tasks` test
  package, mirroring the existing `IndexerHelper` and `TestTaskUtils` there.
- `test_missing_artifact_dir` keeps its meaning, driven by empty args.

### Change B

`CliAppTest`:

- `runPipeline` with `artifacts=raw` in the properties: the captured args for both INDEX and ARTIFACT
  carry it. This is the issue's reproduction, using the `ArgumentCaptor` pattern already at
  `CliAppTest.java:94-100`.
- a focused test that a `CliMode` built with a settings file exposes those keys from `properties()`,
  which is what Change B feeds into the args.

## Risks accepted

1. **Typed settings values fail fast on picocli.** `parallelism=abc` in `datashare.properties` becomes
   a startup usage error instead of surfacing later as a `NumberFormatException` inside a task. Better
   diagnostics, but a behaviour change for a malformed settings file.
2. **Settings-file secrets reach task args.** Change B serialises `oauthSecret`, `dataSourceUrl` and
   `sessionSigningKey` from the settings file into task args in the task repository, where the task API
   can expose them. REST already does this (`TaskResource.java:554`), so the CLI is being made to
   match rather than something new being introduced, but it widens the existing exposure. Deserves its
   own issue.

## Landing

On `main`, before #2262. That PR rebases onto this and inherits the fix, keeping its own diff about
its own feature.

## Suggested follow-ups

- Wire `artifacts` and `artifactsForce` onto the picocli surface (part of #2262).
- Decide whether task args should be redacted or filtered before being persisted to the task
  repository (risk 2).
- Decide whether `mode` and `logLevel` should be settable from a settings file.

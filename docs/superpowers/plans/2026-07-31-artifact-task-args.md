# ARTIFACT Task Args and Settings Precedence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `ArtifactTask` read its task args like every other pipeline stage, feed CLI stage args from the resolved provider, and stop a CLI option default from overriding a settings-file value.

**Architecture:** Three independent changes, each landing on its own. (A) `ArtifactTask` builds its `PropertiesProvider` from `taskView.args`. (B) `CliApp` passes `mode.properties()` to `runPipeline` instead of the raw CLI properties. (C) Both CLI parsers put a settings-file value above a CLI option default: picocli via its stock `PropertiesDefaultProvider`, jopt-simple via an `options.has(spec)` check in `asProperties`.

**Tech Stack:** Java 21, Maven multi-module, JUnit 4, Mockito, FEST assertions, picocli 4.7.7, jopt-simple 6.0-alpha-3, Guice assisted inject.

Spec: `docs/superpowers/specs/2026-07-31-artifact-task-args-design.md`. Issue:
[#2298](https://github.com/ICIJ/datashare/issues/2298).

## Global Constraints

- Target branch is `main`. This lands before PR #2262, which will rebase onto it.
- Resolved precedence, highest wins: explicit CLI flag, then `DS_DOCKER_*` env vars, then settings file, then CLI option default. Layers 2 and 3 already resolve in that order inside `PropertiesProvider.getProperties()` and this work does not change their relative order.
- A settings-file value is applied by **substitution, never omission**: when an option was not typed on
  the command line and the settings file defines it, emit the settings value in place of the declared
  default. Every key that used to have a value still has one. This is load-bearing, not stylistic:
  several consumers read the raw CLI `Properties` without ever going through `CommonMode`'s settings
  fold-in, and each has its own unrelated fallback (`Main.java:107` logLevel, `CliApp.java:79-81`
  pluginsDir and extensionsDir). Omitting a key strands those consumers on their fallback.
- Consequence, accepted by the user on 2026-07-31 after a review found the stranding: `logLevel`,
  `pluginsDir`, `extensionsDir` and `mode` set only in `datashare.properties` now take effect on the
  legacy CLI, where they were previously overridden by the option default. This supersedes the earlier
  constraint that `mode` and `logLevel` must not change behaviour.
- Do not add `artifacts` or `artifactsForce` to the picocli surface. That belongs to #2262.
- Never leave `ponytail:` comments in committed code.
- Run tests in the background and poll. Never block on Maven.
- Single-test command shape for this repo, `-Dsurefire.failIfNoSpecifiedTests=false` is required and multiple classes are comma-separated:
  `mvn -pl <module> -am test -Dtest=ClassA,ClassB -Dsurefire.failIfNoSpecifiedTests=false`
- Elasticsearch is at `elasticsearch:9200` and Redis at `redis:6379` for integration tests.

## File Structure

**Task 1, change C on the jopt surface (`datashare-cli`):**
- Modify: `datashare-cli/src/main/java/org/icij/datashare/cli/DatashareCli.java:224-235` (`asProperties`) plus imports
- Test: `datashare-cli/src/test/java/org/icij/datashare/cli/DatashareCliTest.java`

**Task 2, change C on the picocli surface (`datashare-cli`, `datashare-app`):**
- Modify: `datashare-cli/src/main/java/org/icij/datashare/cli/command/DatashareCommand.java` (add one public static method)
- Modify: `datashare-app/src/main/java/org/icij/datashare/Main.java:53-74` (`runPicocli`, one call)
- Modify: `datashare-cli/src/test/java/org/icij/datashare/cli/command/AbstractDatashareCommandTest.java` (same call, so the harness matches production)
- Test: `datashare-cli/src/test/java/org/icij/datashare/cli/command/SettingsDefaultsTest.java` (new)

**Task 3, change A (`datashare-app`):**
- Modify: `datashare-app/src/main/java/org/icij/datashare/tasks/ArtifactTask.java:58-66`
- Create: `datashare-app/src/test/java/org/icij/datashare/tasks/ArtifactTaskFixture.java`
- Modify: `datashare-app/src/test/java/org/icij/datashare/tasks/ArtifactTaskTest.java` (12 construction sites)
- Modify: `datashare-app/src/test/java/org/icij/datashare/tasks/ArtifactChaosIntTest.java` (4 sites)
- Modify: `datashare-app/src/test/java/org/icij/datashare/tasks/ArtifactCoverageIntTest.java` (1 site)

**Task 4, change B (`datashare-app`):**
- Modify: `datashare-app/src/main/java/org/icij/datashare/CliApp.java:179-181`
- Test: `datashare-app/src/test/java/org/icij/datashare/CliAppTest.java`

**Task 5, whole-suite regression check.** No production files. This task exists because
`datashare-app/src/test/resources/datashare.properties` sets `clusterName`, `redisAddress`,
`messageBusAddress`, `batchDownloadMaxNbFiles` and `batchDownloadMaxSize`, and Task 1 changes whether a
CLI default can override those. `datashare-cli/src/test/resources/` has no such file, so the CLI module
is unaffected.

Tasks 1 and 2 are independent of each other and of Tasks 3 and 4. Task 4 depends on nothing in Task 3
but the pair is what closes the issue, so keep the order.

---

### Task 1: A settings-file value outranks a jopt option default

**Files:**
- Modify: `datashare-cli/src/main/java/org/icij/datashare/cli/DatashareCli.java:224-235`
- Test: `datashare-cli/src/test/java/org/icij/datashare/cli/DatashareCliTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: no new public API. `DatashareCli.asProperties(OptionSet, String)` keeps its package-visible
  signature and return type `java.util.Properties`.

Background the implementer needs: jopt-simple's `OptionSet.asMap()` returns an entry for every declared
option, and for an option declared with `.defaultsTo(x)` that entry holds `[x]` even when the user typed
nothing. `OptionSet.has(spec)` is the only way to tell "typed on the command line" from "this is just the
default". Verified against jopt-simple 6.0-alpha-3: with no args, `has(spec)` is `false` while
`asMap()` shows `[1]` for an option declared `.defaultsTo(1)`. The existing condition at
`DatashareCli.java:228` already ORs the two cases together, which is exactly the bug.

The fix substitutes the settings value; it must never omit the key. An earlier revision of this task
omitted it, on the theory that `CommonMode` folds the settings file back in below the CLI properties.
That is true for consumers of the resolved provider and false for consumers of the raw CLI `Properties`,
of which there are three: `Main.java:107` reads `logLevel` (and `Level.toLevel(null)` is `DEBUG`, not the
`INFO` default), and `CliApp.java:79-81` builds `ExtensionService` and `PluginService` with
`new PropertiesProvider(properties)`, the bare-`Properties` overload that never loads the settings file,
whose own fallbacks are the cwd-relative `./plugins` and `./extensions`. Substitution keeps every key
present and makes this surface behave like the picocli one, which substitutes by construction.

- [ ] **Step 1: Write the failing tests**

Add to `datashare-cli/src/test/java/org/icij/datashare/cli/DatashareCliTest.java`. The class currently
has no `TemporaryFolder`, so add the rule and the imports listed in the next step.

```java
    @Test
    public void test_settings_file_value_is_not_overridden_by_an_option_default() throws IOException {
        Path settings = settingsFile("artifactsForce=true\n");

        cli.parseArguments(new String[] {"-s", settings.toString()});

        // the CLI default is false (DatashareCliOptions.artifactsForce). The operator never typed it,
        // so the settings value must be the one that lands in the properties.
        assertThat(cli.properties).includes(entry("artifactsForce", "true"));
    }

    @Test
    public void test_a_settings_value_is_substituted_not_dropped() throws IOException {
        // Regression guard for the stranding this replaced: several consumers read these raw
        // properties without CommonMode's settings fold-in, so an absent key silently falls through
        // to an unrelated fallback. logLevel is the sharp case: Main.startApplication does
        // Level.toLevel(getProperty("logLevel")), and Level.toLevel(null) is DEBUG, not the INFO
        // default. pluginsDir/extensionsDir would drop to cwd-relative ./plugins and ./extensions.
        Path settings = settingsFile("logLevel=WARN\npluginsDir=/opt/ds/plugins\n");

        cli.parseArguments(new String[] {"-s", settings.toString()});

        assertThat(cli.properties).includes(entry("logLevel", "WARN"));
        assertThat(cli.properties).includes(entry("pluginsDir", "/opt/ds/plugins"));
    }

    @Test
    public void test_an_explicit_option_still_beats_the_settings_file() throws IOException {
        Path settings = settingsFile("artifactsForce=true\n");

        cli.parseArguments(new String[] {"-s", settings.toString(), "--artifactsForce=false"});

        assertThat(cli.properties).includes(entry("artifactsForce", "false"));
    }

    @Test
    public void test_option_defaults_are_still_emitted_without_a_settings_file() {
        cli.parseArguments(new String[] {"--stages", "INDEX"});

        // guards against the settings lookup dropping defaults app-wide
        assertThat(cli.properties).includes(entry("artifactsForce", "false"));
    }

    @Test
    public void test_a_settings_file_only_overrides_the_defaults_it_defines() throws IOException {
        Path settings = settingsFile("artifactsForce=true\n");

        cli.parseArguments(new String[] {"-s", settings.toString()});

        // a key the file does not mention keeps its declared default
        assertThat(cli.properties).includes(entry("parallelism", String.valueOf(DEFAULT_PARALLELISM)));
    }

    private Path settingsFile(String content) throws IOException {
        Path file = tmp.newFile("datashare-test.properties").toPath();
        Files.writeString(file, content);
        return file;
    }
```

Add the rule to the class body, next to the existing `exit` rule:

```java
    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();
```

- [ ] **Step 2: Add the test imports**

Add to `DatashareCliTest.java`:

```java
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
```

`java.io.IOException`, `java.nio.file.Path`, `org.junit.Rule`, `assertThat` and `entry` are already
imported.

- [ ] **Step 3: Run the tests to verify they fail**

Run in the background and poll:

```bash
mvn -pl datashare-cli -am test -Dtest=DatashareCliTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: two RED.

- `test_settings_file_value_is_not_overridden_by_an_option_default` FAILS: `artifactsForce` is `"false"`
  (the declared default) where `"true"` (the settings value) is required.
- `test_a_settings_value_is_substituted_not_dropped` FAILS: `logLevel` is `"INFO"` and `pluginsDir` is
  the built-in default, where the settings values are required.

`test_an_explicit_option_still_beats_the_settings_file` and
`test_option_defaults_are_still_emitted_without_a_settings_file` and
`test_a_settings_file_only_overrides_the_defaults_it_defines` PASS already. That is expected and
correct: they are regression guards for behaviour that must survive Step 4, not drivers of new
behaviour. Do not proceed until both RED failures are confirmed.

- [ ] **Step 4: Make the tests pass**

Replace `asProperties` at `DatashareCli.java:224-235` with:

```java
    Properties asProperties(OptionSet options, String prefix) {
        Properties properties = new Properties();
        // resolved the same way CommonMode does, so both paths agree on which file is in play
        Properties settings = new PropertiesProvider(
                options.has(SETTINGS_OPT) ? String.valueOf(options.valueOf(SETTINGS_OPT)) : null).getProperties();
        for (Map.Entry<OptionSpec<?>, List<?>> entry : options.asMap().entrySet()) {
            OptionSpec<?> spec = entry.getKey();
            if (entry.getValue().isEmpty() && !options.has(spec)) {
                continue;
            }
            String key = asPropertyKey(prefix, spec);
            if (!options.has(spec) && settings.containsKey(key)) {
                // The operator did not type this option and their settings file defines it: the file
                // wins over the declared default. Substitute rather than omit. Several consumers read
                // these raw properties without CommonMode's settings fold-in (Main.startApplication for
                // logLevel, CliApp.start for pluginsDir/extensionsDir) and would otherwise silently drop
                // to their own unrelated fallback.
                properties.setProperty(key, settings.getProperty(key));
                continue;
            }
            properties.setProperty(key, asPropertyValue(entry.getValue()));
        }
        return properties;
    }
```

Add the imports:

```java
import org.icij.datashare.PropertiesProvider;

import static org.icij.datashare.PropertiesProvider.SETTINGS_OPT;
```

Two notes for the implementer. First, the loop condition is inverted from the original
`if (options.has(spec) || !entry.getValue().isEmpty())` into an early `continue`; the set of options it
admits is identical, it just leaves room for the second guard. Second, `SETTINGS_OPT` is the string
`"settings"` and jopt's `OptionSet.has(String)` and `valueOf(String)` accept the option string directly,
so there is no need to change `DatashareCliOptions.settings` (`:351-356`), which returns `void`.

- [ ] **Step 5: Run the tests to verify they pass**

```bash
mvn -pl datashare-cli -am test -Dtest=DatashareCliTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: all four new tests PASS and every pre-existing test in the class still passes.
`datashare-cli/src/test/resources/` has no `datashare.properties`, so the no-`-s` classpath fallback
finds nothing and existing expectations about defaults are untouched.

- [ ] **Step 6: Run the whole CLI module**

```bash
mvn -pl datashare-cli -am test
```

Expected: green. `LegacyInvocationTest` and `RetroCompatibilityTest` are the ones most likely to notice
a precedence change, so read their output specifically.

- [ ] **Step 7: Commit**

```bash
git add datashare-cli/src/main/java/org/icij/datashare/cli/DatashareCli.java \
        datashare-cli/src/test/java/org/icij/datashare/cli/DatashareCliTest.java
git commit -m "fix(cli): stop an untyped option default from shadowing the settings file"
```

---

### Task 2: The same precedence on the picocli surface

**Files:**
- Modify: `datashare-cli/src/main/java/org/icij/datashare/cli/command/DatashareCommand.java`
- Modify: `datashare-app/src/main/java/org/icij/datashare/Main.java:53-74`
- Modify: `datashare-cli/src/test/java/org/icij/datashare/cli/command/AbstractDatashareCommandTest.java`
- Test: `datashare-cli/src/test/java/org/icij/datashare/cli/command/SettingsDefaultsTest.java` (new)

**Interfaces:**
- Consumes: nothing from Task 1. The two surfaces are independent.
- Produces: `public static void DatashareCommand.applySettingsDefaults(CommandLine commandLine, String[] args)`.
  Returns `void`, mutates the `CommandLine`, and is a no-op when the resolved settings are empty.

Background the implementer needs. picocli resolves an option's default in this order: the
`IDefaultValueProvider` first, then the annotation's `defaultValue`, then the field initialiser. An
explicitly typed argument always wins over both. `PropertiesDefaultProvider` looks a value up by the
option's **longest** name with the leading dashes stripped, so `@Option(names = {"--port", "--tcpListenPort"})`
at `ServerOptions.java:28` is queried as `tcpListenPort`, which is the property key. All four behaviours
were verified directly against picocli 4.7.7.

The provider must be installed before parsing, so the settings path is read straight out of `args`. A
throwaway pre-parse would also work but leaves field state and error reporting to reason about.

`AbstractDatashareCommandTest.configure()` builds its own `CommandLine` rather than calling
`Main.runPicocli`, which is why the wiring lives in one shared static method that both production and the
harness call. If you only wire `Main`, the tests in this task pass against a harness that does not match
production and the feature is untested.

- [ ] **Step 1: Write the failing tests**

Create `datashare-cli/src/test/java/org/icij/datashare/cli/command/SettingsDefaultsTest.java`:

```java
package org.icij.datashare.cli.command;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;

public class SettingsDefaultsTest extends AbstractDatashareCommandTest {
    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void test_settings_file_value_beats_an_annotation_default() throws IOException {
        // --ocr is declared with defaultValue = "true" in PipelineOptions
        Path settings = settingsFile("ocr=false\n");

        Properties props = parse("-s", settings.toString(), "stage", "run", "--stages", "INDEX");

        assertThat(props).includes(entry("ocr", "false"));
    }

    @Test
    public void test_an_explicit_option_beats_the_settings_file() throws IOException {
        Path settings = settingsFile("ocr=false\n");

        Properties props = parse("-s", settings.toString(), "stage", "run", "--stages", "INDEX",
                "--ocr", "true");

        assertThat(props).includes(entry("ocr", "true"));
    }

    @Test
    public void test_annotation_default_applies_for_a_key_the_settings_file_omits() throws IOException {
        Path settings = settingsFile("ocr=false\n");

        Properties props = parse("-s", settings.toString(), "stage", "run", "--stages", "INDEX");

        // scrollSize is declared with defaultValue = "1000" and the file says nothing about it
        assertThat(props).includes(entry("scrollSize", "1000"));
    }

    @Test
    public void test_annotation_defaults_are_untouched_without_a_settings_file() {
        Properties props = parse("stage", "run", "--stages", "INDEX");

        assertThat(props).includes(entry("ocr", "true"));
        assertThat(props).includes(entry("scrollSize", "1000"));
    }

    private Path settingsFile(String content) throws IOException {
        Path file = tmp.newFile("datashare-test.properties").toPath();
        Files.writeString(file, content);
        return file;
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
mvn -pl datashare-cli -am test -Dtest=SettingsDefaultsTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `test_settings_file_value_beats_an_annotation_default` FAILS with `ocr` equal to `"true"`. The
other three PASS already as regression guards. Confirm that failure before writing code.

There is deliberately no test for the `--port` / `--tcpListenPort` alias here. Looking a value up by the
option's longest name is picocli's own behaviour, not behaviour this change introduces, and it is already
proven by the probe recorded in the spec. Asserting it would mean parsing `app serve`, which starts a web
server in a unit test.

- [ ] **Step 3: Add the shared wiring method**

Add to `datashare-cli/src/main/java/org/icij/datashare/cli/command/DatashareCommand.java`:

```java
    /**
     * Puts the settings file above the annotation defaults: picocli consults an IDefaultValueProvider
     * before an option's declared defaultValue, and an explicitly typed argument still wins over both.
     * Read from args because the provider has to be installed before parsing, and a no-op on empty
     * settings so a run with no file behaves exactly as before.
     */
    public static void applySettingsDefaults(CommandLine commandLine, String[] args) {
        Properties settings = new PropertiesProvider(settingsPathFrom(args)).getProperties();
        if (!settings.isEmpty()) {
            commandLine.setDefaultValueProvider(new CommandLine.PropertiesDefaultProvider(settings));
        }
    }

    /** Reads -s/--settings out of the raw args, in both the "-s value" and "-s=value" forms. */
    private static String settingsPathFrom(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("-s".equals(arg) || "--settings".equals(arg)) {
                return i + 1 < args.length ? args[i + 1] : null;
            }
            if (arg.startsWith("-s=")) {
                return arg.substring("-s=".length());
            }
            if (arg.startsWith("--settings=")) {
                return arg.substring("--settings=".length());
            }
        }
        return null;
    }
```

Add the imports:

```java
import org.icij.datashare.PropertiesProvider;
```

`picocli.CommandLine` and `java.util.Properties` are already imported.

- [ ] **Step 4: Call it from the test harness**

In `AbstractDatashareCommandTest`, change `parse` and `parseExitCode` to hand the args to `configure`,
and install the provider there so the harness matches production:

```java
    protected Properties parse(String... args) {
        DatashareCommand cmd = new DatashareCommand();
        CommandLine commandLine = configure(cmd, args);
        commandLine.execute(args);
        return cmd.collectProperties();
    }

    protected int parseExitCode(String... args) {
        DatashareCommand cmd = new DatashareCommand();
        CommandLine commandLine = configure(cmd, args);
        return commandLine.execute(args);
    }

    private static CommandLine configure(DatashareCommand cmd, String[] args) {
```

Then add this as the last statement before `return commandLine;` in `configure`:

```java
        DatashareCommand.applySettingsDefaults(commandLine, args);
```

Leave the rest of `configure` exactly as it is.

- [ ] **Step 5: Run the tests to verify they pass**

```bash
mvn -pl datashare-cli -am test -Dtest=SettingsDefaultsTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

- [ ] **Step 6: Run the whole CLI module**

```bash
mvn -pl datashare-cli -am test
```

Expected: green. Every picocli test now runs through `applySettingsDefaults`;
`datashare-cli/src/test/resources/` has no `datashare.properties`, so the resolved settings are empty
and the provider is never installed for the existing tests.

- [ ] **Step 7: Call it from production**

In `datashare-app/src/main/java/org/icij/datashare/Main.java`, inside `runPicocli`, add the call right
after `applyColorScheme(commandLine, args);`:

```java
        DatashareCommand.applySettingsDefaults(commandLine, args);
```

`DatashareCommand` is already imported at `Main.java:8`.

- [ ] **Step 8: Verify the app module still compiles and its CLI tests pass**

```bash
mvn -pl datashare-app -am test -Dtest=CliAppTest,LegacyInvocationTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: green. Note that `datashare-app/src/test/resources/datashare.properties` does exist, so from
here on any app-module test that goes through `runPicocli` resolves that file. Task 5 checks the whole
suite for exactly this reason.

- [ ] **Step 9: Commit**

```bash
git add datashare-cli/src/main/java/org/icij/datashare/cli/command/DatashareCommand.java \
        datashare-cli/src/test/java/org/icij/datashare/cli/command/AbstractDatashareCommandTest.java \
        datashare-cli/src/test/java/org/icij/datashare/cli/command/SettingsDefaultsTest.java \
        datashare-app/src/main/java/org/icij/datashare/Main.java
git commit -m "fix(cli): let a settings file outrank a picocli option default"
```

---

### Task 3: ArtifactTask reads its task args

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/tasks/ArtifactTask.java:58-66`
- Create: `datashare-app/src/test/java/org/icij/datashare/tasks/ArtifactTaskFixture.java`
- Modify: `datashare-app/src/test/java/org/icij/datashare/tasks/ArtifactTaskTest.java`
- Modify: `datashare-app/src/test/java/org/icij/datashare/tasks/ArtifactChaosIntTest.java`
- Modify: `datashare-app/src/test/java/org/icij/datashare/tasks/ArtifactCoverageIntTest.java`

**Interfaces:**
- Consumes: nothing from Tasks 1 and 2.
- Produces: the constructor
  `ArtifactTask(DocumentCollectionFactory<String> factory, Indexer indexer, UpstreamGate.Factory gateFactory, Task<Long> taskView, Function<Double, Void> updateCallback)`.
  The `PropertiesProvider` parameter is gone; every other parameter keeps its position and type.
  Also produces `static Task<Long> ArtifactTaskFixture.taskWith(Map<String, Object> args)` returning a
  `Task<Long>` whose `args` are a mutable copy of `args`.

Background the implementer needs. `DatashareTaskFactory.java:23` declares
`ArtifactTask createArtifactTask(Task<Long> taskView, Function<Double, Void> updateCallback)`. That is a
Guice assisted-inject factory: the `@Assisted` parameters come from the caller and the rest are injected.
Removing an injected parameter does not change the factory interface, so `DatashareTaskFactory` needs no
edit. Do not touch it.

In `ArtifactChaosIntTest` and `ArtifactCoverageIntTest` the provider handed to the constructor is already
`new PropertiesProvider(map)` built from the very same `map` used as the task args, so dropping the
argument there is behaviour-preserving. The local `props` variable stays: those tests still use it for
`SourceExtractor` and `ArtifactCoverageChecker`.

- [ ] **Step 1: Write the failing test**

Add to `datashare-app/src/test/java/org/icij/datashare/tasks/ArtifactTaskTest.java`:

Write it against the **current** 6-argument constructor, passing an **empty** injected provider and
putting the configuration in the task args. That makes it fail at runtime with a real assertion rather
than failing to compile, which is what a genuine RED looks like:

```java
    @Test(timeout = 10000)
    public void test_configuration_comes_from_the_task_args() throws Exception {
        indexEmbeddedDoc();
        DocumentQueue<String> queue = factory.createQueue("extract:queue:artifact", String.class);
        queue.add(EMBEDDED_DOC_SHA256);

        // args carry the whole configuration, exactly as IndexTask and every other stage expects.
        // The injected provider is deliberately empty: nothing may be read from it.
        Long numberOfDocuments = new ArtifactTask(factory, mockEs, new PropertiesProvider(Map.of()),
                new UpstreamGate.Factory(taskRepository),
                ArtifactTaskFixture.taskWith(Map.of(
                        "artifactDir", artifactDir.getRoot().toString(),
                        "defaultProject", "prj")), null)
                .call();

        assertThat(numberOfDocuments).isEqualTo(1);
        assertThat(artifactDir.getRoot().toPath().resolve("prj/6a/bb").toFile()).isDirectory();
    }
```

In Step 4, after the constructor loses the provider parameter, delete the
`new PropertiesProvider(Map.of()),` argument from this call. Every assertion stays byte-for-byte
identical across RED and GREEN, which is the point: the test pins behaviour, not a signature.

- [ ] **Step 2: Create the shared fixture**

Create `datashare-app/src/test/java/org/icij/datashare/tasks/ArtifactTaskFixture.java`:

```java
package org.icij.datashare.tasks;

import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.user.User;

import java.util.HashMap;
import java.util.Map;

/** Builds the Task an ArtifactTask reads its configuration from. Shared by the three artifact test classes. */
class ArtifactTaskFixture {
    /** Task args are mutable in production (the launcher adds the upstream id), so copy into a HashMap. */
    static Task<Long> taskWith(Map<String, Object> args) {
        return new Task<>(ArtifactTask.class.getName(), User.local(), new HashMap<>(args));
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

```bash
mvn -pl datashare-app -am test -Dtest=ArtifactTaskTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAILS with `IllegalArgumentException: cannot create artifact task with empty artifactDir`,
thrown from `ArtifactTask.java:64`, because today the task reads `artifactDir` from the injected provider
(empty) and ignores the args that carry it. That exception IS the bug in #2298, reproduced. Capture the
failure output before writing any production code.

- [ ] **Step 4: Change the production constructor**

In `ArtifactTask.java`, replace lines 58-66 with:

```java
    @Inject
    public ArtifactTask(DocumentCollectionFactory<String> factory, Indexer indexer, final UpstreamGate.Factory gateFactory, @Assisted Task<Long> taskView, @Assisted final Function<Double, Void> updateCallback) {
        super(Stage.ARTIFACT, taskView.getUser(), factory, new PropertiesProvider(taskView.args), String.class, gateFactory.forTask(taskView));
        this.indexer = indexer;
        project = Project.project(propertiesProvider.get(DEFAULT_PROJECT_OPT).orElse(DEFAULT_DEFAULT_PROJECT));
        parallelism = Math.max(1, propertiesProvider.get(PARALLELISM_OPT).map(Integer::parseInt).orElse(1));
        artifactDir = Path.of(propertiesProvider.get(ARTIFACT_DIR_OPT).orElseThrow(() -> new IllegalArgumentException(String.format("cannot create artifact task with empty %s", ARTIFACT_DIR_OPT))));
        executor = Executors.newFixedThreadPool(parallelism, namedThreadFactory("artifact-worker"));
    }
```

Only the parameter list and the `super(...)` provider argument change. The body below `super(...)` reads
`propertiesProvider`, the field set by `PipelineTask`, so it now resolves against the args. The
`org.icij.datashare.PropertiesProvider` import at line 5 stays; it is still used in `super(...)`.

- [ ] **Step 5: Update the 12 sites in ArtifactTaskTest**

Every site drops the provider argument. Where a test declares a local
`PropertiesProvider props = new PropertiesProvider(Map.of(...))` and a separate
`Task<Long> task = new Task<>(..., new HashMap<>())`, keep `props` only if the test body still uses it
(the anonymous `createSourceExtractor()` overrides do) and move the config map into the task args. The
mechanical shape, using `test_workers_run_concurrently` at `:100-118` as the worked example:

```java
        Map<String, Object> config = Map.of(
                "artifactDir", artifactDir.getRoot().toString(),
                "defaultProject", "prj",
                "parallelism", "2");
        PropertiesProvider props = new PropertiesProvider(config);
        Task<Long> task = ArtifactTaskFixture.taskWith(config);

        ArtifactTask artifactTask = new ArtifactTask(factory, mockEs, new UpstreamGate.Factory(taskRepository), task, null) {
```

`test_missing_artifact_dir` at `:54-57` becomes, keeping its `expected = IllegalArgumentException.class`:

```java
    @Test(expected = IllegalArgumentException.class)
    public void test_missing_artifact_dir() {
        new ArtifactTask(factory, mockEs, new UpstreamGate.Factory(taskRepository),
                ArtifactTaskFixture.taskWith(Map.of()), null);
    }
```

The two private helpers at `:480-496` become:

```java
    private Long runArtifactTask() throws Exception {
        return new ArtifactTask(factory, mockEs, new UpstreamGate.Factory(taskRepository),
                ArtifactTaskFixture.taskWith(Map.of(
                        "artifactDir", artifactDir.getRoot().toString(),
                        "defaultProject", "prj")), null)
                .call();
    }

    private Long runArtifactTask(int parallelism) throws Exception {
        return new ArtifactTask(factory, mockEs, new UpstreamGate.Factory(taskRepository),
                ArtifactTaskFixture.taskWith(Map.of(
                        "artifactDir", artifactDir.getRoot().toString(),
                        "defaultProject", "prj",
                        "parallelism", String.valueOf(parallelism))), null)
                .call();
    }
```

Apply the same transformation to the remaining sites at `:161`, `:191`, `:238`, `:276`, `:333`, `:382`,
`:452` and `:507`. Do not change any assertion. If a test's args map was already non-empty for another
reason, merge the config into it rather than replacing it.

- [ ] **Step 6: Update the 5 sites in the two integration tests**

In `ArtifactChaosIntTest` at `:83`, `:140`, `:194` and `:250`, and `ArtifactCoverageIntTest` at `:65`,
delete the `props` argument and route the task through the fixture:

```java
        new ArtifactTask(stringQueueFactory, indexer, new UpstreamGate.Factory(taskRepository),
                ArtifactTaskFixture.taskWith(map), null).call();
```

These already pass `map` as the task args and build `props` from that same `map`
(`ArtifactChaosIntTest.java:55-61` and `ArtifactCoverageIntTest.java:41-47`), so this is
behaviour-preserving. Keep the `props` local: it is still used for `SourceExtractor` and
`ArtifactCoverageChecker`.

`taskWith` copies the map, which is safe here: no site mutates `map` after building the task, verified
with `grep -n "map\.put\|map\.remove"` across both files (no matches). Using the fixture rather than a
bare `new Task<>(...)` is what keeps the construction shape in one place across all three test classes.

- [ ] **Step 7: Run the unit test class to verify it passes**

```bash
mvn -pl datashare-app -am test -Dtest=ArtifactTaskTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS, including the new test and all 12 updated sites.

- [ ] **Step 8: Run the integration tests**

These need Elasticsearch at `elasticsearch:9200`.

```bash
mvn -pl datashare-app -am test -Dtest=ArtifactChaosIntTest,ArtifactCoverageIntTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS. If they fail, first confirm they pass on an unmodified `main` before assuming this change
caused it.

- [ ] **Step 9: Commit**

```bash
git add datashare-app/src/main/java/org/icij/datashare/tasks/ArtifactTask.java \
        datashare-app/src/test/java/org/icij/datashare/tasks/ArtifactTaskFixture.java \
        datashare-app/src/test/java/org/icij/datashare/tasks/ArtifactTaskTest.java \
        datashare-app/src/test/java/org/icij/datashare/tasks/ArtifactChaosIntTest.java \
        datashare-app/src/test/java/org/icij/datashare/tasks/ArtifactCoverageIntTest.java
git commit -m "refactor(tasks): configure ArtifactTask from its task args like its siblings"
```

---

### Task 4: CLI stage args come from the resolved provider

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/CliApp.java:179-181`
- Test: `datashare-app/src/test/java/org/icij/datashare/CliAppTest.java`

**Interfaces:**
- Consumes: `ArtifactTask` reading its args, from Task 3. Without that, the ARTIFACT half of this is
  invisible.
- Produces: no signature change.
  `CliApp.runPipeline(TaskManager, PipelineHelper, Properties)` keeps its shape and stays package-visible
  and static, which is what makes it directly testable.

Background the implementer needs. `runTaskWorker` already holds the `CommonMode`, and
`CommonMode.properties()` (`CommonMode.java:469`) returns the resolved provider's properties: the
settings file, the `DS_DOCKER_*` env vars, then the CLI properties over the top
(`CommonMode.java:115`). That is the same resolved view REST builds its args from at
`TaskResource.java:553-556`. The change is which `Properties` the launcher hands to `runPipeline`, not
how `runPipeline` works.

- [ ] **Step 1: Write the failing test**

Add to `datashare-app/src/test/java/org/icij/datashare/CliAppTest.java`:

```java
    @Test
    public void test_run_pipeline_passes_the_resolved_configuration_to_every_stage() throws Exception {
        TaskManager mockedManager = mock(TaskManager.class);
        when(mockedManager.startTask(eq(IndexTask.class), any(), any())).thenReturn("id-index");
        when(mockedManager.startTask(eq(ArtifactTask.class), any(), any())).thenReturn("id-artifact");
        doReturn(doneTask()).when(mockedManager).getTask("id-index");
        doReturn(doneTask()).when(mockedManager).getTask("id-artifact");
        Properties properties = new Properties();
        properties.setProperty("stages", "INDEX,ARTIFACT");
        // a value an operator would set in datashare.properties: it has to reach both stages, not just
        // the one that used to read the process-wide provider
        properties.setProperty("artifacts", "raw");

        CliApp.runPipeline(mockedManager, new PipelineHelper(new PropertiesProvider(properties)), properties);

        ArgumentCaptor<Map<String, Object>> indexArgs = ArgumentCaptor.forClass(Map.class);
        verify(mockedManager).startTask(eq(IndexTask.class), any(), indexArgs.capture());
        assertThat(indexArgs.getValue().get("artifacts")).isEqualTo("raw");
        ArgumentCaptor<Map<String, Object>> artifactArgs = ArgumentCaptor.forClass(Map.class);
        verify(mockedManager).startTask(eq(ArtifactTask.class), any(), artifactArgs.capture());
        assertThat(artifactArgs.getValue().get("artifacts")).isEqualTo("raw");
    }
```

Add the import:

```java
import org.icij.datashare.tasks.ArtifactTask;
```

`PipelineHelper` is in the same package as the test, `ArgumentCaptor`, `verify`, `when`, `doReturn`,
`eq`, `any`, `Properties` and `Map` are already imported, and `doneTask()` is an existing helper in the
class.

- [ ] **Step 2: Run the test to verify it passes, and understand why**

```bash
mvn -pl datashare-app -am test -Dtest=CliAppTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS on the first run. `runPipeline` already copies whatever `Properties` it is handed into
each stage's args, so this test pins the contract rather than driving new code. It is here because it is
the issue's literal reproduction and it must keep passing. The behaviour that is actually broken lives
one level up, in which `Properties` the launcher passes, and that is Step 3. If this test FAILS, stop:
something in `runPipeline` is not doing what this plan assumes.

- [ ] **Step 3: Feed runPipeline the resolved properties**

In `CliApp.runTaskWorker`, replace lines 179-181:

```java
        PipelineHelper pipeline = new PipelineHelper(new PropertiesProvider(properties));
        logger.info("executing {}", pipeline);
        boolean completed = runPipeline(taskManager, pipeline, properties);
```

with:

```java
        // The resolved properties, not the raw CLI ones: the settings file and the DS_DOCKER_* env vars
        // are folded in here (CommonMode), and a stage is configured by its task args alone. Passing the
        // raw CLI properties is what made a settings-file-only value reach no stage at all.
        Properties resolved = mode.properties();
        PipelineHelper pipeline = new PipelineHelper(new PropertiesProvider(resolved));
        logger.info("executing {}", pipeline);
        boolean completed = runPipeline(taskManager, pipeline, resolved);
```

Leave every other use of `properties` in `runTaskWorker` alone. The admin dispatchers above
(`CREATE_INDEX_OPT`, `CRE_API_KEY_OPT`, the user and project handlers) describe an action to perform now
rather than configuration, and moving them is out of scope.

- [ ] **Step 4: Run the test class again**

```bash
mvn -pl datashare-app -am test -Dtest=CliAppTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS, unchanged. `runPipeline`'s own tests all call it directly and are unaffected by the
caller change.

- [ ] **Step 5: Verify the wiring by hand**

`runTaskWorker` is private and takes a live `CommonMode`, so the one-line caller change is verified by
reading it rather than by a unit test. Confirm all three of these:

```bash
grep -n "mode.properties()" datashare-app/src/main/java/org/icij/datashare/CliApp.java
grep -n "runPipeline(taskManager, pipeline, resolved)" datashare-app/src/main/java/org/icij/datashare/CliApp.java
grep -n "public Properties properties()" datashare-app/src/main/java/org/icij/datashare/mode/CommonMode.java
```

Expected: the first two match inside `runTaskWorker`, and the third confirms the accessor still exists at
`CommonMode.java:469`.

- [ ] **Step 6: Commit**

```bash
git add datashare-app/src/main/java/org/icij/datashare/CliApp.java \
        datashare-app/src/test/java/org/icij/datashare/CliAppTest.java
git commit -m "fix(cli): build stage task args from the resolved properties"
```

---

### Task 5: Whole-suite regression check

**Files:** none. This task changes no code and produces no commit unless it finds a break.

**Interfaces:**
- Consumes: all of Tasks 1 to 4.
- Produces: a pass or fail verdict for the branch, and a written note separating pre-existing failures
  from new ones.

Why this task exists. `datashare-app/src/test/resources/datashare.properties` sets `clusterName`,
`redisAddress`, `messageBusAddress`, `batchDownloadMaxNbFiles` and `batchDownloadMaxSize`. Before this
work, a CLI default could override those in the resolved provider; after Tasks 1 and 2 the file wins for
any key it defines. Any app-module test that goes through a CLI parse can therefore see different values.
Four classes are known to fail on any branch for environment reasons, so a baseline is required to tell
a real regression from the usual noise.

- [ ] **Step 1: Capture the baseline on unmodified main**

From a clean checkout of `main` (a worktree is fine; copy the gitignored
`datashare-devenv.properties` into it or jOOQ codegen fails with "No JDBC Connection configured"):

```bash
mvn test > /tmp/baseline-main.txt 2>&1
```

Run it in the background and poll. Then extract the failures:

```bash
grep -E "^\[ERROR\].*Tests run|Tests run:.*Failures" /tmp/baseline-main.txt | tail -30
```

- [ ] **Step 2: Run the full suite on the branch**

```bash
mvn test > /tmp/branch-2298.txt 2>&1
```

- [ ] **Step 3: Diff the two failure sets**

Surefire prints one `Tests run: ... <<< FAILURE!` line per failing class, so extract those class names
rather than every class named in the log:

```bash
failing() { grep -oE "^\[ERROR\] [A-Za-z0-9_.]+Test" "$1" | sort -u; }
diff <(failing /tmp/baseline-main.txt) <(failing /tmp/branch-2298.txt)
```

Expected: the branch's failing set is a subset of the baseline's. Any test failing on the branch and not
on `main` is a real regression from this work and must be fixed before the PR, not explained away.

- [ ] **Step 4: Write the verdict**

Record, in the PR description draft, which classes failed on both (pre-existing, environment) and which
failed only on the branch (must be zero). Name them explicitly. "Tests pass" without this split is not
an acceptable report for this branch, because the precedence change can shift values silently rather
than loudly.

---

## Self-Review

**Spec coverage**

| Spec section | Task |
| --- | --- |
| Change A, `ArtifactTask` reads task args | Task 3 |
| Change B, CLI args from the resolved provider | Task 4 |
| Change C, picocli default value provider | Task 2 |
| Change C, jopt `has(spec)` check | Task 1 |
| Test plan, `DatashareCliTest` three cases | Task 1, Step 1 (four cases: the fourth pins that only defined keys are suppressed) |
| Test plan, picocli settings test | Task 2, Step 1 |
| Test plan, `ArtifactTaskTest` args test and shared helper | Task 3, Steps 1, 2, 5 |
| Test plan, `CliAppTest` both-stages assertion | Task 4, Step 1 |
| Scope, `mode` and `logLevel` unchanged | Global Constraints, and Task 4 Step 3 leaves the other `properties` reads alone |
| Risk 1, typed settings values fail fast | Task 5, which is the check that catches it |
| Risk 2, settings secrets in task args | Not implemented, it is a follow-up in the spec |

Two deviations from the spec, both deliberate and both an improvement on what the spec assumed:

1. The spec put the picocli args scan in a private `Main` helper. It is a public static on
   `DatashareCommand` instead, because `AbstractDatashareCommandTest` builds its own `CommandLine` and
   would otherwise test a harness that does not match production. Discovered while reading the harness.
2. The spec's `CliAppTest` case is described as a red test. It passes on the first run, because
   `runPipeline` was never the broken part. Task 4 Step 2 says so explicitly rather than dressing it up
   as TDD, and the untestable one-line caller change is verified by reading it in Step 5.

**Ponytail pass applied.** Six cuts, roughly 36 lines: the one-caller `resolvedSettings` helper inlined,
the alias test deleted (it would have booted a web server to assert picocli's own behaviour), two javadoc
blocks shrunk, the fixture's private constructor dropped, the integration tests routed through the fixture
so it has more than one consumer, and the Task 5 failure diff fixed to compare failing classes instead of
every class named in the log.

**Placeholder scan:** no TBD, TODO, "similar to Task N", or "add error handling". Every code step carries
the actual code. Task 3 Steps 5 and 6 describe a mechanical transformation across 17 sites with a worked
example and the exact line numbers rather than reproducing all 17, which is the one place this plan
trades repetition for a pattern; the transformation is "delete one argument, move the config map into the
args" and the example shows both halves.

**Type consistency:** `ArtifactTaskFixture.taskWith(Map<String, Object>)` returns `Task<Long>` and is
used with that type in Task 3 Steps 1 and 5. `DatashareCommand.applySettingsDefaults(CommandLine, String[])`
returns `void` and is called identically in Task 2 Steps 4 and 7. `resolvedSettings(OptionSet)` returns
`Properties` and is consumed as `Properties` in Task 1 Step 4. The `ArtifactTask` five-argument
constructor is written out in full in Task 3 Step 4 and matched by every call site in Steps 1, 5 and 6.

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-07-31-artifact-task-args.md`.

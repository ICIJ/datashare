# `--parseTimeout` CLI Option Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Promote the extractor's `parseTimeout` duration to a first-class `--parseTimeout` CLI flag on both datashare CLI surfaces, and warn when it is set to 0 (disabled).

**Architecture:** Add a dedicated flag mirroring the existing `ocrTimeout` twin in the legacy JOpt parser (`DatashareCliOptions`/`DatashareCli`) and the picocli parser (`PipelineOptions`). The value lands in `Properties` under the key `parseTimeout`, flows through task args into `IndexTask` (`@OptionsClass(Extractor.class)`), and is picked up by the `Extractor` with no task-side plumbing change. `IndexTask` gains a startup WARN when the resolved timeout is zero/negative.

**Tech Stack:** Java, JOpt Simple, picocli, extract-lib (`org.icij.extract.extractor.Extractor`, `org.icij.time.HumanDuration`), JUnit 4, FEST assertions, Mockito, Logback (via `LogbackAppenderWrapper`).

## Global Constraints

- extract-lib is a released dependency (>= 9.15.2); do NOT modify extract. datashare-side changes only.
- The property key MUST be exactly `parseTimeout` (the string the `Extractor` reads via `options.get("parseTimeout", "24h")`).
- CLI default is `"24h"` via `.defaultsTo(...)` / picocli `defaultValue`, matching the `ocrTimeout` precedent.
- The flag value is an unvalidated `String` (like `ocrTimeout`); no CLI-side duration validation.
- Duration syntax (extract's `HumanDuration`): `^(\d+)(d|h|m|s|ms)?$`, e.g. `24h`, `30m`, `500ms`, `0`. Bare number = milliseconds; `0` disables.
- No abbreviation for the flag (matches `ocrTimeout`/`indexTimeout`).
- Commit messages: `type(scope): description`, no body, no co-authors. Never credit AI.
- Test invocation (per project): `mvn -pl <module> -am test -Dtest=<Class>#<method> -Dsurefire.failIfNoSpecifiedTests=false`. ES/Redis are NOT required for the tests in this plan (they use mocks / in-process parsing).

---

### Task 1: JOpt `--parseTimeout` flag

**Files:**
- Modify: `datashare-cli/src/main/java/org/icij/datashare/cli/DatashareCliOptions.java` (add constants near the other `*_OPT` / `DEFAULT_*` fields; add a `parseTimeout(OptionParser)` method next to `ocrTimeout(...)`)
- Modify: `datashare-cli/src/main/java/org/icij/datashare/cli/DatashareCli.java` (register in `createParser()`)
- Test: `datashare-cli/src/test/java/org/icij/datashare/cli/DatashareCliTest.java`

**Interfaces:**
- Produces:
  - `public static final String DatashareCliOptions.PARSE_TIMEOUT_OPT = "parseTimeout"`
  - `public static final String DatashareCliOptions.DEFAULT_PARSE_TIMEOUT = "24h"`
  - `static void DatashareCliOptions.parseTimeout(OptionParser parser)`
- Consumes: nothing from earlier tasks.

- [ ] **Step 1: Write the failing test**

Add to `DatashareCliTest.java`:

```java
    @Test
    public void test_parse_timeout_opt() {
        cli.parseArguments(new String[] {"--parseTimeout", "48h"});
        assertThat(cli.properties).includes(entry("parseTimeout", "48h"));
    }

    @Test
    public void test_parse_timeout_opt_defaults_to_24h() {
        cli.parseArguments(new String[] {""});
        assertThat(cli.properties).includes(entry("parseTimeout", "24h"));
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -pl datashare-cli -am test -Dtest=DatashareCliTest#test_parse_timeout_opt+test_parse_timeout_opt_defaults_to_24h -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL. `test_parse_timeout_opt` fails because JOpt rejects the unrecognized `--parseTimeout` option (the parser does not allow unrecognized options); `test_parse_timeout_opt_defaults_to_24h` fails because `parseTimeout` is absent from properties.

- [ ] **Step 3: Add the constants**

In `DatashareCliOptions.java`, add alongside the other option-name constants (near `PARALLELISM_OPT` / `PARSER_PARALLELISM_OPT`):

```java
    public static final String PARSE_TIMEOUT_OPT = "parseTimeout";
```

and alongside the other defaults (near `DEFAULT_OCR_TIMEOUT`):

```java
    public static final String DEFAULT_PARSE_TIMEOUT = "24h";
```

- [ ] **Step 4: Add the option method**

In `DatashareCliOptions.java`, immediately after the `ocrTimeout(OptionParser)` method:

```java
    static void parseTimeout(OptionParser parser) {
        parser.acceptsAll(List.of(PARSE_TIMEOUT_OPT),
                "Wall-clock timeout for a single document's parse and output, e.g. \"30m\" or \"24h\". " +
                        "Set to 0 to disable. Defaults to 24h.")
            .withRequiredArg()
            .ofType(String.class)
            .defaultsTo(DEFAULT_PARSE_TIMEOUT);
    }
```

- [ ] **Step 5: Register the option in the parser**

In `DatashareCli.java`, inside `createParser()`, add next to the other extractor option registrations (e.g. just after `DatashareCliOptions.ocrTimeout(parser);`):

```java
        DatashareCliOptions.parseTimeout(parser);
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `mvn -pl datashare-cli -am test -Dtest=DatashareCliTest#test_parse_timeout_opt+test_parse_timeout_opt_defaults_to_24h -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (both).

- [ ] **Step 7: Commit**

```bash
git add datashare-cli/src/main/java/org/icij/datashare/cli/DatashareCliOptions.java \
        datashare-cli/src/main/java/org/icij/datashare/cli/DatashareCli.java \
        datashare-cli/src/test/java/org/icij/datashare/cli/DatashareCliTest.java
git commit -m "feat(cli): add first-class --parseTimeout option to the JOpt parser"
```

---

### Task 2: picocli `--parseTimeout` flag

**Files:**
- Modify: `datashare-cli/src/main/java/org/icij/datashare/cli/command/PipelineOptions.java` (add field + `toProperties()` mapping, mirroring `ocrTimeout`)
- Test: `datashare-cli/src/test/java/org/icij/datashare/cli/command/StageRunCommandTest.java`

**Interfaces:**
- Consumes: `DatashareCliOptions.PARSE_TIMEOUT_OPT`, `DatashareCliOptions.DEFAULT_PARSE_TIMEOUT` (from Task 1; `PipelineOptions` already has `import static org.icij.datashare.cli.DatashareCliOptions.*;`).
- Produces: `parseTimeout` key in the `Properties` returned by `PipelineOptions.toProperties()`.

- [ ] **Step 1: Write the failing test**

Add to `StageRunCommandTest.java` (uses the existing `parse(...)` helper from `AbstractDatashareCommandTest`, returning `Properties`):

```java
    @Test
    public void test_stage_run_parse_timeout() {
        Properties props = parse("stage", "run", "--stages", "SCAN,INDEX", "--parseTimeout", "48h");
        assertThat(props).includes(entry("parseTimeout", "48h"));
    }

    @Test
    public void test_stage_run_parse_timeout_defaults_to_24h() {
        Properties props = parse("stage", "run", "--stages", "SCAN,INDEX");
        assertThat(props).includes(entry("parseTimeout", "24h"));
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -pl datashare-cli -am test -Dtest=StageRunCommandTest#test_stage_run_parse_timeout+test_stage_run_parse_timeout_defaults_to_24h -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL. The explicit-value test fails because picocli rejects the unknown `--parseTimeout` option; the default test fails because `parseTimeout` is absent from the properties.

- [ ] **Step 3: Add the option field**

In `PipelineOptions.java`, add immediately after the `ocrTimeout` field (line ~44):

```java
    @Option(names = {"--parseTimeout"}, description = "Wall-clock timeout for a single document's parse and output, e.g. \"30m\" or \"24h\". Set to 0 to disable. Defaults to 24h.", defaultValue = DEFAULT_PARSE_TIMEOUT)
    String parseTimeout;
```

- [ ] **Step 4: Map the field into properties**

In `PipelineOptions.toProperties()`, add next to the `OCR_TIMEOUT` mapping (line ~110):

```java
        DatashareOptions.put(props, PARSE_TIMEOUT_OPT, parseTimeout);
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn -pl datashare-cli -am test -Dtest=StageRunCommandTest#test_stage_run_parse_timeout+test_stage_run_parse_timeout_defaults_to_24h -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (both).

- [ ] **Step 6: Commit**

```bash
git add datashare-cli/src/main/java/org/icij/datashare/cli/command/PipelineOptions.java \
        datashare-cli/src/test/java/org/icij/datashare/cli/command/StageRunCommandTest.java
git commit -m "feat(cli): add --parseTimeout option to the picocli pipeline options"
```

---

### Task 3: `IndexTask` value propagation + warn-on-0

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/tasks/IndexTask.java` (add a warn-on-disabled check in the constructor)
- Test: `datashare-app/src/test/java/org/icij/datashare/tasks/IndexTaskTest.java`

**Interfaces:**
- Consumes: `PARSE_TIMEOUT_OPT` (already reachable via IndexTask's existing `import static org.icij.datashare.cli.DatashareCliOptions.*;`).
- Produces: nothing consumed by later tasks.

**Note on the value-propagation test:** `IndexTask` builds `allTaskOptions = options().createFrom(Options.from(taskView.args))` and passes it to both `spewer.configure(allTaskOptions)` and `createExtractor(documentFactory, allTaskOptions)`. Asserting the captured `spewer.configure(...)` argument contains `parseTimeout=48h` proves the value reaches the exact `Options` the `Extractor` is constructed from. This mirrors the existing `test_configure_called_on_spewer`.

- [ ] **Step 1: Write the failing tests**

Add to `IndexTaskTest.java`. Add these imports at the top of the file:

```java
import org.icij.datashare.test.LogbackAppenderWrapper;
import org.slf4j.event.Level;
import static org.icij.datashare.cli.DatashareCliOptions.PARSE_TIMEOUT_OPT;
```

Then add the tests:

```java
    @Test
    public void test_parse_timeout_value_reaches_extractor_options() throws Exception {
        ElasticsearchSpewer spewer = mock(ElasticsearchSpewer.class);
        Mockito.when(spewer.configure(Mockito.any())).thenReturn(spewer);

        new IndexTask(spewer, mock(DocumentCollectionFactory.class),
                new Task<>(IndexTask.class.getName(), nullUser(),
                        Map.of(PARSE_TIMEOUT_OPT, "48h", "queueName", "test:queue")), null);

        ArgumentCaptor<Options> captor = ArgumentCaptor.forClass(Options.class);
        verify(spewer).configure(captor.capture());
        Option<String> option = new Option<>(PARSE_TIMEOUT_OPT, StringOptionParser::new).update("48h");
        assertThat(captor.getValue()).contains(option);
    }

    @Test
    public void test_warns_when_parse_timeout_disabled() throws Exception {
        LogbackAppenderWrapper logWrapper = new LogbackAppenderWrapper();
        ElasticsearchSpewer spewer = mock(ElasticsearchSpewer.class);
        Mockito.when(spewer.configure(Mockito.any())).thenReturn(spewer);

        new IndexTask(spewer, mock(DocumentCollectionFactory.class),
                new Task<>(IndexTask.class.getName(), nullUser(),
                        Map.of(PARSE_TIMEOUT_OPT, "0", "queueName", "test:queue")), null);

        assertThat(logWrapper.logs(Level.WARN).stream()
                .anyMatch(l -> l.contains("parseTimeout") && l.contains("DISABLED"))).isTrue();
    }

    @Test
    public void test_does_not_warn_when_parse_timeout_enabled() throws Exception {
        LogbackAppenderWrapper logWrapper = new LogbackAppenderWrapper();
        ElasticsearchSpewer spewer = mock(ElasticsearchSpewer.class);
        Mockito.when(spewer.configure(Mockito.any())).thenReturn(spewer);

        new IndexTask(spewer, mock(DocumentCollectionFactory.class),
                new Task<>(IndexTask.class.getName(), nullUser(),
                        Map.of(PARSE_TIMEOUT_OPT, "48h", "queueName", "test:queue")), null);

        assertThat(logWrapper.logs(Level.WARN).stream()
                .anyMatch(l -> l.contains("parseTimeout"))).isFalse();
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -pl datashare-app -am test -Dtest=IndexTaskTest#test_parse_timeout_value_reaches_extractor_options+test_warns_when_parse_timeout_disabled+test_does_not_warn_when_parse_timeout_enabled -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL. The value-propagation test may already pass (existing plumbing carries the arg); the `test_warns_when_parse_timeout_disabled` test fails because no WARN is emitted yet.

- [ ] **Step 3: Add the warn-on-disabled logic**

In `IndexTask.java`, add imports:

```java
import java.time.Duration;
import java.time.format.DateTimeParseException;
import org.icij.time.HumanDuration;
```

In the constructor, after `indexTimeout = getIndexTimeout();`, add:

```java
        warnIfParseTimeoutDisabled();
```

Add the private method (place it near `getIndexTimeout()`):

```java
    private void warnIfParseTimeoutDisabled() {
        propertiesProvider.get(PARSE_TIMEOUT_OPT).ifPresent(value -> {
            try {
                Duration parseTimeout = HumanDuration.parse(value);
                if (parseTimeout.isZero() || parseTimeout.isNegative()) {
                    logger.warn("parseTimeout is set to {}: the parse timeout is DISABLED. " +
                            "A pathological document can hang a worker indefinitely.", value);
                }
            } catch (DateTimeParseException e) {
                // Leave duration validation to the extractor; do not fail task construction here.
            }
        });
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -pl datashare-app -am test -Dtest=IndexTaskTest#test_parse_timeout_value_reaches_extractor_options+test_warns_when_parse_timeout_disabled+test_does_not_warn_when_parse_timeout_enabled -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (all three).

- [ ] **Step 5: Run the full IndexTaskTest to check for regressions**

Run: `mvn -pl datashare-app -am test -Dtest=IndexTaskTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (all tests in the class).

- [ ] **Step 6: Commit**

```bash
git add datashare-app/src/main/java/org/icij/datashare/tasks/IndexTask.java \
        datashare-app/src/test/java/org/icij/datashare/tasks/IndexTaskTest.java
git commit -m "feat(index): warn when parseTimeout is disabled and cover value propagation"
```

---

## Self-Review

**Spec coverage:**
- Dedicated flag on the JOpt surface → Task 1. ✓
- Dedicated flag on the picocli surface → Task 2. ✓
- `.defaultsTo("24h")` / `defaultValue` → Tasks 1 & 2 (default tests included). ✓
- Unvalidated String → Tasks 1 & 2 use `String` with no converter. ✓
- Property key exactly `parseTimeout` → `PARSE_TIMEOUT_OPT = "parseTimeout"` (Task 1), reused everywhere. ✓
- Value propagates to the Extractor for all entry points → verified via `spewer.configure` captor in Task 3; API/UI use the same task-args path (no code change needed). ✓
- Warn on 0/disabled, consistent across entry points (placed in `IndexTask`) → Task 3. ✓
- Help/description text → set in Tasks 1 & 2. ✓
- Out of scope (extract-lib, docs file, API/UI schema) → honored; no such tasks. ✓

**Placeholder scan:** No TBDs; every code step shows complete code and exact commands. ✓

**Type consistency:** `PARSE_TIMEOUT_OPT`/`DEFAULT_PARSE_TIMEOUT` defined in Task 1 and consumed by Tasks 2 & 3 with matching names; `parseTimeout(OptionParser)` returns `void` like sibling methods; picocli field typed `String`; `HumanDuration.parse` returns `java.time.Duration`. ✓

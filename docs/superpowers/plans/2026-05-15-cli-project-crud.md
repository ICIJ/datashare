# CLI project CRUD Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `datashare project create` and `datashare project delete` CLI commands per [issue #2122](https://github.com/ICIJ/datashare/issues/2122).

**Architecture:** Three layers, mirroring the merged user-CRUD work: picocli leaf commands in `datashare-cli` (parse + prompt for the project name), a `ProjectAdminService` in `datashare-app` that owns the full create / delete cascade (DB row + ES index + queues + report map + artifact dir), and two thin `if` blocks in `CliApp.runTaskWorker` that resolve the service from Guice and translate exceptions to exit codes. The `delete` dispatcher additionally queries `service.stats(name)` for index size + member count and runs a typed-name confirmation via `Prompter` before calling `service.delete(...)`.

**Tech Stack:** Java 21, picocli (CLI), Guice (DI), jOOQ (DB), JUnit 4, FEST assertions, Mockito. Build via `make` / Maven.

**Spec:** `docs/superpowers/specs/2026-05-15-cli-project-crud-design.md`.

---

## Translation: spec types → concrete paths

The spec is the conceptual contract; the merged user-CRUD code shows the actual conventions used in this repo. Where they differ, this plan follows the conventions.

| Spec name | Concrete location |
|-----------|-------------------|
| `cli.command.project.ProjectCommand` | `cli.command.ProjectCommand` (flat, like `UserCommand`) |
| `cli.command.project.ProjectCreateCommand` | `cli.command.ProjectCreateCommand` |
| `cli.command.project.ProjectDeleteCommand` | `cli.command.ProjectDeleteCommand` |
| `project.ProjectAdminService` | `project.admin.ProjectAdminService` (parallels `user.admin.UserAdminService`) |
| `project.ProjectAdminServiceImpl` | `project.admin.ProjectAdminServiceImpl` |
| `project.ProjectCreateRequest` etc. | `project.admin.ProjectCreateRequest` etc. |
| `ValidationException` (reused) | New `project.admin.ValidationException`. The user-CRUD ValidationException doc-comment explicitly says: "do not deduplicate the two; that would create a circular module dependency." We mirror the pattern: one ValidationException per admin service. |
| "service takes `--if-not-exists` flag" | Two service methods: `create()` (throws `ProjectExistsException`) and `createIfNotExists()` (returns `noop=true`). Mirrors `UserAdminService.createIfNotExists`. |
| "service takes `--if-exists` flag" | Two service methods: `delete()` (throws `ProjectNotFoundException`) and `deleteIfExists()` (returns `false` on missing). Mirrors `UserAdminService.deleteIfExists`. |
| "Confirmation in CLI" (`project delete`) | Confirmation is in the **dispatcher** (`CliApp.handleProjectDelete`). The CLI module has no DI access to the service, but needs `service.stats(name)` to render the prompt. Dispatcher uses `Prompter` (from `datashare-cli`, which `datashare-app` already imports). The CLI command still prompts for the missing `name` field if needed. |

## File Structure

### Created

| File | Responsibility |
|------|----------------|
| `datashare-app/src/main/java/org/icij/datashare/project/admin/ProjectAdminService.java` | Service interface: `create`, `createIfNotExists`, `stats`, `delete`, `deleteIfExists` |
| `datashare-app/src/main/java/org/icij/datashare/project/admin/ProjectAdminServiceImpl.java` | Default impl — drives Repository, Indexer, Authorizer, DocumentCollectionFactory, PropertiesProvider |
| `datashare-app/src/main/java/org/icij/datashare/project/admin/ProjectCreateRequest.java` | Immutable record for `create` input |
| `datashare-app/src/main/java/org/icij/datashare/project/admin/ProjectCreated.java` | Immutable record for `create` result |
| `datashare-app/src/main/java/org/icij/datashare/project/admin/ProjectStats.java` | Immutable record for `stats` result |
| `datashare-app/src/main/java/org/icij/datashare/project/admin/ProjectDeleteOptions.java` | Immutable record for `delete` options |
| `datashare-app/src/main/java/org/icij/datashare/project/admin/ProjectDeleted.java` | Immutable record for `delete` result |
| `datashare-app/src/main/java/org/icij/datashare/project/admin/ProjectExistsException.java` | Checked exception → exit 4 |
| `datashare-app/src/main/java/org/icij/datashare/project/admin/ProjectNotFoundException.java` | Checked exception → exit 3 |
| `datashare-app/src/main/java/org/icij/datashare/project/admin/ValidationException.java` | Checked exception → exit 5 (mirrors user-admin's local copy) |
| `datashare-cli/src/main/java/org/icij/datashare/cli/command/ProjectCommand.java` | Picocli `project` group; prints usage and exits 2 when no subcommand |
| `datashare-cli/src/main/java/org/icij/datashare/cli/command/ProjectCreateCommand.java` | Picocli `project create` leaf |
| `datashare-cli/src/main/java/org/icij/datashare/cli/command/ProjectDeleteCommand.java` | Picocli `project delete` leaf |
| `datashare-app/src/test/java/org/icij/datashare/project/admin/ProjectAdminServiceImplTest.java` | Service unit tests |
| `datashare-app/src/test/java/org/icij/datashare/CliAppProjectDispatchTest.java` | Dispatcher unit tests (create + delete handlers) |
| `datashare-index/src/test/java/org/icij/datashare/text/indexing/elasticsearch/ElasticsearchIndexerCountTest.java` | New `count()` integration test (or extend an existing ES test class — see Task 1) |

### Modified

| File | Change |
|------|--------|
| `datashare-api/src/main/java/org/icij/datashare/text/indexing/Indexer.java` | Add `long count(String indexName) throws IOException` |
| `datashare-index/src/main/java/org/icij/datashare/text/indexing/elasticsearch/ElasticsearchIndexer.java` | Implement `count()` via `search(...).limit(0).execute(); totalHits()` |
| `datashare-cli/src/main/java/org/icij/datashare/cli/DatashareCliOptions.java` | Add `PROJECT_CREATE_*` and `PROJECT_DELETE_*` constants (one per typed sibling key) |
| `datashare-cli/src/main/java/org/icij/datashare/cli/Validators.java` | Add `projectName`, `allowFromMask`, `uri` methods (throwing `InvalidValueException`) |
| `datashare-cli/src/main/java/org/icij/datashare/cli/command/DatashareCommand.java` | Register `ProjectCommand.class` in `subcommands` |
| `datashare-app/src/main/java/org/icij/datashare/CliApp.java` | Two new `if` blocks; `handleProjectCreate`, `handleProjectDelete` static helpers |
| `datashare-app/src/main/java/org/icij/datashare/mode/CommonMode.java` | Bind `ProjectAdminService` to `ProjectAdminServiceImpl` (singleton) |
| `datashare-cli/src/test/java/org/icij/datashare/cli/ValidatorsTest.java` | Tests for the three new validators |
| `datashare-cli/src/test/java/org/icij/datashare/cli/command/DatashareCommandTest.java` | Tests for the new picocli surface |

---

## Task 1: `Indexer.count` — interface method and ES implementation

**Files:**
- Modify: `datashare-api/src/main/java/org/icij/datashare/text/indexing/Indexer.java` (interface; add right after `deleteAll` around line 27)
- Modify: `datashare-index/src/main/java/org/icij/datashare/text/indexing/elasticsearch/ElasticsearchIndexer.java`
- Test: locate the existing `ElasticsearchIndexer` test class first (likely `datashare-index/src/test/java/org/icij/datashare/text/indexing/elasticsearch/ElasticsearchIndexerTest.java`); extend it rather than creating a new file if it exists.

### Background

The spec lists `Indexer.count(String)` as a dependency. The interface today has `createIndex` and `deleteAll` but no `count`. The simplest implementation reuses the existing `Searcher.totalHits()` path (`datashare-index/.../ElasticsearchSearcher.java:194`): run a `limit(0)` search on `Document.class`, execute it, return `totalHits()`. No new dependencies.

### Steps

- [ ] **Step 1.1: Locate the existing ES indexer test class**

```bash
find datashare-index/src/test -name 'Elasticsearch*Test*.java' | head -5
```

Pick the class that already wires up a real ES connection (most likely named `ElasticsearchIndexerTest`). If none exists, create `ElasticsearchIndexerCountTest` next to `ElasticsearchIndexer.java`'s test peers.

- [ ] **Step 1.2: Write the failing count test**

Append (or create as new file) the test below. Adapt fixture setup to whatever helper the existing tests use to seed documents (`bulkAdd`, `add`, etc.):

```java
@Test
public void test_count_returns_zero_for_empty_index() throws IOException {
    indexer.createIndex("test-count-empty");
    assertThat(indexer.count("test-count-empty")).isEqualTo(0L);
    indexer.deleteAll("test-count-empty");
}

@Test
public void test_count_returns_document_count() throws IOException {
    indexer.createIndex("test-count-docs");
    Document d1 = DocumentBuilder.createDoc("id1").build();
    Document d2 = DocumentBuilder.createDoc("id2").build();
    indexer.bulkAdd("test-count-docs", List.of(d1, d2));
    // ES is near-real-time; refresh if the existing tests use a refresh helper
    refreshIndex("test-count-docs"); // adapt to whatever helper the suite uses
    assertThat(indexer.count("test-count-docs")).isEqualTo(2L);
    indexer.deleteAll("test-count-docs");
}
```

If the test class already has fixture helpers (`createIndex` / `refresh` / `seedDocs`), prefer those over inline calls. Do not invent new helpers.

- [ ] **Step 1.3: Run the test — expect compile failure**

```bash
mvn -pl datashare-index test -Dtest=ElasticsearchIndexerTest -DfailIfNoTests=false 2>&1 | tail -30
```

Expected: compile error "cannot find symbol: method count(String)".

- [ ] **Step 1.4: Add `count` to the Indexer interface**

In `datashare-api/src/main/java/org/icij/datashare/text/indexing/Indexer.java`, right after `boolean deleteAll(String indexName) throws IOException;` (around line 27):

```java
/**
 * Returns the number of documents indexed in {@code indexName}, or 0 if the index
 * is empty or does not exist. Implemented as a {@code limit(0)} search over
 * {@code Document.class}, so it includes only first-class documents (not
 * embedded children that share a root id).
 */
long count(String indexName) throws IOException;
```

- [ ] **Step 1.5: Implement `count` in `ElasticsearchIndexer`**

In `datashare-index/src/main/java/org/icij/datashare/text/indexing/elasticsearch/ElasticsearchIndexer.java`, add the method (place it near the existing `createIndex` / `deleteAll`):

```java
@Override
public long count(String indexName) throws IOException {
    if (!exists(indexName)) {
        return 0L;
    }
    Searcher searcher = search(List.of(indexName), Document.class).limit(0);
    try (Stream<? extends Entity> ignored = searcher.execute()) {
        // Stream is closed in try-with-resources so the underlying ES request is
        // released even though we never read the entities.
    }
    return searcher.totalHits();
}
```

If `Stream` is not `AutoCloseable` in this codebase's `Searcher.execute()` return, drop the try-with-resources and just call `searcher.execute()`; the goal is to populate `totalHits` on the searcher.

- [ ] **Step 1.6: Run the tests — expect pass**

```bash
mvn -pl datashare-index test -Dtest=ElasticsearchIndexerTest -DfailIfNoTests=false 2>&1 | tail -30
```

Expected: both new tests pass.

- [ ] **Step 1.7: Commit**

```bash
git add datashare-api/src/main/java/org/icij/datashare/text/indexing/Indexer.java \
        datashare-index/src/main/java/org/icij/datashare/text/indexing/elasticsearch/ElasticsearchIndexer.java \
        datashare-index/src/test/java/org/icij/datashare/text/indexing/elasticsearch/ElasticsearchIndexerTest.java
git commit -m "feat(indexer): add count(String indexName)"
```

---

## Task 2: CLI option constants

**Files:**
- Modify: `datashare-cli/src/main/java/org/icij/datashare/cli/DatashareCliOptions.java`

### Steps

- [ ] **Step 2.1: Add the constants**

In `DatashareCliOptions.java`, locate the existing `USER_CREATE_OPT` / `USER_DELETE_OPT` block (around lines 142–155) and add a parallel project block right after it:

```java
// Project admin CLI: keys consumed by CliApp.handleProjectCreate /
// handleProjectDelete. PROJECT_CREATE_OPT and PROJECT_DELETE_OPT carry the
// project name; the dotted siblings carry one typed field each.
public static final String PROJECT_CREATE_OPT = "projectCreate";
public static final String PROJECT_CREATE_LABEL_OPT = PROJECT_CREATE_OPT + ".label";
public static final String PROJECT_CREATE_DESCRIPTION_OPT = PROJECT_CREATE_OPT + ".description";
public static final String PROJECT_CREATE_SOURCE_PATH_OPT = PROJECT_CREATE_OPT + ".sourcePath";
public static final String PROJECT_CREATE_ALLOW_FROM_MASK_OPT = PROJECT_CREATE_OPT + ".allowFromMask";
public static final String PROJECT_CREATE_SOURCE_URL_OPT = PROJECT_CREATE_OPT + ".sourceUrl";
public static final String PROJECT_CREATE_MAINTAINER_NAME_OPT = PROJECT_CREATE_OPT + ".maintainerName";
public static final String PROJECT_CREATE_PUBLISHER_NAME_OPT = PROJECT_CREATE_OPT + ".publisherName";
public static final String PROJECT_CREATE_LOGO_URL_OPT = PROJECT_CREATE_OPT + ".logoUrl";
public static final String PROJECT_CREATE_NO_INDEX_OPT = PROJECT_CREATE_OPT + ".noIndex";
public static final String PROJECT_CREATE_IF_NOT_EXISTS_OPT = PROJECT_CREATE_OPT + ".ifNotExists";
public static final String PROJECT_CREATE_JSON_OPT = PROJECT_CREATE_OPT + ".json";

public static final String PROJECT_DELETE_OPT = "projectDelete";
public static final String PROJECT_DELETE_YES_OPT = PROJECT_DELETE_OPT + ".yes";
public static final String PROJECT_DELETE_KEEP_INDEX_OPT = PROJECT_DELETE_OPT + ".keepIndex";
public static final String PROJECT_DELETE_IF_EXISTS_OPT = PROJECT_DELETE_OPT + ".ifExists";
public static final String PROJECT_DELETE_NO_INPUT_OPT = PROJECT_DELETE_OPT + ".noInput";
public static final String PROJECT_DELETE_JSON_OPT = PROJECT_DELETE_OPT + ".json";
```

- [ ] **Step 2.2: Compile check**

```bash
mvn -pl datashare-cli compile 2>&1 | tail -10
```

Expected: BUILD SUCCESS. No tests are added for plain constants.

- [ ] **Step 2.3: Commit**

```bash
git add datashare-cli/src/main/java/org/icij/datashare/cli/DatashareCliOptions.java
git commit -m "feat(cli): add PROJECT_CREATE/DELETE option constants"
```

---

## Task 3: Validators — `projectName`, `allowFromMask`, `uri`

**Files:**
- Modify: `datashare-cli/src/main/java/org/icij/datashare/cli/Validators.java`
- Test: `datashare-cli/src/test/java/org/icij/datashare/cli/ValidatorsTest.java`

### Background

`Validators.java:24` already has a `PROJECT` regex used internally by `groups()`. We expose it as a top-level validator and add two more (`allowFromMask`, `uri`). All three throw `InvalidValueException` like the existing methods.

### Steps

- [ ] **Step 3.1: Write the failing tests**

Append to `ValidatorsTest.java` (the class already has helpers for the existing validators; mirror that style):

```java
@Test
public void test_project_name_accepts_valid_name() {
    Validators.projectName("foo");
    Validators.projectName("project-1");
    Validators.projectName("0abc");
}

@Test
public void test_project_name_rejects_invalid() {
    assertProjectNameInvalid(null);
    assertProjectNameInvalid("");
    assertProjectNameInvalid("-leading-dash");
    assertProjectNameInvalid("Has-Uppercase");
    assertProjectNameInvalid("has_underscore");
    assertProjectNameInvalid("a");                                // too short (1 char)
    assertProjectNameInvalid("a".repeat(65));                     // too long
}

private void assertProjectNameInvalid(String value) {
    try {
        Validators.projectName(value);
        fail("expected InvalidValueException for " + value);
    } catch (Validators.InvalidValueException e) {
        assertThat(e.field()).isEqualTo("projectName");
    }
}

@Test
public void test_allow_from_mask_accepts_valid() {
    Validators.allowFromMask("*.*.*.*");
    Validators.allowFromMask("10.0.0.0");
    Validators.allowFromMask("192.168.*.*");
}

@Test
public void test_allow_from_mask_rejects_invalid() {
    assertAllowFromMaskInvalid(null);
    assertAllowFromMaskInvalid("");
    assertAllowFromMaskInvalid("192.168");                        // too few octets
    assertAllowFromMaskInvalid("192.168.1.1.1");                  // too many
    assertAllowFromMaskInvalid("999.999.999.999");                // ok by regex; OK to accept per spec — drop this assertion if it passes
    assertAllowFromMaskInvalid("a.b.c.d");                        // non-digit non-star
}

private void assertAllowFromMaskInvalid(String value) {
    try {
        Validators.allowFromMask(value);
        fail("expected InvalidValueException for " + value);
    } catch (Validators.InvalidValueException e) {
        assertThat(e.field()).isEqualTo("allowFromMask");
    }
}

@Test
public void test_uri_accepts_https_and_http() {
    Validators.uri("https://example.org");
    Validators.uri("http://example.org/path?q=1");
}

@Test
public void test_uri_rejects_invalid() {
    assertUriInvalid(null);
    assertUriInvalid("");
    assertUriInvalid("not a uri");
    assertUriInvalid("/just/a/path");                              // no scheme
}

private void assertUriInvalid(String value) {
    try {
        Validators.uri(value);
        fail("expected InvalidValueException for " + value);
    } catch (Validators.InvalidValueException e) {
        assertThat(e.field()).isEqualTo("uri");
    }
}
```

Note on the `999.999.999.999` case: the spec's regex `^[\d*]{1,3}(\.[\d*]{1,3}){3}$` accepts it as a string but it isn't a valid IP. We do **not** validate per-octet numeric range; matching the spec verbatim is sufficient. If your test framework flags it, drop the assertion.

- [ ] **Step 3.2: Run tests — expect compile failure**

```bash
mvn -pl datashare-cli test -Dtest=ValidatorsTest 2>&1 | tail -20
```

Expected: "cannot find symbol: method projectName" etc.

- [ ] **Step 3.3: Implement `projectName`, `allowFromMask`, `uri`**

In `Validators.java`, add the methods (place them after `groups` so the regex constants stay grouped at the top):

```java
private static final Pattern ALLOW_FROM_MASK = Pattern.compile("^[\\d*]{1,3}(\\.[\\d*]{1,3}){3}$");

public static void projectName(String value) {
    if (value == null || !PROJECT.matcher(value).matches()) {
        throw new InvalidValueException("projectName",
                "project name must match ^[a-z0-9][a-z0-9-]{1,63}$");
    }
}

public static void allowFromMask(String value) {
    if (value == null || !ALLOW_FROM_MASK.matcher(value).matches()) {
        throw new InvalidValueException("allowFromMask",
                "allow-from-mask must match ^[\\d*]{1,3}(\\.[\\d*]{1,3}){3}$ (e.g. *.*.*.*)");
    }
}

public static void uri(String value) {
    if (value == null || value.isBlank()) {
        throw new InvalidValueException("uri", "uri is required");
    }
    try {
        URI parsed = URI.create(value);
        if (parsed.getScheme() == null) {
            throw new InvalidValueException("uri", "uri must include a scheme (e.g. https://)");
        }
    } catch (IllegalArgumentException e) {
        throw new InvalidValueException("uri", "uri is not a valid RFC 3986 URI: " + e.getMessage());
    }
}
```

Add `import java.net.URI;` near the existing imports.

- [ ] **Step 3.4: Run tests — expect pass**

```bash
mvn -pl datashare-cli test -Dtest=ValidatorsTest 2>&1 | tail -10
```

Expected: PASS.

- [ ] **Step 3.5: Commit**

```bash
git add datashare-cli/src/main/java/org/icij/datashare/cli/Validators.java \
        datashare-cli/src/test/java/org/icij/datashare/cli/ValidatorsTest.java
git commit -m "feat(cli): add projectName/allowFromMask/uri validators"
```

---

## Task 4: `project.admin` package — records and exceptions

**Files:**
- Create: `datashare-app/src/main/java/org/icij/datashare/project/admin/ProjectCreateRequest.java`
- Create: `datashare-app/src/main/java/org/icij/datashare/project/admin/ProjectCreated.java`
- Create: `datashare-app/src/main/java/org/icij/datashare/project/admin/ProjectStats.java`
- Create: `datashare-app/src/main/java/org/icij/datashare/project/admin/ProjectDeleteOptions.java`
- Create: `datashare-app/src/main/java/org/icij/datashare/project/admin/ProjectDeleted.java`
- Create: `datashare-app/src/main/java/org/icij/datashare/project/admin/ProjectExistsException.java`
- Create: `datashare-app/src/main/java/org/icij/datashare/project/admin/ProjectNotFoundException.java`
- Create: `datashare-app/src/main/java/org/icij/datashare/project/admin/ValidationException.java`

No tests for plain records / exception types — they're verified through the service tests in Task 5+.

### Steps

- [ ] **Step 4.1: `ProjectCreateRequest`**

```java
package org.icij.datashare.project.admin;

import java.nio.file.Path;

public record ProjectCreateRequest(
        String name,
        String label,
        String description,
        Path sourcePath,
        String allowFromMask,
        String sourceUrl,
        String maintainerName,
        String publisherName,
        String logoUrl,
        boolean createIndex
) {}
```

- [ ] **Step 4.2: `ProjectCreated`**

```java
package org.icij.datashare.project.admin;

import java.nio.file.Path;

public record ProjectCreated(
        String name,
        String label,
        String description,
        Path sourcePath,
        String allowFromMask,
        String sourceUrl,
        String maintainerName,
        String publisherName,
        String logoUrl,
        boolean indexCreated,
        boolean noop
) {}
```

- [ ] **Step 4.3: `ProjectStats`**

```java
package org.icij.datashare.project.admin;

public record ProjectStats(
        String name,
        long indexedDocuments,
        int memberCount
) {
    /** Sentinel meaning "index check skipped" (used when --keep-index is set). */
    public static final long INDEX_CHECK_SKIPPED = -1L;
}
```

- [ ] **Step 4.4: `ProjectDeleteOptions`**

```java
package org.icij.datashare.project.admin;

public record ProjectDeleteOptions(boolean keepIndex) {
    public static ProjectDeleteOptions defaults() {
        return new ProjectDeleteOptions(false);
    }
}
```

- [ ] **Step 4.5: `ProjectDeleted`**

```java
package org.icij.datashare.project.admin;

public record ProjectDeleted(
        String name,
        boolean dbDeleted,
        boolean indexDeleted,
        boolean queuesDeleted,
        boolean reportMapDeleted,
        boolean artifactsDeleted,
        boolean noop
) {}
```

- [ ] **Step 4.6: `ProjectExistsException`**

```java
package org.icij.datashare.project.admin;

public class ProjectExistsException extends Exception {
    public ProjectExistsException(String name) {
        super("project '" + name + "' already exists");
    }
}
```

- [ ] **Step 4.7: `ProjectNotFoundException`**

```java
package org.icij.datashare.project.admin;

public class ProjectNotFoundException extends Exception {
    public ProjectNotFoundException(String name) {
        super("project '" + name + "' not found");
    }
}
```

- [ ] **Step 4.8: `ValidationException`**

```java
package org.icij.datashare.project.admin;

/**
 * Service-side validation exception thrown by {@code ProjectAdminService}.
 *
 * <p>This intentionally duplicates {@code org.icij.datashare.user.admin.ValidationException}.
 * The {@code datashare-cli} module also has its own counterpart at
 * {@code org.icij.datashare.cli.Validators.InvalidValueException}. The three
 * exception types deliberately live in different modules to avoid a circular
 * dependency between {@code datashare-cli} and {@code datashare-app}.
 */
public class ValidationException extends Exception {
    private final String field;

    public ValidationException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() { return field; }
}
```

- [ ] **Step 4.9: Compile**

```bash
mvn -pl datashare-app compile 2>&1 | tail -10
```

Expected: BUILD SUCCESS.

- [ ] **Step 4.10: Commit**

```bash
git add datashare-app/src/main/java/org/icij/datashare/project/admin/
git commit -m "feat(project-admin): add records and exceptions"
```

---

## Task 5: `ProjectAdminService` interface

**Files:**
- Create: `datashare-app/src/main/java/org/icij/datashare/project/admin/ProjectAdminService.java`

### Steps

- [ ] **Step 5.1: Create the interface**

```java
package org.icij.datashare.project.admin;

import java.io.IOException;

public interface ProjectAdminService {

    /**
     * Creates a project row, optionally creates the ES index, throws if the
     * project already exists.
     */
    ProjectCreated create(ProjectCreateRequest request)
            throws ProjectExistsException, ValidationException, IOException;

    /**
     * Idempotent counterpart of {@link #create}: if the project already exists,
     * returns a {@code ProjectCreated} with {@code noop=true} populated from
     * the existing row.
     */
    ProjectCreated createIfNotExists(ProjectCreateRequest request)
            throws ValidationException, IOException;

    /**
     * Returns indexed-document count + member count for the named project.
     * Throws if the project row is missing. Used by the CLI confirmation prompt.
     */
    ProjectStats stats(String name) throws ProjectNotFoundException, IOException;

    /**
     * Deletes the project and every dependent resource (ES index unless
     * {@link ProjectDeleteOptions#keepIndex()}, queues, report map, artifact dir).
     * Throws if the project row is missing.
     */
    ProjectDeleted delete(String name, ProjectDeleteOptions options)
            throws ProjectNotFoundException, IOException;

    /**
     * Idempotent counterpart of {@link #delete}: returns a noop result when the
     * project is already missing.
     */
    ProjectDeleted deleteIfExists(String name, ProjectDeleteOptions options) throws IOException;
}
```

- [ ] **Step 5.2: Compile**

```bash
mvn -pl datashare-app compile 2>&1 | tail -5
```

- [ ] **Step 5.3: Commit**

```bash
git add datashare-app/src/main/java/org/icij/datashare/project/admin/ProjectAdminService.java
git commit -m "feat(project-admin): add service interface"
```

---

## Task 6: `ProjectAdminServiceImpl` — skeleton, validation, `create` happy path

**Files:**
- Create: `datashare-app/src/main/java/org/icij/datashare/project/admin/ProjectAdminServiceImpl.java`
- Test: `datashare-app/src/test/java/org/icij/datashare/project/admin/ProjectAdminServiceImplTest.java`

### Steps

- [ ] **Step 6.1: Write the failing tests**

```java
package org.icij.datashare.project.admin;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Repository;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.policies.Authorizer;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.Indexer;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.List;

import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ProjectAdminServiceImplTest {

    private Repository repository;
    private Indexer indexer;
    private Authorizer authorizer;
    private DocumentCollectionFactory<Path> documentCollectionFactory;
    private PropertiesProvider propertiesProvider;
    private ProjectAdminServiceImpl service;

    @Before
    public void setUp() {
        repository = mock(Repository.class);
        indexer = mock(Indexer.class);
        authorizer = mock(Authorizer.class);
        documentCollectionFactory = mock(DocumentCollectionFactory.class);
        propertiesProvider = mock(PropertiesProvider.class);
        service = new ProjectAdminServiceImpl(
                repository, indexer, authorizer, documentCollectionFactory, propertiesProvider);
    }

    private ProjectCreateRequest minimalRequest(String name) {
        return new ProjectCreateRequest(name, null, null, null, null, null, null, null, null, true);
    }

    @Test
    public void test_create_persists_project_with_supplied_fields() throws Exception {
        when(repository.getProject("my-project")).thenReturn(null);
        when(repository.save(any(Project.class))).thenReturn(true);

        ProjectCreated created = service.create(new ProjectCreateRequest(
                "my-project", "My Project", "leak archive",
                Path.of("/data/my"), "10.0.0.0",
                "https://src/", "Maint", "Pub", "https://logo.png",
                true));

        ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
        verify(repository).save(captor.capture());
        Project saved = captor.getValue();

        assertThat(saved.getName()).isEqualTo("my-project");
        assertThat(saved.getLabel()).isEqualTo("My Project");
        assertThat(saved.getDescription()).isEqualTo("leak archive");
        assertThat(saved.getSourcePath()).isEqualTo(Path.of("/data/my"));
        assertThat(saved.getAllowFromMask()).isEqualTo("10.0.0.0");

        verify(indexer).createIndex("my-project");

        assertThat(created.name()).isEqualTo("my-project");
        assertThat(created.indexCreated()).isTrue();
        assertThat(created.noop()).isFalse();
    }

    @Test
    public void test_create_defaults_label_to_name_and_path_to_vault() throws Exception {
        when(repository.getProject("foo")).thenReturn(null);
        when(repository.save(any(Project.class))).thenReturn(true);

        service.create(minimalRequest("foo"));

        ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getLabel()).isEqualTo("foo");
        assertThat(captor.getValue().getSourcePath()).isEqualTo(Path.of("/vault/foo"));
        assertThat(captor.getValue().getAllowFromMask()).isEqualTo("*.*.*.*");
    }

    @Test
    public void test_create_throws_when_project_exists() throws Exception {
        when(repository.getProject("foo")).thenReturn(new Project("foo"));

        try {
            service.create(minimalRequest("foo"));
            fail("expected ProjectExistsException");
        } catch (ProjectExistsException e) {
            assertThat(e.getMessage()).contains("foo");
        }
        verify(repository, never()).save(any(Project.class));
        verify(indexer, never()).createIndex(any());
    }

    @Test
    public void test_create_with_blank_name_throws_validation() throws Exception {
        try {
            service.create(minimalRequest(""));
            fail("expected ValidationException");
        } catch (ValidationException e) {
            assertThat(e.field()).isEqualTo("name");
        } catch (ProjectExistsException e) {
            fail("unexpected ProjectExistsException");
        }
        verify(repository, never()).save(any(Project.class));
    }

    @Test
    public void test_create_skips_index_when_createIndex_false() throws Exception {
        when(repository.getProject("foo")).thenReturn(null);
        when(repository.save(any(Project.class))).thenReturn(true);

        ProjectCreated created = service.create(new ProjectCreateRequest(
                "foo", null, null, null, null, null, null, null, null, false));

        verify(indexer, never()).createIndex(any());
        assertThat(created.indexCreated()).isFalse();
    }
}
```

- [ ] **Step 6.2: Run tests — expect compile failure**

```bash
mvn -pl datashare-app test -Dtest=ProjectAdminServiceImplTest 2>&1 | tail -20
```

Expected: "cannot find symbol: class ProjectAdminServiceImpl".

- [ ] **Step 6.3: Implement the skeleton + create**

`datashare-app/src/main/java/org/icij/datashare/project/admin/ProjectAdminServiceImpl.java`:

```java
package org.icij.datashare.project.admin;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Repository;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.policies.Authorizer;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.Indexer;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

@Singleton
public class ProjectAdminServiceImpl implements ProjectAdminService {

    private static final Pattern NAME = Pattern.compile("^[a-z0-9][a-z0-9-]{1,63}$");
    private static final String DEFAULT_ALLOW_FROM_MASK = "*.*.*.*";
    private static final Path DEFAULT_VAULT = Paths.get("/vault");

    private final Repository repository;
    private final Indexer indexer;
    private final Authorizer authorizer;
    private final DocumentCollectionFactory<Path> documentCollectionFactory;
    private final PropertiesProvider propertiesProvider;

    @Inject
    public ProjectAdminServiceImpl(Repository repository,
                                   Indexer indexer,
                                   Authorizer authorizer,
                                   DocumentCollectionFactory<Path> documentCollectionFactory,
                                   PropertiesProvider propertiesProvider) {
        this.repository = repository;
        this.indexer = indexer;
        this.authorizer = authorizer;
        this.documentCollectionFactory = documentCollectionFactory;
        this.propertiesProvider = propertiesProvider;
    }

    @Override
    public ProjectCreated create(ProjectCreateRequest request)
            throws ProjectExistsException, ValidationException, IOException {
        validate(request);
        if (repository.getProject(request.name()) != null) {
            throw new ProjectExistsException(request.name());
        }
        return persist(request);
    }

    @Override
    public ProjectCreated createIfNotExists(ProjectCreateRequest request)
            throws ValidationException, IOException {
        validate(request);
        Project existing = repository.getProject(request.name());
        if (existing != null) {
            return new ProjectCreated(
                    existing.getName(),
                    existing.getLabel(),
                    existing.getDescription(),
                    existing.getSourcePath(),
                    existing.getAllowFromMask(),
                    existing.getSourceUrl(),
                    existing.getMaintainerName(),
                    existing.getPublisherName(),
                    existing.getLogoUrl(),
                    false,
                    true);
        }
        return persist(request);
    }

    @Override
    public ProjectStats stats(String name) throws ProjectNotFoundException, IOException {
        throw new UnsupportedOperationException("implemented in Task 7");
    }

    @Override
    public ProjectDeleted delete(String name, ProjectDeleteOptions options)
            throws ProjectNotFoundException, IOException {
        throw new UnsupportedOperationException("implemented in Task 8");
    }

    @Override
    public ProjectDeleted deleteIfExists(String name, ProjectDeleteOptions options) throws IOException {
        throw new UnsupportedOperationException("implemented in Task 8");
    }

    private void validate(ProjectCreateRequest request) throws ValidationException {
        if (request.name() == null || !NAME.matcher(request.name()).matches()) {
            throw new ValidationException("name",
                    "project name must match ^[a-z0-9][a-z0-9-]{1,63}$");
        }
    }

    private ProjectCreated persist(ProjectCreateRequest request) throws IOException {
        String label = request.label() == null ? request.name() : request.label();
        Path sourcePath = request.sourcePath() == null
                ? DEFAULT_VAULT.resolve(request.name())
                : request.sourcePath();
        String allowFromMask = request.allowFromMask() == null
                ? DEFAULT_ALLOW_FROM_MASK
                : request.allowFromMask();

        Project project = new Project(
                request.name(),
                label,
                request.description(),
                sourcePath,
                request.sourceUrl(),
                request.maintainerName(),
                request.publisherName(),
                request.logoUrl(),
                allowFromMask,
                null,  // creationDate (null matches REST POST behavior)
                null   // updateDate
        );

        if (!repository.save(project)) {
            throw new IOException("repository.save(Project) returned false for " + request.name());
        }

        boolean indexCreated = false;
        if (request.createIndex()) {
            try {
                indexer.createIndex(request.name());
                indexCreated = true;
            } catch (RuntimeException | IOException e) {
                // Compensating delete: a DB row pointing at a missing index
                // is the worst end-state. Roll back, then rethrow.
                try {
                    repository.deleteAll(request.name());
                } catch (RuntimeException rollback) {
                    e.addSuppressed(rollback);
                }
                if (e instanceof IOException io) throw io;
                throw (RuntimeException) e;
            }
        }

        return new ProjectCreated(
                project.getName(),
                project.getLabel(),
                project.getDescription(),
                project.getSourcePath(),
                project.getAllowFromMask(),
                project.getSourceUrl(),
                project.getMaintainerName(),
                project.getPublisherName(),
                project.getLogoUrl(),
                indexCreated,
                false);
    }
}
```

- [ ] **Step 6.4: Run tests — expect pass**

```bash
mvn -pl datashare-app test -Dtest=ProjectAdminServiceImplTest 2>&1 | tail -10
```

Expected: 5 tests pass.

- [ ] **Step 6.5: Commit**

```bash
git add datashare-app/src/main/java/org/icij/datashare/project/admin/ProjectAdminServiceImpl.java \
        datashare-app/src/test/java/org/icij/datashare/project/admin/ProjectAdminServiceImplTest.java
git commit -m "feat(project-admin): implement create + createIfNotExists"
```

---

## Task 7: `ProjectAdminServiceImpl.create` — compensating delete + `createIfNotExists`

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/project/admin/ProjectAdminServiceImpl.java` (compensating-delete is already in Task 6; this task adds the missing tests + the `createIfNotExists` test surface)
- Modify: `datashare-app/src/test/java/org/icij/datashare/project/admin/ProjectAdminServiceImplTest.java`

### Steps

- [ ] **Step 7.1: Write the failing compensating-delete + idempotency tests**

Append to `ProjectAdminServiceImplTest.java`:

```java
@Test
public void test_create_compensates_db_when_index_creation_fails() throws Exception {
    when(repository.getProject("foo")).thenReturn(null);
    when(repository.save(any(Project.class))).thenReturn(true);
    when(indexer.createIndex("foo")).thenThrow(new IOException("ES down"));

    try {
        service.create(minimalRequest("foo"));
        fail("expected IOException");
    } catch (IOException e) {
        assertThat(e.getMessage()).contains("ES down");
    }

    org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(repository, indexer);
    inOrder.verify(repository).save(any(Project.class));
    inOrder.verify(indexer).createIndex("foo");
    inOrder.verify(repository).deleteAll("foo");
}

@Test
public void test_create_logs_suppressed_when_compensating_delete_also_fails() throws Exception {
    when(repository.getProject("foo")).thenReturn(null);
    when(repository.save(any(Project.class))).thenReturn(true);
    when(indexer.createIndex("foo")).thenThrow(new IOException("ES down"));
    when(repository.deleteAll("foo")).thenThrow(new RuntimeException("rollback boom"));

    try {
        service.create(minimalRequest("foo"));
        fail("expected IOException");
    } catch (IOException e) {
        assertThat(e.getMessage()).contains("ES down");
        Throwable[] suppressed = e.getSuppressed();
        assertThat(suppressed).hasSize(1);
        assertThat(suppressed[0].getMessage()).contains("rollback boom");
    }
}

@Test
public void test_create_if_not_exists_returns_noop_when_project_exists() throws Exception {
    Project existing = new Project("foo", "Existing", "existing desc",
            Path.of("/old/foo"), "https://old/", null, null, null, "*.*.*.*", null, null);
    when(repository.getProject("foo")).thenReturn(existing);

    ProjectCreated created = service.createIfNotExists(minimalRequest("foo"));

    assertThat(created.noop()).isTrue();
    assertThat(created.indexCreated()).isFalse();
    assertThat(created.name()).isEqualTo("foo");
    assertThat(created.label()).isEqualTo("Existing");
    assertThat(created.description()).isEqualTo("existing desc");
    assertThat(created.sourcePath()).isEqualTo(Path.of("/old/foo"));
    verify(repository, never()).save(any(Project.class));
    verify(indexer, never()).createIndex(any());
}

@Test
public void test_create_if_not_exists_persists_when_project_missing() throws Exception {
    when(repository.getProject("foo")).thenReturn(null);
    when(repository.save(any(Project.class))).thenReturn(true);

    ProjectCreated created = service.createIfNotExists(minimalRequest("foo"));

    verify(repository).save(any(Project.class));
    verify(indexer).createIndex("foo");
    assertThat(created.noop()).isFalse();
    assertThat(created.indexCreated()).isTrue();
}
```

- [ ] **Step 7.2: Run tests — expect pass**

```bash
mvn -pl datashare-app test -Dtest=ProjectAdminServiceImplTest 2>&1 | tail -10
```

The compensating-delete code from Task 6 should already make these pass. If anything fails, debug rather than rewriting.

- [ ] **Step 7.3: Commit**

```bash
git add datashare-app/src/test/java/org/icij/datashare/project/admin/ProjectAdminServiceImplTest.java
git commit -m "test(project-admin): cover create compensating-delete + createIfNotExists"
```

---

## Task 8: `ProjectAdminServiceImpl.stats`

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/project/admin/ProjectAdminServiceImpl.java`
- Modify: `datashare-app/src/test/java/org/icij/datashare/project/admin/ProjectAdminServiceImplTest.java`

### Background

`stats(name)` returns indexed-document count and distinct member count. The member count comes from `Authorizer.getGroupPermissions(Domain, String)` — a list of `CasbinRule` rows scoped to a project; distinct user ids live in column V0.

### Steps

- [ ] **Step 8.1: Write the failing tests**

Append to `ProjectAdminServiceImplTest.java`:

```java
import org.icij.datashare.policies.CasbinRule;
import org.icij.datashare.policies.Domain;

@Test
public void test_stats_returns_index_count_and_distinct_member_count() throws Exception {
    when(repository.getProject("foo")).thenReturn(new Project("foo"));
    when(indexer.count("foo")).thenReturn(42L);
    when(authorizer.getGroupPermissions(any(Domain.class), eq("foo")))
            .thenReturn(List.of(
                    casbinRule("alice", "PROJECT_ADMIN", "datashare::foo"),
                    casbinRule("bob",   "PROJECT_MEMBER", "datashare::foo"),
                    casbinRule("alice", "PROJECT_VISITOR", "datashare::foo")  // duplicate user
            ));

    ProjectStats stats = service.stats("foo");

    assertThat(stats.name()).isEqualTo("foo");
    assertThat(stats.indexedDocuments()).isEqualTo(42L);
    assertThat(stats.memberCount()).isEqualTo(2);  // alice + bob, deduped
}

@Test
public void test_stats_throws_when_project_missing() throws Exception {
    when(repository.getProject("ghost")).thenReturn(null);
    try {
        service.stats("ghost");
        fail("expected ProjectNotFoundException");
    } catch (ProjectNotFoundException e) {
        assertThat(e.getMessage()).contains("ghost");
    }
    verify(indexer, never()).count(any());
}

private static CasbinRule casbinRule(String userId, String role, String domainProject) {
    return CasbinRule.fromArray(List.of("g", userId, role, domainProject));
}
```

Add the corresponding import: `import static org.mockito.ArgumentMatchers.eq;`

If `CasbinRule.fromArray(List<String>)` is not the actual factory in this codebase, check `Authorizer.java:78` — it shows `streamToCasbinRule` calls `CasbinRule.fromArray(Stream<...>.collect(toList()))`. Use whichever factory is publicly accessible. If none is, construct a `CasbinRule` via its public constructor (look at `datashare-app/src/main/java/org/icij/datashare/policies/CasbinRule.java`) and adapt.

- [ ] **Step 8.2: Run tests — expect failure (UnsupportedOperationException from the Task 6 stub)**

```bash
mvn -pl datashare-app test -Dtest=ProjectAdminServiceImplTest#test_stats_returns_index_count_and_distinct_member_count 2>&1 | tail -10
```

- [ ] **Step 8.3: Implement `stats`**

Replace the `stats` stub in `ProjectAdminServiceImpl.java`:

```java
@Override
public ProjectStats stats(String name) throws ProjectNotFoundException, IOException {
    if (repository.getProject(name) == null) {
        throw new ProjectNotFoundException(name);
    }
    long indexedDocuments = indexer.count(name);
    int memberCount = (int) authorizer
            .getGroupPermissions(Domain.of("datashare"), name)
            .stream()
            .map(rule -> rule.v0)  // CasbinRule subject column; verify field name
            .distinct()
            .count();
    return new ProjectStats(name, indexedDocuments, memberCount);
}
```

Add `import org.icij.datashare.policies.Domain;`.

If `CasbinRule.v0` is a method (e.g. `getV0()`) rather than a public field, adapt — `Authorizer.java` access patterns are the source of truth. The `domainProject` slot is `v2` in casbin's default model (subject=v0, role=v1, domain::project=v2).

- [ ] **Step 8.4: Run tests — expect pass**

```bash
mvn -pl datashare-app test -Dtest=ProjectAdminServiceImplTest 2>&1 | tail -10
```

- [ ] **Step 8.5: Commit**

```bash
git add datashare-app/src/main/java/org/icij/datashare/project/admin/ProjectAdminServiceImpl.java \
        datashare-app/src/test/java/org/icij/datashare/project/admin/ProjectAdminServiceImplTest.java
git commit -m "feat(project-admin): implement stats"
```

---

## Task 9: `ProjectAdminServiceImpl.delete` + `deleteIfExists` — full cascade

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/project/admin/ProjectAdminServiceImpl.java`
- Modify: `datashare-app/src/test/java/org/icij/datashare/project/admin/ProjectAdminServiceImplTest.java`

### Background

The cascade mirrors `ProjectResource.projectDelete` (`datashare-app/.../web/ProjectResource.java:172-194`):

1. ES index (skip if `keepIndex=true`)
2. DB rows (`repository.deleteAll(name)`)
3. Document queues (`deleteQueues(project)`)
4. Report map (`deleteReportMap(project)`)
5. Artifact dir (from `PropertiesProvider.get(ARTIFACT_DIR_OPT)`)

Failures bubble up; the only step wrapped in try/catch is the artifact-dir delete (matching the REST pattern). Each step records its boolean outcome on `ProjectDeleted`.

### Steps

- [ ] **Step 9.1: Write the failing tests**

```java
import org.icij.extract.queue.DocumentQueue;
import org.icij.extract.report.ReportMap;
import java.util.Map;

@Test
public void test_delete_runs_full_cascade_in_order() throws Exception {
    Project project = new Project("foo");
    when(repository.getProject("foo")).thenReturn(project);
    when(repository.deleteAll("foo")).thenReturn(true);
    when(indexer.deleteAll("foo")).thenReturn(true);
    DocumentQueue<Path> queue = mock(DocumentQueue.class);
    when(queue.delete()).thenReturn(true);
    when(documentCollectionFactory.getQueues(any(), eq(Path.class))).thenReturn(List.of(queue));
    ReportMap reportMap = mock(ReportMap.class);
    when(reportMap.delete()).thenReturn(true);
    when(documentCollectionFactory.createMap(any())).thenReturn(reportMap);
    when(propertiesProvider.get(any())).thenReturn(java.util.Optional.empty()); // no artifact dir configured

    ProjectDeleted deleted = service.delete("foo", new ProjectDeleteOptions(false));

    org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(indexer, repository, queue, reportMap);
    inOrder.verify(indexer).deleteAll("foo");
    inOrder.verify(repository).deleteAll("foo");
    inOrder.verify(queue).delete();
    inOrder.verify(reportMap).delete();

    assertThat(deleted.name()).isEqualTo("foo");
    assertThat(deleted.indexDeleted()).isTrue();
    assertThat(deleted.dbDeleted()).isTrue();
    assertThat(deleted.queuesDeleted()).isTrue();
    assertThat(deleted.reportMapDeleted()).isTrue();
    assertThat(deleted.artifactsDeleted()).isFalse(); // no artifact dir configured
    assertThat(deleted.noop()).isFalse();
}

@Test
public void test_delete_skips_index_when_keepIndex_true() throws Exception {
    when(repository.getProject("foo")).thenReturn(new Project("foo"));
    when(repository.deleteAll("foo")).thenReturn(true);
    when(documentCollectionFactory.getQueues(any(), eq(Path.class))).thenReturn(List.of());
    ReportMap reportMap = mock(ReportMap.class);
    when(reportMap.delete()).thenReturn(true);
    when(documentCollectionFactory.createMap(any())).thenReturn(reportMap);
    when(propertiesProvider.get(any())).thenReturn(java.util.Optional.empty());

    ProjectDeleted deleted = service.delete("foo", new ProjectDeleteOptions(true));

    verify(indexer, never()).deleteAll(any());
    assertThat(deleted.indexDeleted()).isFalse();
    assertThat(deleted.dbDeleted()).isTrue();
}

@Test
public void test_delete_throws_when_project_missing() throws Exception {
    when(repository.getProject("ghost")).thenReturn(null);
    try {
        service.delete("ghost", ProjectDeleteOptions.defaults());
        fail("expected ProjectNotFoundException");
    } catch (ProjectNotFoundException e) {
        assertThat(e.getMessage()).contains("ghost");
    }
    verify(indexer, never()).deleteAll(any());
    verify(repository, never()).deleteAll(any());
}

@Test
public void test_delete_if_exists_returns_noop_when_project_missing() throws Exception {
    when(repository.getProject("ghost")).thenReturn(null);

    ProjectDeleted deleted = service.deleteIfExists("ghost", ProjectDeleteOptions.defaults());

    assertThat(deleted.noop()).isTrue();
    assertThat(deleted.dbDeleted()).isFalse();
    assertThat(deleted.indexDeleted()).isFalse();
    verify(indexer, never()).deleteAll(any());
    verify(repository, never()).deleteAll(any());
}

@Test
public void test_delete_aborts_when_db_delete_fails() throws Exception {
    when(repository.getProject("foo")).thenReturn(new Project("foo"));
    when(indexer.deleteAll("foo")).thenReturn(true);
    when(repository.deleteAll("foo")).thenThrow(new RuntimeException("DB down"));

    try {
        service.delete("foo", ProjectDeleteOptions.defaults());
        fail("expected RuntimeException");
    } catch (RuntimeException e) {
        assertThat(e.getMessage()).contains("DB down");
    }
    // Cascade aborts: queues, report-map, artifacts must NOT run.
    verify(documentCollectionFactory, never()).getQueues(any(), any());
    verify(documentCollectionFactory, never()).createMap(any());
}
```

- [ ] **Step 9.2: Run tests — expect failure (`UnsupportedOperationException`)**

- [ ] **Step 9.3: Implement `delete` and `deleteIfExists`**

In `ProjectAdminServiceImpl.java`, replace the two stubbed methods:

```java
@Override
public ProjectDeleted delete(String name, ProjectDeleteOptions options)
        throws ProjectNotFoundException, IOException {
    Project project = repository.getProject(name);
    if (project == null) {
        throw new ProjectNotFoundException(name);
    }
    return cascade(project, options);
}

@Override
public ProjectDeleted deleteIfExists(String name, ProjectDeleteOptions options) throws IOException {
    Project project = repository.getProject(name);
    if (project == null) {
        return new ProjectDeleted(name, false, false, false, false, false, true);
    }
    return cascade(project, options);
}

private ProjectDeleted cascade(Project project, ProjectDeleteOptions options) throws IOException {
    String name = project.getName();

    boolean indexDeleted = false;
    if (!options.keepIndex()) {
        indexDeleted = indexer.deleteAll(name);
    }

    boolean dbDeleted = repository.deleteAll(name);

    boolean queuesDeleted = deleteQueues(project);
    boolean reportMapDeleted = deleteReportMap(project);
    boolean artifactsDeleted = deleteArtifacts(name);

    return new ProjectDeleted(name, dbDeleted, indexDeleted,
            queuesDeleted, reportMapDeleted, artifactsDeleted, false);
}

private boolean deleteQueues(Project project) {
    String name = project.getName();
    Properties properties = propertiesProvider.createOverriddenWith(
            java.util.Map.of(org.icij.datashare.PropertiesProvider.DEFAULT_PROJECT_OPT, name));
    String defaultQueueName = properties.getOrDefault(
            org.icij.datashare.PropertiesProvider.QUEUE_NAME_OPT, "extract:queue").toString();
    String queuePrefix = defaultQueueName + org.icij.datashare.PropertiesProvider.QUEUE_SEPARATOR + name;
    String queuePattern = queuePrefix + org.icij.datashare.PropertiesProvider.QUEUE_SEPARATOR + "*";
    return java.util.stream.Stream.concat(
                    documentCollectionFactory.getQueues(queuePrefix, Path.class).stream(),
                    documentCollectionFactory.getQueues(queuePattern, Path.class).stream())
            .allMatch(org.icij.extract.queue.DocumentQueue::delete);
}

private boolean deleteReportMap(Project project) {
    String reportMapName = "extract:report:" + project.getName();
    return documentCollectionFactory.createMap(reportMapName).delete();
}

private boolean deleteArtifacts(String name) {
    return propertiesProvider.get(org.icij.datashare.cli.DatashareCliOptions.ARTIFACT_DIR_OPT)
            .map(dir -> {
                try {
                    java.io.File projectArtifactDir = Path.of(dir).resolve(name).toFile();
                    org.apache.commons.io.FileUtils.deleteDirectory(projectArtifactDir);
                    return true;
                } catch (IOException e) {
                    LoggerFactory.getLogger(ProjectAdminServiceImpl.class)
                            .error("cannot delete project {} artifact dir", name, e);
                    return false;
                }
            })
            .orElse(false);
}
```

Add imports:

```java
import java.util.Properties;
import org.slf4j.LoggerFactory;
```

(The other types are referenced fully-qualified above to keep the diff legible. Refactor to imports if desired, but don't slip in unrelated reformatting.)

- [ ] **Step 9.4: Run tests — expect pass**

```bash
mvn -pl datashare-app test -Dtest=ProjectAdminServiceImplTest 2>&1 | tail -10
```

- [ ] **Step 9.5: Commit**

```bash
git add datashare-app/src/main/java/org/icij/datashare/project/admin/ProjectAdminServiceImpl.java \
        datashare-app/src/test/java/org/icij/datashare/project/admin/ProjectAdminServiceImplTest.java
git commit -m "feat(project-admin): implement delete cascade + deleteIfExists"
```

---

## Task 10: Bind `ProjectAdminService` in `CommonMode`

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/mode/CommonMode.java` (around line 386, where `UserAdminService` is bound)

### Steps

- [ ] **Step 10.1: Add the binding**

In `CommonMode.java`, locate:

```java
bind(UserAdminService.class).to(UserAdminServiceImpl.class).in(Singleton.class);
```

Add directly below:

```java
bind(ProjectAdminService.class).to(ProjectAdminServiceImpl.class).in(Singleton.class);
```

Add the corresponding imports near the existing `UserAdminService` import:

```java
import org.icij.datashare.project.admin.ProjectAdminService;
import org.icij.datashare.project.admin.ProjectAdminServiceImpl;
```

- [ ] **Step 10.2: Compile**

```bash
mvn -pl datashare-app compile 2>&1 | tail -5
```

- [ ] **Step 10.3: Commit**

```bash
git add datashare-app/src/main/java/org/icij/datashare/mode/CommonMode.java
git commit -m "feat(mode): bind ProjectAdminService"
```

---

## Task 11: `ProjectCreateCommand`

**Files:**
- Create: `datashare-cli/src/main/java/org/icij/datashare/cli/command/ProjectCreateCommand.java`
- Test: extend `datashare-cli/src/test/java/org/icij/datashare/cli/command/DatashareCommandTest.java`

### Background

Mirrors `UserCreateCommand` structurally. Only `<name>` is ever prompted (everything else is optional with service-side defaults).

### Steps

- [ ] **Step 11.1: Write the failing CLI parsing tests**

Append to `DatashareCommandTest.java` (the file already has a `parse(...)` helper that builds the picocli graph; use it):

```java
@Test
public void test_project_create_minimal_emits_name() {
    Properties props = parse("project", "create", "my-project");
    assertThat(props).includes(entry("projectCreate", "my-project"));
}

@Test
public void test_project_create_all_flags_propagate_as_sibling_keys() {
    Properties props = parse("project", "create", "my-project",
            "--label", "My Project",
            "--description", "leak archive",
            "--source-path", "/data/my-project",
            "--allow-from-mask", "10.0.0.0",
            "--source-url", "https://src/",
            "--maintainer-name", "Maint",
            "--publisher-name", "Pub",
            "--logo-url", "https://logo.png",
            "--no-index",
            "--if-not-exists",
            "--json");

    assertThat(props).includes(entry("projectCreate", "my-project"));
    assertThat(props).includes(entry("projectCreate.label", "My Project"));
    assertThat(props).includes(entry("projectCreate.description", "leak archive"));
    assertThat(props).includes(entry("projectCreate.sourcePath", "/data/my-project"));
    assertThat(props).includes(entry("projectCreate.allowFromMask", "10.0.0.0"));
    assertThat(props).includes(entry("projectCreate.sourceUrl", "https://src/"));
    assertThat(props).includes(entry("projectCreate.maintainerName", "Maint"));
    assertThat(props).includes(entry("projectCreate.publisherName", "Pub"));
    assertThat(props).includes(entry("projectCreate.logoUrl", "https://logo.png"));
    assertThat(props).includes(entry("projectCreate.noIndex", "true"));
    assertThat(props).includes(entry("projectCreate.ifNotExists", "true"));
    assertThat(props).includes(entry("projectCreate.json", "true"));
}

@Test
public void test_project_create_invalid_name_exits_5() {
    assertExitCode(5, "project", "create", "Has-Uppercase");
}

@Test
public void test_project_create_invalid_allow_from_mask_exits_5() {
    assertExitCode(5, "project", "create", "my-project", "--allow-from-mask", "not-a-mask");
}

@Test
public void test_project_create_invalid_source_url_exits_5() {
    assertExitCode(5, "project", "create", "my-project", "--source-url", "not a uri");
}
```

If the test file does not yet have an `assertExitCode(int expected, String... args)` helper, look at how the user-CRUD tests assert exit codes (they call `CliExitException`-catching code) and use the same pattern. Search the file for "exitCode" or "CliExitException".

- [ ] **Step 11.2: Run tests — expect compile or runtime failure (`project` subcommand unknown)**

```bash
mvn -pl datashare-cli test -Dtest=DatashareCommandTest 2>&1 | tail -10
```

Expected: failures like "Unmatched argument at index 0: 'project'".

- [ ] **Step 11.3: Implement `ProjectCreateCommand`**

```java
package org.icij.datashare.cli.command;

import org.icij.datashare.cli.CliExitException;
import org.icij.datashare.cli.Mode;
import org.icij.datashare.cli.Prompter;
import org.icij.datashare.cli.Validators;
import org.icij.datashare.cli.Validators.InvalidValueException;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.Properties;

import static org.icij.datashare.cli.DatashareCliOptions.MODE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_ALLOW_FROM_MASK_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_DESCRIPTION_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_IF_NOT_EXISTS_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_JSON_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_LABEL_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_LOGO_URL_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_MAINTAINER_NAME_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_NO_INDEX_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_PUBLISHER_NAME_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_SOURCE_PATH_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_SOURCE_URL_OPT;

@Command(name = "create", mixinStandardHelpOptions = true, description = {
        "Create a Datashare project.",
        "",
        "Examples:",
        "  datashare project create my-project",
        "  datashare project create my-project --label 'My Project' --description 'leak archive'",
        "  datashare project create my-project --source-path /data/my-project --allow-from-mask 10.0.0.0",
        "  datashare project create my-project --no-index --if-not-exists"
})
public class ProjectCreateCommand implements Runnable, DatashareSubcommand {

    @Parameters(index = "0", arity = "0..1", description = "Project name (positional)")
    String namePositional;

    @Option(names = "--name", description = "Project name (alternative to positional)")
    String nameFlag;

    @Option(names = "--label", description = "Display label (default: name)")
    String label;

    @Option(names = "--description", description = "Free-form description")
    String description;

    @Option(names = "--source-path", description = "Filesystem source path (default: /vault/<name>)")
    String sourcePath;

    @Option(names = "--allow-from-mask",
            description = "IP mask for download access (default: *.*.*.*)")
    String allowFromMask;

    @Option(names = "--source-url", description = "URL of the data origin")
    String sourceUrl;

    @Option(names = "--maintainer-name", description = "Maintainer display name")
    String maintainerName;

    @Option(names = "--publisher-name", description = "Publisher display name")
    String publisherName;

    @Option(names = "--logo-url", description = "URL to the project logo")
    String logoUrl;

    @Option(names = "--no-index", description = "Skip Elasticsearch index creation")
    boolean noIndex;

    @Option(names = "--if-not-exists", description = "Idempotent: exit 0 if project exists")
    boolean ifNotExists;

    @Option(names = "--no-input", description = "Disable interactive prompts")
    boolean noInput;

    @Option(names = "--json", description = "Emit JSON result on stdout")
    boolean json;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    Prompter prompterOverride;

    private String resolvedName;
    private boolean ready;

    @Override
    public void run() {
        String name = namePositional != null ? namePositional : nameFlag;
        try {
            if (name != null) Validators.projectName(name);
            if (allowFromMask != null) Validators.allowFromMask(allowFromMask);
            if (sourceUrl != null) Validators.uri(sourceUrl);
            if (logoUrl != null) Validators.uri(logoUrl);

            if (name == null) {
                if (noInput) {
                    spec.commandLine().getErr().println(
                            "error: --name is required when --no-input is set");
                    throw new CliExitException(2);
                }
                Prompter prompter = prompterOverride != null ? prompterOverride : new Prompter();
                if (prompterOverride == null && !prompter.isInteractive()) {
                    spec.commandLine().getErr().println(
                            "error: --name is required and no TTY available");
                    throw new CliExitException(2);
                }
                try {
                    name = prompter.promptString("Project name", Validators::projectName);
                } catch (Prompter.ValidationFailedException e) {
                    spec.commandLine().getErr().println("error: " + e.getMessage());
                    throw new CliExitException(5);
                }
            }

            this.resolvedName = name;
            this.ready = true;
        } catch (InvalidValueException e) {
            spec.commandLine().getErr().println("error: " + e.getMessage());
            throw new CliExitException(5);
        }
    }

    @Override
    public Properties getSubcommandProperties() {
        Properties props = new Properties();
        DatashareOptions.put(props, MODE_OPT, Mode.CLI);
        if (!ready) {
            return props;
        }
        DatashareOptions.put(props, PROJECT_CREATE_OPT, resolvedName);
        DatashareOptions.putIfNotNull(props, PROJECT_CREATE_LABEL_OPT, label);
        DatashareOptions.putIfNotNull(props, PROJECT_CREATE_DESCRIPTION_OPT, description);
        DatashareOptions.putIfNotNull(props, PROJECT_CREATE_SOURCE_PATH_OPT, sourcePath);
        DatashareOptions.putIfNotNull(props, PROJECT_CREATE_ALLOW_FROM_MASK_OPT, allowFromMask);
        DatashareOptions.putIfNotNull(props, PROJECT_CREATE_SOURCE_URL_OPT, sourceUrl);
        DatashareOptions.putIfNotNull(props, PROJECT_CREATE_MAINTAINER_NAME_OPT, maintainerName);
        DatashareOptions.putIfNotNull(props, PROJECT_CREATE_PUBLISHER_NAME_OPT, publisherName);
        DatashareOptions.putIfNotNull(props, PROJECT_CREATE_LOGO_URL_OPT, logoUrl);
        DatashareOptions.putIfTrue(props, PROJECT_CREATE_NO_INDEX_OPT, noIndex);
        DatashareOptions.putIfTrue(props, PROJECT_CREATE_IF_NOT_EXISTS_OPT, ifNotExists);
        DatashareOptions.putIfTrue(props, PROJECT_CREATE_JSON_OPT, json);
        return props;
    }
}
```

(The command isn't yet wired into the picocli tree — that happens in Task 13. Don't run the CLI tests yet.)

- [ ] **Step 11.4: Compile only**

```bash
mvn -pl datashare-cli compile 2>&1 | tail -5
```

Expected: BUILD SUCCESS. We commit at this point even though the tests still fail — wiring lands in Task 13.

- [ ] **Step 11.5: Commit**

```bash
git add datashare-cli/src/main/java/org/icij/datashare/cli/command/ProjectCreateCommand.java
git commit -m "feat(cli): add ProjectCreateCommand"
```

---

## Task 12: `ProjectDeleteCommand`

**Files:**
- Create: `datashare-cli/src/main/java/org/icij/datashare/cli/command/ProjectDeleteCommand.java`

### Background

`ProjectDeleteCommand` only prompts for `name` if missing. The typed-name confirmation lives in the **dispatcher** (Task 15) because the CLI module has no DI access to `ProjectAdminService.stats`. The command emits properties; dispatcher reads them, calls `stats`, prompts, then calls `delete`.

### Steps

- [ ] **Step 12.1: Write the failing CLI parsing tests**

Append to `DatashareCommandTest.java`:

```java
@Test
public void test_project_delete_minimal_emits_name() {
    Properties props = parse("project", "delete", "my-project");
    assertThat(props).includes(entry("projectDelete", "my-project"));
}

@Test
public void test_project_delete_all_flags_propagate() {
    Properties props = parse("project", "delete", "my-project",
            "--yes", "--keep-index", "--if-exists", "--no-input", "--json");
    assertThat(props).includes(entry("projectDelete", "my-project"));
    assertThat(props).includes(entry("projectDelete.yes", "true"));
    assertThat(props).includes(entry("projectDelete.keepIndex", "true"));
    assertThat(props).includes(entry("projectDelete.ifExists", "true"));
    assertThat(props).includes(entry("projectDelete.noInput", "true"));
    assertThat(props).includes(entry("projectDelete.json", "true"));
}

@Test
public void test_project_delete_invalid_name_exits_5() {
    assertExitCode(5, "project", "delete", "Has-Uppercase");
}
```

- [ ] **Step 12.2: Implement `ProjectDeleteCommand`**

```java
package org.icij.datashare.cli.command;

import org.icij.datashare.cli.CliExitException;
import org.icij.datashare.cli.Mode;
import org.icij.datashare.cli.Prompter;
import org.icij.datashare.cli.Validators;
import org.icij.datashare.cli.Validators.InvalidValueException;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.Properties;

import static org.icij.datashare.cli.DatashareCliOptions.MODE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_DELETE_IF_EXISTS_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_DELETE_JSON_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_DELETE_KEEP_INDEX_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_DELETE_NO_INPUT_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_DELETE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_DELETE_YES_OPT;

@Command(name = "delete", mixinStandardHelpOptions = true, description = {
        "Delete a Datashare project (DB, ES index, queues, report map, artifacts).",
        "",
        "Examples:",
        "  datashare project delete my-project --yes",
        "  datashare project delete my-project --yes --keep-index",
        "  datashare project delete missing --if-exists --no-input"
})
public class ProjectDeleteCommand implements Runnable, DatashareSubcommand {

    @Parameters(index = "0", arity = "0..1", description = "Project name (positional)")
    String namePositional;

    @Option(names = "--name", description = "Project name (alternative to positional)")
    String nameFlag;

    @Option(names = {"--yes", "-y"}, description = "Skip the typed-name confirmation")
    boolean yes;

    @Option(names = "--keep-index", description = "Do not drop the Elasticsearch index")
    boolean keepIndex;

    @Option(names = "--if-exists", description = "Idempotent: exit 0 if project missing")
    boolean ifExists;

    @Option(names = "--no-input", description = "Disable interactive prompts (implies --yes)")
    boolean noInput;

    @Option(names = "--json", description = "Emit JSON result on stdout")
    boolean json;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    Prompter prompterOverride;

    private String resolvedName;
    private boolean ready;

    @Override
    public void run() {
        String name = namePositional != null ? namePositional : nameFlag;
        try {
            if (name != null) Validators.projectName(name);

            if (name == null) {
                if (noInput) {
                    spec.commandLine().getErr().println(
                            "error: --name is required when --no-input is set");
                    throw new CliExitException(2);
                }
                Prompter prompter = prompterOverride != null ? prompterOverride : new Prompter();
                if (prompterOverride == null && !prompter.isInteractive()) {
                    spec.commandLine().getErr().println(
                            "error: --name is required and no TTY available");
                    throw new CliExitException(2);
                }
                try {
                    name = prompter.promptString("Project name", Validators::projectName);
                } catch (Prompter.ValidationFailedException e) {
                    spec.commandLine().getErr().println("error: " + e.getMessage());
                    throw new CliExitException(5);
                }
            }

            this.resolvedName = name;
            this.ready = true;
        } catch (InvalidValueException e) {
            spec.commandLine().getErr().println("error: " + e.getMessage());
            throw new CliExitException(5);
        }
    }

    @Override
    public Properties getSubcommandProperties() {
        Properties props = new Properties();
        DatashareOptions.put(props, MODE_OPT, Mode.CLI);
        if (!ready) {
            return props;
        }
        DatashareOptions.put(props, PROJECT_DELETE_OPT, resolvedName);
        DatashareOptions.putIfTrue(props, PROJECT_DELETE_YES_OPT, yes);
        DatashareOptions.putIfTrue(props, PROJECT_DELETE_KEEP_INDEX_OPT, keepIndex);
        DatashareOptions.putIfTrue(props, PROJECT_DELETE_IF_EXISTS_OPT, ifExists);
        DatashareOptions.putIfTrue(props, PROJECT_DELETE_NO_INPUT_OPT, noInput);
        DatashareOptions.putIfTrue(props, PROJECT_DELETE_JSON_OPT, json);
        return props;
    }
}
```

- [ ] **Step 12.3: Compile**

```bash
mvn -pl datashare-cli compile 2>&1 | tail -5
```

- [ ] **Step 12.4: Commit**

```bash
git add datashare-cli/src/main/java/org/icij/datashare/cli/command/ProjectDeleteCommand.java
git commit -m "feat(cli): add ProjectDeleteCommand"
```

---

## Task 13: `ProjectCommand` group + register in `DatashareCommand`

**Files:**
- Create: `datashare-cli/src/main/java/org/icij/datashare/cli/command/ProjectCommand.java`
- Modify: `datashare-cli/src/main/java/org/icij/datashare/cli/command/DatashareCommand.java`

### Steps

- [ ] **Step 13.1: Create `ProjectCommand`**

```java
package org.icij.datashare.cli.command;

import org.icij.datashare.cli.CliExitException;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Spec;

@Command(name = "project", mixinStandardHelpOptions = true,
        description = "Manage Datashare projects (create, delete).",
        subcommands = { ProjectCreateCommand.class, ProjectDeleteCommand.class })
public class ProjectCommand implements Runnable {

    @Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        // No subcommand: print usage and exit 2, matching UserCommand.
        spec.commandLine().usage(spec.commandLine().getErr());
        throw new CliExitException(2);
    }
}
```

- [ ] **Step 13.2: Register in `DatashareCommand`**

In `DatashareCommand.java`, add `ProjectCommand.class` to the `subcommands` array, after `UserCommand.class`:

```java
        subcommands = {
                AppCommand.class,
                WorkerCommand.class,
                StageCommand.class,
                PluginCommand.class,
                ExtensionCommand.class,
                ApiKeyCommand.class,
                UserCommand.class,
                ProjectCommand.class,
                CommandLine.HelpCommand.class
        },
```

- [ ] **Step 13.3: Add a "no subcommand" test**

Append to `DatashareCommandTest.java`:

```java
@Test
public void test_project_with_no_subcommand_exits_2() {
    assertExitCode(2, "project");
}
```

- [ ] **Step 13.4: Run tests — expect pass for all CLI tests added in Tasks 11/12/13**

```bash
mvn -pl datashare-cli test -Dtest=DatashareCommandTest 2>&1 | tail -10
```

- [ ] **Step 13.5: Commit**

```bash
git add datashare-cli/src/main/java/org/icij/datashare/cli/command/ProjectCommand.java \
        datashare-cli/src/main/java/org/icij/datashare/cli/command/DatashareCommand.java \
        datashare-cli/src/test/java/org/icij/datashare/cli/command/DatashareCommandTest.java
git commit -m "feat(cli): wire `project` subcommand"
```

---

## Task 14: `CliApp.handleProjectCreate` + dispatch block

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/CliApp.java`
- Test: `datashare-app/src/test/java/org/icij/datashare/CliAppProjectDispatchTest.java`

### Steps

- [ ] **Step 14.1: Write the failing dispatcher test**

Create `CliAppProjectDispatchTest.java`:

```java
package org.icij.datashare;

import org.icij.datashare.project.admin.ProjectAdminService;
import org.icij.datashare.project.admin.ProjectCreateRequest;
import org.icij.datashare.project.admin.ProjectCreated;
import org.icij.datashare.project.admin.ProjectExistsException;
import org.icij.datashare.project.admin.ValidationException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Properties;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_JSON_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_LABEL_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_IF_NOT_EXISTS_OPT;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class CliAppProjectDispatchTest {
    private ProjectAdminService service;
    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    private PrintStream originalOut;
    private PrintStream originalErr;

    @Before
    public void setUp() {
        service = mock(ProjectAdminService.class);
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(stdout));
        System.setErr(new PrintStream(stderr));
    }

    @After
    public void tearDown() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    @Test
    public void test_create_happy_path_text_output() throws Exception {
        when(service.create(any(ProjectCreateRequest.class)))
                .thenReturn(new ProjectCreated("foo", "Foo", null, Path.of("/vault/foo"),
                        "*.*.*.*", null, null, null, null, true, false));
        Properties props = new Properties();
        props.setProperty(PROJECT_CREATE_OPT, "foo");
        props.setProperty(PROJECT_CREATE_LABEL_OPT, "Foo");

        int exit = CliApp.handleProjectCreate(service, props);

        assertThat(exit).isEqualTo(0);
        assertThat(stdout.toString()).contains("created project 'foo'");
        assertThat(stdout.toString()).contains("index=created");
    }

    @Test
    public void test_create_conflict_returns_4() throws Exception {
        when(service.create(any(ProjectCreateRequest.class)))
                .thenThrow(new ProjectExistsException("foo"));
        Properties props = new Properties();
        props.setProperty(PROJECT_CREATE_OPT, "foo");

        int exit = CliApp.handleProjectCreate(service, props);

        assertThat(exit).isEqualTo(4);
        assertThat(stderr.toString()).contains("already exists");
    }

    @Test
    public void test_create_validation_returns_5() throws Exception {
        when(service.create(any(ProjectCreateRequest.class)))
                .thenThrow(new ValidationException("name", "bad"));
        Properties props = new Properties();
        props.setProperty(PROJECT_CREATE_OPT, "foo");

        int exit = CliApp.handleProjectCreate(service, props);

        assertThat(exit).isEqualTo(5);
    }

    @Test
    public void test_create_if_not_exists_routes_to_createIfNotExists() throws Exception {
        when(service.createIfNotExists(any(ProjectCreateRequest.class)))
                .thenReturn(new ProjectCreated("foo", "foo", null, Path.of("/vault/foo"),
                        "*.*.*.*", null, null, null, null, false, true));
        Properties props = new Properties();
        props.setProperty(PROJECT_CREATE_OPT, "foo");
        props.setProperty(PROJECT_CREATE_IF_NOT_EXISTS_OPT, "true");

        int exit = CliApp.handleProjectCreate(service, props);

        assertThat(exit).isEqualTo(0);
        assertThat(stdout.toString()).contains("already exists (no-op)");
        verify(service).createIfNotExists(any(ProjectCreateRequest.class));
    }

    @Test
    public void test_create_json_output() throws Exception {
        when(service.create(any(ProjectCreateRequest.class)))
                .thenReturn(new ProjectCreated("foo", "foo", null, Path.of("/vault/foo"),
                        "*.*.*.*", null, null, null, null, true, false));
        Properties props = new Properties();
        props.setProperty(PROJECT_CREATE_OPT, "foo");
        props.setProperty(PROJECT_CREATE_JSON_OPT, "true");

        int exit = CliApp.handleProjectCreate(service, props);

        assertThat(exit).isEqualTo(0);
        String json = stdout.toString().trim();
        assertThat(json).startsWith("{");
        assertThat(json).contains("\"created\":true");
        assertThat(json).contains("\"noop\":false");
        assertThat(json).contains("\"name\":\"foo\"");
        assertThat(json).contains("\"indexCreated\":true");
    }
}
```

- [ ] **Step 14.2: Run tests — expect compile failure**

```bash
mvn -pl datashare-app test -Dtest=CliAppProjectDispatchTest 2>&1 | tail -20
```

Expected: "cannot find symbol: method handleProjectCreate".

- [ ] **Step 14.3: Add `handleProjectCreate` and the dispatch block to `CliApp.java`**

Locate the existing `if (properties.getProperty(USER_DELETE_OPT) != null) { ... }` block in `runTaskWorker` (around line 119). Add directly below:

```java
if (properties.getProperty(PROJECT_CREATE_OPT) != null) {
    ProjectAdminService projectAdminService = mode.get(ProjectAdminService.class);
    System.exit(handleProjectCreate(projectAdminService, properties));
}
```

Add `handleProjectCreate` as a static method below `handleUserDelete` (around line 240):

```java
static int handleProjectCreate(ProjectAdminService service, Properties properties) {
    String name = properties.getProperty(PROJECT_CREATE_OPT);
    boolean json = Boolean.parseBoolean(properties.getProperty(PROJECT_CREATE_JSON_OPT));
    boolean ifNotExists = Boolean.parseBoolean(properties.getProperty(PROJECT_CREATE_IF_NOT_EXISTS_OPT));
    boolean noIndex = Boolean.parseBoolean(properties.getProperty(PROJECT_CREATE_NO_INDEX_OPT));
    try {
        String sourcePathOpt = properties.getProperty(PROJECT_CREATE_SOURCE_PATH_OPT);
        ProjectCreateRequest request = new ProjectCreateRequest(
                name,
                properties.getProperty(PROJECT_CREATE_LABEL_OPT),
                properties.getProperty(PROJECT_CREATE_DESCRIPTION_OPT),
                sourcePathOpt == null ? null : Path.of(sourcePathOpt),
                properties.getProperty(PROJECT_CREATE_ALLOW_FROM_MASK_OPT),
                properties.getProperty(PROJECT_CREATE_SOURCE_URL_OPT),
                properties.getProperty(PROJECT_CREATE_MAINTAINER_NAME_OPT),
                properties.getProperty(PROJECT_CREATE_PUBLISHER_NAME_OPT),
                properties.getProperty(PROJECT_CREATE_LOGO_URL_OPT),
                !noIndex);

        ProjectCreated created = ifNotExists
                ? service.createIfNotExists(request)
                : service.create(request);

        if (json) {
            System.out.println(MAPPER.writeValueAsString(Map.ofEntries(
                    Map.entry("created", !created.noop()),
                    Map.entry("noop", created.noop()),
                    Map.entry("name", created.name()),
                    Map.entry("label", created.label() == null ? "" : created.label()),
                    Map.entry("description", created.description() == null ? "" : created.description()),
                    Map.entry("sourcePath", created.sourcePath() == null ? "" : created.sourcePath().toString()),
                    Map.entry("allowFromMask", created.allowFromMask() == null ? "" : created.allowFromMask()),
                    Map.entry("sourceUrl", created.sourceUrl() == null ? "" : created.sourceUrl()),
                    Map.entry("maintainerName", created.maintainerName() == null ? "" : created.maintainerName()),
                    Map.entry("publisherName", created.publisherName() == null ? "" : created.publisherName()),
                    Map.entry("logoUrl", created.logoUrl() == null ? "" : created.logoUrl()),
                    Map.entry("indexCreated", created.indexCreated()))));
        } else if (created.noop()) {
            System.out.println("project '" + created.name() + "' already exists (no-op)");
        } else {
            String indexBadge = created.indexCreated() ? "index=created" : "index=skipped";
            System.out.println("created project '" + created.name() + "' (label='"
                    + (created.label() == null ? "" : created.label()) + "', source-path="
                    + created.sourcePath() + ", allow-from-mask=" + created.allowFromMask()
                    + ", " + indexBadge + ")");
        }
        return 0;
    } catch (ProjectExistsException e) {
        return error(e.getMessage(), "conflict", 4, json);
    } catch (ValidationException e) {
        return error(e.getMessage(), "validation", 5, json);
    } catch (Exception e) {
        return error("runtime: " + e.getMessage(), "runtime", 1, json);
    }
}
```

Add the necessary imports at the top of `CliApp.java`:

```java
import org.icij.datashare.project.admin.ProjectAdminService;
import org.icij.datashare.project.admin.ProjectCreateRequest;
import org.icij.datashare.project.admin.ProjectCreated;
import org.icij.datashare.project.admin.ProjectExistsException;
import org.icij.datashare.project.admin.ValidationException;

import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_ALLOW_FROM_MASK_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_DESCRIPTION_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_IF_NOT_EXISTS_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_JSON_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_LABEL_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_LOGO_URL_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_MAINTAINER_NAME_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_NO_INDEX_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_PUBLISHER_NAME_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_SOURCE_PATH_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_CREATE_SOURCE_URL_OPT;
```

There is a name collision risk: `ValidationException` already exists in the `user.admin` package and the new one in `project.admin`. Java does not allow two unqualified `ValidationException` imports. Resolve by **not** importing `org.icij.datashare.user.admin.ValidationException` at the top — the existing handler catches it via the full name. Inspect the existing imports for `user.admin.ValidationException` and convert that one to a fully-qualified reference inside `handleUserCreate`, OR import only one of the two and fully-qualify the other in its handler. Pick whichever change is smaller.

- [ ] **Step 14.4: Run tests — expect pass**

```bash
mvn -pl datashare-app test -Dtest=CliAppProjectDispatchTest 2>&1 | tail -10
```

Also verify the existing user-CRUD dispatch tests still pass:

```bash
mvn -pl datashare-app test -Dtest=CliAppUserDispatchTest 2>&1 | tail -5
```

- [ ] **Step 14.5: Commit**

```bash
git add datashare-app/src/main/java/org/icij/datashare/CliApp.java \
        datashare-app/src/test/java/org/icij/datashare/CliAppProjectDispatchTest.java
git commit -m "feat(cli-dispatch): handle projectCreate"
```

---

## Task 15: `CliApp.handleProjectDelete` — stats + typed-name confirmation + delete

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/CliApp.java`
- Modify: `datashare-app/src/test/java/org/icij/datashare/CliAppProjectDispatchTest.java`

### Background

`handleProjectDelete` does, in order:

1. Read `name` and flags from properties.
2. If `--if-exists` is set, call `service.stats(name)`; catch `ProjectNotFoundException` and exit 0 with a no-op message.
3. Else call `service.stats(name)`; let `ProjectNotFoundException` bubble to the catch block (exit 3).
4. If `--yes` or `--no-input` is set, skip confirmation. Otherwise build a `Prompter` and read a typed-name confirmation. On mismatch exit 0 with an "aborted" message.
5. Call `service.delete(name, options)` (or `deleteIfExists` per flag).
6. Print text/JSON result.

To keep tests fast, the helper takes a `Prompter` factory parameter that the tests can override. Use a default supplier when called from production.

### Steps

- [ ] **Step 15.1: Write the failing dispatcher tests**

Append to `CliAppProjectDispatchTest.java`:

```java
import org.icij.datashare.cli.Prompter;
import org.icij.datashare.project.admin.ProjectDeleteOptions;
import org.icij.datashare.project.admin.ProjectDeleted;
import org.icij.datashare.project.admin.ProjectNotFoundException;
import org.icij.datashare.project.admin.ProjectStats;

import java.util.function.Supplier;

import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_DELETE_IF_EXISTS_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_DELETE_JSON_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_DELETE_KEEP_INDEX_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_DELETE_NO_INPUT_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_DELETE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_DELETE_YES_OPT;
import static org.mockito.ArgumentMatchers.eq;

private static Supplier<Prompter> alwaysConfirming(String expected) {
    return () -> {
        Prompter mock = mock(Prompter.class);
        when(mock.promptString(any(), any())).thenReturn(expected);
        return mock;
    };
}

private static Supplier<Prompter> alwaysDeclining() {
    return () -> {
        Prompter mock = mock(Prompter.class);
        when(mock.promptString(any(), any())).thenReturn("WRONG");
        return mock;
    };
}

@Test
public void test_delete_happy_path_with_yes_skips_prompt() throws Exception {
    when(service.stats("foo")).thenReturn(new ProjectStats("foo", 42L, 3));
    when(service.delete(eq("foo"), any(ProjectDeleteOptions.class)))
            .thenReturn(new ProjectDeleted("foo", true, true, true, true, false, false));
    Properties props = new Properties();
    props.setProperty(PROJECT_DELETE_OPT, "foo");
    props.setProperty(PROJECT_DELETE_YES_OPT, "true");

    int exit = CliApp.handleProjectDelete(service, props, alwaysDeclining());  // prompt should not be reached

    assertThat(exit).isEqualTo(0);
    assertThat(stdout.toString()).contains("deleted project 'foo'");
    verify(service).delete(eq("foo"), any(ProjectDeleteOptions.class));
}

@Test
public void test_delete_with_typed_name_confirmation_proceeds() throws Exception {
    when(service.stats("foo")).thenReturn(new ProjectStats("foo", 42L, 3));
    when(service.delete(eq("foo"), any(ProjectDeleteOptions.class)))
            .thenReturn(new ProjectDeleted("foo", true, true, true, true, false, false));
    Properties props = new Properties();
    props.setProperty(PROJECT_DELETE_OPT, "foo");

    int exit = CliApp.handleProjectDelete(service, props, alwaysConfirming("foo"));

    assertThat(exit).isEqualTo(0);
    verify(service).delete(eq("foo"), any(ProjectDeleteOptions.class));
}

@Test
public void test_delete_aborts_when_typed_name_mismatches() throws Exception {
    when(service.stats("foo")).thenReturn(new ProjectStats("foo", 42L, 3));
    Properties props = new Properties();
    props.setProperty(PROJECT_DELETE_OPT, "foo");

    int exit = CliApp.handleProjectDelete(service, props, alwaysDeclining());

    assertThat(exit).isEqualTo(0);
    assertThat(stderr.toString()).contains("aborted");
    verify(service, never()).delete(any(), any());
}

@Test
public void test_delete_missing_returns_3() throws Exception {
    when(service.stats("ghost")).thenThrow(new ProjectNotFoundException("ghost"));
    Properties props = new Properties();
    props.setProperty(PROJECT_DELETE_OPT, "ghost");
    props.setProperty(PROJECT_DELETE_YES_OPT, "true");

    int exit = CliApp.handleProjectDelete(service, props, alwaysConfirming("ghost"));

    assertThat(exit).isEqualTo(3);
    verify(service, never()).delete(any(), any());
}

@Test
public void test_delete_if_exists_missing_returns_0_noop() throws Exception {
    when(service.stats("ghost")).thenThrow(new ProjectNotFoundException("ghost"));
    Properties props = new Properties();
    props.setProperty(PROJECT_DELETE_OPT, "ghost");
    props.setProperty(PROJECT_DELETE_IF_EXISTS_OPT, "true");
    props.setProperty(PROJECT_DELETE_YES_OPT, "true");

    int exit = CliApp.handleProjectDelete(service, props, alwaysConfirming("ghost"));

    assertThat(exit).isEqualTo(0);
    assertThat(stdout.toString()).contains("does not exist (no-op)");
}

@Test
public void test_delete_keep_index_passes_option() throws Exception {
    when(service.stats("foo")).thenReturn(new ProjectStats("foo", ProjectStats.INDEX_CHECK_SKIPPED, 3));
    org.mockito.ArgumentCaptor<ProjectDeleteOptions> captor =
            org.mockito.ArgumentCaptor.forClass(ProjectDeleteOptions.class);
    when(service.delete(eq("foo"), captor.capture()))
            .thenReturn(new ProjectDeleted("foo", true, false, true, true, false, false));
    Properties props = new Properties();
    props.setProperty(PROJECT_DELETE_OPT, "foo");
    props.setProperty(PROJECT_DELETE_KEEP_INDEX_OPT, "true");
    props.setProperty(PROJECT_DELETE_YES_OPT, "true");

    int exit = CliApp.handleProjectDelete(service, props, alwaysConfirming("foo"));

    assertThat(exit).isEqualTo(0);
    assertThat(captor.getValue().keepIndex()).isTrue();
}

@Test
public void test_delete_json_output() throws Exception {
    when(service.stats("foo")).thenReturn(new ProjectStats("foo", 42L, 3));
    when(service.delete(eq("foo"), any(ProjectDeleteOptions.class)))
            .thenReturn(new ProjectDeleted("foo", true, true, true, true, true, false));
    Properties props = new Properties();
    props.setProperty(PROJECT_DELETE_OPT, "foo");
    props.setProperty(PROJECT_DELETE_YES_OPT, "true");
    props.setProperty(PROJECT_DELETE_JSON_OPT, "true");

    int exit = CliApp.handleProjectDelete(service, props, alwaysConfirming("foo"));

    assertThat(exit).isEqualTo(0);
    String out = stdout.toString().trim();
    assertThat(out).contains("\"deleted\":true");
    assertThat(out).contains("\"noop\":false");
    assertThat(out).contains("\"name\":\"foo\"");
    assertThat(out).contains("\"dbDeleted\":true");
    assertThat(out).contains("\"indexDeleted\":true");
}
```

- [ ] **Step 15.2: Run tests — expect compile failure**

- [ ] **Step 15.3: Add `handleProjectDelete` + dispatch block**

Add the dispatch block in `runTaskWorker` (after `handleProjectCreate` from Task 14):

```java
if (properties.getProperty(PROJECT_DELETE_OPT) != null) {
    ProjectAdminService projectAdminService = mode.get(ProjectAdminService.class);
    System.exit(handleProjectDelete(projectAdminService, properties, Prompter::new));
}
```

Add `handleProjectDelete`:

```java
static int handleProjectDelete(ProjectAdminService service,
                               Properties properties,
                               Supplier<Prompter> prompterFactory) {
    String name = properties.getProperty(PROJECT_DELETE_OPT);
    boolean json = Boolean.parseBoolean(properties.getProperty(PROJECT_DELETE_JSON_OPT));
    boolean ifExists = Boolean.parseBoolean(properties.getProperty(PROJECT_DELETE_IF_EXISTS_OPT));
    boolean yes = Boolean.parseBoolean(properties.getProperty(PROJECT_DELETE_YES_OPT));
    boolean noInput = Boolean.parseBoolean(properties.getProperty(PROJECT_DELETE_NO_INPUT_OPT));
    boolean keepIndex = Boolean.parseBoolean(properties.getProperty(PROJECT_DELETE_KEEP_INDEX_OPT));
    ProjectDeleteOptions options = new ProjectDeleteOptions(keepIndex);

    try {
        ProjectStats stats;
        try {
            stats = service.stats(name);
        } catch (ProjectNotFoundException e) {
            if (ifExists) {
                emitDeleteNoop(name, json);
                return 0;
            }
            throw e;
        }

        if (!(yes || noInput)) {
            String docCount = stats.indexedDocuments() == ProjectStats.INDEX_CHECK_SKIPPED
                    ? "(index check skipped)"
                    : stats.indexedDocuments() + " indexed documents";
            System.err.println("Project '" + name + "' has " + docCount
                    + " and " + stats.memberCount() + " members.");
            System.err.println("This will permanently delete the project, its index, "
                    + "document queues, report map, and artifact directory. "
                    + "This cannot be undone.");
            Prompter prompter = prompterFactory.get();
            String typed = prompter.promptString(
                    "To confirm, type the project name",
                    typedName -> {
                        if (!typedName.trim().equals(name)) {
                            throw new org.icij.datashare.cli.Validators.InvalidValueException(
                                    "name", "typed name does not match");
                        }
                    });
            // promptString returns the input only after validation succeeded,
            // so reaching here means confirmation passed; double-check anyway.
            if (!typed.trim().equals(name)) {
                emitDeleteAborted(name, json);
                return 0;
            }
        }

        ProjectDeleted deleted = ifExists
                ? service.deleteIfExists(name, options)
                : service.delete(name, options);

        if (json) {
            System.out.println(MAPPER.writeValueAsString(Map.ofEntries(
                    Map.entry("deleted", !deleted.noop()),
                    Map.entry("noop", deleted.noop()),
                    Map.entry("name", deleted.name()),
                    Map.entry("dbDeleted", deleted.dbDeleted()),
                    Map.entry("indexDeleted", deleted.indexDeleted()),
                    Map.entry("queuesDeleted", deleted.queuesDeleted()),
                    Map.entry("reportMapDeleted", deleted.reportMapDeleted()),
                    Map.entry("artifactsDeleted", deleted.artifactsDeleted()))));
        } else if (deleted.noop()) {
            System.out.println("project '" + deleted.name() + "' does not exist (no-op)");
        } else {
            String indexBadge = options.keepIndex() ? "index skipped" : "index OK";
            String artifactsBadge = deleted.artifactsDeleted() ? "artifacts OK" : "artifacts skipped";
            System.out.println("deleted project '" + deleted.name() + "' (db OK, "
                    + indexBadge + ", queues OK, report-map OK, " + artifactsBadge + ")");
        }
        return 0;
    } catch (ProjectNotFoundException e) {
        return error(e.getMessage(), "not_found", 3, json);
    } catch (Exception e) {
        return error("runtime: " + e.getMessage(), "runtime", 1, json);
    }
}

private static void emitDeleteNoop(String name, boolean json) {
    if (json) {
        try {
            System.out.println(MAPPER.writeValueAsString(Map.ofEntries(
                    Map.entry("deleted", false),
                    Map.entry("noop", true),
                    Map.entry("name", name),
                    Map.entry("dbDeleted", false),
                    Map.entry("indexDeleted", false),
                    Map.entry("queuesDeleted", false),
                    Map.entry("reportMapDeleted", false),
                    Map.entry("artifactsDeleted", false))));
        } catch (Exception e) {
            System.out.println("project '" + name + "' does not exist (no-op)");
        }
    } else {
        System.out.println("project '" + name + "' does not exist (no-op)");
    }
}

private static void emitDeleteAborted(String name, boolean json) {
    if (json) {
        try {
            System.out.println(MAPPER.writeValueAsString(Map.ofEntries(
                    Map.entry("deleted", false),
                    Map.entry("noop", true),
                    Map.entry("aborted", true),
                    Map.entry("name", name))));
        } catch (Exception e) {
            System.err.println("aborted");
        }
    } else {
        System.err.println("aborted");
    }
}
```

Note on `promptString` semantics: the existing `Prompter.promptString` retries up to `MAX_RETRIES=3` times on validation failure and throws `Prompter.ValidationFailedException` after exhausting them. If the user types the wrong name 3 times, that exception propagates and lands in the outer `catch (Exception e)` block as exit code 1 ("runtime"). That is acceptable for now — failing-to-confirm three times is a misuse, not a normal flow. If desired, catch `Prompter.ValidationFailedException` specifically and emit `aborted` + exit 0 instead; not required by the spec.

Add the missing imports:

```java
import java.util.function.Supplier;
import org.icij.datashare.cli.Prompter;
import org.icij.datashare.project.admin.ProjectDeleteOptions;
import org.icij.datashare.project.admin.ProjectDeleted;
import org.icij.datashare.project.admin.ProjectNotFoundException;
import org.icij.datashare.project.admin.ProjectStats;

import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_DELETE_IF_EXISTS_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_DELETE_JSON_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_DELETE_KEEP_INDEX_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_DELETE_NO_INPUT_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_DELETE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PROJECT_DELETE_YES_OPT;
```

- [ ] **Step 15.4: Run tests — expect pass**

```bash
mvn -pl datashare-app test -Dtest=CliAppProjectDispatchTest 2>&1 | tail -10
mvn -pl datashare-app test -Dtest=CliAppUserDispatchTest 2>&1 | tail -5
```

Both classes must pass.

- [ ] **Step 15.5: Commit**

```bash
git add datashare-app/src/main/java/org/icij/datashare/CliApp.java \
        datashare-app/src/test/java/org/icij/datashare/CliAppProjectDispatchTest.java
git commit -m "feat(cli-dispatch): handle projectDelete with stats + typed-name confirmation"
```

---

## Task 16: End-to-end smoke + final cleanup

**Files:**
- Test: append to `datashare-cli/src/test/java/org/icij/datashare/cli/command/DatashareCommandTest.java` (one happy-path each for create and delete, exercising the full picocli → properties → command spec)
- Verify: full module build clean

### Steps

- [ ] **Step 16.1: Add smoke tests**

Append to `DatashareCommandTest.java`:

```java
@Test
public void test_project_create_smoke_end_to_end() {
    Properties props = parse("project", "create", "my-project",
            "--label", "My Project",
            "--source-path", "/data/my-project",
            "--json");

    // The CLI layer only produces properties; the dispatcher (tested separately
    // in CliAppProjectDispatchTest) consumes them. This smoke test confirms the
    // picocli graph hands off a coherent set of typed sibling keys.
    assertThat(props).includes(entry("projectCreate", "my-project"));
    assertThat(props).includes(entry("projectCreate.label", "My Project"));
    assertThat(props).includes(entry("projectCreate.sourcePath", "/data/my-project"));
    assertThat(props).includes(entry("projectCreate.json", "true"));
}

@Test
public void test_project_delete_smoke_end_to_end() {
    Properties props = parse("project", "delete", "my-project", "--yes", "--keep-index", "--json");
    assertThat(props).includes(entry("projectDelete", "my-project"));
    assertThat(props).includes(entry("projectDelete.yes", "true"));
    assertThat(props).includes(entry("projectDelete.keepIndex", "true"));
    assertThat(props).includes(entry("projectDelete.json", "true"));
}
```

- [ ] **Step 16.2: Run the full test suite for affected modules**

```bash
mvn -pl datashare-cli,datashare-app,datashare-index,datashare-api test 2>&1 | tail -30
```

Expected: all green. Any failure here either (a) reveals a regression introduced in earlier tasks (fix the failing test, do not delete it), or (b) is an unrelated flaky test in the existing suite (re-run once; if it persists, flag it but do not block this PR on it).

- [ ] **Step 16.3: Run the full build to catch javadoc / surefire issues**

```bash
mvn -pl datashare-cli,datashare-app,datashare-index,datashare-api verify -DskipITs 2>&1 | tail -20
```

- [ ] **Step 16.4: Commit smoke tests**

```bash
git add datashare-cli/src/test/java/org/icij/datashare/cli/command/DatashareCommandTest.java
git commit -m "test(cli): smoke tests for `project` end-to-end picocli graph"
```

- [ ] **Step 16.5: Confirm the branch state**

```bash
git log --oneline main..HEAD
git diff --stat main..HEAD
```

You should see ~16 commits: the spec + amendment, plus one commit per task above (some tasks are split into multiple commits where there's a meaningful boundary). The diff-stat should not touch `ProjectResource.java`, the `Repository` interface, the `JooqRepository` class, or anything under `datashare-db`.

---

## Self-Review Notes

This plan covers the spec section-by-section:

- **Command surface** (spec §"Command surface"): Tasks 11, 12, 13 implement the picocli flags table verbatim; Task 16 smoke-tests them.
- **Architecture** (spec §"Architecture"): Tasks 4–10 deliver the three-layer split.
- **Data model** (spec §"Data model"): Task 4.
- **Cascade semantics** (spec §"Cascade semantics"): Task 6 (create), Task 9 (delete) — including the compensating-delete on index failure and the artifact-dir try/catch.
- **Interactive prompts and validation** (spec §"Interactive prompts and validation"): Tasks 3 (validators), 11–12 (name prompt), 15 (typed-name confirmation in dispatcher).
- **Output and exit codes** (spec §"Output and exit codes"): Tasks 14, 15.
- **Files touched** (spec §"Files touched"): mirrored in the File Structure table above, with the spec→concrete translations noted.
- **Testing** (spec §"Testing"): Layer 1 (CLI parsing) in Tasks 11–13, 16; Layer 2 (service) in Tasks 6–9; Layer 3 (dispatcher smoke) in Tasks 14–15.
- **Indexer.count** (spec §"One new interface method"): Task 1.

Items intentionally **not** delivered by this plan: any refactor of `ProjectResource.java`, any new SQL or liquibase migration, anything related to `project grant` / `project revoke` (deferred to its own spec).

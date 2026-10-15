# Batch search validation layering Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move batch-search name/description domain invariants out of the web layer into the `BatchSearch` model, enforce them at every repository write boundary, and slim the resources down to HTTP-boundary concerns.

**Architecture:** Domain rules (empty name, max lengths) become static validators on `BatchSearch` (single source of truth, next to the existing "queries cannot be empty" invariant). `JooqBatchSearchRepository.setName`/`setDescription`/`save` call them so every caller is protected. `BatchSearchResource.updateBatch` keeps only JSON type-coercion + validate-all-before-apply orchestration and calls the model validators up-front. The two creation handlers in `TaskResource` (`search`, `copySearch`) map the thrown `IllegalArgumentException` to a 400.

**Tech Stack:** Java, Maven (multi-module), jOOQ, net.codestory fluent-http, JUnit 4, fest-assert, Mockito.

**Spec:** `docs/superpowers/specs/2026-06-09-batch-search-validation-layering-design.md`

**Test commands (run from repo root):**
- api: `mvn -q -pl datashare-api -am -Dtest=BatchSearchTest test`
- db: `mvn -q -pl datashare-db -am -Dtest=JooqBatchSearchRepositoryTest test`
- app (edit): `mvn -q -pl datashare-app -am -Dtest=BatchSearchResourceTest test`
- app (create): `mvn -q -pl datashare-app -am -Dtest=TaskResourceBatchSearchTest test`

> Note: `JooqBatchSearchRepositoryTest` is `@Parameterized` over SQLite and PostgreSQL. The SQLite parameter runs locally with no extra setup; the PostgreSQL parameter needs the project's test DB (as in CI). The new validation tests throw before any SQL runs, so they pass under both parameters regardless.

---

## File Structure

- `datashare-api/src/main/java/org/icij/datashare/batch/BatchSearch.java` — add `MAX_NAME_LENGTH`, `MAX_DESCRIPTION_LENGTH` constants and `validateName` / `validateDescription` static methods (the single source of truth for the rules).
- `datashare-api/src/test/java/org/icij/datashare/batch/BatchSearchTest.java` — unit tests for the validators.
- `datashare-app/src/main/java/org/icij/datashare/web/BatchSearchResource.java` — slim `updateBatch`/`applyEdits`; delete resource-local domain validation + constants.
- `datashare-db/src/main/java/org/icij/datashare/db/JooqBatchSearchRepository.java` — enforce validators in `setName`, `setDescription`, `save`.
- `datashare-db/src/test/java/org/icij/datashare/db/JooqBatchSearchRepositoryTest.java` — tests that the repo rejects invalid input.
- `datashare-app/src/main/java/org/icij/datashare/web/TaskResource.java` — map `IllegalArgumentException` to 400 in `search` and `copySearch`.
- `datashare-app/src/test/java/org/icij/datashare/web/TaskResourceBatchSearchTest.java` — tests that creation/copy return 400 on invalid input.

---

## Task 1: Domain validators on the `BatchSearch` model

**Files:**
- Modify: `datashare-api/src/main/java/org/icij/datashare/batch/BatchSearch.java`
- Test: `datashare-api/src/test/java/org/icij/datashare/batch/BatchSearchTest.java`

- [ ] **Step 1: Write the failing tests**

Add these tests to `BatchSearchTest` (class already imports `org.junit.Test` and `org.fest.assertions.Assertions.assertThat`):

```java
    @Test(expected = IllegalArgumentException.class)
    public void test_validate_name_rejects_null() {
        BatchSearch.validateName(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_validate_name_rejects_blank() {
        BatchSearch.validateName("   ");
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_validate_name_rejects_too_long() {
        BatchSearch.validateName("x".repeat(BatchSearch.MAX_NAME_LENGTH + 1));
    }

    @Test
    public void test_validate_name_accepts_max_length() {
        String name = "x".repeat(BatchSearch.MAX_NAME_LENGTH);
        assertThat(BatchSearch.validateName(name)).isEqualTo(name);
    }

    @Test
    public void test_validate_description_null_becomes_empty_string() {
        assertThat(BatchSearch.validateDescription(null)).isEqualTo("");
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_validate_description_rejects_too_long() {
        BatchSearch.validateDescription("x".repeat(BatchSearch.MAX_DESCRIPTION_LENGTH + 1));
    }

    @Test
    public void test_validate_description_accepts_max_length() {
        String description = "x".repeat(BatchSearch.MAX_DESCRIPTION_LENGTH);
        assertThat(BatchSearch.validateDescription(description)).isEqualTo(description);
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q -pl datashare-api -am -Dtest=BatchSearchTest test`
Expected: FAIL — compilation error, `cannot find symbol: method validateName` / `validateDescription` / variable `MAX_NAME_LENGTH`.

- [ ] **Step 3: Add the constants and validators to `BatchSearch`**

In `BatchSearch.java`, add the constants and methods inside the class body. Place them immediately after the field declarations block (right before the first constructor, after the `public final SearchQuery queryTemplate;` line and its `// batch search creation` comment):

```java
    public static final int MAX_NAME_LENGTH = 255;
    public static final int MAX_DESCRIPTION_LENGTH = 4096;

    public static String validateName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Batch search name cannot be empty.");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("Batch search name exceeds " + MAX_NAME_LENGTH + " characters.");
        }
        return name;
    }

    public static String validateDescription(String description) {
        String value = description == null ? "" : description;
        if (value.length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException("Batch search description exceeds " + MAX_DESCRIPTION_LENGTH + " characters.");
        }
        return value;
    }
```

Do NOT add these calls to any constructor — the constructor is shared with DB hydration and must not re-validate legacy rows.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q -pl datashare-api -am -Dtest=BatchSearchTest test`
Expected: PASS (all `BatchSearchTest` tests green, including the pre-existing ones).

- [ ] **Step 5: Commit**

```bash
git add datashare-api/src/main/java/org/icij/datashare/batch/BatchSearch.java datashare-api/src/test/java/org/icij/datashare/batch/BatchSearchTest.java
git commit -m "feat: add name/description validators to BatchSearch model"
```

---

## Task 2: Slim `BatchSearchResource.updateBatch` to use the model validators

This is a refactor: external behavior is unchanged, so the existing `BatchSearchResourceTest` cases (empty name, blank name, null name, too-long name/description, non-string name/description) stay green. The resource keeps calling the validators up-front (the test mocks the repository, so validation must happen in the resource, not only in the repo).

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/web/BatchSearchResource.java`
- Test: `datashare-app/src/test/java/org/icij/datashare/web/BatchSearchResourceTest.java` (no changes — used as the regression guard)

- [ ] **Step 1: Run the existing resource tests to confirm the green baseline**

Run: `mvn -q -pl datashare-app -am -Dtest=BatchSearchResourceTest test`
Expected: PASS (baseline before refactor).

- [ ] **Step 2: Remove the two constants**

In `BatchSearchResource.java`, delete these two field declarations (near the top of the class, after the `propertiesProvider` field):

```java
    private static final int MAX_NAME_LENGTH = 255;
    private static final int MAX_DESCRIPTION_LENGTH = 4096;
```

- [ ] **Step 3: Rewrite `applyEdits` to call the model validators**

Replace the existing `applyEdits` method with this version (only the `name` / `description` lines change — they now call `BatchSearch.validateName` / `validateDescription` after type-coercion):

```java
    private boolean applyEdits(User user, String batchId, Map<String, Object> body) {
        // Validate every present field before applying any, so an invalid field never leaves a partial update.
        Boolean published = body.containsKey("published") ? validBoolean(body.get("published"), "published") : null;
        String name = body.containsKey("name") ? BatchSearch.validateName(validString(body.get("name"), "name")) : null;
        String description = body.containsKey("description") ? BatchSearch.validateDescription(validString(body.get("description"), "description")) : null;

        boolean updated = false;
        if (published != null) {
            updated |= batchSearchRepository.publish(user, batchId, published);
        }
        if (name != null) {
            updated |= batchSearchRepository.setName(user, batchId, name);
        }
        if (description != null) {
            updated |= batchSearchRepository.setDescription(user, batchId, description);
        }
        return updated;
    }
```

- [ ] **Step 4: Delete the now-unused domain helpers**

Delete these three methods from `BatchSearchResource.java` (they have moved to the model):

```java
    private static String validName(Object value) {
        String name = validString(value, "name");
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Batch search name cannot be empty.");
        }
        return withinMaxLength(name, MAX_NAME_LENGTH, "name");
    }

    private static String validDescription(Object value) {
        String description = validString(value, "description");
        return withinMaxLength(description == null ? "" : description, MAX_DESCRIPTION_LENGTH, "description");
    }

    private static String withinMaxLength(String value, int maxLength, String field) {
        if (value.length() > maxLength) {
            throw new IllegalArgumentException("Batch search " + field + " exceeds " + maxLength + " characters.");
        }
        return value;
    }
```

Keep `hasUpdatableField`, `validString`, and `validBoolean` — those are the HTTP-boundary concerns (presence check + JSON type-coercion).

Confirm `BatchSearchResource` already imports `org.icij.datashare.batch.BatchSearch` (it does — `BatchSearch.class` is referenced in the `@Get("/search/:batchid")` `@ApiResponse` schema). No import change needed.

- [ ] **Step 5: Run the resource tests to verify they still pass**

Run: `mvn -q -pl datashare-app -am -Dtest=BatchSearchResourceTest test`
Expected: PASS — identical external behavior, fewer lines in the resource.

- [ ] **Step 6: Commit**

```bash
git add datashare-app/src/main/java/org/icij/datashare/web/BatchSearchResource.java
git commit -m "refactor: use BatchSearch model validators in updateBatch endpoint"
```

---

## Task 3: Enforce validators at the repository write boundaries

**Files:**
- Modify: `datashare-db/src/main/java/org/icij/datashare/db/JooqBatchSearchRepository.java`
- Test: `datashare-db/src/test/java/org/icij/datashare/db/JooqBatchSearchRepositoryTest.java`

- [ ] **Step 1: Write the failing tests**

Add these tests to `JooqBatchSearchRepositoryTest` (class already imports `org.junit.Test`, `org.fest.assertions.Assertions.assertThat`, `singletonList`, `asSet`, `proxy`, `User`, `BatchSearch`):

```java
    @Test(expected = IllegalArgumentException.class)
    public void test_set_name_too_long_throws() {
        repository.setName(User.local(), "uuid", "x".repeat(BatchSearch.MAX_NAME_LENGTH + 1));
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_set_name_blank_throws() {
        repository.setName(User.local(), "uuid", "   ");
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_set_description_too_long_throws() {
        repository.setDescription(User.local(), "uuid", "x".repeat(BatchSearch.MAX_DESCRIPTION_LENGTH + 1));
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_save_with_too_long_name_throws() {
        repository.save(new BatchSearch(singletonList(proxy("prj")), "x".repeat(BatchSearch.MAX_NAME_LENGTH + 1),
                "description", asSet("q1", "q2"), null, User.local()));
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_save_with_too_long_description_throws() {
        repository.save(new BatchSearch(singletonList(proxy("prj")), "name",
                "x".repeat(BatchSearch.MAX_DESCRIPTION_LENGTH + 1), asSet("q1", "q2"), null, User.local()));
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q -pl datashare-db -am -Dtest=JooqBatchSearchRepositoryTest test`
Expected: FAIL — no exception thrown (the methods currently run the SQL without validating), so the `expected = IllegalArgumentException.class` tests fail with "Expected exception ... but no exception was thrown".

- [ ] **Step 3: Add validation to `setName`, `setDescription`, and `save`**

In `JooqBatchSearchRepository.java`, add a validation call as the first statement of each method.

`setName`:

```java
    @Override
    public boolean setName(User user, String batchId, String name) {
        BatchSearch.validateName(name);
        return DSL.using(dataSource, dialect).update(BATCH_SEARCH).
                set(BATCH_SEARCH.NAME, name).
                where(BATCH_SEARCH.UUID.eq(batchId).
                        and(BATCH_SEARCH.USER_ID.eq(user.id))).execute() > 0;
    }
```

`setDescription`:

```java
    @Override
    public boolean setDescription(User user, String batchId, String description) {
        BatchSearch.validateDescription(description);
        return DSL.using(dataSource, dialect).update(BATCH_SEARCH).
                set(BATCH_SEARCH.DESCRIPTION, description).
                where(BATCH_SEARCH.UUID.eq(batchId).
                        and(BATCH_SEARCH.USER_ID.eq(user.id))).execute() > 0;
    }
```

`save` — add the two calls as the first statements, before `DSLContext context = ...`:

```java
    public boolean save(final BatchSearch batchSearch) {
        BatchSearch.validateName(batchSearch.name);
        BatchSearch.validateDescription(batchSearch.description);
        DSLContext context = DSL.using(dataSource, dialect);
        return context.transactionResult(configuration -> {
```

`BatchSearch` is already imported via `import org.icij.datashare.batch.*;`. No import change needed.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q -pl datashare-db -am -Dtest=JooqBatchSearchRepositoryTest test`
Expected: PASS — including the pre-existing `test_set_name`, `test_set_description`, `test_set_name_unauthorized_user_does_nothing`, and the many `save(...)` tests (all use valid names/descriptions).

- [ ] **Step 5: Commit**

```bash
git add datashare-db/src/main/java/org/icij/datashare/db/JooqBatchSearchRepository.java datashare-db/src/test/java/org/icij/datashare/db/JooqBatchSearchRepositoryTest.java
git commit -m "feat: enforce name/description rules in batch search repository writes"
```

---

## Task 4: Map creation `IllegalArgumentException` to 400 in `TaskResource`

The two creation handlers (`search` for fresh CSV creation, `copySearch` for copy) call `batchSearchRepository.save(...)`, which now throws `IllegalArgumentException` on an invalid name/description. Without mapping, fluent-http would render that as a 500. Map it to 400, following the file's existing message-less convention (`badRequest()` / `BadRequestException`).

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/web/TaskResource.java`
- Test: `datashare-app/src/test/java/org/icij/datashare/web/TaskResourceBatchSearchTest.java`

> The test uses a Mockito `@Mock BatchSearchRepository`, so the real `save()` validation does not run there. The tests stub `save()` to throw `IllegalArgumentException` and assert the handler maps it to 400 — this verifies the mapping (the rule itself is covered by Tasks 1 and 3).

- [ ] **Step 1: Write the failing tests**

Add these tests to `TaskResourceBatchSearchTest` (class already imports/sets up `batchSearchRepository`, `postRaw`, `post`, `MultipartContentBuilder`, `FileUpload`, `when`, `any`, `User`, `BatchSearch`, `asSet`, `project`, `singletonList`/`asList`):

```java
    @Test
    public void test_create_batch_search_with_invalid_name_returns_400() {
        when(batchSearchRepository.save(any())).thenThrow(new IllegalArgumentException("Batch search name exceeds 255 characters."));
        postRaw("/api/task/batchSearch/prj", "multipart/form-data;boundary=AaB03x",
                new MultipartContentBuilder("AaB03x")
                        .addField("name", "x".repeat(256))
                        .addFile(new FileUpload("csvFile").withFilename("search.csv").withContentType("text/csv").withContent("query one\r\n"))
                        .build()).should().respond(400);
    }

    @Test
    public void test_copy_batch_search_with_invalid_name_returns_400() {
        BatchSearch sourceSearch = new BatchSearch(asList(project("prj1"), project("prj2")), "name", "description1",
                asSet("query 1", "query 2"), "/?q=", User.local());
        when(batchSearchRepository.get(null, sourceSearch.uuid)).thenReturn(sourceSearch);
        when(batchSearchRepository.save(any())).thenThrow(new IllegalArgumentException("Batch search name exceeds 255 characters."));

        post("/api/task/batchSearch/copy/" + sourceSearch.uuid,
                "{\"name\": \"" + "x".repeat(256) + "\"}").should().respond(400);
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q -pl datashare-app -am -Dtest=TaskResourceBatchSearchTest test`
Expected: FAIL — the thrown `IllegalArgumentException` is currently unmapped and surfaces as a 500 (response code 500, not 400).

- [ ] **Step 3: Map the exception in the `search` handler**

In `TaskResource.java`, find the end of the `search` method (`@Post("/batchSearch/:coma_separated_projects")`). Replace this block:

```java
        BatchSearch batchSearch = new BatchSearch(stream(comaSeparatedProjects.split(",")).map(Project::project).collect(Collectors.toList()), name, description, queries,uri,
                (User) context.currentUser(), published, fileTypes, queryTemplate, paths, fuzziness,phraseMatches);
        boolean isSaved = batchSearchRepository.save(batchSearch);
        if (isSaved) {
            taskManager.startTask(batchSearch.uuid, BatchSearchRunner.class, (User) context.currentUser(), Map.of("batchRecord", new BatchSearchRecord(batchSearch)));
        }
        return isSaved ? new Payload("application/json", batchSearch.uuid, 200) : badRequest();
```

with:

```java
        BatchSearch batchSearch = new BatchSearch(stream(comaSeparatedProjects.split(",")).map(Project::project).collect(Collectors.toList()), name, description, queries,uri,
                (User) context.currentUser(), published, fileTypes, queryTemplate, paths, fuzziness,phraseMatches);
        boolean isSaved;
        try {
            isSaved = batchSearchRepository.save(batchSearch);
        } catch (IllegalArgumentException e) {
            return badRequest();
        }
        if (isSaved) {
            taskManager.startTask(batchSearch.uuid, BatchSearchRunner.class, (User) context.currentUser(), Map.of("batchRecord", new BatchSearchRecord(batchSearch)));
        }
        return isSaved ? new Payload("application/json", batchSearch.uuid, 200) : badRequest();
```

`badRequest()` is already available via `import static net.codestory.http.payload.Payload.*;`.

- [ ] **Step 4: Map the exception in the `copySearch` handler**

In `TaskResource.java`, find the `copySearch` method (`@Post("/batchSearch/copy/:sourcebatchid")`, returns `String`). Replace this block:

```java
        BatchSearch copy = new BatchSearch(sourceBatchSearch, context.extract(HashMap.class));
        boolean isSaved = batchSearchRepository.save(copy);
        if (isSaved) taskManager.startTask(copy.uuid, BatchSearchRunner.class, (User) context.currentUser(), Map.of("batchRecord", new BatchSearchRecord(copy)));
        return copy.uuid;
```

with:

```java
        BatchSearch copy = new BatchSearch(sourceBatchSearch, context.extract(HashMap.class));
        boolean isSaved;
        try {
            isSaved = batchSearchRepository.save(copy);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException();
        }
        if (isSaved) taskManager.startTask(copy.uuid, BatchSearchRunner.class, (User) context.currentUser(), Map.of("batchRecord", new BatchSearchRecord(copy)));
        return copy.uuid;
```

`BadRequestException` (no-arg, → HTTP 400) is already imported: `import net.codestory.http.errors.BadRequestException;` (used elsewhere in this file). No import change needed.

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn -q -pl datashare-app -am -Dtest=TaskResourceBatchSearchTest test`
Expected: PASS — the two new 400 tests pass and all pre-existing creation/copy tests stay green.

- [ ] **Step 6: Update the OpenAPI annotation on `search` (docs)**

On the `search` method, the existing annotation reads:

```java
    @ApiResponse(responseCode = "400", description = "if either name or CSV file is missing")
```

Replace it with:

```java
    @ApiResponse(responseCode = "400", description = "if name or CSV file is missing, or name/description are empty or exceed their maximum length")
```

- [ ] **Step 7: Commit**

```bash
git add datashare-app/src/main/java/org/icij/datashare/web/TaskResource.java datashare-app/src/test/java/org/icij/datashare/web/TaskResourceBatchSearchTest.java
git commit -m "feat: return 400 on invalid name/description when creating or copying a batch search"
```

---

## Task 5: Full verification

- [ ] **Step 1: Run all four affected test classes**

```bash
mvn -q -pl datashare-api -am -Dtest=BatchSearchTest test
mvn -q -pl datashare-db -am -Dtest=JooqBatchSearchRepositoryTest test
mvn -q -pl datashare-app -am -Dtest=BatchSearchResourceTest,TaskResourceBatchSearchTest test
```

Expected: all PASS.

- [ ] **Step 2: Confirm the resource no longer carries domain rules**

Run: `grep -n "MAX_NAME_LENGTH\|MAX_DESCRIPTION_LENGTH\|withinMaxLength\|cannot be empty" datashare-app/src/main/java/org/icij/datashare/web/BatchSearchResource.java`
Expected: no matches (the constants, `withinMaxLength`, and the empty-name message now live only in `BatchSearch`).

---

## Spec coverage check

- Rules as single source of truth on the model → Task 1.
- Not in the constructor (avoid re-validating DB rows) → Task 1, Step 3 note.
- Enforce in `setName`/`setDescription`/`save` → Task 3.
- Resource keeps type-coercion + orchestration, drops domain helpers/constants → Task 2.
- No global `IllegalArgumentException → 400` mapper; creation handlers must map → Task 4.
- `createBatchSearch`(`search`) and `copySearch` return 400 → Task 4.
- Behavior change: creation now 400s on empty/over-long → covered by Task 4 tests + Task 6/OpenAPI note in Task 4 Step 6.
- Edit endpoint external behavior unchanged → Task 2 uses `BatchSearchResourceTest` as regression guard.
- `null` description handling unchanged on create → `save` calls `validateDescription` for its throwing side-effect only; the raw `batchSearch.description` is still what gets inserted (Task 3, Step 3).

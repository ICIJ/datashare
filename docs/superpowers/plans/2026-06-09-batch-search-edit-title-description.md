# Edit Batch Search Title & Description Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a batch search's owner update its `name` (title) and `description` — in addition to the existing `published` flag — through the existing `PATCH /api/batch/search/:batchid` endpoint, using partial-update semantics.

**Architecture:** Add two owner-scoped single-column setters (`setName`, `setDescription`) to `BatchSearchRepository`, alongside the unchanged `publish`. Rewrite the `updateBatch` controller to read whichever of `published`/`name`/`description` are present in the JSON body, validate them, and call the matching setter(s). Ownership is enforced (and the 404 signal produced) by the `AND user_id = ?` clause already used by `publish`.

**Tech Stack:** Java, Guice, jOOQ (SQLite + Postgres), net.codestory HTTP (`fluent-http`), JUnit 4, Mockito, AssertJ. Build: Maven (`mvn`, no wrapper).

---

## File Structure

- **Modify** `datashare-api/src/main/java/org/icij/datashare/batch/BatchSearchRepository.java` — add `setName` and `setDescription` to the interface (next to `publish`, line 36).
- **Modify** `datashare-db/src/main/java/org/icij/datashare/db/JooqBatchSearchRepository.java` — implement both setters (next to `publish`, lines 341-350). This is the only implementation of the interface.
- **Modify** `datashare-app/src/main/java/org/icij/datashare/web/BatchSearchResource.java` — rewrite `updateBatch` (lines 183-188) for partial update + validation.
- **Modify** `datashare-db/src/test/java/org/icij/datashare/db/JooqBatchSearchRepositoryTest.java` — integration tests for the setters (mirror `test_publish` at lines 675-691).
- **Modify** `datashare-app/src/test/java/org/icij/datashare/web/BatchSearchResourceTest.java` — endpoint tests (extend `test_update_batch_search` at lines 215-221).

Confirmed facts used below:
- jOOQ columns: `BATCH_SEARCH.NAME` (varchar 255), `BATCH_SEARCH.DESCRIPTION` (varchar 4096), `BATCH_SEARCH.UUID`, `BATCH_SEARCH.USER_ID`.
- `JsonData` (`datashare-app/.../web/JsonData.java`) exposes a public `Map<String,Object> data`; `data.containsKey(k)` detects field presence; `asBoolean(k)` casts and throws on a missing/null key.
- `BatchSearchResource` already statically imports `net.codestory.http.payload.Payload.*` (`ok()`, `notFound()`) and imports `PayloadFormatter` and `net.codestory.http.constants.HttpStatus`.
- `JooqBatchSearchRepositoryTest` is `@RunWith(Parameterized.class)` — each test runs once per DB backend automatically.

---

## Task 1: Repository `setName`

**Files:**
- Modify: `datashare-api/src/main/java/org/icij/datashare/batch/BatchSearchRepository.java:36`
- Modify: `datashare-db/src/main/java/org/icij/datashare/db/JooqBatchSearchRepository.java:350`
- Test: `datashare-db/src/test/java/org/icij/datashare/db/JooqBatchSearchRepositoryTest.java:691`

- [ ] **Step 1: Add the interface method**

In `BatchSearchRepository.java`, immediately after the existing `publish` declaration (line 36):

```java
    boolean publish(User user, String batchId, boolean published);

    boolean setName(User user, String batchId, String name);
```

- [ ] **Step 2: Write the failing integration tests**

In `JooqBatchSearchRepositoryTest.java`, after `test_publish_unauthorized_user_does_nothing` (line 691), add:

```java
    @Test
    public void test_set_name() {
        repository.save(new BatchSearch("uuid", singletonList(proxy("prj")), "name", "description",
                asSet("q1", "q2"), new Date(), State.FAILURE, User.local()));

        assertThat(repository.setName(User.local(), "uuid", "new name")).isTrue();
        assertThat(repository.get(User.local(), "uuid").name).isEqualTo("new name");
    }

    @Test
    public void test_set_name_unauthorized_user_does_nothing() {
        repository.save(new BatchSearch("uuid", singletonList(proxy("prj")), "name1", "description1",
                asSet("q1", "q2"), new Date(), State.FAILURE, User.local()));

        assertThat(repository.setName(new User("unauthorized"), "uuid", "hacked")).isFalse();
        assertThat(repository.get(User.local(), "uuid").name).isEqualTo("name1");
    }
```

- [ ] **Step 3: Run the tests to verify they fail to compile**

Run: `mvn -q -pl datashare-db -am test-compile`
Expected: COMPILE FAILURE — `JooqBatchSearchRepository` does not implement `setName` (abstract method not overridden) / `cannot find symbol method setName`.

- [ ] **Step 4: Implement `setName` in the jOOQ repository**

In `JooqBatchSearchRepository.java`, immediately after the `publish` method (closing brace at line 350), add:

```java
    @Override
    public boolean setName(User user, String batchId, String name) {
        return DSL.using(dataSource, dialect).update(BATCH_SEARCH).
                set(BATCH_SEARCH.NAME, name).
                where(BATCH_SEARCH.UUID.eq(batchId).
                        and(BATCH_SEARCH.USER_ID.eq(user.id))).execute() > 0;
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `mvn -q -pl datashare-db -am test -Dtest=JooqBatchSearchRepositoryTest#test_set_name+test_set_name_unauthorized_user_does_nothing`
Expected: PASS (each test green for every parameterized DB backend).

- [ ] **Step 6: Commit**

```bash
git add datashare-api/src/main/java/org/icij/datashare/batch/BatchSearchRepository.java \
        datashare-db/src/main/java/org/icij/datashare/db/JooqBatchSearchRepository.java \
        datashare-db/src/test/java/org/icij/datashare/db/JooqBatchSearchRepositoryTest.java
git commit -m "feat: add setName to BatchSearchRepository"
```

---

## Task 2: Repository `setDescription`

**Files:**
- Modify: `datashare-api/src/main/java/org/icij/datashare/batch/BatchSearchRepository.java`
- Modify: `datashare-db/src/main/java/org/icij/datashare/db/JooqBatchSearchRepository.java`
- Test: `datashare-db/src/test/java/org/icij/datashare/db/JooqBatchSearchRepositoryTest.java`

- [ ] **Step 1: Add the interface method**

In `BatchSearchRepository.java`, immediately after the `setName` declaration added in Task 1:

```java
    boolean setName(User user, String batchId, String name);

    boolean setDescription(User user, String batchId, String description);
```

- [ ] **Step 2: Write the failing integration tests**

In `JooqBatchSearchRepositoryTest.java`, after the `test_set_name_unauthorized_user_does_nothing` test added in Task 1, add:

```java
    @Test
    public void test_set_description() {
        repository.save(new BatchSearch("uuid", singletonList(proxy("prj")), "name", "description",
                asSet("q1", "q2"), new Date(), State.FAILURE, User.local()));

        assertThat(repository.setDescription(User.local(), "uuid", "new description")).isTrue();
        assertThat(repository.get(User.local(), "uuid").description).isEqualTo("new description");
    }

    @Test
    public void test_set_description_unauthorized_user_does_nothing() {
        repository.save(new BatchSearch("uuid", singletonList(proxy("prj")), "name1", "description1",
                asSet("q1", "q2"), new Date(), State.FAILURE, User.local()));

        assertThat(repository.setDescription(new User("unauthorized"), "uuid", "hacked")).isFalse();
        assertThat(repository.get(User.local(), "uuid").description).isEqualTo("description1");
    }
```

- [ ] **Step 3: Run the tests to verify they fail to compile**

Run: `mvn -q -pl datashare-db -am test-compile`
Expected: COMPILE FAILURE — `JooqBatchSearchRepository` does not implement `setDescription` / `cannot find symbol method setDescription`.

- [ ] **Step 4: Implement `setDescription` in the jOOQ repository**

In `JooqBatchSearchRepository.java`, immediately after the `setName` method added in Task 1, add:

```java
    @Override
    public boolean setDescription(User user, String batchId, String description) {
        return DSL.using(dataSource, dialect).update(BATCH_SEARCH).
                set(BATCH_SEARCH.DESCRIPTION, description).
                where(BATCH_SEARCH.UUID.eq(batchId).
                        and(BATCH_SEARCH.USER_ID.eq(user.id))).execute() > 0;
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `mvn -q -pl datashare-db -am test -Dtest=JooqBatchSearchRepositoryTest#test_set_description+test_set_description_unauthorized_user_does_nothing`
Expected: PASS for every parameterized DB backend.

- [ ] **Step 6: Commit**

```bash
git add datashare-api/src/main/java/org/icij/datashare/batch/BatchSearchRepository.java \
        datashare-db/src/main/java/org/icij/datashare/db/JooqBatchSearchRepository.java \
        datashare-db/src/test/java/org/icij/datashare/db/JooqBatchSearchRepositoryTest.java
git commit -m "feat: add setDescription to BatchSearchRepository"
```

---

## Task 3: Controller partial update + validation

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/web/BatchSearchResource.java:183-188`
- Test: `datashare-app/src/test/java/org/icij/datashare/web/BatchSearchResourceTest.java:215-221`

- [ ] **Step 1: Write the failing endpoint tests**

In `BatchSearchResourceTest.java`, replace the existing `test_update_batch_search` (lines 215-221) with the following block (the original published-only assertions are preserved as `test_update_batch_search_published`):

```java
    @Test
    public void test_update_batch_search_published() {
        when(batchSearchRepository.publish(User.local(), "batchId", true)).thenReturn(true).thenReturn(false);

        patch("/api/batch/search/batchId", "{\"data\": {\"published\": true}}").should().respond(200);
        patch("/api/batch/search/batchId", "{\"data\": {\"published\": true}}").should().respond(404);
    }

    @Test
    public void test_update_batch_search_name() {
        when(batchSearchRepository.setName(User.local(), "batchId", "new name")).thenReturn(true);

        patch("/api/batch/search/batchId", "{\"data\": {\"name\": \"new name\"}}").should().respond(200);
    }

    @Test
    public void test_update_batch_search_description() {
        when(batchSearchRepository.setDescription(User.local(), "batchId", "new description")).thenReturn(true);

        patch("/api/batch/search/batchId", "{\"data\": {\"description\": \"new description\"}}").should().respond(200);
    }

    @Test
    public void test_update_batch_search_all_fields() {
        when(batchSearchRepository.publish(User.local(), "batchId", true)).thenReturn(true);
        when(batchSearchRepository.setName(User.local(), "batchId", "new name")).thenReturn(true);
        when(batchSearchRepository.setDescription(User.local(), "batchId", "new description")).thenReturn(true);

        patch("/api/batch/search/batchId",
                "{\"data\": {\"published\": true, \"name\": \"new name\", \"description\": \"new description\"}}")
                .should().respond(200);
    }

    @Test
    public void test_update_batch_search_unauthorized_returns_404() {
        when(batchSearchRepository.setName(User.local(), "batchId", "new name")).thenReturn(false);

        patch("/api/batch/search/batchId", "{\"data\": {\"name\": \"new name\"}}").should().respond(404);
    }

    @Test
    public void test_update_batch_search_empty_body_returns_400() {
        patch("/api/batch/search/batchId", "{\"data\": {}}").should().respond(400);
    }

    @Test
    public void test_update_batch_search_blank_name_returns_400() {
        patch("/api/batch/search/batchId", "{\"data\": {\"name\": \"  \"}}").should().respond(400);
    }

    @Test
    public void test_update_batch_search_name_too_long_returns_400() {
        String longName = "x".repeat(256);
        patch("/api/batch/search/batchId", "{\"data\": {\"name\": \"" + longName + "\"}}").should().respond(400);
    }

    @Test
    public void test_update_batch_search_description_too_long_returns_400() {
        String longDescription = "x".repeat(4097);
        patch("/api/batch/search/batchId", "{\"data\": {\"description\": \"" + longDescription + "\"}}").should().respond(400);
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -q -pl datashare-app -am test -Dtest=BatchSearchResourceTest`
Expected: FAIL — new tests return 200/404 where 400 is expected (no validation yet), and `setName`/`setDescription` are never called (still 404), plus possibly a `ClassCastException`→500 path. The unchanged `test_update_batch_search_published` passes.

- [ ] **Step 3: Rewrite `updateBatch`**

In `BatchSearchResource.java`, replace the method body at lines 183-188 with:

```java
    @Patch("/search/:batchid")
    public Payload updateBatch(String batchId, Context context, JsonData data) {
        User user = (User) context.currentUser();
        Map<String, Object> body = data.data;
        boolean hasPublished = body.containsKey("published");
        boolean hasName = body.containsKey("name");
        boolean hasDescription = body.containsKey("description");

        if (!hasPublished && !hasName && !hasDescription) {
            return PayloadFormatter.error("No updatable field provided (expected published, name or description).", HttpStatus.BAD_REQUEST);
        }

        String name = null;
        if (hasName) {
            name = (String) body.get("name");
            if (name == null || name.trim().isEmpty()) {
                return PayloadFormatter.error("Batch search name cannot be empty.", HttpStatus.BAD_REQUEST);
            }
            if (name.length() > MAX_NAME_LENGTH) {
                return PayloadFormatter.error("Batch search name exceeds " + MAX_NAME_LENGTH + " characters.", HttpStatus.BAD_REQUEST);
            }
        }

        String description = null;
        if (hasDescription) {
            description = (String) body.get("description");
            if (description == null) {
                description = "";
            }
            if (description.length() > MAX_DESCRIPTION_LENGTH) {
                return PayloadFormatter.error("Batch search description exceeds " + MAX_DESCRIPTION_LENGTH + " characters.", HttpStatus.BAD_REQUEST);
            }
        }

        boolean updated = false;
        if (hasPublished) {
            updated |= batchSearchRepository.publish(user, batchId, data.asBoolean("published"));
        }
        if (hasName) {
            updated |= batchSearchRepository.setName(user, batchId, name);
        }
        if (hasDescription) {
            updated |= batchSearchRepository.setDescription(user, batchId, description);
        }
        return updated ? ok() : notFound();
    }
```

- [ ] **Step 4: Add the length constants**

In `BatchSearchResource.java`, add these constants to the class body, just below the field declarations (after `private final PropertiesProvider propertiesProvider;` at line 38):

```java
    private static final int MAX_NAME_LENGTH = 255;
    private static final int MAX_DESCRIPTION_LENGTH = 4096;
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `mvn -q -pl datashare-app -am test -Dtest=BatchSearchResourceTest`
Expected: PASS — all `test_update_batch_search_*` tests green.

- [ ] **Step 6: Commit**

```bash
git add datashare-app/src/main/java/org/icij/datashare/web/BatchSearchResource.java \
        datashare-app/src/test/java/org/icij/datashare/web/BatchSearchResourceTest.java
git commit -m "feat: allow editing batch search title and description via API"
```

---

## Task 4: Update the endpoint OpenAPI description

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/web/BatchSearchResource.java:178-182`

- [ ] **Step 1: Update the `@Operation` description**

In `BatchSearchResource.java`, replace the `@Operation` description text on the `updateBatch` annotation (line 178) so it documents all three editable fields:

```java
    @Operation(description = "Updates a batch search with the given id. The JSON body may contain any of `published` (boolean), `name` (string) and `description` (string); only the provided fields are updated.",
            requestBody = @RequestBody(content = @Content(schema = @Schema(implementation = JsonData.class))))
    @ApiResponse(responseCode = "400", description = "If no updatable field is provided, the name is empty, or name/description exceed their maximum length")
    @ApiResponse(responseCode = "404", description = "If the user issuing the request is not the same as the batch owner in database, it will do nothing (thus returning 404)")
    @ApiResponse(responseCode = "200", description = "If the batch has been updated")
```

- [ ] **Step 2: Verify it compiles**

Run: `mvn -q -pl datashare-app -am test-compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add datashare-app/src/main/java/org/icij/datashare/web/BatchSearchResource.java
git commit -m "docs: document name/description fields on batch search update endpoint"
```

---

## Final verification

- [ ] **Run both affected modules' tests together**

Run: `mvn -q -pl datashare-db,datashare-app -am test -Dtest=JooqBatchSearchRepositoryTest,BatchSearchResourceTest`
Expected: BUILD SUCCESS, all tests pass.

---

## Self-Review Notes

- **Spec coverage:** partial-update semantics (Task 3 presence checks); separate setters keeping `publish` (Tasks 1-2); validation — non-empty name, name ≤255, description ≤4096, 400 on empty body (Task 3 tests + logic); 404 ownership via `user_id` clause (Tasks 1-2 unauthorized tests); non-transactional multi-statement apply (Task 3 sequential `|=`). No DB migration, no `publish` signature change, no frontend — all honored.
- **Type consistency:** `setName(User, String, String)` and `setDescription(User, String, String)` used identically in interface, impl, controller, and all tests. `MAX_NAME_LENGTH`/`MAX_DESCRIPTION_LENGTH` defined in Task 3 Step 4 and referenced in Step 3.
- **No placeholders:** every code and command step is concrete.

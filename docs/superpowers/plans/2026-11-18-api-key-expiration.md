# API Key Expiration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** API keys get an optional expiration date, capped by an optional server-wide max lifetime, and expired keys are rejected like unknown keys.

**Architecture:** Nullable `expiration_date` column on `api_key`. One injectable policy class, `ApiKeyExpiration`, holds every rule (effective expiry, default and validation at creation). It is used by `ApiKeyStoreAdapter` (auth check), `GenApiKeyTask` (creation) and `ApiKeyResource` (GET display).

**Tech Stack:** Java 17, Liquibase, jOOQ, Guice assisted inject, fluent-http, JOpt + picocli, JUnit 4 + fest-assert + Mockito.

**Spec:** `docs/superpowers/specs/2026-11-18-api-key-expiration-design.md`

## Global Constraints

- Setting name `apiKeyMaxLifetime`, ISO-8601 duration (`P90D`), **no default** (unset = no cap). Never use JOpt `defaultsTo` or a picocli `defaultValue` for it: a default clobbers the `-s` settings file.
- Effective expiry = `min(expiration_date, creation_date + cap)`; either term absent is ignored; both absent = never expires.
- An expired key gets the exact same 401 body as an unknown key ("authentication required").
- With no cap and no expiration requested, behavior is identical to today.
- Time comes from `DatashareTime.getInstance()` so tests can freeze it (`DatashareTimeRule`).
- Test command shape (T3 worktree): `mvn -q -pl <module> -am test -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.gitcommitid.skip=true`. Multiple classes are comma-separated, never `+`.
- Before pushing: `make format` and `make lint`.
- One operation per line, no comments unless they explain a non-obvious why.

## Review Focus

1. **Cap set, old key with null expiration:** it must expire at `creation_date + cap`. Pinned in Task 2 (`test_old_key_without_expiration_is_capped_from_creation`).
2. **Re-creating a key for the same user:** the on-conflict update must overwrite `expiration_date`, including back to `NULL`. Pinned in Task 1 (`test_save_again_resets_expiration_date`).
3. **`PUT /api/key/:userId` with an empty body:** must still return 201 (the current client sends no body). Pinned in Task 4 (`test_generate_key_without_body`).
4. **Malformed date in body or CLI** (`"expirationDate": "tomorrow"`, `--expiration-date 2027-13-01`): 400 / `EXIT_VALIDATION`, not 500 / stack trace. Pinned in Tasks 4 and 5.
5. **Malformed cap** (`apiKeyMaxLifetime=90d`): fail loudly at startup, not silently treat it as no cap. Pinned in Task 2 (`test_invalid_max_lifetime_throws`).

---

### Task 1: Store the expiration date

**Files:**
- Create: `datashare-db/src/main/resources/liquibase/changelog/changes/053-api-key-expiration-date.yml` (changeSet id `90`, author `pirhoo`, `addColumn` `expiration_date` type `datetime`, nullable)
- Modify: `datashare-db/src/main/resources/liquibase/changelog/db.changelog.yml` (include after line 159, `052-...`)
- Modify: `datashare-api/src/main/java/org/icij/datashare/user/ApiKey.java`
- Modify: `datashare-api/src/main/java/org/icij/datashare/user/DatashareApiKey.java`
- Modify: `datashare-db/src/main/java/org/icij/datashare/db/JooqApiKeyRepository.java`
- Test: `datashare-db/src/test/java/org/icij/datashare/db/JooqApiKeyRepositoryTest.java`

**Interfaces:**
- Produces: `Date ApiKey.getExpirationDate()` (nullable). `DatashareApiKey(SecretKey secretKey, User user, Date expirationDate)` and `DatashareApiKey(String hashedKey, User user, Date creationDate, Date expirationDate)`. Existing constructors delegate with `null` expiration.

- [ ] **Step 1: Write the failing tests** in `JooqApiKeyRepositoryTest` (time frozen at `2020-07-08T12:13:14Z`):

```java
@Test
public void test_save_and_get_expiration_date() throws NoSuchAlgorithmException {
    SecretKey secretKey = DatashareApiKey.generateSecretKey();
    Date expiration = Date.from(Instant.parse("2020-08-01T00:00:00Z"));
    repository.save(new DatashareApiKey(secretKey, User.local(), expiration));

    assertThat(repository.get(getBase64Encoded(secretKey)).getExpirationDate()).isEqualTo(expiration);
}

@Test
public void test_save_without_expiration_date_reads_null() throws NoSuchAlgorithmException {
    repository.save(new DatashareApiKey(User.local()));
    assertThat(repository.get(User.local()).getExpirationDate()).isNull();
}

@Test
public void test_save_again_resets_expiration_date() throws NoSuchAlgorithmException {
    Date expiration = Date.from(Instant.parse("2020-08-01T00:00:00Z"));
    repository.save(new DatashareApiKey(DatashareApiKey.generateSecretKey(), User.local(), expiration));
    repository.save(new DatashareApiKey(User.local()));

    assertThat(repository.get(User.local()).getExpirationDate()).isNull();
}
```

- [ ] **Step 2: Run, verify RED.** `mvn -q -pl datashare-db -am test -Dtest=JooqApiKeyRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.gitcommitid.skip=true`. Expected: compilation failure, `getExpirationDate` / constructor not found. Show this output to Pierre.

- [ ] **Step 3: Add the migration and regenerate jOOQ.** jOOQ codegen reads the live dev DB, so run `make migrate` first, then the module build that regenerates `org.icij.datashare.db.tables.ApiKey` (it must expose `API_KEY.EXPIRATION_DATE`).

- [ ] **Step 4: Implement.** Add the field and getter to `DatashareApiKey`. In `JooqApiKeyRepository.save`, write `expiration_date` in both the insert values and the `doUpdate().set(...)` branch, and convert `Date` to `LocalDateTime` at UTC like `creation_date`. In `createApiKey`, read it back (null-safe).

- [ ] **Step 5: Run, verify GREEN** with the Step 2 command (all tests in the class pass).

- [ ] **Step 6: Commit** `feat(api-key): store expiration date`.

---

### Task 2: Expiration policy and auth check

**Files:**
- Create: `datashare-app/src/main/java/org/icij/datashare/session/ApiKeyExpiration.java`
- Modify: `datashare-app/src/main/java/org/icij/datashare/session/ApiKeyStoreAdapter.java`
- Modify: `datashare-cli/src/main/java/org/icij/datashare/cli/DatashareCliOptions.java` (constant `API_KEY_MAX_LIFETIME_OPT = "apiKeyMaxLifetime"` only; parsing options come in Task 5)
- Test: `datashare-app/src/test/java/org/icij/datashare/session/ApiKeyExpirationTest.java`, `datashare-app/src/test/java/org/icij/datashare/session/ApiKeyStoreAdapterTest.java` (create)

**Interfaces:**
- Consumes: `ApiKey.getExpirationDate()`, `ApiKey.getCreationDate()` (Task 1).
- Produces, in `ApiKeyExpiration` (`@Singleton`, `@Inject ApiKeyExpiration(PropertiesProvider)`, the only constructor; tests build it with `new PropertiesProvider(Map.of("apiKeyMaxLifetime", "P90D"))` or `new PropertiesProvider()` for no cap):
  - `Date effectiveExpiration(ApiKey apiKey)`: nullable, per the Global Constraints formula.
  - `boolean isExpired(ApiKey apiKey)`: `true` iff the effective expiration is non-null and `now` is strictly after it.
  - `Date expirationForNewKey(Date requested)`: `requested == null` gives `now + cap`, or `null` without a cap. Throws `IllegalArgumentException` when `requested` is not after `now`, or when it is after `now + cap`. The messages are `"expiration date must be in the future"` and `"expiration date exceeds the maximum lifetime of P90D"` (the cap's `Duration` printed via `toString()` may read `PT2160H`; that is acceptable).
- Produces: `ApiKeyStoreAdapter(ApiKeyRepository, ApiKeyExpiration)`.

- [ ] **Step 1: Write the failing tests.** Use `DatashareTimeRule("2026-01-01T00:00:00Z")`, a cap of `P90D` and `DatashareApiKey(hash, user, creationDate, expirationDate)`.
  - `test_no_cap_no_expiration_never_expires`: `isExpired` is false and `effectiveExpiration` is null.
  - `test_expiration_date_in_past_is_expired`: expiration `2025-12-31T23:59:59Z` gives `true`.
  - `test_expiration_date_in_future_is_not_expired`.
  - `test_old_key_without_expiration_is_capped_from_creation`: creation `2025-09-01T00:00:00Z`, no expiration, cap 90 days. `isExpired` is true and `effectiveExpiration` equals `2025-11-30T00:00:00Z`.
  - `test_cap_shorter_than_expiration_wins`: creation `2026-01-01`, expiration `2027-01-01`. Effective expiration is `2026-04-01T00:00:00Z`.
  - `test_new_key_defaults_to_cap`: `expirationForNewKey(null)` equals `2026-04-01T00:00:00Z`. Without a cap it is `null`.
  - `test_new_key_beyond_cap_throws`, `test_new_key_in_past_throws`: `IllegalArgumentException`.
  - `test_invalid_max_lifetime_throws`: `new ApiKeyExpiration(new PropertiesProvider(Map.of("apiKeyMaxLifetime", "90d")))` throws `IllegalArgumentException`.
  - `ApiKeyStoreAdapterTest`, with a mocked repo:
    - `test_get_login_returns_user_for_valid_key`
    - `test_get_login_returns_null_for_expired_key`
    - `test_get_login_returns_null_for_unknown_key`

- [ ] **Step 2: Run, verify RED.** `mvn -q -pl datashare-app -am test -Dtest=ApiKeyExpirationTest,ApiKeyStoreAdapterTest -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.gitcommitid.skip=true`. Expected: compilation failure (class missing). Show the output.

- [ ] **Step 3: Implement** `ApiKeyExpiration`. Parse the cap once in the constructor with `Duration.parse` and wrap `DateTimeParseException` as `IllegalArgumentException`. `Duration.parse` accepts `P90D`. Then make `ApiKeyStoreAdapter.getLogin` return `null` when `apiKeyExpiration.isExpired(apiKey)`. Guice binds `ApiKeyExpiration` just-in-time, so no module change is needed. Check that `ServerMode.java:37` still compiles.

- [ ] **Step 4: Run, verify GREEN** (Step 2 command, plus `ApiKeyFilterTest` to confirm the 401 body is unchanged).

- [ ] **Step 5: Commit** `feat(api-key): reject expired keys`.

---

### Task 3: Create keys with an expiration

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/tasks/GenApiKeyTask.java`
- Modify: `datashare-app/src/main/java/org/icij/datashare/tasks/DatashareTaskFactory.java:41`
- Test: `datashare-app/src/test/java/org/icij/datashare/tasks/GenApiKeyTaskTest.java`

**Interfaces:**
- Consumes: `ApiKeyExpiration.expirationForNewKey(Date)` (Task 2), `DatashareApiKey(SecretKey, User, Date)` (Task 1).
- Produces: `GenApiKeyTask createGenApiKey(User user, @Nullable Date expirationDate)` replaces the one-arg method. The constructor is `@Inject GenApiKeyTask(ApiKeyRepository, ApiKeyExpiration, @Assisted User, @Assisted @Nullable Date expirationDate)`. Use `javax.annotation.Nullable`, on both the factory parameter and the constructor parameter, so Guice accepts a null assisted value. `call()` propagates the `IllegalArgumentException` before saving anything.

- [ ] **Step 1: Write the failing tests.**
  - Update `test_call` to pass `null` and `new ApiKeyExpiration(new PropertiesProvider())`. It asserts the saved key's `getExpirationDate()` is null.
  - `test_call_saves_requested_expiration`.
  - `test_call_defaults_to_cap`: cap 90 days, frozen time, saved expiration = now + 90 days.
  - `test_call_rejects_expiration_beyond_cap`: `IllegalArgumentException`, and `verify(apiKeyRepository, never()).save(any())`.

- [ ] **Step 2: Run, verify RED** with `-Dtest=GenApiKeyTaskTest`. Expected: compilation failure (constructor arity). Show the output.

- [ ] **Step 3: Implement.** Update the callers so they compile:
  - `ApiKeyResource` calls `createGenApiKey(user, null)` until Task 4.
  - `CliApp.handleApiKeyCreate` calls `createGenApiKey(localUser(userName), null)` until Task 5.
  - `CliAppTaskDispatchTest` and `ApiKeyResourceTest` stub `createGenApiKey(any(), any())` / `createGenApiKey(User.local(), null)`.

- [ ] **Step 4: Run, verify GREEN** with `-Dtest=GenApiKeyTaskTest,ApiKeyResourceTest,CliAppTaskDispatchTest`.

- [ ] **Step 5: Commit** `feat(api-key): set expiration when generating a key`.

---

### Task 4: REST: request and expose the expiration

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/web/ApiKeyResource.java`
- Test: `datashare-app/src/test/java/org/icij/datashare/web/ApiKeyResourceTest.java`

**Interfaces:**
- Consumes: `createGenApiKey(User, Date)` (Task 3), `ApiKeyExpiration.effectiveExpiration(ApiKey)` (Task 2), `ApiKeyRepository.get(User)`.
- Produces: `@Inject ApiKeyResource(DatashareTaskFactory, ApiKeyRepository, ApiKeyExpiration)`.
  - `PUT` reads the optional body `{"expirationDate": "<ISO-8601 instant>"}` from `context.request().content()` with Jackson. An empty or blank body means `null`.
  - An unparsable date, or an `IllegalArgumentException` from the task, gives `BadRequestException` (`org.icij.datashare.web.errors`), which returns 400.
  - `GET` returns `{"hashedKey": ..., "expirationDate": <Instant.toString() or null>}`. The expiration comes from `effectiveExpiration(repository.get(user))`. `GetApiKeyTask` is unchanged.
  - Update the swagger `@ExampleObject`s for both routes.

- [ ] **Step 1: Write the failing tests.** In `setUp`, build the resource with a mocked `ApiKeyRepository` and `new ApiKeyExpiration(new PropertiesProvider())`.
  - `test_generate_key_without_body`: 201, `createGenApiKey(User.local(), null)` is called.
  - `test_generate_key_with_expiration_date`: `put(url, "{\"expirationDate\":\"2027-02-01T00:00:00Z\"}")` gives 201 and verifies `createGenApiKey(User.local(), Date.from(Instant.parse("2027-02-01T00:00:00Z")))`.
  - `test_generate_key_with_invalid_date_is_bad_request`: `"tomorrow"` gives 400.
  - `test_generate_key_rejected_by_policy_is_bad_request`: the task's `call()` throws `IllegalArgumentException("expiration date must be in the future")`. Expect 400 with a body that contains that message.
  - `test_get_key_returns_expiration_date`: the repo returns a key with expiration `2027-02-01T00:00:00Z`. The body contains `"expirationDate":"2027-02-01T00:00:00Z"`.

- [ ] **Step 2: Run, verify RED** with `-Dtest=ApiKeyResourceTest`. Show the output.

- [ ] **Step 3: Implement** in `ApiKeyResource`. Switch the response maps to `HashMap<String, Object>` so `null` serializes.

- [ ] **Step 4: Run, verify GREEN** with `-Dtest=ApiKeyResourceTest,ApiKeyFilterTest`.

- [ ] **Step 5: Commit** `feat(api-key): accept and return expiration date over REST`.

---

### Task 5: CLI: max lifetime setting and `--expiration-date`

**Files:**
- Modify: `datashare-cli/src/main/java/org/icij/datashare/cli/DatashareCliOptions.java` (`API_KEY_EXPIRATION_DATE_OPT` constant, JOpt `apiKeyMaxLifetime` option)
- Modify: `datashare-cli/src/main/java/org/icij/datashare/cli/DatashareCli.java:191-197` (register `apiKeyMaxLifetime`)
- Modify: `datashare-cli/src/main/java/org/icij/datashare/cli/command/ServerOptions.java` (next to `--sessionTtlSeconds`, line 68)
- Modify: `datashare-cli/src/main/java/org/icij/datashare/cli/command/ApiKeyCreateCommand.java`
- Modify: `datashare-app/src/main/java/org/icij/datashare/CliApp.java:141-143, 1021-1027`
- Test: `datashare-cli/src/test/java/org/icij/datashare/cli/command/ApiKeyCommandTest.java`, `DatashareCommandTest.java`, `RetroCompatibilityTest.java`, `datashare-cli/src/test/java/org/icij/datashare/cli/LegacyInvocationTest.java`, `datashare-app/src/test/java/org/icij/datashare/CliAppTaskDispatchTest.java`

**Interfaces:**
- Consumes: `createGenApiKey(User, Date)` (Task 3).
- Produces:
  - Property `apiKeyMaxLifetime`, from JOpt `--apiKeyMaxLifetime` and picocli `--apiKeyMaxLifetime` on `ServerOptions`. It is a `String`, with no default, and is only `put` when non-null.
  - Property `apiKeyExpirationDate`, from picocli `api-key create --expiration-date` only. The legacy JOpt `-k` path keeps creating keys without an expiration. It is a `String`, `YYYY-MM-DD`, and is only `put` when non-null.
  - `static int handleApiKeyCreate(DatashareTaskFactory, String userName, String expirationDate)`. It parses with `LocalDate.parse(...).atStartOfDay(ZoneOffset.UTC)`. A `DateTimeParseException` or `IllegalArgumentException` returns `error(e.getMessage(), "validation", EXIT_VALIDATION, false)`.

- [ ] **Step 1: Write the failing tests.**
  - `ApiKeyCommandTest.test_api_key_create_with_expiration_date`: `parse("api-key","create","alice","--expiration-date","2027-02-01")` includes `entry("apiKeyExpirationDate","2027-02-01")`.
  - `test_api_key_create_without_expiration_date`: the property is null.
  - `DatashareCommandTest.test_api_key_max_lifetime`: `parse("app","start","--apiKeyMaxLifetime","P90D")` includes the entry.
  - `test_api_key_max_lifetime_has_no_default`: `parse("app","start")` gives `getProperty("apiKeyMaxLifetime")` null.
  - `LegacyInvocationTest`: `--apiKeyMaxLifetime P90D` lands in properties.
  - `RetroCompatibilityTest`: add `assertPropertyEqual(legacy, picocli, "apiKeyMaxLifetime")` next to line 130.
  - `CliAppTaskDispatchTest`:
    - `test_create_api_key_with_expiration_date` verifies `createGenApiKey(any(), eq(Date.from(Instant.parse("2027-02-01T00:00:00Z"))))`.
    - `test_create_api_key_with_invalid_date_exits_validation`: `"2027-13-01"` returns `EXIT_VALIDATION`.

- [ ] **Step 2: Run, verify RED.**
  - `mvn -q -pl datashare-cli -am test -Dtest=ApiKeyCommandTest,DatashareCommandTest,LegacyInvocationTest,RetroCompatibilityTest -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.gitcommitid.skip=true`
  - Then the same with `-pl datashare-app -Dtest=CliAppTaskDispatchTest`.
  - Show the output.

- [ ] **Step 3: Implement.** In `CliApp`, line 142 passes `properties.getProperty(API_KEY_EXPIRATION_DATE_OPT)`. Update the existing `handleApiKeyCreate(taskFactory, "alice")` test calls to pass `null`.

- [ ] **Step 4: Run, verify GREEN** (both Step 2 commands).

- [ ] **Step 5: Commit** `feat(api-key): add CLI options for key expiration`.

---

### Task 6: Whole-branch check

- [ ] **Step 1:** Run `make format` then `make lint`. Commit any formatter drift as `style: format`.
- [ ] **Step 2:** Run every test class touched above in one command per module (in the background), and report the results.
- [ ] **Step 3:** Manual smoke test in SERVER mode, started with `--apiKeyMaxLifetime P1D`:
  1. `PUT /api/key/<user>` with no body.
  2. `GET` shows an expiration about 1 day out.
  3. A call with `Authorization: Bearer <key>` gives 200.
  4. Set the row's `expiration_date` to the past with psql, then the same call gives 401.

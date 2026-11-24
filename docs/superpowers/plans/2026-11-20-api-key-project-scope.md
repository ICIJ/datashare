# API Keys Scoped to Projects Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A user can hold several API keys, each optionally limited to some projects, and a scoped key never reaches a project outside its scope.

**Architecture:** Scope rows live in a new `api_key_project` join table; `api_key.user_id` stops being unique. `ApiKeyFilter` wraps the found user in a `ScopedUser` whose `getProjects()` is the real user's projects intersected with the key's. One static check, `ScopedUser.allows(user, projectIds)`, closes the paths that do not read `getProjects()`: the two role annotations, task visibility and creation, batch searches by id, user history.

**Tech Stack:** Java 17, Liquibase, jOOQ, Guice assisted inject, fluent-http, picocli, JUnit 4 + fest-assert + Mockito.

**Spec:** `docs/superpowers/specs/2026-11-19-api-key-project-scope-design.md`

## Global Constraints

- An empty scope means unscoped. A scope whose projects were all deleted or revoked is still scoped and reaches nothing.
- No foreign key from `api_key_project` to `project`.
- Every existing key stays unscoped. The client routes `GET`, `PUT`, `DELETE /api/key/:userId` keep their #2559 request and response shapes.
- A scoped user is refused on project id `*` (instance-wide, domain-wide, "any project") even when the user is admin. Refusals reuse the existing 403 body (`Authorizer.INSUFFICIENT_ROLE`).
- Tasks are stored with the unscoped user. `ScopedUser` must never reach `Task.args` (they are serialized with `@JsonTypeInfo(CLASS)`, `Task.java:53`).
- `PostLoginEnroller.enroll` receives the unscoped user.
- Key write routes (`PUT`, `POST`, both `DELETE`s) answer 403 to a key-authenticated request. Read routes stay open.
- Test command (T3 worktree): `mvn -q -pl <module> -am test -Dtest=<A>,<B> -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.gitcommitid.skip=true`. Comma-separated, never `+`. Run it in the background and report each poll.
- jOOQ codegen reads the live dev DB: run `make migrate` before building after a migration (see the `dsbuild` schema drift note if codegen misses the table).
- Commit through the `icij:commit` skill. Before pushing: `make format` and `make lint`.
- One operation per line, no comments unless they explain a non-obvious why.

## Review Focus

1. **Scope fully deleted** (key scoped to `a`, project `a` deleted): every read returns nothing; no path treats "empty intersection" as "no filter". Pinned in Task 2 (`test_scope_with_no_remaining_project_reaches_nothing`) and Task 6 (`test_history_of_scoped_user_with_no_project_left_is_empty`).
2. **Task without a project argument** seen by a scoped key: hidden from lists and lookups, refused on `PUT /api/task/:id`. Pinned in Task 4.
3. **Rotation must kill every key**: legacy `PUT` and CLI `create` without `--project` leave exactly one key, including when the user had scoped keys. Pinned in Task 1 (`test_replace_deletes_every_key_of_the_user`).
4. **Messy `projects` body** on `POST /keys`: duplicates collapse, `[]`/`null`/missing mean unscoped, a non-array is 400. Pinned in Task 7.
5. **Upgrade with existing keys** on SQLite: rows and `expiration_date` survive the table rebuild, and a second key for the same user then inserts. Pinned in Task 1 Step 6 (manual upgrade check).

## Calls made in this plan (check these, Pierre)

- History read and bulk delete reuse the existing "event touches any listed project" filter (`JooqRepository.java:245-252`). An event linked to an in-scope and an out-of-scope project stays visible and deletable.
- `DELETE /api/batch/search` (delete all) answers 403 to a scoped key instead of growing a project-filtered delete.
- A batch search by id outside the scope answers like an unknown id.

---

### Task 0: Restack on the latest #2559

The branch is 5 commits behind `origin/feat/2531-api-key-expiration` (30-day default, `88c5a14c6`..`69631f45a`). Those commits touch `ApiKeyExpiration`, `ApiKeyResource` and `ApiKeyCreateCommand`, which later tasks edit.

- [ ] **Step 1:** Rebase onto `origin/feat/2531-api-key-expiration` with the `icij:sync-branch` skill.
- [ ] **Step 2:** Run `ApiKeyExpirationTest,GenApiKeyTaskTest,ApiKeyResourceTest` in `datashare-app` and `ApiKeyCommandTest` in `datashare-cli`. Expected: green.

---

### Task 1: Store several keys per user, each with a scope

**Files:**
- Create: `datashare-db/src/main/resources/liquibase/changelog/changes/054-api-key-project-scope.yml`
- Modify: `datashare-db/src/main/resources/liquibase/changelog/db.changelog.yml` (include after `053-…`)
- Modify: `datashare-api/src/main/java/org/icij/datashare/user/ApiKey.java`, `DatashareApiKey.java`, `ApiKeyRepository.java`
- Modify: `datashare-db/src/main/java/org/icij/datashare/db/JooqApiKeyRepository.java`
- Test: `datashare-db/src/test/java/org/icij/datashare/db/JooqApiKeyRepositoryTest.java`

**Interfaces:**
- Produces:
  - `List<String> ApiKey.getProjects()` (never null, empty = unscoped).
  - `DatashareApiKey(SecretKey secretKey, User user, Date expirationDate, List<String> projects)` and `DatashareApiKey(String hashedKey, User user, Date creationDate, Date expirationDate, List<String> projects)`. Existing constructors delegate with `List.of()`.
  - `ApiKeyRepository`: `boolean save(ApiKey)` (plain insert, key + scope rows in one transaction), `ApiKey get(String base64Key)` (with projects), `ApiKey get(User)` (newest by `creation_date`, then `id`), `List<ApiKey> getAll(User)` (newest first, with projects), `boolean delete(User)` (all keys + rows), `boolean delete(User user, String hashedKey)`, `boolean replace(ApiKey)` (delete all the user's keys, then save, one transaction).

- [ ] **Step 1: Write the failing tests.** Replace `test_save_api_key_for_user_overrides_old_key` (save no longer overrides). Advance `DatashareTime` between two saves so "newest" is deterministic.

```java
@Test public void test_save_twice_keeps_both_keys()            // getAll(local) has 2 keys, both get(base64) non-null
@Test public void test_get_user_returns_newest_key()           // save k1, +1s, save k2 -> get(local).getId() == k2.getId()
@Test public void test_save_and_get_projects()                 // save with List.of("a","b") -> get(base64).getProjects() containsOnly "a","b"
@Test public void test_key_without_projects_is_unscoped()      // getProjects() isEmpty
@Test public void test_delete_one_key_by_hash()                // delete(local, k1.getId()) true; k2 still there; scope rows of k1 gone
@Test public void test_delete_one_key_of_another_user_is_refused() // delete(new User("bob"), k1.getId()) false; k1 still there
@Test public void test_delete_user_deletes_all_keys_and_scopes()
@Test public void test_replace_deletes_every_key_of_the_user() // two scoped keys, replace(unscoped k3) -> getAll(local) == [k3], k3 projects empty
```

Scope-row absence is asserted with a direct `select count(*) from api_key_project where api_key_id = ?` through `dbRule.dataSource`.

- [ ] **Step 2: Run, verify RED.** `mvn -q -pl datashare-db -am test -Dtest=JooqApiKeyRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.gitcommitid.skip=true`. Expected: compilation failure (`getProjects`, `getAll`, `replace` not found). Show this output to Pierre.

- [ ] **Step 3: Write the migration** `054-api-key-project-scope.yml`, author `pirhoo`:
  - `92` (all dbms): `createTable api_key_project(api_key_id varchar(96) not null, prj_id varchar(96) not null)` + unique index `idx_api_key_project_unique` on `(api_key_id, prj_id)`.
  - `93` (postgresql): `dropUniqueConstraint` on `api_key.user_id` + `createIndex api_key_user_id` on `api_key(user_id)`. The constraint was created unnamed in `019-create_apikey.yml`; read its real name with `\d api_key` on the dev DB (expected `api_key_user_id_key`).
  - `94`-`98` (sqlite), one statement each, like `027` changesets 51-55: create `api_key_temporary(id varchar(96) primary key, user_id varchar(96) not null, creation_date datetime not null, expiration_date datetime)`, `INSERT … SELECT id, user_id, creation_date, expiration_date FROM api_key`, `DROP TABLE api_key`, rename, `CREATE INDEX api_key_user_id ON api_key(user_id)`.

  Then `make migrate` and rebuild so jOOQ generates `API_KEY_PROJECT`.

- [ ] **Step 4: Implement** the `ApiKey`/`DatashareApiKey` changes and the repository. `get(User)` and `getAll` read scope rows with one extra query on `api_key_project` per call (group by key id), not one per key.

- [ ] **Step 5: Run, verify GREEN** (same command). Both parameterized dialects pass.

- [ ] **Step 6: Manual upgrade check (SQLite).** Check out `origin/feat/2531-api-key-expiration` in a scratch worktree, start once on a fresh SQLite file to migrate, insert one key with an `expiration_date`. Point this branch at the same file and run migrations. Expected: `select * from api_key` still shows the row with its date; inserting a second row for the same `user_id` succeeds. Paste both outputs.

- [ ] **Step 7: Commit** via `icij:commit`: `feat(api-key): store several keys per user with a project scope`.

---

### Task 2: Narrow the user of a scoped key

**Files:**
- Create: `datashare-app/src/main/java/org/icij/datashare/session/ScopedUser.java`
- Modify: `datashare-app/src/main/java/org/icij/datashare/session/ApiKeyStore.java`, `ApiKeyStoreAdapter.java`, `ApiKeyFilter.java`
- Test: `datashare-app/src/test/java/org/icij/datashare/session/ScopedUserTest.java` (new), `ApiKeyStoreAdapterTest.java`, `ApiKeyFilterTest.java`

**Interfaces:**
- Consumes: `ApiKey.getProjects()` (Task 1).
- Produces:
  - `public class ScopedUser extends DatashareUser`, constructor `ScopedUser(DatashareUser user, Collection<String> projectIds)`. It copies through `super(user)` and keeps `user` as the unscoped delegate; it never mutates `user`.
  - `List<Project> getProjects()` override: `unscoped().getProjects()` filtered on `Project.getId()` in scope. `getProjectNames()` and `isGranted(...)` follow from it.
  - `Map<String, Object> getDetails()` override: same map, with the project list at the user's project key path (`groups_by_applications.datashare` by default) narrowed to the scope. Expose the key path from `User` with a protected getter if needed.
  - `static User unscoped(User user)`: the private delegate when `user` is a `ScopedUser`, else `user`.
  - `static boolean allows(User user, Collection<String> projectIds)`: `true` when `user` is not a `ScopedUser`; otherwise `true` only when `projectIds` is non-empty, contains no `*`, and every id passes `user.isGranted(id)`.
  - `ApiKeyStore.get(String base64Key)` returns the matching non-expired `ApiKey`, or `null`. It replaces `getLogin`.

- [ ] **Step 1: Write the failing tests.**

`ScopedUserTest`:
```java
@Test public void test_projects_are_intersected_with_scope()   // user a,b,c; scope b,c,z -> getProjectNames containsOnly b,c
@Test public void test_scope_with_no_remaining_project_reaches_nothing() // user a; scope z -> getProjects isEmpty, isGranted("z") false
@Test public void test_wrapped_user_is_not_mutated()           // after wrapping, original.getProjectNames() still a,b,c
@Test public void test_details_project_list_is_narrowed()      // getDetails() groups_by_applications.datashare == [b]
@Test public void test_unscoped_returns_delegate()             // ScopedUser.unscoped(scoped) isSameAs original; unscoped(plain) isSameAs plain
@Test public void test_allows()                                // plain user: allows(["*"]) true; scoped: [] false, ["*"] false, ["b","a"] false when a not in scope, ["b"] true
```

`ApiKeyStoreAdapterTest`: `test_get_returns_key_with_projects`, and adapt the expired/unknown tests to expect `null` from `get`.

`ApiKeyFilterTest`:
```java
@Test public void test_scoped_key_sets_scoped_user()           // key projects ["p1"], users.find -> user with p1,p2; captured currentUser instanceof ScopedUser, getProjectNames == [p1]
@Test public void test_unscoped_key_sets_found_user()          // captured currentUser isSameAs users.find result
@Test public void test_enroller_receives_unscoped_user()       // verify(postLoginEnroller).enroll(same(foundUser))
@Test public void test_session_cookie_ignores_scoped_key()     // cookie present -> apiKeyStore never called
```

- [ ] **Step 2: Run, verify RED.** `mvn -q -pl datashare-app -am test -Dtest=ScopedUserTest,ApiKeyStoreAdapterTest,ApiKeyFilterTest -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.gitcommitid.skip=true`. Expected: compilation failure (`ScopedUser`, `ApiKeyStore.get`). Show it.

- [ ] **Step 3: Implement** `ScopedUser`, the store change, and the filter: enroll the found user first, then set the current user to `new ScopedUser(dsUser, key.getProjects())` when the scope is non-empty, else the found user.

- [ ] **Step 4: Run, verify GREEN.** Also run `CsrfFilterTest,FormAuthFilterTest,ModeFilterOrderTest` (they reference `ApiKeyStore`/`ApiKeyFilter`).

- [ ] **Step 5: Commit:** `feat(api-key): narrow the user of a scoped key to its projects`.

---

### Task 3: Role annotations refuse out-of-scope and wildcard checks

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/policies/PolicyAnnotation.java`, `TaskPolicyAnnotation.java`
- Test: `datashare-app/src/test/java/org/icij/datashare/policies/PolicyAnnotationTest.java`, `TaskPolicyAnnotationTest.java`

**Interfaces:**
- Consumes: `ScopedUser.allows(User, Collection<String>)` (Task 2), `TaskFinder.projectIdsOf(Task<?>)` (defined here, used again in Task 4).
- Produces: `public static List<String> TaskFinder.projectIdsOf(Task<?> task)`: the batch search's or batch download's project ids, else the `DEFAULT_PROJECT_OPT` arg as a one-element list, else an empty list. Same extraction as `TaskPolicyAnnotation.isAllowedSingleTask` (`TaskPolicyAnnotation.java:62-80`).

- [ ] **Step 1: Write the failing tests.** Each uses a `ScopedUser` whose real user holds the role that would otherwise pass.

```java
// PolicyAnnotationTest
@Test public void test_scoped_user_refused_on_instance_admin()   // real user instance admin -> 403 INSUFFICIENT_ROLE
@Test public void test_scoped_user_refused_on_domain_admin()
@Test public void test_scoped_user_refused_without_project_id()  // resolveProjectId -> "*" -> 403
@Test public void test_scoped_user_refused_outside_scope()       // member of p2, scope p1, route on p2 -> 403
@Test public void test_scoped_user_allowed_inside_scope()        // p1 in scope -> next payload
// TaskPolicyAnnotationTest
@Test public void test_scoped_user_refused_on_multi_task_route() // singleTask=false -> 403
@Test public void test_scoped_user_refused_on_batch_download_with_out_of_scope_project() // projects p1,p2; scope p1
@Test public void test_scoped_user_allowed_on_own_task_in_scope()
@Test public void test_is_allowed_refuses_scoped_user_outside_scope() // TaskPolicyChecker.isAllowed path (MCP)
```

- [ ] **Step 2: Run, verify RED** (`PolicyAnnotationTest,TaskPolicyAnnotationTest`). Expected: the scoped tests fail with 200 instead of 403. Show it.

- [ ] **Step 3: Implement.** `PolicyAnnotation.apply`: after resolving `projectId`, refuse when `!ScopedUser.allows(user, List.of(projectId))`. `TaskPolicyAnnotation`: refuse in the `!singleTask()` branch when `!ScopedUser.allows(user, List.of("*"))`; in `isAllowedSingleTask`, refuse when `!ScopedUser.allows(user, projectIdsOf(task))` so `isAllowed` (MCP) gets it too.

- [ ] **Step 4: Run, verify GREEN.**

- [ ] **Step 5: Commit:** `feat(api-key): keep scoped keys out of role checks beyond their projects`.

---

### Task 4: Tasks: visibility, creation, stored user

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/tasks/TaskFinder.java`
- Modify: `datashare-app/src/main/java/org/icij/datashare/web/TaskResource.java`, `ExtractionMappingResource.java`
- Test: `datashare-app/src/test/java/org/icij/datashare/tasks/TaskFinderTest.java`, `datashare-app/src/test/java/org/icij/datashare/web/TaskResourceTest.java`

**Interfaces:**
- Consumes: `ScopedUser.allows`, `ScopedUser.unscoped(User)`, `TaskFinder.projectIdsOf`.
- Produces: nothing new for later tasks.

Web tests set the scoped user with an inline filter: `routes.filter((uri, context, next) -> { context.setCurrentUser(scopedUser); return next.get(); })`.

- [ ] **Step 1: Write the failing tests.**

```java
// TaskFinderTest
@Test public void test_scoped_user_does_not_see_owned_task_outside_scope()    // findVisibleTasksFor: task on p2, scope p1 -> absent
@Test public void test_scoped_user_does_not_see_task_without_project()
@Test public void test_scoped_user_sees_batch_search_only_if_all_projects_in_scope() // p1 visible, p1+p2 hidden
@Test public void test_scoped_user_lookup_outside_scope_is_forbidden()        // findVisibleTaskFor -> ForbiddenException (same as not owned)
@Test public void test_unscoped_user_still_sees_all_owned_tasks()
// TaskResourceTest
@Test public void test_scoped_user_cannot_create_task_on_project_outside_scope() // PUT /api/task/:id args defaultProject=p2 -> 403
@Test public void test_scoped_user_cannot_create_task_without_project()          // no defaultProject -> 403
@Test public void test_task_started_by_scoped_user_stores_unscoped_user()        // in scope -> 201; stored task.getUser().getClass() == DatashareUser.class
@Test public void test_scoped_user_cannot_read_result_of_task_outside_scope()    // GET /api/task/:id/result -> 403
```

- [ ] **Step 2: Run, verify RED** (`TaskFinderTest,TaskResourceTest`). Show it.

- [ ] **Step 3: Implement.**
  - `TaskFinder.findVisibleTasksFor` and `findVisibleTaskFor`: drop tasks where `!ScopedUser.allows(user, projectIdsOf(task))`. In `findVisibleTaskFor`, an owned out-of-scope task falls through to the existing `ForbiddenException`.
  - `TaskResource.createTask`: before `new Task<>`, answer 403 when `!ScopedUser.allows(user, projectIdsOf(taskView))`.
  - Add `private static User taskUser(Context context)` = `ScopedUser.unscoped((User) context.currentUser())` and use it at every place the request user becomes a task or batch owner: `TaskResource.java:192, 310, 319, 355, 395, 398, 418, 452, 494, 607, 614`, and `ExtractionMappingResource.java:84`. Checks that only read access keep `context.currentUser()`.

- [ ] **Step 4: Run, verify GREEN.** Also run `McpServerModeTest` (MCP `list_tasks` goes through `findVisibleTasks`).

- [ ] **Step 5: Commit:** `feat(api-key): limit task lists, lookups and creation to the key scope`.

---

### Task 5: Batch searches

**Files:**
- Modify: `datashare-db/src/main/java/org/icij/datashare/db/JooqBatchSearchRepository.java:245` (and `getTotal` if it has the same branch)
- Modify: `datashare-app/src/main/java/org/icij/datashare/web/BatchSearchResource.java`, `TaskResource.java:343`
- Test: `datashare-db/src/test/java/org/icij/datashare/db/JooqBatchSearchRepositoryTest.java`, `datashare-app/src/test/java/org/icij/datashare/web/BatchSearchResourceTest.java`

**Interfaces:**
- Consumes: `ScopedUser.allows`.

- [ ] **Step 1: Write the failing tests.**

```java
// JooqBatchSearchRepositoryTest
@Test public void test_get_records_project_filter_is_intersected_with_user_projects() // search on p2, user projects [p1], webQuery project=[p2] -> empty; getTotal -> 0
// BatchSearchResourceTest (scoped user, scope p1)
@Test public void test_scoped_user_get_batch_search_outside_scope_is_not_found()      // GET /api/batch/search/:id on p2 -> 404
@Test public void test_scoped_user_cannot_patch_or_delete_batch_search_outside_scope()
@Test public void test_scoped_user_cannot_delete_all_batch_searches()                 // DELETE /api/batch/search -> 403
```

- [ ] **Step 2: Run, verify RED.** Show it.

- [ ] **Step 3: Implement.** Repository: `filteredProjects` = requested projects intersected with `projectsIds` (for every user: the client filter must not widen access). Resource: one private helper that loads the batch search and treats it as unknown when `!ScopedUser.allows(user, projectIds)`; use it in the by-id routes (`:123, :158, :189, :224-232, :281, :328`) and in the copy route (`TaskResource.java:343`). `deleteAll` (`:312`) answers 403 when `!ScopedUser.allows(user, List.of("*"))`.

- [ ] **Step 4: Run, verify GREEN.**

- [ ] **Step 5: Commit:** `feat(api-key): limit batch searches to the key scope`.

---

### Task 6: User history, `/me`, starred documents

**Files:**
- Modify: `datashare-api/src/main/java/org/icij/datashare/Repository.java`, `datashare-db/src/main/java/org/icij/datashare/db/JooqRepository.java`
- Modify: `datashare-app/src/main/java/org/icij/datashare/web/UserResource.java`, `DocumentResource.java:446`
- Test: `datashare-db/src/test/java/org/icij/datashare/db/JooqRepositoryTest.java`, `datashare-app/src/test/java/org/icij/datashare/web/UserResourceTest.java`, `DocumentResourceTest.java`

**Interfaces:**
- Consumes: `ScopedUser`, its `getDetails()` override (Task 2).
- Produces: `boolean Repository.deleteUserHistory(User user, UserEvent.Type type, String... projectIds)`: deletes the user's events of that type linked to any of `projectIds` (same filter as `getUserHistory`, `JooqRepository.java:245-252`), with their `user_history_project` rows.

- [ ] **Step 1: Write the failing tests.**

```java
// JooqRepositoryTest
@Test public void test_delete_user_history_by_projects()   // events on p1 and p2; delete(type, "p1") -> only p2 event left
// UserResourceTest (scoped user, scope p1, real user p1+p2)
@Test public void test_history_of_scoped_user_is_limited_to_scope()          // no projects param -> only p1 events; projects=p2 -> empty
@Test public void test_history_of_scoped_user_with_no_project_left_is_empty() // scope [z] -> empty list, total 0 (never "no filter")
@Test public void test_scoped_user_bulk_delete_only_deletes_scope()
@Test public void test_me_lists_only_scope_projects()                         // GET /api/users/me: projects and groups_by_applications.datashare == [p1]
// DocumentResourceTest
@Test public void test_scoped_user_starred_documents_limited_to_scope()      // GET /api/document/starred -> no p2 document
```

- [ ] **Step 2: Run, verify RED.** Show it.

- [ ] **Step 3: Implement.** In `UserResource`, for a `ScopedUser`: the effective project ids are the requested ones intersected with `getProjectNames()`, or `getProjectNames()` when none is requested. When that set is empty, return an empty page (reads) or 204 without deleting (bulk delete), never the unfiltered call. `/me` needs no code beyond Task 2's `getDetails()` override; the test pins it. Starred: filter the result on `ScopedUser.allows(user, List.of(document.getProject().getId()))`.

- [ ] **Step 4: Run, verify GREEN.**

- [ ] **Step 5: Commit:** `feat(api-key): limit user history and starred documents to the key scope`.

---

### Task 7: Key management REST routes

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/tasks/GenApiKeyTask.java`, `DatashareTaskFactory.java`
- Modify: `datashare-app/src/main/java/org/icij/datashare/web/ApiKeyResource.java`
- Test: `datashare-app/src/test/java/org/icij/datashare/tasks/GenApiKeyTaskTest.java`, `datashare-app/src/test/java/org/icij/datashare/web/ApiKeyResourceTest.java`

**Interfaces:**
- Consumes: `ApiKeyRepository.save/replace/getAll/delete(User, String)` (Task 1).
- Produces (used by Task 8):
  - `GenApiKeyTask createGenApiKey(User user, @Nullable Date expirationDate)`: rotates (`repository.replace`), unscoped. Existing signature, new behavior.
  - `GenApiKeyTask createGenApiKey(User user, @Nullable Date expirationDate, List<String> projects)`: adds (`repository.save`) with that scope, even when empty. Two `@AssistedInject` constructors.

- [ ] **Step 1: Write the failing tests.**

```java
// GenApiKeyTaskTest
@Test public void test_legacy_create_replaces_all_keys()   // verify(repository).replace(any()); never save
@Test public void test_create_with_projects_adds_a_key()   // verify(repository).save(argThat(k -> k.getProjects().equals(List.of("p1"))))
// ApiKeyResourceTest
@Test public void test_list_keys()                         // GET /api/key/local/keys -> 200, [{hashedKey, projects, creationDate, expirationDate}]
@Test public void test_add_key_with_projects()             // POST /api/key/local/keys {"projects":["p1","p1"]} -> 201 {"apiKey"}; task created with ["p1"]
@Test public void test_add_key_without_body_adds_unscoped()// POST no body -> 201; createGenApiKey(user, null, List.of())
@Test public void test_add_key_with_ungranted_project()    // {"projects":["p1","nope"]} -> 400, body names "nope"
@Test public void test_add_key_with_non_array_projects()   // {"projects":"p1"} -> 400
@Test public void test_delete_one_key()                    // DELETE /api/key/local/keys/hash -> 204
@Test public void test_delete_unknown_key()                // -> 404
@Test public void test_write_routes_refused_with_api_key() // PUT, POST /keys, DELETE, DELETE /keys/:hash through ApiKeyFilter -> 403
@Test public void test_read_routes_open_with_api_key()     // GET and GET /keys through ApiKeyFilter -> 200
@Test public void test_other_user_keys_forbidden()         // GET/POST/DELETE /api/key/bob/keys as local -> 403
```

Existing `PUT`/`GET`/`DELETE` tests stay unchanged and must keep passing (client contract).

- [ ] **Step 2: Run, verify RED** (`GenApiKeyTaskTest,ApiKeyResourceTest`). Show it.

- [ ] **Step 3: Implement.**
  - Tasks and factory as in Interfaces. `DELETE /keys/:hashedKey` calls `apiKeyRepository.delete(user, hashedKey)` directly, like `GET` reads the repository.
  - `ApiKeyResource`: routes `GET /:userId/keys`, `POST /:userId/keys`, `DELETE /:userId/keys/:hashedKey`, plus `@Options` for both new paths. Every write route starts with `verifyOwnership` then one shared `refuseApiKeyAuthentication(context)` (the existing `PUT` check, moved into a method). Parse `projects` next to `requestedExpirationDate`: missing/null/`[]` = `List.of()`, an array of strings is deduplicated in order, anything else is a 400. Refused projects = those failing `currentUser.isGranted(id)`; 400 message `"projects not granted: " + String.join(", ", refused)`.
  - Update the `@Operation` descriptions of the three legacy routes (`PUT` rotates all keys, `GET` returns the newest, `DELETE` removes all).

- [ ] **Step 4: Run, verify GREEN.**

- [ ] **Step 5: Commit:** `feat(api-key): list, add and delete scoped keys over REST`.

---

### Task 8: CLI

**Files:**
- Create: `datashare-cli/src/main/java/org/icij/datashare/cli/command/ApiKeyListCommand.java`
- Modify: `datashare-cli/src/main/java/org/icij/datashare/cli/DatashareCliOptions.java` (constants only, as #2559 did for `API_KEY_EXPIRATION_DATE_OPT`)
- Modify: `datashare-cli/src/main/java/org/icij/datashare/cli/command/ApiKeyCommand.java`, `ApiKeyCreateCommand.java`, `ApiKeyDeleteCommand.java`
- Modify: `datashare-app/src/main/java/org/icij/datashare/CliApp.java:148-160, 1030-1080`
- Test: `datashare-cli/src/test/java/org/icij/datashare/cli/command/ApiKeyCommandTest.java`, `datashare-app/src/test/java/org/icij/datashare/CliAppTaskDispatchTest.java`

**Interfaces:**
- Consumes: both `createGenApiKey` overloads (Task 7), `ApiKeyRepository.getAll`, `ApiKeyRepository.delete(User, String)`, `ApiKeyExpiration.effectiveExpiration`, `Repository.getProjects(List<String>)`.
- Produces: properties `apiKeyProjects` (comma-joined), `listApiKeys` (user name), `apiKeyHash`. Constants `API_KEY_PROJECTS_OPT`, `LIST_API_KEYS_OPT`, `API_KEY_HASH_OPT`.
- Handlers: `handleApiKeyCreate(DatashareTaskFactory, Repository, String userName, String expirationDate, List<String> projects)`, `handleApiKeyList(ApiKeyRepository, ApiKeyExpiration, String userName)`, `handleApiKeyDelete(DatashareTaskFactory, ApiKeyRepository, String userName, String hashedKey)` (no hash: the existing task; a hash: the repository).

- [ ] **Step 1: Write the failing tests.**

```java
// ApiKeyCommandTest
@Test public void test_create_with_projects()      // "api-key create alice --project a --project b" -> apiKeyProjects == "a,b"
@Test public void test_list()                      // "api-key list alice" -> listApiKeys == "alice", mode CLI
@Test public void test_delete_one_key()            // "api-key delete alice --key h" -> deleteApiKey == "alice", apiKeyHash == "h"
// CliAppTaskDispatchTest
@Test public void test_create_without_projects_rotates()          // createGenApiKey(user, null) called; 2-arg overload never
@Test public void test_create_with_projects_adds()                // createGenApiKey(user, null, List.of("a","b"))
@Test public void test_create_with_unknown_project_fails()        // repository knows only "a"; ["a","nope"] -> EXIT_VALIDATION, stderr names "nope", no task created
@Test public void test_list_prints_one_line_per_key()             // "<hash>\t*\t-" for unscoped no-expiry, "<hash>\ta,b\t2027-02-01T00:00:00Z"
@Test public void test_delete_one_unknown_key_is_not_found()      // EXIT_NOT_FOUND
```

- [ ] **Step 2: Run, verify RED.** `mvn -q -pl datashare-cli -am test -Dtest=ApiKeyCommandTest …` and `mvn -q -pl datashare-app -am test -Dtest=CliAppTaskDispatchTest …`. Show both.

- [ ] **Step 3: Implement.** `--project` is a repeatable picocli `List<String>` option; `--key` a `String`. `ApiKeyListCommand` mirrors `ApiKeyGetCommand`; register it in `ApiKeyCommand.subcommands`. In `CliApp.runTaskWorker`, read the new properties and dispatch; get `Repository`, `ApiKeyRepository` and `ApiKeyExpiration` from `mode`. List output: one line per key, tab-separated `hash`, projects joined by `,` (`*` when unscoped), effective expiration ISO instant (`-` when none), to stdout. Add `--project` and `--key` examples to the commands' `description` blocks.

- [ ] **Step 4: Run, verify GREEN.** Also `RetroCompatibilityTest,LegacyInvocationTest` (legacy `-k alice` still rotates).

- [ ] **Step 5: Commit:** `feat(api-key): create scoped keys, list and delete one key from the CLI`.

---

### Task 9: Audit the remaining routes, then verify the branch

- [ ] **Step 1: Audit.** In `datashare-app/src/main/java/org/icij/datashare/web/`, list every route that (a) filters by `user.id` or a client-supplied project list without passing through `getProjects()`/`isGranted()`, or (b) takes a project id in the path or body without `@Policy` and without an `isGranted`/`IndexAccessVerifier` check. Already handled: Tasks 3 to 6. Start from `DocumentResource.java:291` (starred by project), `DocumentUserRecommendationResource`, `McpIndexSearch`, `ProjectResource`. Write the list with a verdict per route into the PR description draft (`~/2532-pr.md`).
- [ ] **Step 2: Fix each leak found** with the Task 4 pattern: failing test with a scoped user first (show RED), then `ScopedUser.allows` or a project filter, then GREEN, one commit per resource: `feat(api-key): limit <resource> to the key scope`.
- [ ] **Step 3: Full changed-module run in the background**, polled: `mvn -q -pl datashare-db,datashare-cli,datashare-app -am test -Dmaven.gitcommitid.skip=true`. Separate the 4 known environment failures (see `reference_datashare_local_test_gotchas`) from new ones.
- [ ] **Step 4:** `make format` then `make lint`. Commit formatter drift as `style: format`.
- [ ] **Step 5:** Hostile self-review of `git diff origin/feat/2531-api-key-expiration...HEAD`: for each hunk, the input it now handles differently. Report the list to Pierre.

# ES proxy grant checks on HEAD and OPTIONS: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop the Elasticsearch search proxy from serving `HEAD` and `OPTIONS` requests without checking the caller's project grants, and stop `OPTIONS` from forwarding the request body as an Elasticsearch path.

**Architecture:** One new grant helper in `IndexAccessVerifier` (shape check, then `allMatch(isGranted)`), called by `esHead` which gains a `Context` parameter. `esOptions` loses its bogus second `String` parameter, stops calling Elasticsearch entirely, and answers a static method list. `checkPath` is not touched.

**Tech Stack:** Java, fluent-http (`net.codestory`), JUnit 4, Mockito, FEST assertions, `FluentRestTest` against a real Elasticsearch (`ElasticsearchRule`).

**Spec:** `docs/superpowers/specs/2026-08-04-es-proxy-grant-checks-design.md`

## Global Constraints

- Branch `fix/2312-es-proxy-grant-checks`, based on `main`. Worktree: `.worktrees/2312-es-proxy-grants`. Work only in that worktree.
- TDD is mandatory: every new test must be shown FAILING against unmodified production code before the implementation is written. A test that has never been RED is not a test.
- Every new test in `IndexResourceTest` gets its **own** `configure(...)` call. `ProdWebServerRule` is a `@ClassRule` in `AbstractProdWebServerTest`, so configuration leaks between tests.
- **Do not touch `IndexAccessVerifier.checkPath`.** Its grant test is fused into a boolean expression with the operation flags; restructuring a security function under test to save a line is out of scope.
- **Do not touch `createIndex`, `closeIndex` (`_close`), `openIndex` (`_open`), or `createIndexPreflight`.** Those endpoints belong to PR #2311, which is open on branch `refactor/2199-namespaced-index-authorization`. Editing them here creates a collision.
- **No grant check on `esOptions`.** Browser preflights are sent without credentials; a 401 would break legitimate cross-origin preflights. It is safe because the response becomes identical for every index.
- The new helper is named `checkIndicesGranted` (named for the check, not for a caller) so #2311 can adopt it instead of introducing a second one.
- Tests need Elasticsearch at `elasticsearch:9200`. Run Maven in the **background** and poll; never block on it.
- Single-test command shape: `mvn -pl datashare-app -am test -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false`. Multiple classes are **comma**-separated (a `+` silently produces a fake green). Never drop `-am`.

---

## File Structure

No new files. Four files change.

| File | Responsibility after this change |
| --- | --- |
| `datashare-app/src/main/java/org/icij/datashare/utils/IndexAccessVerifier.java` | Gains `checkIndicesGranted(String, Context)`: the grant check for an index-addressed endpoint, with no path-or-operation policy. Everything else unchanged. |
| `datashare-app/src/main/java/org/icij/datashare/web/IndexResource.java` | `esHead` authorizes before forwarding; `esOptions` answers a static method list and never calls Elasticsearch. |
| `datashare-app/src/test/java/org/icij/datashare/utils/IndexAccessVerifierTest.java` | Unit coverage for the new helper. Gains a Mockito `Context` mock helper; the file has none today. |
| `datashare-app/src/test/java/org/icij/datashare/web/IndexResourceTest.java` | End-to-end coverage: `esHead` refuses an ungranted index and still serves a granted one; `esOptions` advertises the route's own methods. |

---

### Task 1: `checkIndicesGranted` helper

The grant check `esHead` needs, split out so it carries no path-or-operation policy. Nothing calls it yet at the end of this task; Task 2 wires it up.

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/utils/IndexAccessVerifier.java` (add a method after `checkIndices`, which ends at line 26)
- Test: `datashare-app/src/test/java/org/icij/datashare/utils/IndexAccessVerifierTest.java`

**Interfaces:**
- Consumes: existing `IndexAccessVerifier.checkIndices(String)`, which returns the validated string and throws `IllegalArgumentException` on a malformed name.
- Produces: `public static String checkIndicesGranted(String indices, Context context)`. Returns `indices` unchanged when every comma-separated name is granted to `context.currentUser()`. Throws `IllegalArgumentException` for a malformed name (the caller maps it to 400) and `net.codestory.http.errors.UnauthorizedException` for an ungranted one (fluent-http maps it to 401). Task 2 calls this.

**Background the implementer needs:**

`context.currentUser()` returns a `net.codestory.http.security.User`; the codebase casts it to `org.icij.datashare.session.DatashareUser`, which extends `org.icij.datashare.user.User`. `User.isGranted(String)` is exact-string containment over granted project names (`datashare-api/src/main/java/org/icij/datashare/user/User.java:188-190`), never a prefix match.

In tests, `User.localUser(id, projectNames...)` stores the granted names in the user's details map under `groups_by_applications.datashare` (`User.java:207-220`), and `getProjects()` unions that with the `projects` field (`User.java:131-134`).

- [ ] **Step 1: Write the failing tests**

Add these imports to `IndexAccessVerifierTest.java` (the file currently imports only `org.junit.Test` and the two static assertion helpers):

```java
import net.codestory.http.Context;
import net.codestory.http.errors.UnauthorizedException;
import org.icij.datashare.session.DatashareUser;
import org.icij.datashare.user.User;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
```

Add these tests and the helper at the end of the class:

```java
    @Test
    public void test_check_indices_granted_returns_indices_when_granted() {
        assertThat(IndexAccessVerifier.checkIndicesGranted("foo", contextFor("foo"))).isEqualTo("foo");
    }

    @Test
    public void test_check_indices_granted_refuses_ungranted_index() {
        assertThrows(UnauthorizedException.class, () -> IndexAccessVerifier.checkIndicesGranted("bar", contextFor("foo")));
    }

    @Test
    public void test_check_indices_granted_refuses_when_one_of_several_is_ungranted() {
        // a foreign index piggybacked on a granted one is still refused
        assertThrows(UnauthorizedException.class, () -> IndexAccessVerifier.checkIndicesGranted("foo,bar", contextFor("foo")));
    }

    @Test
    public void test_check_indices_granted_refuses_prefix_match_on_a_granted_name() {
        // isGranted is exact containment: "foobar" must not inherit the grant on "foo"
        assertThrows(UnauthorizedException.class, () -> IndexAccessVerifier.checkIndicesGranted("foobar", contextFor("foo")));
    }

    @Test
    public void test_check_indices_granted_rejects_malformed_name_before_checking_grants() {
        // shape first: a malformed name is a 400, not a 401, even for a user granted nothing
        assertThrows(IllegalArgumentException.class, () -> IndexAccessVerifier.checkIndicesGranted("foo!", contextFor("foo")));
    }

    private static Context contextFor(String... grantedProjects) {
        Context context = mock(Context.class);
        when(context.currentUser()).thenReturn(new DatashareUser(User.localUser("cecile", grantedProjects)));
        return context;
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run in the background, then poll:

```bash
mvn -pl datashare-app -am test -Dtest=IndexAccessVerifierTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: **compilation failure**, `cannot find symbol: method checkIndicesGranted(java.lang.String,net.codestory.http.Context)`. That is the RED state for a method that does not exist yet. Do not proceed until you have seen it.

- [ ] **Step 3: Write the minimal implementation**

In `IndexAccessVerifier.java`, immediately after the closing brace of `checkIndices` (line 26), add:

```java
    /** Shape check, then grants: the index-addressed counterpart of checkPath, without its
     *  path-and-operation policy. IllegalArgumentException (400) for a malformed name,
     *  UnauthorizedException (401) for an ungranted one. */
    static public String checkIndicesGranted(String indices, Context context) {
        String[] indexes = checkIndices(indices).split(",");
        DatashareUser currentUser = (DatashareUser) context.currentUser();
        if (!stream(indexes).allMatch(currentUser::isGranted)) {
            throw new UnauthorizedException();
        }
        return indices;
    }
```

No new imports: `Context`, `UnauthorizedException`, `DatashareUser` and the static `java.util.Arrays.stream` are all already imported in this file (lines 3-12).

- [ ] **Step 4: Run the tests to verify they pass**

```bash
mvn -pl datashare-app -am test -Dtest=IndexAccessVerifierTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS, all tests in the class green (the five new ones plus the six that already existed).

- [ ] **Step 5: Commit**

Use the `/commit` skill. Intended message:

```
feat(index): add a grant check for index-addressed proxy endpoints
```

Staged files: `IndexAccessVerifier.java`, `IndexAccessVerifierTest.java`.

---

### Task 2: `esHead` checks grants

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/web/IndexResource.java:100-110` (the `esHead` method and its annotations)
- Test: `datashare-app/src/test/java/org/icij/datashare/web/IndexResourceTest.java`

**Interfaces:**
- Consumes: `IndexAccessVerifier.checkIndicesGranted(String, Context)` from Task 1.
- Produces: nothing later tasks depend on.

**Background the implementer needs:**

Today `esHead` is:

```java
    @Head("/search/:path:")
    public Payload esHead(final String path) throws IOException {
        try {
            return new Payload(indexer.executeRaw("HEAD", path, null));
        } catch (IllegalArgumentException e){
            return PayloadFormatter.error(e, HttpStatus.BAD_REQUEST);
        }
    }
```

It takes no `Context`, so it structurally cannot see the caller. Any authenticated user can probe any index or document and read existence off the status code.

The leading path segment holds the comma-separated indices, the same segment `checkPath` reads (`IndexAccessVerifier.java:34`). Adding a `Context` parameter alongside one route parameter is an established pattern in this file: `esPost` (`IndexResource.java:131`) and `closeIndex` do exactly that, and fluent-http fills the trailing parameter via `Context.extract`.

For the tests: `ElasticsearchRule es = new ElasticsearchRule(3)` creates **three** real indices with random names (`commons-test/.../ElasticsearchRule.java:51,82-89`); `es.getIndexNames()[i]` names them and all three exist in Elasticsearch. `LocalUserFilter`'s test constructor takes the granted project names as varargs (`LocalUserFilter.java:29`), so passing only `es.getIndexNames()[0]` grants index 0 and not index 1. That is what makes the refusal test prove the grant check rather than the index's absence.

`UnauthorizedException` escapes the `catch (IllegalArgumentException)` and fluent-http answers 401, the same refusal the existing `test_no_auth_get_forward_request_to_elastic_if_granted_to_read_index` asserts.

- [ ] **Step 1: Write the failing tests**

Add to `IndexResourceTest.java`, next to the other `/search/` tests:

```java
    @Test
    public void test_head_refuses_ungranted_index() {
        configure(routes -> routes.add(new IndexResource(indexer, propertiesProvider))
                .filter(new LocalUserFilter(propertiesProvider, jooqRepository, es.getIndexNames()[0])));
        // indexNames[1] exists in elasticsearch but is not granted, so a 401 proves the grant
        // check rather than the index being absent
        head("/api/index/search/%s".formatted(es.getIndexNames()[1])).should().respond(401);
    }

    @Test
    public void test_head_still_serves_granted_index() {
        configure(routes -> routes.add(new IndexResource(indexer, propertiesProvider))
                .filter(new LocalUserFilter(propertiesProvider, jooqRepository, es.getIndexNames()[0])));
        head("/api/index/search/%s".formatted(es.getIndexNames()[0])).should().respond(200);
    }
```

No new imports: `LocalUserFilter`, `IndexResource` and `head(...)` (from `FluentRestTest`, via `AbstractProdWebServerTest`) are all already available in this class.

- [ ] **Step 2: Run the tests to verify the first one fails**

```bash
mvn -pl datashare-app -am test -Dtest=IndexResourceTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `test_head_refuses_ungranted_index` FAILS with "expected 401 but was 200" (that is the existence oracle). `test_head_still_serves_granted_index` passes already; it is the guard against the fix disabling the endpoint. Do not proceed until you have seen the 200.

- [ ] **Step 3: Write the minimal implementation**

Replace `esHead` and add a 401 to its documented responses:

```java
    @Operation(description = "Head request useful for JavaScript API (for example to test if an index exists)")
    @ApiResponse(responseCode = "200", description = "returns 200")
    @ApiResponse(responseCode = "400", description = "returns 400 if there is an error from ElasticSearch")
    @ApiResponse(responseCode = "401", description = "returns 401 if the user is not granted the index")
    @Head("/search/:path:")
    public Payload esHead(final String path, Context context) throws IOException {
        try {
            IndexAccessVerifier.checkIndicesGranted(path.split("/")[0], context);
            return new Payload(indexer.executeRaw("HEAD", path, null));
        } catch (IllegalArgumentException e){
            return PayloadFormatter.error(e, HttpStatus.BAD_REQUEST);
        }
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
mvn -pl datashare-app -am test -Dtest=IndexResourceTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: both new tests PASS, and no previously-green test in the class regresses.

- [ ] **Step 5: Commit**

Use the `/commit` skill. Intended message:

```
fix(index): check project grants before forwarding a HEAD to elasticsearch
```

Staged files: `IndexResource.java`, `IndexResourceTest.java`.

---

### Task 3: `esOptions` stops calling Elasticsearch

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/web/IndexResource.java:257-275` (the `esOptions` method, its `@Operation` text and its annotations)
- Test: `datashare-app/src/test/java/org/icij/datashare/web/IndexResourceTest.java:99-105` (rename one existing test) and a new test

**Interfaces:**
- Consumes: existing `IndexAccessVerifier.checkIndices(String)`, `IndexAccessVerifier.isAsyncSearchStatusPath(String)`, `PayloadFormatter.allowMethods(String...)`.
- Produces: nothing later tasks depend on.

**Background the implementer needs.** Read this before editing; the bug is in the parameter list, not the body.

Today the method is:

```java
    @Options("/search/:path:")
    public Payload esOptions(final String index, final String path, Context context) throws IOException {
        // For OPTIONS on "_async_search/<id>", net.codestory puts the whole splat in
        // `index` and leaves `path` empty, so fall back to `index` in that case.
        String effectivePath = (path != null && !path.isEmpty()) ? path : index;
        if (effectivePath != null && IndexAccessVerifier.isAsyncSearchStatusPath(effectivePath)) {
            return PayloadFormatter.allowMethods("OPTIONS", "GET", "DELETE");
        }
        try {
            IndexAccessVerifier.checkIndices(index);
            return PayloadFormatter.allowMethods(indexer.executeRaw("OPTIONS", path, null));
        } catch (IllegalArgumentException e){
            return PayloadFormatter.error(e, HttpStatus.BAD_REQUEST);
        }
    }
```

The route `@Options("/search/:path:")` declares **one** parameter, but the method declares three. fluent-http fills `params[0]` (the splat) into `index`, then fills every remaining parameter from `Context.extract(type)`, and `Context.extract(String.class)` returns the **request body**. So `path` is the request body, and it is that value which reaches `indexer.executeRaw("OPTIONS", path, null)`. The value that is shape-checked (`index`) is not the value that is executed (`path`).

With an empty body the forwarded path is `""`, which `executeRaw` normalises to `/` (`ElasticsearchIndexer.java:234`), so the preflight currently answers with the `Allow` header of the Elasticsearch **root**: `GET,DELETE,HEAD`. That advertises DELETE, which the proxy allows only on an async-search status path, and omits POST, the main search verb. For `OPTIONS`, `executeRaw` returns only that header (`ElasticsearchIndexer.java:240-242`).

Deleting the second `String` parameter is what removes the body-steering: one route parameter means one method parameter, so nothing is filled from `Context.extract`. The `index`/`path` fallback and its comment go with it, since there is no longer a pair to disambiguate. `Context` is dropped too, as nothing left in the method uses it, and so is `throws IOException`, as there is no longer an Elasticsearch call.

`FluentRestTest.options(String)` cannot attach a body, so the body-steering path itself is not directly testable; deleting the parameter is what closes it, and the Allow-list assertion is what proves the method no longer echoes Elasticsearch's root.

- [ ] **Step 1: Write the failing test and rename the stale one**

Add:

```java
    @Test
    public void test_options_advertises_the_methods_this_route_accepts() {
        configure(routes -> routes.add(new IndexResource(indexer, propertiesProvider))
                .filter(new LocalUserFilter(propertiesProvider, jooqRepository, es.getIndexNames())));
        // must be the proxy's own method set, not the Allow header of the elasticsearch root
        Response response = options("/api/index/search/%s".formatted(es.getIndexName())).response();
        String allowMethods = String.join(",", response.header("Access-Control-Allow-Methods"));
        assertThat(allowMethods).contains("OPTIONS").contains("GET").contains("POST").contains("HEAD");
    }
```

`Response`, `assertThat` and `options(...)` are already imported/available in this class (see `test_async_search_options_advertises_get_and_delete`, which uses the same header idiom).

Then rename the existing test at line 100 from `test_no_auth_options_forward_request_to_elastic` to `test_options_checks_index_name_shape`, because after this task it forwards nothing. Leave its three assertions untouched:

```java
    @Test
    public void test_options_checks_index_name_shape() {
        configure(routes -> routes.add(new IndexResource(indexer, propertiesProvider)).filter(new LocalUserFilter(propertiesProvider, jooqRepository, es.getIndexNames())));
        options("/api/index/search/%s".formatted(es.getIndexName())).should().respond(200);
        options("/api/index/search/  /").should().respond(400);
        options("/api/index/search/  \\").should().respond(400);
    }
```

- [ ] **Step 2: Run the tests to verify the new one fails**

```bash
mvn -pl datashare-app -am test -Dtest=IndexResourceTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `test_options_advertises_the_methods_this_route_accepts` FAILS. The header today is the Elasticsearch root's `GET,DELETE,HEAD`, so the `contains("OPTIONS")` assertion fails first. The renamed test passes unchanged. Do not proceed until you have seen the failure.

- [ ] **Step 3: Write the minimal implementation**

Replace the whole `esOptions` method and its `@Operation`/`@ApiResponse` block:

```java
    @Operation(description = "Preflight request with OPTIONS. Answers the method set this route accepts without forwarding to Elasticsearch. For an async-search status path (_async_search/<id>) it returns Allow: OPTIONS, GET, DELETE.")
    @ApiResponse(responseCode = "200", description = "returns OPTIONS, GET, POST, HEAD (or OPTIONS, GET, DELETE for an async-search status path)")
    @ApiResponse(responseCode = "400", description = "returns 400 if the index name is malformed")
    @Options("/search/:path:")
    public Payload esOptions(final String path) {
        if (path != null && IndexAccessVerifier.isAsyncSearchStatusPath(path)) {
            return PayloadFormatter.allowMethods("OPTIONS", "GET", "DELETE");
        }
        try {
            IndexAccessVerifier.checkIndices(path);
            return PayloadFormatter.allowMethods("OPTIONS", "GET", "POST", "HEAD");
        } catch (IllegalArgumentException e){
            return PayloadFormatter.error(e, HttpStatus.BAD_REQUEST);
        }
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
mvn -pl datashare-app -am test -Dtest=IndexResourceTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS. In particular `test_async_search_options_advertises_get_and_delete` must stay green: the splat for `/api/index/search/_async_search/some-id` is `_async_search/some-id`, which `isAsyncSearchStatusPath` still matches, now read from `path` instead of the deleted `index`.

- [ ] **Step 5: Commit**

Use the `/commit` skill. Intended message:

```
fix(index): stop the OPTIONS preflight forwarding the request body to elasticsearch
```

Staged files: `IndexResource.java`, `IndexResourceTest.java`.

---

### Task 4: Full-class verification

The three tasks each ran one test class. This confirms nothing else in the module moved.

**Files:** none modified. Verification only.

- [ ] **Step 1: Run both touched test classes together**

```bash
mvn -pl datashare-app -am test -Dtest=IndexAccessVerifierTest,IndexResourceTest -Dsurefire.failIfNoSpecifiedTests=false
```

Note the **comma**: a `+` separator silently matches nothing and reports a fake green.

Expected: both classes fully green.

- [ ] **Step 2: Confirm no other caller depended on the changed signatures**

```bash
git grep -n "esOptions\|esHead" -- datashare-app/src
```

Expected: only the two route methods in `IndexResource.java` and the test names. Both are route entry points, so no production caller exists. If this turns up anything else, fix it before finishing.

- [ ] **Step 3: Review the diff as a hostile reviewer**

```bash
git diff main...HEAD
```

For each hunk, state what input it now handles differently. The intended behavior changes, and nothing more:
1. `HEAD /api/index/search/<ungranted>` : was 200 (or 500 for an absent index), now 401.
2. `HEAD /api/index/search/<granted>` : unchanged, 200.
3. `HEAD /api/index/search/<malformed>` : unchanged, 400.
4. `OPTIONS /api/index/search/<index>` : still 200, but `Access-Control-Allow-Methods` is now `OPTIONS, GET, POST, HEAD` instead of Elasticsearch's root `GET, DELETE, HEAD`.
5. `OPTIONS` with a body : the body is no longer forwarded as an Elasticsearch path.
6. `OPTIONS /api/index/search/_async_search/<id>` : unchanged, `OPTIONS, GET, DELETE`.

Confirm `checkPath`, `createIndex`, `_close`, `_open` and `createIndexPreflight` are absent from the diff.

---

## Coordination with #2311

PR #2311 (branch `refactor/2199-namespaced-index-authorization`) gates `createIndex`, `_close` and `_open`, and introduces a `<project>.entities` suffix rule. Two notes for whoever lands second:

- #2311 currently gates those three endpoints with `ForbiddenException.requireGranted` (403), while this branch refuses with `UnauthorizedException` (401) to match the proxy's existing `checkPath`. The two refusal codes coexist on different endpoints today; unifying them is not in either scope.
- #2311's `baseProject` mapping (which authorizes `p.entities` against `p`) is **not** applied by `checkIndicesGranted`. After both branches land, `HEAD` on a `<project>.entities` index will be refused for a user granted only `<project>`. That is a follow-up, not a defect of either branch: the `.entities` indices do not exist until #2205 creates them.

## Suggested follow-ups

Not in this diff.

- `esHead` answers 500 rather than 404 for a missing index: `executeRaw` raises the Elasticsearch `ResponseException`, which escapes the `catch (IllegalArgumentException)`. Orthogonal to authorization.
- Issue #2312 should be retitled, now that its scope is `esHead` and `esOptions` only.
- Teach `checkIndicesGranted` the `baseProject` mapping once #2311 has landed and #2205 creates the `.entities` indices.

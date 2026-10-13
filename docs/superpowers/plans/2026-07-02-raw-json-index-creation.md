# Raw-JSON Index Creation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create Elasticsearch/OpenSearch indexes by sending the mapping+settings as a raw JSON body, so the scalar `"match_mapping_type": "date"` reaches the server verbatim instead of being rewritten to the array `["date"]` by the typed client.

**Architecture:** Replace the typed `CreateIndexRequest`/`TypeMapping`/`IndexSettings` build in `ElasticsearchConfiguration.createIndex` with a raw `PUT /{index}` sent through the low-level `org.elasticsearch.client.RestClient` (reachable via `((RestClientTransport) client._transport()).restClient()`). The body is assembled by verbatim concatenation of the two resource files, so no object model touches the mapping and nothing arrays the scalar. A no-server unit test asserts the body keeps the scalar; the existing integration test stays as an end-to-end smoke test.

**Tech Stack:** Java 17, Maven, `co.elastic.clients:elasticsearch-java` 8.19.8, low-level `org.elasticsearch.client` REST client, JUnit 4, fest-assert, Jackson (`org.icij.datashare.json.JsonObjectMapper`).

## Global Constraints

- The fix must work on any supported server: Elasticsearch older than 8.13, current Elasticsearch, and OpenSearch. Do not rely on the cluster version.
- Never construct `TypeMapping`, `IndexSettings`, `DynamicTemplate`, or `CreateIndexRequest` for the create call — those are what rewrite the scalar to an array.
- Do not change the content of `datashare_index_mappings.json` or `datashare_index_settings.json`.
- Preserve the per-OS settings selection: `SETTINGS_RESOURCE_NAME_WINDOWS` when `IS_OS_WINDOWS`, else `SETTINGS_RESOURCE_NAME`.
- Preserve the existing `createIndex` contract: `exists` precheck on the typed client; return `true` when the index is created, `false` when it already exists; wrap `IOException` (which includes `ResponseException`) in `ConfigurationException`.
- Commit messages use a semantic prefix and carry no AI attribution / co-author trailer.

---

### Task 1: Raw-JSON create with a scalar-preserving body builder

**Files:**
- Modify: `datashare-index/src/main/java/org/icij/datashare/text/indexing/elasticsearch/ElasticsearchConfiguration.java` (imports; `createIndex` at lines 121-134; add `createIndexBody`)
- Test: `datashare-index/src/test/java/org/icij/datashare/text/indexing/elasticsearch/ElasticsearchConfigurationTest.java`

**Interfaces:**
- Consumes: existing `static String getResourceContent(String)`, constants `MAPPING_RESOURCE_NAME`, `SETTINGS_RESOURCE_NAME`, `SETTINGS_RESOURCE_NAME_WINDOWS`, the static import `IS_OS_WINDOWS`, and `ConfigurationException`.
- Produces: `static String createIndexBody()` returning the full `{"settings":...,"mappings":...}` PUT body; `createIndex(ElasticsearchClient, String)` keeps its existing signature and boolean contract.

- [ ] **Step 1: Write the failing unit test**

Add to `ElasticsearchConfigurationTest.java`. Add these imports to the file's import block:

```java
import com.fasterxml.jackson.databind.JsonNode;
import org.icij.datashare.json.JsonObjectMapper;
```

Add this test method inside the class:

```java
    @Test
    public void test_create_index_body_keeps_match_mapping_type_scalar() throws Exception {
        JsonNode body = JsonObjectMapper.getMapper().readTree(ElasticsearchConfiguration.createIndexBody());

        JsonNode matchMappingType = body.at("/mappings/dynamic_templates/0/metadata_dates/match_mapping_type");
        assertThat(matchMappingType.isTextual()).isTrue();
        assertThat(matchMappingType.asText()).isEqualTo("date");
        assertThat(body.get("settings").isObject()).isTrue();
        assertThat(body.get("mappings").isObject()).isTrue();
    }
```

This asserts the body carries `match_mapping_type` as a JSON string, not an array. It is independent of what the server tolerates, which is why it catches the regression the ES-8.19 integration test cannot.

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
mvn -pl datashare-index -am test -Dtest=ElasticsearchConfigurationTest#test_create_index_body_keeps_match_mapping_type_scalar -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: FAIL — compilation error, `cannot find symbol: method createIndexBody()`.

- [ ] **Step 3: Add the `createIndexBody` method**

In `ElasticsearchConfiguration.java`, add this method next to `createIndex` (e.g. directly after it):

```java
    static String createIndexBody() {
        String settingsResource = IS_OS_WINDOWS ? SETTINGS_RESOURCE_NAME_WINDOWS : SETTINGS_RESOURCE_NAME;
        return "{\"settings\":" + getResourceContent(settingsResource)
                + ",\"mappings\":" + getResourceContent(MAPPING_RESOURCE_NAME) + "}";
    }
```

Both resource files are already the bare bodies of `settings` and `mappings`, so this concatenation is a valid `PUT /{index}` body.

- [ ] **Step 4: Rewrite `createIndex` to send the raw body**

Replace the current body of `createIndex` (lines 121-134):

```java
    public static boolean createIndex(ElasticsearchClient client, String indexName) {
        ExistsRequest existsRequest = ExistsRequest.of(er -> er.index(indexName));
        try {
            if (!client.indices().exists(existsRequest).value()) {
                LOGGER.info("index {} does not exist, creating one", indexName);
                CreateIndexRequest.Builder createReq = new CreateIndexRequest.Builder().index(indexName);
                createReq.settings(IndexSettings.of(is -> is.withJson(ElasticsearchConfiguration.getSettings())));
                createReq.mappings(TypeMapping.of(tm -> tm.withJson(ElasticsearchConfiguration.getMapping())));
                client.indices().create(createReq.build());
                return true;
            }
        } catch (IOException e) {
            throw new ConfigurationException(e);
        }
        return false;
    }
```

with:

```java
    public static boolean createIndex(ElasticsearchClient client, String indexName) {
        ExistsRequest existsRequest = ExistsRequest.of(er -> er.index(indexName));
        try {
            if (!client.indices().exists(existsRequest).value()) {
                LOGGER.info("index {} does not exist, creating one", indexName);
                RestClient restClient = ((RestClientTransport) client._transport()).restClient();
                Request request = new Request("PUT", "/" + indexName);
                request.setJsonEntity(createIndexBody());
                restClient.performRequest(request);
                return true;
            }
        } catch (IOException e) {
            throw new ConfigurationException(e);
        }
        return false;
    }
```

- [ ] **Step 5: Fix imports**

Add this import (the class already imports `RestClient` and `RestClientTransport`):

```java
import org.elasticsearch.client.Request;
```

Remove these three imports, now unused (they only appeared in the old `createIndex` body):

```java
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.IndexSettings;
```

Leave `getSettings()`/`getMapping()` and the `StringReader` import in place for now (removed in Task 2).

- [ ] **Step 6: Run the unit test to verify it passes**

Run:
```bash
mvn -pl datashare-index -am test -Dtest=ElasticsearchConfigurationTest#test_create_index_body_keeps_match_mapping_type_scalar -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS.

- [ ] **Step 7: Run the existing config + malformed-date tests as end-to-end smoke tests**

Requires Elasticsearch reachable at `elasticsearch:9200`.

Run:
```bash
mvn -pl datashare-index -am test -Dtest=ElasticsearchConfigurationTest,ElasticsearchIndexerTest#test_malformed_metadata_date_does_not_fail_the_document -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS — index still creates via the raw path and the malformed-date document still indexes.

- [ ] **Step 8: Commit**

```bash
git add datashare-index/src/main/java/org/icij/datashare/text/indexing/elasticsearch/ElasticsearchConfiguration.java \
        datashare-index/src/test/java/org/icij/datashare/text/indexing/elasticsearch/ElasticsearchConfigurationTest.java
git commit -m "fix(index): create index with raw JSON so match_mapping_type stays scalar

The typed elasticsearch-java client serializes match_mapping_type as an
array, which ES < 8.13 and OpenSearch reject. Send the create request as a
raw PUT body instead so the scalar reaches the server verbatim."
```

---

### Task 2: Remove the now-dead typed mapping/settings helpers

**Files:**
- Modify: `datashare-index/src/main/java/org/icij/datashare/text/indexing/elasticsearch/ElasticsearchConfiguration.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: no public API additions. Removes `public static StringReader getSettings()` and `public static StringReader getMapping()`, which have no callers after Task 1.

- [ ] **Step 1: Confirm the helpers are unused**

Run:
```bash
grep -rn --include='*.java' "getSettings()\|getMapping()" datashare-index datashare-app | grep -v /target/
```
Expected: only the two method definitions in `ElasticsearchConfiguration.java` (the call sites were removed in Task 1). If anything else appears, stop and keep the referenced method.

- [ ] **Step 2: Remove the two helper methods**

Delete these methods from `ElasticsearchConfiguration.java`:

```java
    public static StringReader getSettings() {
        if (IS_OS_WINDOWS) {
            return new StringReader(getResourceContent(SETTINGS_RESOURCE_NAME_WINDOWS));
        }
        return new StringReader(getResourceContent(SETTINGS_RESOURCE_NAME));
    }

    public static StringReader getMapping() {
        return new StringReader(getResourceContent(MAPPING_RESOURCE_NAME));
    }
```

- [ ] **Step 3: Remove the now-unused import**

Delete this import (only `getSettings`/`getMapping` used it):

```java
import java.io.StringReader;
```

Keep the `static ... IS_OS_WINDOWS` import — `createIndexBody()` still uses it.

- [ ] **Step 4: Compile and re-run the config tests**

Run:
```bash
mvn -pl datashare-index -am test -Dtest=ElasticsearchConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS — module compiles with the helpers and import removed, all config tests green.

- [ ] **Step 5: Commit**

```bash
git add datashare-index/src/main/java/org/icij/datashare/text/indexing/elasticsearch/ElasticsearchConfiguration.java
git commit -m "refactor(index): drop unused getSettings/getMapping helpers

Superseded by createIndexBody now that the index is created from a raw
JSON body."
```

---

## Self-Review

**Spec coverage:**
- Raw `PUT /{index}` via low-level `RestClient` bypassing typed builders → Task 1 Steps 3-5.
- `createIndexBody()` testable body builder with per-OS settings selection → Task 1 Step 3.
- Verbatim concatenation (no Jackson round-trip, no typed model) → Task 1 Step 3.
- Keep `exists` precheck; unchanged error/return contract → Task 1 Step 4 (matches Global Constraints).
- Unit test asserting scalar `match_mapping_type` → Task 1 Steps 1-2, 6.
- Keep existing integration test as smoke test → Task 1 Step 7.
- Cleanup of dead `getMapping()`/`getSettings()` → Task 2.
- Out of scope (no mapping/settings content change, no server upgrade, no other typed-call migration) → respected; no task touches those.

**Placeholder scan:** none — every code and command step is concrete.

**Type consistency:** `createIndexBody()` is referenced identically in the test (Task 1 Step 1) and defined identically (Task 1 Step 3); `createIndex(ElasticsearchClient, String)` signature unchanged; `Request`/`RestClient`/`RestClientTransport` types match the existing usage already present in `ElasticsearchConfigurationTest`.

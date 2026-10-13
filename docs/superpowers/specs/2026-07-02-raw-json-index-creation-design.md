# Raw-JSON index creation to preserve scalar `match_mapping_type`

Date: 2026-07-02

## Problem

Since commit `1eca2bd1a` ("fix(index): tolerate malformed metadata dates with
ignore_malformed dynamic template"), `datashare_index_mappings.json` declares a
dynamic template:

```json
"dynamic_templates": [
  { "metadata_dates": {
      "path_match": "metadata.*",
      "match_mapping_type": "date",
      "mapping": { "type": "date", "ignore_malformed": true } } }
]
```

The file is correct: `match_mapping_type` is a scalar string, which every
Elasticsearch and OpenSearch version accepts.

The bug is in how the mapping reaches the server. `ElasticsearchConfiguration.createIndex`
loads it with `TypeMapping.of(tm -> tm.withJson(...))`. `withJson` **deserializes**
the JSON into the typed client model, where `DynamicTemplate.matchMappingType` is a
`List<String>` (verified via `javap` on `elasticsearch-java` 8.19.8). The scalar
`"date"` becomes `["date"]`, and `DynamicTemplate.serializeInternal` unconditionally
emits `writeStartArray()` (verified in bytecode). So the client **always** sends
`"match_mapping_type": ["date"]` (an array) to the server, regardless of the scalar
in the file.

Array `match_mapping_type` was only introduced in **Elasticsearch 8.13.0**
(issue #102807 / PR #103171). Clusters older than 8.13 — and OpenSearch, which
forked from ES 7.10 and never gained the feature — try to resolve the whole array
`["date"]` as a single type name, stringify it to `[date]`, and reject it:

```
mapper_parsing_exception: Failed to parse mapping [_doc]:
No field type matched on [[date]], possible values are
[object, string, long, double, boolean, date, binary]
```

This fires in `createIndexIfNotExists` (`IndexTask` startup, project/index creation),
so on affected clusters **no new index can be created**. Existing indexes keep
working until a create is triggered.

Dev/CI runs Elasticsearch 8.19.8, which accepts the array form, which is why the
issue was invisible until production (a pre-8.13 cluster) hit it on 2026-07-02.

### Constraint

Datashare is deployed by users who run their own search clusters. We do **not**
control the server version or distribution, and OpenSearch is officially supported
(`RootResource` reports `index.distribution`). Any fix that depends on the cluster
being ES >= 8.13 is unacceptable. The fix must produce a request that every
supported server accepts.

## Chosen approach: raw `PUT /{index}` via the low-level `RestClient`

Send the index-creation request as a raw JSON body through the low-level
`org.elasticsearch.client.RestClient`, bypassing the typed request builders
(`CreateIndexRequest`, `IndexSettings`, `TypeMapping`) entirely. Because the typed
model is never constructed, nothing rewrites the scalar into an array: the body is
shipped byte-for-byte from the resource files, so `"match_mapping_type": "date"`
reaches the server verbatim.

This works on old ES, current ES, and OpenSearch, with no dependency on the server
version.

### Rejected alternative

- **`index.mapping.ignore_malformed: true` (index setting, no dynamic template).**
  Also version-independent and verified to preserve the malformed-date tolerance,
  but it is a different fix: it broadens `ignore_malformed` to all supporting field
  types index-wide (numerics, booleans, ip), not just metadata dates, and abandons
  the targeted dynamic template. Kept on record as a fallback, not chosen.

## Design

Single production entry point: `ElasticsearchConfiguration.createIndex(ElasticsearchClient client, String indexName)`
(all callers route through `ElasticsearchIndexer.createIndex` -> here). Changes are
localized to this class.

### 1. `createIndexBody()` — testable body builder

Extract a `static String createIndexBody()` that:

- selects the settings resource per-OS, preserving the existing Windows branch
  (`SETTINGS_RESOURCE_NAME_WINDOWS` when `IS_OS_WINDOWS`, else `SETTINGS_RESOURCE_NAME`);
- returns the combined body by **verbatim concatenation** of the two resource file
  contents:

  ```
  {"settings":<settings-resource>,"mappings":<mappings-resource>}
  ```

Both resource files are already the bare bodies of `settings` and `mappings`
respectively (the settings file has top-level `index.*` keys; the mappings file has
top-level `dynamic_templates`/`properties`), so this concatenation is exactly a valid
`PUT /{index}` body.

Verbatim concatenation (not a Jackson round-trip, not the typed model) is the
mechanism that guarantees the scalar survives: zero transformation means nothing can
rewrite `match_mapping_type`.

### 2. `createIndex` — raw request

Keep the existing `exists` precheck on the typed client (`client.indices().exists(...)`)
— it involves no dynamic template and is safe on every server. Only the create becomes
raw:

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

`_transport()` is public on `ApiClient` and returns the `RestClientTransport`
constructed in this class; `RestClientTransport.restClient()` exposes the low-level
`RestClient`. `Request` is `org.elasticsearch.client.Request` (new import; the
`RestClient`/`RestClientTransport` imports already exist).

### Data flow

```
datashare_index_settings[_windows].json ┐
                                         ├─ createIndexBody() (verbatim concat)
datashare_index_mappings.json ──────────┘
        -> new Request("PUT", "/<index>").setJsonEntity(body)
        -> low-level RestClient.performRequest
        -> ES / OpenSearch   (scalar "match_mapping_type":"date" byte-for-byte)
```

No `TypeMapping` / `IndexSettings` / `DynamicTemplate` object is instantiated.

### Error handling

Contract unchanged. `RestClient.performRequest` throws `IOException` on transport
failure and `ResponseException` (a subclass of `IOException`, carrying the ES error
body) on HTTP 4xx/5xx. Both are caught and wrapped in `ConfigurationException`, as
today. Returns `true` when the index was created, `false` when it already existed.

We lose the typed client's structured error decoding on this one call, but the error
body is still available on `ResponseException`, so the failure surface is adequate.

### Cleanup

`getMapping()` (returns a `StringReader` for `TypeMapping.withJson`) becomes unused
once the typed create is gone. Remove it if no other caller remains; otherwise leave
it. `getSettings()` may likewise be reducible — verify callers before removing.
(Verify with a usage search during implementation; do not remove anything still
referenced by tests.)

## Testing

1. **Unit test `createIndexBody()` — the regression guard.** Parse the returned
   string with Jackson and assert
   `dynamic_templates[0].metadata_dates.match_mapping_type` is `isTextual()` (a JSON
   scalar), not an array, and that the body is well-formed JSON with `settings` and
   `mappings` objects. This is the test the existing integration test cannot provide:
   dev/CI ES 8.19 accepts both scalar and array, so an end-to-end test passes even
   with the bug present. The unit test fails immediately if anyone reintroduces a
   path that arrays the value.

2. **Keep the existing integration test**
   (`test_malformed_metadata_date_does_not_fail_the_document`) as an end-to-end smoke
   test that index creation + malformed-date indexing works against a real server.

## Out of scope

- Changing the mapping/settings content.
- Migrating other typed client calls to raw JSON.
- Any ES/OpenSearch server upgrade (operational, and explicitly not relied upon).

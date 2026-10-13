# Design: the `<project>.entities` index and the `ExtractedEntity` type

Issue [#2205](https://github.com/ICIJ/datashare/issues/2205), part of epic
[#2194](https://github.com/ICIJ/datashare/issues/2194) (import structured entities from tabular data
using a data schema). Designed 2026-08-26.

## Branch topology

`feat/2205-entities-index` branches off `feat/2202-statement-store` (PR
[#2359](https://github.com/ICIJ/datashare/pull/2359)), which is itself stacked on
`feat/2197-target-model` (PR [#2354](https://github.com/ICIJ/datashare/pull/2354)).

#2359 supplies everything this design reads: `ModelEntity`, whose `properties` map is already keyed
by the namespaced wire form, and `StatementRepository.entities(projectId, consumer)`, which streams a
project's rebuilt entities from a lazily fetched, ordered query rather than buffering them.

This branch modifies `ModelEntity`, a record #2359 introduces. That is a stacked-branch edit to a
still-draft parent, not a cross-branch dependency: the change lands here and rebases cleanly as long
as #2359's own review does not delete the record.

## Scope

Ships the index, the type, the projection, the rebuilder, index creation, and the project-deletion
fix. It ships no task, no HTTP endpoint and no Guice binding: #2206 owns the extraction task that
first writes statements, and #2207 owns the REST and CLI triggers. This mirrors how #2359 shipped
storage with no production caller, and it keeps the branch reviewable against hand-built statements
rather than against a pipeline that does not exist yet.

Three of the four hygiene items #2205 inherited from #2199/#2311 stay out:

- **Deferred.** No migration or startup check for a project row already named with a dot or a leading
  underscore. #2311's guards run on creation only, and a retroactive check is its own issue with its
  own remediation question (rename, refuse to start, or log).
- **Deferred.** The `.entities` suffix bypassing `ForbiddenException.requireGranted`, so a `.entities`
  hit can be searched but not opened. It is an authorization asymmetry, and changing it here would
  mean redesigning #2311's rule with no reader to test it against.
- **Resolved by this branch.** "Nothing in the repo produces a `.entities` index yet" stops being
  true the moment this lands.

### Backups, snapshots and the client

#2205's last checkbox asks that backups, snapshots and the client be made aware of the extra index.
Checked against the code, the first two need no change and the third is not in this repository:

- The snapshot routes are repository-level pass-throughs. `IndexResource.getSnapshotRepositories`,
  `createSnapshotRepository`, `getSnapshots` and their siblings (`:325-375`) forward to
  `indexer.executeRaw(...)` against `_snapshot/...` and never enumerate indices, so which indices a
  snapshot holds is decided by the caller's body, not by Datashare.
- The embedded backup path only configures where snapshots are written:
  `EmbeddedMode.createDefaultSettingsFile` (`:89-101`) sets `path.repo` to a `backups` directory and
  says nothing about indices.
- The client is a separate repository (`ICIJ/datashare-client`). Teaching it about `.entities` is its
  own issue, and it has nothing to display until #2206 and #2207 make an extraction runnable.

In: **project deletion never removes `<id>.entities`**. It is the one item that becomes a live data
leak the day this branch produces the index, since a later team granted a recycled project name would
inherit the previous team's entity data. The database half of the same gap belongs to
[#2358](https://github.com/ICIJ/datashare/issues/2358).

## The indexed document

```json
{
  "type": "ExtractedEntity",
  "id": "9f2c...",
  "model": "ftm",
  "modelVersions": ["4.10.2"],
  "types": ["Person", "LegalEntity"],
  "documentIds": ["a1b2...", "c3d4..."],
  "properties": {
    "ftm:name": ["Acme Ltd"],
    "ftm:birthDate": ["1970-01-01"]
  }
}
```

`type` is not a field on the Java type. `ElasticsearchIndexer.java:272` injects it from the class's
`@IndexType` annotation, and `:313-314` reads it back with
`Class.forName("org.icij.datashare.text." + type)`. That is what fixes both the type's package and
its `@IndexType` value.

### Why a flat object rather than a nested key/value array

`properties` is an object whose keys are the namespaced property names, mapped by one dynamic
template. The alternative, `[{"name": "ftm:name", "value": "Acme"}]` under a `nested` mapping, keeps
the mapping constant-size whatever the model, but every query becomes a `nested` query and
per-property facets and sort get materially harder for the client to express.

The flat form's cost is one mapped field per distinct property ever seen. FtM declares roughly 250
properties, the settings raise `index.mapping.total_fields.limit` to 100000 as the documents index
already does, and namespacing is precisely what stops a second model's `founder` colliding with FtM's
on type. That is the mapping-isolation argument #2205 states, and it holds.

### Mappings

New resource `datashare_entities_index_mappings.json` in `datashare-index`:

- `"dynamic": "strict"` at the root. An unplanned top-level field then fails loudly instead of
  silently minting a mapping. The integration test writes through the real indexer, so anything the
  indexer injects is caught at build time rather than in production.
- `properties` is an object with `"dynamic": true`, the one place new fields may appear.
- One dynamic template, `path_match: "properties.*"`, `match_mapping_type: "string"`, mapping each
  value to `keyword` with `ignore_above: 8191` and a `text` subfield on the `folding` analyzer.
  Keyword first because facets, sort and exact term lookups are the entity index's reason to exist;
  the subfield covers accent-insensitive search. `ignore_above` keeps an oversized cell value from
  failing the whole bulk request on the 32 kB keyword term limit.
- `id`, `model`, `modelVersions`, `types`, `documentIds` and `type` are `keyword`.
- `join` is declared as a plain object with a `keyword` `name`. It is not optional and not decorative:
  `ElasticsearchIndexer.setJoinFields` (`:271-283`) writes `join: {name: <type>}` onto every document
  it indexes, and a `dynamic: strict` mapping rejects the write outright if the field is undeclared.
  It is a plain object rather than Elasticsearch's `join` type because this index has no parent/child
  relations.
- `"date_detection": false` at the root. Elasticsearch resolves a JSON string's dynamic type before
  consulting dynamic templates, so a value like `1980-04-02` would otherwise be mapped `date`, the
  `entity_properties` template (gated on `match_mapping_type: "string"`) would never fire for it, and
  the next unparseable value in that property would fail its bulk item forever after a purge. It also
  gives that field no `.text` subfield, which silently breaks folded search on it.

Values are indexed as strings, including dates. A statement's `value` is the raw cell text
(`statement.value` is `text`, not typed), and #2204 owns value formatting. A date-typed mapping here
would mean guessing a format the pipeline does not yet promise.

### Settings

New resource `datashare_entities_index_settings.json`: the `folding` analyzer,
`index.mapping.total_fields.limit`, one shard, and `index.query.default_field: ["properties.*"]` so a
bare `query_string` from the search proxy hits property values.

No Windows variant. The only difference between `datashare_index_settings.json` and
`datashare_index_settings_windows.json` is the path tokenizer's delimiter (lines 43-44), and this
index holds no paths.

### Creation stays on the raw REST path

`ElasticsearchConfiguration.createIndex` builds a JSON body and sends it with
`new Request("PUT", "/" + indexName)`. It must not move to the typed `client.indices().create(...)`
builder: the typed client serializes `match_mapping_type` as an array, which production Elasticsearch
below 8.13 rejects, and this index is built entirely on a dynamic template.

## Code

### `ModelEntity` gains `model`, `modelVersions` and `documentIds`

`ModelEntity` today is `(id, types, properties)`, and its property keys are **bare**, not namespaced:
`JooqStatementRepository.toRow` strips the model prefix off the stored column
(`JooqStatementRepository.java:206`) and `ModelEntity.from` keys on `statement.property()`. That is the
form `TargetModel.validate` and `ExtractionMapping.probe` consume, so it stays. `ExtractedEntity.from`
re-qualifies each key with the entity's model, which is what puts `ftm:name` in the index. It computes the model to assert a group belongs to
one (`ModelEntity.java:41-43`) and then discards it, and it never looks at provenance.

- `model`: kept rather than recomputed, since `from` already derives it.
- `documentIds`: a set, collected from each statement's `provenance.documentId()` in the same loop
  that groups values. Without it an entity is searchable but cannot be traced back to a file, which
  makes a search hit unopenable.
- `modelVersions`: a set, and it cannot come from `Statement`, which deliberately carries no version
  field (#2202's design keeps `model_version` a column outside the id hash). So the signature becomes

  ```java
  public static ModelEntity from(Collection<Statement> statements, Set<String> modelVersions)
  ```

  and `JooqStatementRepository` passes the `MODEL_VERSION` values from the rows it is already
  reading. One method, no overload: the two call sites are the repository and `ModelEntityTest`.

A set, not a single value, because `model_version` is per-fact and first-seen: #2359's fix wave A
moved the upsert to `onConflictDoNothing()`, so a row records the ontology that first asserted the
fact and never moves. An entity re-asserted after an ontology bump genuinely holds statements from
two versions, and collapsing that to one value would pick a winner silently.

### `ExtractedEntity`

New record in `datashare-api`, package `org.icij.datashare.text` (forced by the `Class.forName` read
path above), annotated `@IndexType("ExtractedEntity")` with `@IndexId` on `id`:

```java
@IndexType("ExtractedEntity")
public record ExtractedEntity(@IndexId String id, String model, Set<String> types,
                              Set<String> modelVersions, Set<String> documentIds,
                              Map<String, List<String>> properties) implements Entity {
    public String getId() { return id; }
    public static ExtractedEntity from(ModelEntity entity) { ... }
}
```

A record round-trips through `JsonObjectMapper` without extra annotations:
`ParameterNamesModule` is registered, field visibility is `ANY`, and
`FAIL_ON_UNKNOWN_PROPERTIES` is off (`JsonObjectMapper.java:64-67`), which also means the extra
`projectId` and `rootDocument` keys `getObject` injects on read are ignored rather than fatal.

No new write method on `Indexer`: `bulkAdd(String indexName, List<T extends Entity> entities)` takes
it as is.

### The mappings-per-index seam

`ElasticsearchConfiguration.createIndex(client, indexName)` hardcodes `createIndexBody()`, which
reads `datashare_index_mappings.json` and picks the settings file by OS. The seam:

```java
static boolean createIndex(ElasticsearchClient client, String indexName,
                           String mappingsResource, String settingsResource)
```

with the existing two-argument form delegating with the document resources, and `createIndexBody`
taking the two resource names. The raw-REST body construction is untouched.

`Indexer` gains one method:

```java
boolean createEntitiesIndex(String projectId) throws IOException;
```

`ElasticsearchIndexer` implements it as `createIndex(client, Project.entitiesIndex(projectId),
ENTITIES_MAPPING_RESOURCE_NAME, ENTITIES_SETTINGS_RESOURCE_NAME)`. It is idempotent for free:
`createIndex` returns `false` when the index exists rather than failing, so calling it on an already
provisioned project is a no-op and deployment order does not matter.

An explicit method rather than deriving the mappings file from the `.entities` suffix inside
`createIndex`: the caller says which kind of index it wants instead of a naming convention deciding
it. The consequence is that `PUT /api/index/foo.entities`, which #2311's grant rule lets through,
would otherwise create a documents-shaped index under an entities name. `IndexResource.createIndex`
therefore routes a name ending in `.entities` to `createEntitiesIndex`, a one-line branch that keeps
the route and the service from disagreeing about what that name means.

### The suffix has one home

`IndexAccessVerifier.java:22-23` holds a private `ENTITIES_SUFFIX`. Three more call sites now need
it, so it moves to `Project` in `datashare-api`, next to `NAME_PATTERN`, as a constant plus
`Project.entitiesIndex(String projectId)`. `IndexAccessVerifier` uses it instead of its own copy.

### `EntitiesIndexRebuilder`

New class in `datashare-app`, package `org.icij.datashare.model`, constructed with an `Indexer` and a
`StatementRepository`, exposing `int rebuild(String projectId)`:

1. `indexer.createEntitiesIndex(projectId)`, the retro-compatibility path for every project that
   predates this branch.
2. `indexer.deleteAll(entitiesIndex)`, emptying it.
3. `statementRepository.entities(projectId, stream -> ...)`, mapping through `ExtractedEntity::from`
   and calling `bulkAdd` per chunk of 1000, returning the number of entities indexed.

**Purge then fill, not upsert.** An entity dropped from the statement store has to disappear from the
index, and indexing by id alone would leave it behind forever. The cost is a window during which the
index is empty; the upgrade path, if a rebuild ever has to be invisible to searchers, is to build
into a new index and swap an alias, which #2199's index-name-based authorization would have to learn
about first.

**Chunked, not buffered.** `entities` streams by design (#2359 requires Postgres 13 for exactly this
reason), and a single `bulkAdd` of a 100k-entity project would undo that.

The class is in `datashare-app` rather than `datashare-api` because that is where its integration
test can have both a real repository and a real Elasticsearch, and where #2206's task will call it
from.

## Index creation

Two helpers create a project's documents index today. Both create the entities index next to it:

- `ProjectAdminServiceImpl.createIndexOrRollback` (`:512-525`), inside the existing `try`, so a
  failure on either index deletes the project row and rethrows exactly as it does now.
- `ProjectResource.createIndexOnce` (`:310-317`), which swallows its own failures and serves both
  project-creation routes (`:116` and `:169`).

`CliApp.java:115` (`--createIndex`) is left alone. It creates one named index rather than a project,
and pairing it would make a single-name option silently create two indices. Projects created that way
are covered by the rebuilder's create-if-missing step.

## Project deletion

`ProjectResource.java:200-201` and `ProjectAdminServiceImpl.java:353-354` both delete on the base
name only. Both now delete the entities index too, folded into the existing "index" step rather than
added as a new one, so a project that never ran an extraction does not report a failed deletion:
`ElasticsearchIndexer.deleteAll` returns `false` on a missing index (`:541-542`), and the step's
boolean stays driven by the documents index.

This empties the index by `_delete_by_query` rather than dropping it, which is what deletion already
does for documents. Matching the existing semantics is deliberate: a deletion that dropped one index
and emptied the other would be a second surprise.

## Testing

Test-first throughout: each case is red before the code that satisfies it.

**`datashare-index`, against a real Elasticsearch via `ElasticsearchRule`**

- `createEntitiesIndex` creates an index carrying the entities mappings, and the documents index
  created next to it is unaffected.
- A document with an unmapped top-level field is rejected, proving `dynamic: strict` is live.
- Calling it twice returns `false` the second time and leaves the mapping intact.

**`datashare-api`, no database, no Elasticsearch**

- `ExtractedEntity.from` carries id, model, model versions, every type, every document id, and each
  namespaced property with all its values.
- `ModelEntity.from` unions document ids across a group and keeps the model versions it is handed.
- `ModelEntity.from` still refuses a group spanning two entities or two models.

**`datashare-app`, against a real index via `ElasticsearchRule`**

The rebuilder's test drives a hand-written in-memory `StatementRepository` rather than
`JooqStatementRepository`: `datashare-db` is a `provided`-scope dependency of `datashare-app`
(`datashare-app/pom.xml:148-150`) and no module publishes a test-jar, so `DbSetupRule` and
`DbTestRuleProvider` are not reachable from an app test. The database half of the path is covered
where the rules live, in `datashare-db`'s `JooqStatementRepositoryTest`.

- Entities rebuilt into a real index are searchable by `types`, by an exact term on
  `properties.ftm:name`, and by an accent-folded match on `properties.ftm:name.text`.
- A second rebuild over a smaller entity set leaves no stale entity behind.
- A rebuild against a project whose entities index does not exist creates it, which is the
  retro-compatibility case.
- More entities than one chunk are all indexed.

**`datashare-db`**

- `entities(projectId, ...)` carries the document ids of every statement in the group, and the model
  versions read from the `model_version` column.

**`datashare-app`, unit**

- `ProjectAdminServiceImplTest` and `ProjectResourceTest`: creating a project creates both indices,
  and a failure on the entities index still rolls the project row back.

# `<project>.entities` Index Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Project a project's extracted entities out of the statement store into a searchable
`<project>.entities` Elasticsearch index, created next to the documents index and deleted with it.

**Architecture:** `ModelEntity` grows the three fields a searchable entity needs (`model`,
`modelVersions`, `documentIds`); `ExtractedEntity` is the indexable projection of it;
`ElasticsearchConfiguration` gains a mappings-per-index seam so a second mappings file can be used;
`EntitiesIndexRebuilder` purges the index and refills it from `StatementRepository.entities`. No task,
no endpoint and no Guice binding: #2206 and #2207 own those.

**Tech Stack:** Java 17 records, jOOQ, Elasticsearch (raw REST index creation), JUnit 4, Mockito,
fest-assert, Maven.

**Spec:** `docs/superpowers/specs/2026-08-26-entities-index-design.md`

## Global Constraints

- Branch: `feat/2205-entities-index`, already created off `feat/2202-statement-store`. Do not rebase it.
- Index creation stays on the raw REST path (`new Request("PUT", "/" + indexName)` plus
  `setJsonEntity`). Never move it to `client.indices().create(...)`: the typed client serializes
  `match_mapping_type` as an array, which production Elasticsearch below 8.13 rejects.
- The entities index name is always `<projectId>.entities`, built through `Project.entitiesIndex`.
  No string concatenation of `".entities"` anywhere else.
- Elasticsearch runs at `elasticsearch:9200` and is reached by tests through
  `org.icij.datashare.test.ElasticsearchRule`. Any index a test creates itself must be deleted in an
  `@After` with `es.delete(...)`.
- Run a single test class with, for example:
  `mvn -pl datashare-app -am test -Dtest=EntitiesIndexRebuilderTest -Dsurefire.failIfNoSpecifiedTests=false`
  The `-am` and the `-Dsurefire.failIfNoSpecifiedTests=false` are both required. Multiple classes are
  comma-separated (`-Dtest=A,B`), never plus-separated.
- Comments: only a non-obvious *why*. No comment restating what a line does, and no comment on a line
  you did not change.
- Do not commit anything under `docs/superpowers/` (it is gitignored).
- Every task is test-first: write the test, watch it fail for the right reason, then implement.

---

### Task 1: `ModelEntity` carries model, model versions and document ids

**Files:**
- Modify: `datashare-api/src/main/java/org/icij/datashare/model/ModelEntity.java`
- Test: `datashare-api/src/test/java/org/icij/datashare/model/ModelEntityTest.java`

**Interfaces:**
- Consumes: `Statement` (`model()`, `entityId()`, `entityType()`, `property()`, `value()`,
  `provenance().documentId()`), unchanged.
- Produces:
  ```java
  public record ModelEntity(String id, String model, Set<String> types, Set<String> modelVersions,
                            Set<String> documentIds, Map<String, List<String>> properties)
  public static ModelEntity from(Collection<Statement> statements, Set<String> modelVersions)
  ```
  The single-argument `from(Collection<Statement>)` no longer exists.

- [ ] **Step 1: Write the failing tests**

Add to `ModelEntityTest`, and update every existing call in that file to the new shapes (the
canonical constructor takes six components now, and `from` takes two arguments; existing calls pass
`"ftm"` as the model, `Set.of()` for modelVersions and documentIds in constructor calls, and
`Set.of("4.10.2")` as `from`'s second argument):

```java
    @Test
    public void test_keeps_the_model_of_the_statements() {
        ModelEntity entity = ModelEntity.from(List.of(statement("name", "Jane Doe", "full_name")), Set.of("4.10.2"));

        assertThat(entity.model()).isEqualTo("ftm");
    }

    @Test
    public void test_unions_the_document_ids_of_the_statements() {
        ModelEntity entity = ModelEntity.from(List.of(
                statement("name", "Jane Doe", "full_name"),
                Statement.of("ftm", "person-1", "Person", "birthDate", "1980-04-02",
                        new Statement.Provenance("doc-2", "Sheet1", 3, "dob"))), Set.of("4.10.2"));

        assertThat(entity.documentIds()).containsOnly("doc-1", "doc-2");
    }

    @Test
    public void test_the_same_document_seen_twice_is_one_document_id() {
        ModelEntity entity = ModelEntity.from(List.of(
                statement("name", "Jane Doe", "full_name"),
                statement("name", "J. Doe", "known_as")), Set.of("4.10.2"));

        assertThat(entity.documentIds()).containsOnly("doc-1");
    }

    @Test
    public void test_keeps_the_model_versions_it_is_given() {
        ModelEntity entity = ModelEntity.from(List.of(statement("name", "Jane Doe", "full_name")),
                Set.of("4.10.2", "4.11.0"));

        assertThat(entity.modelVersions()).containsOnly("4.10.2", "4.11.0");
    }

    @Test(expected = UnsupportedOperationException.class)
    public void test_the_document_ids_are_immutable() {
        ModelEntity entity = new ModelEntity("p-1", "ftm", new HashSet<>(Set.of("Person")),
                new HashSet<>(Set.of("4.10.2")), new HashSet<>(Set.of("doc-1")),
                new HashMap<>(Map.of("name", new ArrayList<>(List.of("Jane Doe")))));

        entity.documentIds().add("doc-2");
    }

    @Test
    public void test_a_null_model_is_rejected() {
        try {
            new ModelEntity("p-1", null, Set.of("Person"), Set.of("4.10.2"), Set.of("doc-1"),
                    Map.of("name", List.of("Jane Doe")));
            fail("should have rejected a null model");
        } catch (NullPointerException e) {
            assertThat(e.getMessage()).contains("model");
        }
    }
```

- [ ] **Step 2: Run the tests and watch them fail**

Run: `mvn -pl datashare-api -am test -Dtest=ModelEntityTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure, `constructor ModelEntity ... cannot be applied to given types` and
`method from ... cannot be applied to given types`.

- [ ] **Step 3: Implement**

Rewrite `ModelEntity` as:

```java
public record ModelEntity(String id, String model, Set<String> types, Set<String> modelVersions,
                          Set<String> documentIds, Map<String, List<String>> properties) {

    public ModelEntity {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(types, "types");
        Objects.requireNonNull(modelVersions, "modelVersions");
        Objects.requireNonNull(documentIds, "documentIds");
        Objects.requireNonNull(properties, "properties");
        types = Set.copyOf(types);
        modelVersions = Set.copyOf(modelVersions);
        documentIds = Set.copyOf(documentIds);
        Map<String, List<String>> copy = new LinkedHashMap<>();
        properties.forEach((property, values) -> copy.put(property, List.copyOf(values)));
        properties = Collections.unmodifiableMap(copy);
    }

    public static ModelEntity from(Collection<Statement> statements, Set<String> modelVersions) {
        if (statements.isEmpty()) {
            throw new IllegalArgumentException("cannot rebuild an entity from no statement");
        }
        SortedSet<String> ids = new TreeSet<>();
        SortedSet<String> models = new TreeSet<>();
        Set<String> types = new LinkedHashSet<>();
        Set<String> documentIds = new LinkedHashSet<>();
        Map<String, Set<String>> values = new LinkedHashMap<>();
        for (Statement statement : statements) {
            ids.add(statement.entityId());
            models.add(statement.model());
            types.add(statement.entityType());
            documentIds.add(statement.provenance().documentId());
            values.computeIfAbsent(statement.property(), property -> new LinkedHashSet<>()).add(statement.value());
        }
        if (ids.size() > 1) {
            throw new IllegalArgumentException("statements belong to " + ids.size() + " entities: " + ids);
        }
        if (models.size() > 1) {
            throw new IllegalArgumentException("statements belong to " + models.size() + " models: " + models);
        }
        Map<String, List<String>> properties = new LinkedHashMap<>();
        values.forEach((property, distinct) -> properties.put(property, new ArrayList<>(distinct)));
        return new ModelEntity(ids.first(), models.first(), types, modelVersions, documentIds, properties);
    }
}
```

- [ ] **Step 4: Run the tests and watch them pass**

Run: `mvn -pl datashare-api -am test -Dtest=ModelEntityTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, every test in the class.

- [ ] **Step 5: Commit**

```bash
git add datashare-api/src/main/java/org/icij/datashare/model/ModelEntity.java \
        datashare-api/src/test/java/org/icij/datashare/model/ModelEntityTest.java
git commit -m "feat(model): carry the model, its versions and the source documents on an entity"
```

---

### Task 2: the repository fills the new fields from the `model_version` column

**Files:**
- Modify: `datashare-db/src/main/java/org/icij/datashare/db/JooqStatementRepository.java`
- Test: `datashare-db/src/test/java/org/icij/datashare/db/JooqStatementRepositoryTest.java`

**Interfaces:**
- Consumes: `ModelEntity.from(Collection<Statement>, Set<String>)` from Task 1.
- Produces: no signature change. `StatementRepository.entities(projectId, consumer)` and
  `entity(projectId, entityId)` now return `ModelEntity` values whose `model`, `modelVersions` and
  `documentIds` are populated.

Context: `group(...)` currently maps each row to a `Statement` before grouping (`:200-249`), and
`Statement` carries no version, so the `model_version` column has to survive as far as the grouping.
It does that in a private carrier record.

- [ ] **Step 1: Write the failing tests**

Add to `JooqStatementRepositoryTest`, following the existing parameterized style in that file:

```java
    @Test
    public void test_an_entity_carries_the_model_version_of_its_statements() {
        repository.save("prj", "run-1", List.of(
                Statement.of("ftm", "person-1", "Person", "name", "Jane Doe",
                        new Statement.Provenance("doc-1", "Sheet1", 12, "full_name"))));

        ModelEntity entity = repository.entity("prj", "person-1").orElseThrow();

        assertThat(entity.model()).isEqualTo("ftm");
        assertThat(entity.modelVersions()).containsOnly("4.10.2");
    }

    @Test
    public void test_an_entity_carries_the_documents_its_statements_came_from() {
        repository.save("prj", "run-1", List.of(
                Statement.of("ftm", "person-1", "Person", "name", "Jane Doe",
                        new Statement.Provenance("doc-1", "Sheet1", 12, "full_name")),
                Statement.of("ftm", "person-1", "Person", "birthDate", "1980-04-02",
                        new Statement.Provenance("doc-2", "Sheet1", 3, "dob"))));

        ModelEntity entity = repository.entity("prj", "person-1").orElseThrow();

        assertThat(entity.documentIds()).containsOnly("doc-1", "doc-2");
    }

    @Test
    public void test_the_streamed_entities_carry_the_model_version_too() {
        repository.save("prj", "run-1", List.of(
                Statement.of("ftm", "person-1", "Person", "name", "Jane Doe",
                        new Statement.Provenance("doc-1", "Sheet1", 12, "full_name"))));

        List<ModelEntity> entities = repository.entities("prj", Stream::toList);

        assertThat(entities).hasSize(1);
        assertThat(entities.get(0).modelVersions()).containsOnly("4.10.2");
        assertThat(entities.get(0).documentIds()).containsOnly("doc-1");
    }
```

- [ ] **Step 2: Run the tests and watch them fail**

Run: `mvn -pl datashare-db -am test -Dtest=JooqStatementRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure in the production class (`ModelEntity.from` now needs two arguments).
If the module compiles, the failure is `method from in class ModelEntity cannot be applied` — fix
production code, not the test.

- [ ] **Step 3: Implement**

In `JooqStatementRepository`:

1. Add the column to the read list:

```java
    private static final Field<?>[] READ_FIELDS = {
            STATEMENT.ID, STATEMENT.MODEL, STATEMENT.MODEL_VERSION, STATEMENT.ENTITY_ID, STATEMENT.ENTITY_TYPE,
            STATEMENT.PROPERTY, STATEMENT.VALUE, STATEMENT.DOC_ID, STATEMENT.SHEET, STATEMENT.ROW_NUMBER,
            STATEMENT.COLUMN_NAME};
```

2. Add a carrier record next to the existing `private record Write(...)`:

```java
    private record Row(Statement statement, String modelVersion) { }
```

3. Change the comparator to sort carriers:

```java
    private static final Comparator<Row> BY_PROPERTY_THEN_VALUE =
            Comparator.<Row, String>comparing(row -> row.statement().property())
                    .thenComparing(row -> row.statement().value());
```

4. Rename `toStatement` to `toRow`, returning the carrier. Keep the namespacing check and its message
   exactly as they are:

```java
    private static Row toRow(Record row) {
        String model = row.get(STATEMENT.MODEL);
        String prefix = model + ":";
        String property = row.get(STATEMENT.PROPERTY);
        if (!property.startsWith(prefix)) {
            throw new DataAccessException("statement '" + row.get(STATEMENT.ID) + "' holds property '" + property
                    + "', which is not namespaced under its model '" + model + "'");
        }
        return new Row(new Statement(row.get(STATEMENT.ID), model, row.get(STATEMENT.ENTITY_ID),
                row.get(STATEMENT.ENTITY_TYPE), property.substring(prefix.length()), row.get(STATEMENT.VALUE),
                new Statement.Provenance(row.get(STATEMENT.DOC_ID), row.get(STATEMENT.SHEET),
                        row.get(STATEMENT.ROW_NUMBER), row.get(STATEMENT.COLUMN_NAME))),
                row.get(STATEMENT.MODEL_VERSION));
    }
```

5. Point both callers at `toRow`: in `entity(...)`, `.fetch().map(JooqStatementRepository::toRow)`
   yielding a `List<Row>`; in the streaming `entities(...)`, `.map(JooqStatementRepository::toRow)`.

6. Change `group` to take and group carriers, splitting each group at the end:

```java
    private static Stream<ModelEntity> group(Stream<Row> rows) {
        Iterator<Row> source = rows.iterator();
        Iterator<ModelEntity> entities = new Iterator<>() {
            private Row pending;
            private boolean primed;

            @Override
            public boolean hasNext() {
                if (!primed) {
                    pending = source.hasNext() ? source.next() : null;
                    primed = true;
                }
                return pending != null;
            }

            @Override
            public ModelEntity next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                List<Row> group = new ArrayList<>();
                group.add(pending);
                String entityId = pending.statement().entityId();
                String model = pending.statement().model();
                pending = null;
                while (source.hasNext()) {
                    Row row = source.next();
                    if (!row.statement().entityId().equals(entityId) || !row.statement().model().equals(model)) {
                        pending = row;
                        break;
                    }
                    group.add(row);
                }
                group.sort(BY_PROPERTY_THEN_VALUE);
                return ModelEntity.from(group.stream().map(Row::statement).toList(),
                        group.stream().map(Row::modelVersion).collect(Collectors.toSet()));
            }
        };
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(entities, Spliterator.ORDERED), false)
                .onClose(rows::close);
    }
```

Add `import java.util.stream.Collectors;`. Leave the existing comments above `group`,
`entities(String)` and `release(...)` untouched.

- [ ] **Step 4: Run the tests and watch them pass**

Run: `mvn -pl datashare-db -am test -Dtest=JooqStatementRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS on both the SQLite and the Postgres parameterization.

- [ ] **Step 5: Commit**

```bash
git add datashare-db/src/main/java/org/icij/datashare/db/JooqStatementRepository.java \
        datashare-db/src/test/java/org/icij/datashare/db/JooqStatementRepositoryTest.java
git commit -m "feat(db): rebuild an entity with its model version and source documents"
```

---

### Task 3: the `.entities` suffix has one home

**Files:**
- Modify: `datashare-api/src/main/java/org/icij/datashare/text/Project.java`
- Modify: `datashare-app/src/main/java/org/icij/datashare/utils/IndexAccessVerifier.java:22-23,89`
- Test: `datashare-api/src/test/java/org/icij/datashare/text/ProjectTest.java`

**Interfaces:**
- Produces:
  ```java
  public static final String ENTITIES_INDEX_SUFFIX = ".entities";
  public static String entitiesIndex(String projectId)   // "foo" -> "foo.entities"
  ```
  both on `org.icij.datashare.text.Project`.

- [ ] **Step 1: Write the failing test**

Add to `ProjectTest` (create the class only if it does not exist; check first with
`ls datashare-api/src/test/java/org/icij/datashare/text/ProjectTest.java`):

```java
    @Test
    public void test_entities_index_of_a_project() {
        assertThat(Project.entitiesIndex("foo")).isEqualTo("foo.entities");
    }

    @Test
    public void test_entities_index_rejects_a_null_project() {
        try {
            Project.entitiesIndex(null);
            fail("should have rejected a null project id");
        } catch (NullPointerException e) {
            assertThat(e.getMessage()).contains("projectId");
        }
    }
```

- [ ] **Step 2: Run the test and watch it fail**

Run: `mvn -pl datashare-api -am test -Dtest=ProjectTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL, `cannot find symbol: method entitiesIndex`.

- [ ] **Step 3: Implement**

In `Project`, next to `NAME_PATTERN` (`:20-23`):

```java
    /** Suffix of an index derived from a project: "myproject.entities" holds the entities extracted
     *  in "myproject". */
    public static final String ENTITIES_INDEX_SUFFIX = ".entities";

    public static String entitiesIndex(String projectId) {
        return Objects.requireNonNull(projectId, "projectId") + ENTITIES_INDEX_SUFFIX;
    }
```

Add `import java.util.Objects;` if absent.

In `IndexAccessVerifier`, delete the private `ENTITIES_SUFFIX` constant and its javadoc (`:22-23`)
and use the shared one in `baseProjects` (`:89`):

```java
        return stream(indices.split(",")).map(i -> i.endsWith(Project.ENTITIES_INDEX_SUFFIX)
                ? i.substring(0, i.length() - Project.ENTITIES_INDEX_SUFFIX.length()) : i).toList();
```

Add `import org.icij.datashare.text.Project;`.

- [ ] **Step 4: Run the tests and watch them pass**

Run: `mvn -pl datashare-api -am test -Dtest=ProjectTest -Dsurefire.failIfNoSpecifiedTests=false`
Then, to prove the verifier is unchanged in behaviour:
`mvn -pl datashare-app -am test -Dtest=IndexAccessVerifierTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS. If `IndexAccessVerifierTest` does not exist, run
`-Dtest=IndexResourceTest` instead, which exercises `baseProjects` through the async-search routes.

- [ ] **Step 5: Commit**

```bash
git add datashare-api/src/main/java/org/icij/datashare/text/Project.java \
        datashare-api/src/test/java/org/icij/datashare/text/ProjectTest.java \
        datashare-app/src/main/java/org/icij/datashare/utils/IndexAccessVerifier.java
git commit -m "refactor(project): name the entities index in one place"
```

---

### Task 4: the entities index, its mappings and the creation seam

**Files:**
- Create: `datashare-index/src/main/resources/datashare_entities_index_mappings.json`
- Create: `datashare-index/src/main/resources/datashare_entities_index_settings.json`
- Modify: `datashare-index/src/main/java/org/icij/datashare/text/indexing/elasticsearch/ElasticsearchConfiguration.java:36-38,118-142`
- Modify: `datashare-index/src/main/java/org/icij/datashare/text/indexing/elasticsearch/ElasticsearchIndexer.java`
- Modify: `datashare-api/src/main/java/org/icij/datashare/text/indexing/Indexer.java:26`
- Test: `datashare-index/src/test/java/org/icij/datashare/text/indexing/elasticsearch/ElasticsearchIndexerTest.java`

**Interfaces:**
- Consumes: `Project.entitiesIndex(String)` from Task 3.
- Produces:
  ```java
  // org.icij.datashare.text.indexing.Indexer
  boolean createEntitiesIndex(String projectId) throws IOException;
  ```
  Returns `true` when it created the index, `false` when it already existed, matching `createIndex`.

- [ ] **Step 1: Write the failing tests**

Add to `ElasticsearchIndexerTest` (it already has an `ElasticsearchRule`; check the field name at the
top of the class and reuse it, and add the created index to whatever teardown the class has, or add
`@After public void tearDownEntities() throws IOException { es.delete("prj-entities.entities"); }`):

```java
    @Test
    public void test_create_entities_index_maps_a_namespaced_property() throws Exception {
        assertThat(indexer.createEntitiesIndex("prj-entities")).isTrue();

        RestClient restClient = ((RestClientTransport) es.client._transport()).restClient();
        String mapping = EntityUtils.toString(restClient.performRequest(
                new Request("GET", "/prj-entities.entities/_mapping")).getEntity());

        assertThat(mapping).contains("dynamic_templates");
        assertThat(mapping).contains("entity_properties");
    }

    @Test
    public void test_create_entities_index_twice_is_a_no_op() throws Exception {
        assertThat(indexer.createEntitiesIndex("prj-entities")).isTrue();

        assertThat(indexer.createEntitiesIndex("prj-entities")).isFalse();
    }

    @Test
    public void test_the_entities_index_refuses_an_unmapped_top_level_field() throws Exception {
        indexer.createEntitiesIndex("prj-entities");

        RestClient restClient = ((RestClientTransport) es.client._transport()).restClient();
        Request request = new Request("PUT", "/prj-entities.entities/_doc/e-1");
        request.setJsonEntity("{\"surprise\": \"value\"}");
        try {
            restClient.performRequest(request);
            fail("strict mapping should have refused an unmapped field");
        } catch (ResponseException e) {
            assertThat(e.getResponse().getStatusLine().getStatusCode()).isEqualTo(400);
        }
    }
```

Imports needed: `org.apache.http.util.EntityUtils`, `org.elasticsearch.client.Request`,
`org.elasticsearch.client.RestClient`, `org.elasticsearch.client.ResponseException`,
`co.elastic.clients.transport.rest_client.RestClientTransport`, `static org.junit.Assert.fail`.

- [ ] **Step 2: Run the tests and watch them fail**

Run: `mvn -pl datashare-index -am test -Dtest=ElasticsearchIndexerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL, `cannot find symbol: method createEntitiesIndex`.

- [ ] **Step 3: Write the mappings and settings resources**

`datashare-index/src/main/resources/datashare_entities_index_mappings.json`:

```json
{
  "dynamic": "strict",
  "dynamic_templates": [
    {
      "entity_properties": {
        "path_match": "properties.*",
        "match_mapping_type": "string",
        "mapping": {
          "type": "keyword",
          "ignore_above": 8191,
          "fields": {
            "text": {
              "type": "text",
              "analyzer": "folding"
            }
          }
        }
      }
    }
  ],
  "properties": {
    "type": { "type": "keyword" },
    "id": { "type": "keyword" },
    "model": { "type": "keyword" },
    "modelVersions": { "type": "keyword" },
    "types": { "type": "keyword" },
    "documentIds": { "type": "keyword" },
    "properties": {
      "type": "object",
      "dynamic": true
    }
  }
}
```

`datashare-index/src/main/resources/datashare_entities_index_settings.json`:

```json
{
  "index.mapping.total_fields.limit": 100000,
  "index.number_of_shards": 1,
  "index.query.default_field": ["properties.*"],
  "analysis": {
    "analyzer": {
      "folding": {
        "type": "custom",
        "tokenizer": "standard",
        "filter": ["lowercase", "asciifolding"]
      }
    }
  }
}
```

- [ ] **Step 4: Implement the seam**

In `ElasticsearchConfiguration`, add the two resource names next to the existing ones (`:36-38`):

```java
    static final String ENTITIES_MAPPING_RESOURCE_NAME = "datashare_entities_index_mappings.json";
    static final String ENTITIES_SETTINGS_RESOURCE_NAME = "datashare_entities_index_settings.json";
```

Replace `createIndex` and `createIndexBody` (`:118-142`) with:

```java
    public static boolean createIndex(ElasticsearchClient client, String indexName) {
        String settingsResource = IS_OS_WINDOWS ? SETTINGS_RESOURCE_NAME_WINDOWS : SETTINGS_RESOURCE_NAME;
        return createIndex(client, indexName, MAPPING_RESOURCE_NAME, settingsResource);
    }

    public static boolean createIndex(ElasticsearchClient client, String indexName,
                                      String mappingsResource, String settingsResource) {
        ExistsRequest existsRequest = ExistsRequest.of(er -> er.index(indexName));
        try {
            if (!client.indices().exists(existsRequest).value()) {
                LOGGER.info("index {} does not exist, creating one", indexName);
                RestClient restClient = ((RestClientTransport) client._transport()).restClient();
                Request request = new Request("PUT", "/" + indexName);
                request.setJsonEntity(createIndexBody(mappingsResource, settingsResource));
                restClient.performRequest(request);
                return true;
            }
        } catch (IOException e) {
            throw new ConfigurationException(e);
        }
        return false;
    }

    static String createIndexBody(String mappingsResource, String settingsResource) throws JsonProcessingException {
        ObjectMapper mapper = JsonObjectMapper.getMapper();
        ObjectNode body = mapper.createObjectNode();
        body.set("settings", mapper.readTree(getResourceContent(settingsResource)));
        body.set("mappings", mapper.readTree(getResourceContent(mappingsResource)));
        return mapper.writeValueAsString(body);
    }
```

If `createIndexBody()` has callers elsewhere, point them at the two-argument form with
`MAPPING_RESOURCE_NAME` and the OS-dependent settings resource; find them with
`grep -rn 'createIndexBody' --include='*.java' .`

In `Indexer` (`:26`), below `createIndex`:

```java
    /** Creates the "<projectId>.entities" index holding the project's extracted entities, with the
     *  entity mappings rather than the document ones. Returns false when it already exists. */
    boolean createEntitiesIndex(String projectId) throws IOException;
```

In `ElasticsearchIndexer`, next to its `createIndex` implementation:

```java
    @Override
    public boolean createEntitiesIndex(String projectId) {
        return ElasticsearchConfiguration.createIndex(client, Project.entitiesIndex(projectId),
                ElasticsearchConfiguration.ENTITIES_MAPPING_RESOURCE_NAME,
                ElasticsearchConfiguration.ENTITIES_SETTINGS_RESOURCE_NAME);
    }
```

Add `import org.icij.datashare.text.Project;` if absent. Compile the whole project once
(`mvn -q -DskipTests install -pl datashare-api,datashare-index -am`) and fix any other class that
implements `Indexer` and now fails to compile.

- [ ] **Step 5: Run the tests and watch them pass**

Run: `mvn -pl datashare-index -am test -Dtest=ElasticsearchIndexerTest,ElasticsearchConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, including the pre-existing document-index tests.

- [ ] **Step 6: Commit**

```bash
git add datashare-index/src/main/resources/datashare_entities_index_mappings.json \
        datashare-index/src/main/resources/datashare_entities_index_settings.json \
        datashare-index/src/main/java/org/icij/datashare/text/indexing/elasticsearch/ElasticsearchConfiguration.java \
        datashare-index/src/main/java/org/icij/datashare/text/indexing/elasticsearch/ElasticsearchIndexer.java \
        datashare-api/src/main/java/org/icij/datashare/text/indexing/Indexer.java \
        datashare-index/src/test/java/org/icij/datashare/text/indexing/elasticsearch/ElasticsearchIndexerTest.java
git commit -m "feat(index): create a project's entities index with its own mappings"
```

---

### Task 5: `ExtractedEntity`, the indexable projection

**Files:**
- Create: `datashare-api/src/main/java/org/icij/datashare/text/ExtractedEntity.java`
- Test: `datashare-api/src/test/java/org/icij/datashare/text/ExtractedEntityTest.java`

**Interfaces:**
- Consumes: `ModelEntity` from Task 1.
- Produces:
  ```java
  @IndexType("ExtractedEntity")
  public record ExtractedEntity(@IndexId String id, String model, Set<String> modelVersions,
                                Set<String> types, Set<String> documentIds,
                                Map<String, List<String>> properties) implements Entity
  public static ExtractedEntity from(ModelEntity entity)
  public String getId()
  ```

The package is not a choice: `ElasticsearchIndexer.java:313-314` reads a hit back with
`Class.forName("org.icij.datashare.text." + type)`, where `type` is the `@IndexType` value.

- [ ] **Step 1: Write the failing test**

```java
package org.icij.datashare.text;

import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.model.ModelEntity;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.fest.assertions.Assertions.assertThat;

public class ExtractedEntityTest {
    private final ModelEntity source = new ModelEntity("person-1", "ftm", Set.of("Person", "LegalEntity"),
            Set.of("4.10.2"), Set.of("doc-1", "doc-2"),
            Map.of("ftm:name", List.of("Jane Doe", "J. Doe"), "ftm:birthDate", List.of("1980-04-02")));

    @Test
    public void test_projects_every_field_of_the_model_entity() {
        ExtractedEntity entity = ExtractedEntity.from(source);

        assertThat(entity.getId()).isEqualTo("person-1");
        assertThat(entity.model()).isEqualTo("ftm");
        assertThat(entity.modelVersions()).containsOnly("4.10.2");
        assertThat(entity.types()).containsOnly("Person", "LegalEntity");
        assertThat(entity.documentIds()).containsOnly("doc-1", "doc-2");
        assertThat(entity.properties().get("ftm:name")).containsExactly("Jane Doe", "J. Doe");
        assertThat(entity.properties().get("ftm:birthDate")).containsExactly("1980-04-02");
    }

    @Test
    public void test_the_index_type_is_the_class_name() {
        assertThat(JsonObjectMapper.getType(ExtractedEntity.from(source))).isEqualTo("ExtractedEntity");
    }

    @Test
    public void test_serializes_the_namespaced_properties_as_written() {
        Map<String, Object> json = JsonObjectMapper.getJson(ExtractedEntity.from(source));

        assertThat(json.get("model")).isEqualTo("ftm");
        assertThat(((Map<?, ?>) json.get("properties")).get("ftm:birthDate")).isEqualTo(List.of("1980-04-02"));
    }

    @Test
    public void test_reads_back_the_document_it_wrote() {
        Map<String, Object> json = JsonObjectMapper.getJson(ExtractedEntity.from(source));

        ExtractedEntity read = JsonObjectMapper.getObject(json, ExtractedEntity.class);

        assertThat(read).isEqualTo(ExtractedEntity.from(source));
    }
}
```

- [ ] **Step 2: Run the test and watch it fail**

Run: `mvn -pl datashare-api -am test -Dtest=ExtractedEntityTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL, `cannot find symbol: class ExtractedEntity`.

- [ ] **Step 3: Implement**

```java
package org.icij.datashare.text;

import org.icij.datashare.Entity;
import org.icij.datashare.model.ModelEntity;
import org.icij.datashare.text.indexing.IndexId;
import org.icij.datashare.text.indexing.IndexType;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** An entity rebuilt from the statement store, in the shape the "&lt;project&gt;.entities" index holds:
 *  the property keys are the namespaced wire form ("ftm:birthDate") the statements were stored under. */
@IndexType("ExtractedEntity")
public record ExtractedEntity(@IndexId String id, String model, Set<String> modelVersions, Set<String> types,
                              Set<String> documentIds, Map<String, List<String>> properties) implements Entity {

    public static ExtractedEntity from(ModelEntity entity) {
        return new ExtractedEntity(entity.id(), entity.model(), entity.modelVersions(), entity.types(),
                entity.documentIds(), entity.properties());
    }

    @Override
    public String getId() {
        return id;
    }
}
```

- [ ] **Step 4: Run the test and watch it pass**

Run: `mvn -pl datashare-api -am test -Dtest=ExtractedEntityTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add datashare-api/src/main/java/org/icij/datashare/text/ExtractedEntity.java \
        datashare-api/src/test/java/org/icij/datashare/text/ExtractedEntityTest.java
git commit -m "feat(index): add the indexable projection of a model entity"
```

---

### Task 6: `EntitiesIndexRebuilder`

**Files:**
- Create: `datashare-app/src/main/java/org/icij/datashare/model/EntitiesIndexRebuilder.java`
- Test: `datashare-app/src/test/java/org/icij/datashare/model/EntitiesIndexRebuilderTest.java`

**Interfaces:**
- Consumes: `Indexer.createEntitiesIndex(String)` (Task 4), `Indexer.deleteAll(String)`,
  `Indexer.bulkAdd(String, List<T extends Entity>)`, `StatementRepository.entities(String, Function)`
  (Task 2), `ExtractedEntity.from(ModelEntity)` (Task 5), `Project.entitiesIndex(String)` (Task 3).
- Produces:
  ```java
  public EntitiesIndexRebuilder(Indexer indexer, StatementRepository statements)
  public int rebuild(String projectId) throws IOException   // number of entities indexed
  ```
  This is what #2206's task will call.

- [ ] **Step 1: Write the failing test**

```java
package org.icij.datashare.model;

import co.elastic.clients.elasticsearch._types.Refresh;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.test.ElasticsearchRule;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.junit.After;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.fest.assertions.Assertions.assertThat;

public class EntitiesIndexRebuilderTest {
    @ClassRule public static ElasticsearchRule es = new ElasticsearchRule();
    private final ElasticsearchIndexer indexer = new ElasticsearchIndexer(es.client, new PropertiesProvider())
            .withRefresh(Refresh.True);
    private final InMemoryStatements statements = new InMemoryStatements();
    private final EntitiesIndexRebuilder rebuilder = new EntitiesIndexRebuilder(indexer, statements);

    @After
    public void tearDown() throws IOException {
        es.delete(Project.entitiesIndex("prj"));
    }

    @Test
    public void test_rebuild_creates_the_index_when_it_does_not_exist() throws Exception {
        statements.entities.add(entity("person-1", "Jane Doe"));

        assertThat(rebuilder.rebuild("prj")).isEqualTo(1);

        assertThat(indexer.exists(Project.entitiesIndex("prj"))).isTrue();
    }

    @Test
    public void test_the_rebuilt_entity_is_searchable_by_an_exact_property_value() throws Exception {
        statements.entities.add(entity("person-1", "Jane Doe"));
        rebuilder.rebuild("prj");

        assertThat(search("{\"query\":{\"term\":{\"properties.ftm:name\":\"Jane Doe\"}}}")).contains("person-1");
    }

    @Test
    public void test_the_rebuilt_entity_is_searchable_by_a_folded_property_value() throws Exception {
        statements.entities.add(entity("person-2", "Émile Zola"));
        rebuilder.rebuild("prj");

        assertThat(search("{\"query\":{\"match\":{\"properties.ftm:name.text\":\"emile\"}}}")).contains("person-2");
    }

    @Test
    public void test_the_rebuilt_entity_is_searchable_by_type_and_document() throws Exception {
        statements.entities.add(entity("person-1", "Jane Doe"));
        rebuilder.rebuild("prj");

        assertThat(search("{\"query\":{\"term\":{\"types\":\"Person\"}}}")).contains("person-1");
        assertThat(search("{\"query\":{\"term\":{\"documentIds\":\"doc-1\"}}}")).contains("person-1");
    }

    @Test
    public void test_a_second_rebuild_drops_an_entity_that_left_the_store() throws Exception {
        statements.entities.add(entity("person-1", "Jane Doe"));
        statements.entities.add(entity("person-2", "John Doe"));
        rebuilder.rebuild("prj");

        statements.entities.remove(1);
        assertThat(rebuilder.rebuild("prj")).isEqualTo(1);

        String all = search("{\"query\":{\"match_all\":{}}}");
        assertThat(all).contains("person-1");
        assertThat(all).doesNotContain("person-2");
    }

    @Test
    public void test_indexes_more_entities_than_one_chunk() throws Exception {
        IntStream.range(0, 2500).forEach(i -> statements.entities.add(entity("person-" + i, "Name " + i)));

        assertThat(rebuilder.rebuild("prj")).isEqualTo(2500);

        assertThat(search("{\"query\":{\"match_all\":{}},\"track_total_hits\":true}")).contains("\"value\":2500");
    }

    private String search(String query) throws IOException {
        return indexer.executeRaw("POST", Project.entitiesIndex("prj") + "/_search", query);
    }

    private static ModelEntity entity(String id, String name) {
        return new ModelEntity(id, "ftm", Set.of("Person"), Set.of("4.10.2"), Set.of("doc-1"),
                Map.of("ftm:name", List.of(name)));
    }

    private static class InMemoryStatements implements StatementRepository {
        private final List<ModelEntity> entities = new ArrayList<>();

        @Override
        public int save(String projectId, String runId, java.util.Collection<Statement> statements) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <R> R entities(String projectId, Function<Stream<ModelEntity>, R> consumer) {
            return consumer.apply(entities.stream());
        }

        @Override
        public Optional<ModelEntity> entity(String projectId, String entityId) {
            return entities.stream().filter(e -> e.id().equals(entityId)).findFirst();
        }
    }
}
```

- [ ] **Step 2: Run the test and watch it fail**

Run: `mvn -pl datashare-app -am test -Dtest=EntitiesIndexRebuilderTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL, `cannot find symbol: class EntitiesIndexRebuilder`.

- [ ] **Step 3: Implement**

```java
package org.icij.datashare.model;

import org.icij.datashare.text.ExtractedEntity;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.Indexer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Rebuilds a project's entities index from the statement store, which is the system of record: the
 *  index is a disposable projection and this is the only thing that writes it. */
public class EntitiesIndexRebuilder {
    private static final int CHUNK_SIZE = 1_000;
    private final Indexer indexer;
    private final StatementRepository statements;

    public EntitiesIndexRebuilder(Indexer indexer, StatementRepository statements) {
        this.indexer = indexer;
        this.statements = statements;
    }

    /** Empties the index and refills it, so an entity that left the store leaves the index too, and
     *  returns how many entities were indexed. Creates the index first, which is what gives a project
     *  created before the entities index existed one. */
    public int rebuild(String projectId) throws IOException {
        String indexName = Project.entitiesIndex(projectId);
        indexer.createEntitiesIndex(projectId);
        indexer.deleteAll(indexName);
        try {
            return statements.entities(projectId, entities -> index(indexName, entities.iterator()));
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private int index(String indexName, Iterator<ModelEntity> entities) {
        int written = 0;
        while (entities.hasNext()) {
            List<ExtractedEntity> chunk = new ArrayList<>(CHUNK_SIZE);
            while (chunk.size() < CHUNK_SIZE && entities.hasNext()) {
                chunk.add(ExtractedEntity.from(entities.next()));
            }
            try {
                indexer.bulkAdd(indexName, chunk);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            written += chunk.size();
        }
        return written;
    }
}
```

- [ ] **Step 4: Run the test and watch it pass**

Run: `mvn -pl datashare-app -am test -Dtest=EntitiesIndexRebuilderTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, all six tests.

- [ ] **Step 5: Commit**

```bash
git add datashare-app/src/main/java/org/icij/datashare/model/EntitiesIndexRebuilder.java \
        datashare-app/src/test/java/org/icij/datashare/model/EntitiesIndexRebuilderTest.java
git commit -m "feat(index): rebuild a project's entities index from its statements"
```

---

### Task 7: creating a project creates its entities index

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/web/ProjectResource.java:310-317`
- Modify: `datashare-app/src/main/java/org/icij/datashare/project/admin/ProjectAdminServiceImpl.java:512-525`
- Test: `datashare-app/src/test/java/org/icij/datashare/web/ProjectResourceTest.java`
- Test: `datashare-app/src/test/java/org/icij/datashare/project/admin/ProjectAdminServiceImplTest.java`

**Interfaces:**
- Consumes: `Indexer.createEntitiesIndex(String)` from Task 4.
- Produces: nothing new. Both creation paths now call it after `createIndex`.

- [ ] **Step 1: Write the failing tests**

In `ProjectResourceTest`, next to `test_create_project` (`:157-168`):

```java
    @Test
    public void test_create_project_creates_the_entities_index() throws IOException {
        String body = "{ \"name\": \"foo\", \"sourcePath\": \"/vault/foo\" }";
        when(indexer.createIndex("foo")).thenReturn(true);
        when(repository.getProject("foo")).thenReturn(null);
        when(repository.save((Project) any())).thenReturn(true);

        post("/api/project/", body).should().respond(201);

        verify(indexer).createEntitiesIndex("foo");
    }

    @Test
    public void test_create_project_fails_when_the_entities_index_cannot_be_created() throws IOException {
        String body = "{ \"name\": \"foo\", \"sourcePath\": \"/vault/foo\" }";
        when(indexer.createIndex("foo")).thenReturn(true);
        when(indexer.createEntitiesIndex("foo")).thenThrow(new IOException("ES down"));
        when(repository.getProject("foo")).thenReturn(null);
        when(repository.save((Project) any())).thenReturn(true);

        post("/api/project/", body).should().respond(500);
    }
```

Add `import static org.mockito.Mockito.verify;` if absent.

In `ProjectAdminServiceImplTest` (its `@Before` at `:50-61` mocks `Indexer`, and `minimalRequest`
at `:63-65` builds a request with `createIndex = true`):

```java
    @Test
    public void test_create_creates_the_entities_index_too() throws Exception {
        when(repository.getProject("foo")).thenReturn(null);
        when(repository.save(any(Project.class))).thenReturn(true);

        service.create(minimalRequest("foo"));

        verify(indexer).createIndex("foo");
        verify(indexer).createEntitiesIndex("foo");
    }

    @Test
    public void test_create_compensates_db_when_the_entities_index_fails() throws Exception {
        when(repository.getProject("foo")).thenReturn(null);
        when(repository.save(any(Project.class))).thenReturn(true);
        when(indexer.createEntitiesIndex("foo")).thenThrow(new IOException("ES down"));

        try {
            service.create(minimalRequest("foo"));
            fail("expected IOException");
        } catch (IOException e) {
            assertThat(e.getMessage()).contains("ES down");
        }

        InOrder inOrder = Mockito.inOrder(repository, indexer);
        inOrder.verify(repository).save(any(Project.class));
        inOrder.verify(indexer).createEntitiesIndex("foo");
        inOrder.verify(repository).deleteAll("foo");
    }
```

This is the arrangement of the existing `test_create_compensates_db_when_index_creation_fails`
(`:180-197`), with the throw moved to the entities index.

- [ ] **Step 2: Run the tests and watch them fail**

Run: `mvn -pl datashare-app -am test -Dtest=ProjectResourceTest,ProjectAdminServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL, `Wanted but not invoked: indexer.createEntitiesIndex("foo")`.

- [ ] **Step 3: Implement**

`ProjectResource.createIndexOnce` (`:310-317`):

```java
        boolean createIndexOnce(String name) {
            try {
                this.indexer.createIndex(IndexAccessVerifier.checkIndices(name));
                this.indexer.createEntitiesIndex(name);
                return true;
            } catch (IllegalArgumentException | IOException e){
                return false;
            }
        }
```

`ProjectAdminServiceImpl.createIndexOrRollback` (`:512-525`), inside the existing `try` so the
existing rollback covers it:

```java
        try {
            indexer.createIndex(name);
            indexer.createEntitiesIndex(name);
            return true;
        } catch (RuntimeException | IOException e) {
```

Leave the method's javadoc as it is.

- [ ] **Step 4: Run the tests and watch them pass**

Run: `mvn -pl datashare-app -am test -Dtest=ProjectResourceTest,ProjectAdminServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, including every pre-existing test in both classes.

- [ ] **Step 5: Commit**

```bash
git add datashare-app/src/main/java/org/icij/datashare/web/ProjectResource.java \
        datashare-app/src/main/java/org/icij/datashare/project/admin/ProjectAdminServiceImpl.java \
        datashare-app/src/test/java/org/icij/datashare/web/ProjectResourceTest.java \
        datashare-app/src/test/java/org/icij/datashare/project/admin/ProjectAdminServiceImplTest.java
git commit -m "feat(project): create the entities index with the project"
```

---

### Task 8: deleting a project empties its entities index

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/web/ProjectResource.java:201`
- Modify: `datashare-app/src/main/java/org/icij/datashare/project/admin/ProjectAdminServiceImpl.java:353-354`
- Test: `datashare-app/src/test/java/org/icij/datashare/web/ProjectResourceTest.java`
- Test: `datashare-app/src/test/java/org/icij/datashare/project/admin/ProjectAdminServiceImplTest.java`

**Interfaces:**
- Consumes: `Project.entitiesIndex(String)` from Task 3.
- Produces: nothing new.

Why the deletion result stays driven by the documents index: `ElasticsearchIndexer.deleteAll` returns
`false` for an index that does not exist (`:541-542`), and a project that never ran an extraction has
no entities index, so letting that `false` reach `indexDeleted` would report a failed deletion for
the common case.

- [ ] **Step 1: Write the failing tests**

In `ProjectResourceTest`, modelled on `test_delete_project_only_delete_index` (`:575-583`):

```java
    @Test
    public void test_delete_project_deletes_the_entities_index_too() throws Exception {
        Project foo = new Project("local-datashare");
        when(repository.getProjects(any())).thenReturn(List.of(foo));
        when(repository.deleteAll("local-datashare")).thenReturn(true);
        when(indexer.deleteAll("local-datashare")).thenReturn(true);

        delete("/api/project/local-datashare").should().respond(204);

        verify(indexer).deleteAll("local-datashare");
        verify(indexer).deleteAll("local-datashare.entities");
    }
```

Add `import static org.mockito.Mockito.verify;` if absent.

In `ProjectAdminServiceImplTest`, modelled on `test_delete_runs_full_cascade_in_order` (`:333-362`).
Note `indexer.deleteAll("foo.entities")` is left unstubbed, so Mockito returns `false`: that is the
common case of a project that never ran an extraction, and the assertion is that it does not turn a
successful deletion into a failed one.

```java
    @Test
    public void test_delete_empties_the_entities_index_too() throws Exception {
        Project project = new Project("foo");
        when(repository.getProject("foo")).thenReturn(project);
        when(repository.deleteAll("foo")).thenReturn(true);
        when(indexer.deleteAll("foo")).thenReturn(true);
        DocumentQueue<Path> queue = mock(DocumentQueue.class);
        when(queue.delete()).thenReturn(true);
        when(documentCollectionFactory.getQueues(any(String.class), eq(Path.class))).thenReturn(List.of(queue));
        ReportMap reportMap = mock(ReportMap.class);
        when(reportMap.delete()).thenReturn(true);
        when(documentCollectionFactory.createMap(any())).thenReturn(reportMap);
        when(propertiesProvider.createOverriddenWith(any())).thenReturn(new Properties());
        when(propertiesProvider.get(any())).thenReturn(Optional.empty());

        ProjectDeleted deleted = service.delete("foo", new ProjectDeleteOptions(false));

        verify(indexer).deleteAll("foo.entities");
        assertThat(deleted.indexDeleted()).isTrue();
    }

    @Test
    public void test_delete_keeping_the_index_keeps_the_entities_index_too() throws Exception {
        Project project = new Project("foo");
        when(repository.getProject("foo")).thenReturn(project);
        when(repository.deleteAll("foo")).thenReturn(true);
        DocumentQueue<Path> queue = mock(DocumentQueue.class);
        when(documentCollectionFactory.getQueues(any(String.class), eq(Path.class))).thenReturn(List.of(queue));
        ReportMap reportMap = mock(ReportMap.class);
        when(documentCollectionFactory.createMap(any())).thenReturn(reportMap);
        when(propertiesProvider.createOverriddenWith(any())).thenReturn(new Properties());
        when(propertiesProvider.get(any())).thenReturn(Optional.empty());

        service.delete("foo", new ProjectDeleteOptions(true));

        verify(indexer, never()).deleteAll(any());
    }
```

- [ ] **Step 2: Run the tests and watch them fail**

Run: `mvn -pl datashare-app -am test -Dtest=ProjectResourceTest,ProjectAdminServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL, `Wanted but not invoked: indexer.deleteAll("foo.entities")`.

- [ ] **Step 3: Implement**

`ProjectResource.projectDelete` (`:201`):

```java
            logger.info("Deleted {}'s index: {}", id, indexer.deleteAll(id));
            logger.info("Deleted {}'s entities index: {}", id, indexer.deleteAll(Project.entitiesIndex(id)));
```

`ProjectAdminServiceImpl.cascade` (`:353-354`):

```java
        boolean indexDeleted = !options.keepIndex()
                && runStep("index", name, () -> {
                    boolean documents = indexer.deleteAll(name);
                    indexer.deleteAll(Project.entitiesIndex(name));
                    return documents;
                });
```

Add `import org.icij.datashare.text.Project;` to either file if absent.

- [ ] **Step 4: Run the tests and watch them pass**

Run: `mvn -pl datashare-app -am test -Dtest=ProjectResourceTest,ProjectAdminServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add datashare-app/src/main/java/org/icij/datashare/web/ProjectResource.java \
        datashare-app/src/main/java/org/icij/datashare/project/admin/ProjectAdminServiceImpl.java \
        datashare-app/src/test/java/org/icij/datashare/web/ProjectResourceTest.java \
        datashare-app/src/test/java/org/icij/datashare/project/admin/ProjectAdminServiceImplTest.java
git commit -m "fix(project): delete a project's entities index with the project"
```

---

### Task 9: `PUT /api/index/:index` honours the entities suffix

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/web/IndexResource.java:81-88`
- Test: `datashare-app/src/test/java/org/icij/datashare/web/IndexResourceTest.java`

**Interfaces:**
- Consumes: `Indexer.createEntitiesIndex(String)` (Task 4), `Project.ENTITIES_INDEX_SUFFIX` (Task 3).
- Produces: nothing new.

Why: #2311 lets a granted user PUT `myproject.entities`, and without this the route would create a
documents-shaped index under an entities name, which the rebuilder would then write entity documents
into.

- [ ] **Step 1: Write the failing test**

In `IndexResourceTest`. The class's `tearDown` (`:595-598`) already deletes
`cecile-datashare.entities`, so no new cleanup is needed:

```java
    @Test
    public void test_put_create_entities_index_uses_the_entity_mappings() throws IOException {
        configure(routes -> routes.add(new IndexResource(indexer, propertiesProvider))
                .filter(new BasicAuthFilter("/", "icij", DatashareUser.singleUser("cecile"))));

        put("/api/index/cecile-datashare.entities").withPreemptiveAuthentication("cecile", "pass")
                .should().respond(201);

        String mapping = indexer.executeRaw("GET", "cecile-datashare.entities/_mapping", null);
        assertThat(mapping).contains("entity_properties");
    }
```

- [ ] **Step 2: Run the test and watch it fail**

Run: `mvn -pl datashare-app -am test -Dtest=IndexResourceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL, the mapping body does not contain `entity_properties` (a documents-shaped index was
created instead).

- [ ] **Step 3: Implement**

In `IndexResource.createIndex` (`:81-88`):

```java
    public Payload createIndex(@Parameter(name = "index", description = "index to create", in = ParameterIn.PATH) final String index, Context context) throws IOException {
        modeVerifier.checkAllowedMode(Mode.LOCAL, Mode.EMBEDDED);
        try{
            String granted = checkGrantedIndices(index, context);
            // a ".entities" name is granted through its base project, so it reaches here and has to be
            // created with the entity mappings rather than the document ones
            boolean created = granted.endsWith(Project.ENTITIES_INDEX_SUFFIX)
                    ? indexer.createEntitiesIndex(granted.substring(0, granted.length() - Project.ENTITIES_INDEX_SUFFIX.length()))
                    : indexer.createIndex(granted);
            return created ? created() : ok();
        } catch (IllegalArgumentException e){
            return PayloadFormatter.error(e, HttpStatus.BAD_REQUEST);
        }
    }
```

Add `import org.icij.datashare.text.Project;` if absent.

- [ ] **Step 4: Run the test and watch it pass**

Run: `mvn -pl datashare-app -am test -Dtest=IndexResourceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, including `test_put_createIndex` (`:198`) and
`test_async_search_on_entities_index_is_polled_by_its_owner` (`:554`).

- [ ] **Step 5: Commit**

```bash
git add datashare-app/src/main/java/org/icij/datashare/web/IndexResource.java \
        datashare-app/src/test/java/org/icij/datashare/web/IndexResourceTest.java
git commit -m "fix(index): create an entities index with the entity mappings over HTTP"
```

---

### Task 10: full-suite verification

**Files:** none.

- [ ] **Step 1: Build and test every touched module**

Run: `mvn -pl datashare-api,datashare-db,datashare-index,datashare-app -am test`
Expected: PASS.

- [ ] **Step 2: Separate pre-existing failures from new ones**

Four classes fail in this environment on any branch for environment reasons. For each failure,
check it against `main` before attributing it to this branch:

```bash
git stash list  # nothing of ours should be stashed
git log --oneline -1
```

Report which failures are pre-existing and which this branch introduced. Do not claim a green suite
without pasting the summary line.

- [ ] **Step 3: Push the branch**

```bash
git push -u origin feat/2205-entities-index
```

# MCP Document Tagging Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Also required, for implementers and reviewers alike:** `/tdd-workflow`, `/java-exception`, `/java-test-readability`, `/ponytail:ponytail`. The "Working agreements" section below is what those four amount to on this plan; read it before the first step.

**Goal:** Let an MCP client tag and untag documents in a project, see a document's tags, and list the tag vocabulary of a project.

**Architecture:** Three new MCP tools in `org.icij.datashare.web`, following the existing `McpTool` contract: a `name()`, a `description()`, a JSON Schema file on the classpath, and a `call(JsonNode, Context)`. The write tools delegate to `DocumentResource.groupTagDocument` / `groupUntagDocument` rather than to `Repository`/`Indexer` directly, which inherits that resource's `requireGranted(context, projectId)` authorization and its `tagsAsArray(currentUser)` attribution, so a tag written over MCP carries the authenticated user's name. `list_tags` is a terms aggregation sent through the same raw-Elasticsearch path `search_documents` already uses; that path is extracted into a shared `McpIndexSearch` so the project validation and shard check live in one place.

**Tech Stack:** Java 17, Guice (multibinder for tool registration), Jackson, fluent-http (`net.codestory.http`), Elasticsearch REST client, JUnit 4 + Mockito + FEST assertions.

**Spec:** https://github.com/ICIJ/datashare/issues/2483 (the issue body is the spec; it is quoted inline in each task below so an executor never has to leave this file)

## Global Constraints

- Documents are selected by explicit `doc_ids` only. There is no tag-by-query in this work: one bad query would be a mass mutation with no undo.
- `doc_ids` is capped at 100 per call. The cap is declared in the JSON Schema **and** re-checked in Java as a `McpToolException.InvalidArgument`, because `McpResource` does not validate arguments against the schema: the schema only advises the client.
- The same cap of 100 applies to the `tags` list. One constant, `McpTagDocumentsTool.MAX_ITEMS = 100`, is the single rail.
- Every tool delegates to `DocumentResource` for anything that writes or reads tags. Never call `Repository` or `Indexer` directly from a tagging tool: that is how attribution and the `requireGranted` membership gate get lost.
- Tool names are snake_case: `tag_documents`, `untag_documents`, `list_tags`. Argument names are snake_case too (`doc_ids`), matching the MCP convention these tools expose to clients, even though `DocumentResource.BatchTagQuery` calls the same field `docIds`.
- Each tool needs a schema file at `datashare-app/src/main/resources/mcp/schemas/<tool name>.json`. `McpResource` reads every schema at construction time, so a missing file fails at startup, not on the first `tools/list`.
- No em dashes and no non-ASCII characters in code, comments, or commit messages.
- The three tasks are three stacked pull requests, in order. Each is a separate branch off the previous one.

## Working agreements

The four skills above, applied to this plan. A reviewer rejects a task that breaks one of these.

**TDD (`/tdd-workflow`).** Red before green, every time: write the test, run it, watch it fail for the reason you expect, then write the smallest code that passes. A step that says "run it to make sure it fails" is not a formality; if it passes or fails for the wrong reason, the test is wrong, not the plan. Coverage here means every branch this work adds has a test: the happy path, the refused project, and each rejected argument shape. There is no browser in this work, so there is no E2E tier; the tier that plays that role is `McpLocalModeTest`, which boots the real Guice module and proves the bindings and schema files resolve.

**Exceptions (`/java-exception`).** Throw early, catch late, log once at the top.
- `McpResource.callKnownTool` is the single catch-all and the single place that logs. No tool catches `Exception` or `Throwable`, and no tool logs.
- When a tool translates an exception, it passes the cause: `new McpToolException.Forbidden(project, e)`, never `new McpToolException.Forbidden(e.getMessage())`. Losing the stack of a refused write is losing the only record of who was refused what.
- Do not wrap `IOException` into a `RuntimeException`. `DocumentResource.groupTagDocument` and `Indexer.executeRaw` declare it; the tools declare it too and let it travel to `McpResource`. `IOException` on a tool signature is documentation: this tool talks to the database or the index.
- Translate an exception only where the translation adds meaning for the MCP client. Task 2's `readTags` deliberately has no `ForbiddenException` catch, because the document read earlier in the same method already made the grant check and already threw; a second catch there would be dead code pretending to be safety.
- An argument rejection carries its argument name, which is what `McpToolException.InvalidArgument.missing(name)` / `.invalid(name)` exist for. No bare `throw new RuntimeException()`.

**Test readability (`/java-test-readability`).** Only the data the test is about belongs in the test body.
- `McpToolsTest` already has the fixtures that hide the noise: `serveAs(login, projects)`, `call(login, tool, arguments)`, `payload(response)`, `doc(content)`. Use them; add one when a test body starts carrying setup that is not the point of the test (Task 1 adds `manyDocIds(int)` for exactly this reason).
- Name a test after the behavior it pins, in the file's existing `test_<subject>_<expectation>` style: `test_tag_documents_attributes_the_tags_to_the_caller`, not `test_tag_documents_2`.
- Keep the three blocks (context, action, expectation) visually separated by blank lines, as the rest of the file does. Do not introduce `// GIVEN // WHEN // THEN` comments into `McpToolsTest`: that file does not use them, and house style beats personal style.
- One behavior per test. The exception is `test_tag_documents_rejects_bad_lists`, which is a table of rejected shapes of the same behavior.

**Laziness (`/ponytail:ponytail`).** The shortest diff that actually works.
- Reuse before writing: `McpArguments` is the home for argument parsing, `McpToolException` for failures, `DocumentResource` for anything touching tags. Do not add a service, a mapper, a validator class, or an interface with one implementation.
- The one abstraction this plan does introduce, `McpIndexSearch` in Task 3, exists because a second caller appeared. That is the bar. If you find yourself wanting a third helper, it probably belongs as a method on one that exists.
- No new dependency. No config option for a value that never changes: the cap of 100 is a constant, not a property.
- If a step looks like it can be done in fewer lines than the plan shows, and the tests still pass, do it in fewer lines and say so in the task report.

## Review Focus

These are the inputs the issue implies but does not name. Each line has a test in the task that owns the code.

1. `doc_ids` holding a non-string item (`[1, null]`): must be a `-32602` invalid argument, not a 500 from a failed cast. Test in Task 1.
2. `doc_ids` or `tags` holding a blank string (`[""]`): must be `-32602`. An empty-label tag is unremovable noise in the index. Test in Task 1.
3. A doc id that is not in the project: `repository.tag` writes the row regardless and the tool answers `"tagged": 1`. That is the existing REST behavior, so it stays, but it is pinned by a test and stated in the tool description so an agent does not read the count as proof the documents exist. Test in Task 1.
4. A document with no tags: `get_document` must return `"tags": []`, not `null` and not a missing field, so an agent can branch on it without a null check. Test in Task 2.
5. A project where nothing is tagged: `list_tags` must return `[]`, not fail on a missing `aggregations` node. Test in Task 3.

---

### Task 1: `tag_documents` and `untag_documents`

> From the issue: "two tools taking `project`, `doc_ids`, `tags`. Delegates to `DocumentResource.groupTagDocument` / `groupUntagDocument`. Requires making `BatchTagQuery` (`DocumentResource.java:500`) package-private; the MCP tools are in the same package. Cap `doc_ids` at 100 per call, enforced in the schema and re-checked as `McpToolException.InvalidArgument`."

**Files:**
- Create: `datashare-app/src/main/java/org/icij/datashare/web/McpTagDocumentsTool.java`
- Create: `datashare-app/src/main/java/org/icij/datashare/web/McpUntagDocumentsTool.java`
- Create: `datashare-app/src/main/resources/mcp/schemas/tag_documents.json`
- Create: `datashare-app/src/main/resources/mcp/schemas/untag_documents.json`
- Modify: `datashare-app/src/main/java/org/icij/datashare/web/McpArguments.java` (add `requireTexts`)
- Modify: `datashare-app/src/main/java/org/icij/datashare/web/DocumentResource.java:500-513` (make `BatchTagQuery` and its constructor package-private)
- Modify: `datashare-app/src/main/java/org/icij/datashare/mode/CommonMode.java:577-584` (register the two tools)
- Test: `datashare-app/src/test/java/org/icij/datashare/web/McpToolsTest.java`

**Interfaces:**
- Consumes: `McpArguments.requireText(JsonNode, String)`, `McpToolException.InvalidArgument.missing(String)` / `.invalid(String)`, `McpToolException.Forbidden(String, Throwable)`, `DocumentResource.groupTagDocument(String, BatchTagQuery, Context)` and `groupUntagDocument(String, BatchTagQuery, Context)` (both return `Payload`, both throw `IOException`).
- Produces: `McpArguments.requireTexts(JsonNode arguments, String name, int max) -> List<String>`; `McpTagDocumentsTool.MAX_ITEMS` (`static final int`, value 100); `DocumentResource.BatchTagQuery(List<String> tags, List<String> docIds)` as a package-private constructor; two Guice-bound `McpTool` implementations.

- [ ] **Step 1: Write the failing list-argument test**

Add to `datashare-app/src/test/java/org/icij/datashare/web/McpArgumentsTest.java` (new file):

```java
package org.icij.datashare.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.util.List;

import static org.fest.assertions.Assertions.assertThat;

public class McpArgumentsTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonNode arguments(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    @Test
    public void test_require_texts_returns_the_list() throws Exception {
        assertThat(McpArguments.requireTexts(arguments("{\"ids\":[\"a\",\"b\"]}"), "ids", 100))
                .isEqualTo(List.of("a", "b"));
    }

    @Test(expected = McpToolException.InvalidArgument.class)
    public void test_require_texts_rejects_a_missing_list() throws Exception {
        McpArguments.requireTexts(arguments("{}"), "ids", 100);
    }

    @Test(expected = McpToolException.InvalidArgument.class)
    public void test_require_texts_rejects_an_empty_list() throws Exception {
        McpArguments.requireTexts(arguments("{\"ids\":[]}"), "ids", 100);
    }

    @Test(expected = McpToolException.InvalidArgument.class)
    public void test_require_texts_rejects_a_list_above_the_cap() throws Exception {
        McpArguments.requireTexts(arguments("{\"ids\":[\"a\",\"b\",\"c\"]}"), "ids", 2);
    }

    @Test(expected = McpToolException.InvalidArgument.class)
    public void test_require_texts_rejects_a_non_string_item() throws Exception {
        McpArguments.requireTexts(arguments("{\"ids\":[1]}"), "ids", 100);
    }

    @Test(expected = McpToolException.InvalidArgument.class)
    public void test_require_texts_rejects_a_blank_item() throws Exception {
        McpArguments.requireTexts(arguments("{\"ids\":[\" \"]}"), "ids", 100);
    }

    @Test(expected = McpToolException.InvalidArgument.class)
    public void test_require_texts_rejects_a_value_that_is_not_a_list() throws Exception {
        McpArguments.requireTexts(arguments("{\"ids\":\"a\"}"), "ids", 100);
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `mvn -pl datashare-app test -Dtest=McpArgumentsTest`
Expected: compilation failure, "cannot find symbol: method requireTexts".

- [ ] **Step 3: Implement `requireTexts`**

In `McpArguments.java`, add the import `java.util.ArrayList` and `java.util.List`, then add after `optionalText`:

```java
    /** A non-empty list of non-blank strings, capped at {@code max} items: the rail on the batch write tools. */
    static List<String> requireTexts(JsonNode arguments, String name, int max) {
        JsonNode node = arguments.path(name);
        if (node.isMissingNode() || node.isNull()) {
            throw McpToolException.InvalidArgument.missing(name);
        }
        if (!node.isArray() || node.isEmpty() || node.size() > max) {
            throw McpToolException.InvalidArgument.invalid(name);
        }
        List<String> values = new ArrayList<>();
        node.forEach(item -> values.add(requireItemText(name, item)));
        return values;
    }

    private static String requireItemText(String name, JsonNode item) {
        if (!item.isTextual() || item.asText().isBlank()) {
            throw McpToolException.InvalidArgument.invalid(name);
        }
        return item.asText();
    }
```

- [ ] **Step 4: Run the test again**

Run: `mvn -pl datashare-app test -Dtest=McpArgumentsTest`
Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add datashare-app/src/main/java/org/icij/datashare/web/McpArguments.java \
        datashare-app/src/test/java/org/icij/datashare/web/McpArgumentsTest.java
git commit -m "feat(mcp): read a capped list of strings from tool arguments"
```

- [ ] **Step 6: Write the failing tool tests**

In `McpToolsTest.java`, first widen the tool set. Replace the `McpResource mcp = new McpResource(Set.of(...))` block in `serveAs` with:

```java
        McpResource mcp = new McpResource(Set.of(new McpListProjectsTool(projectResource),
                                                 new McpSearchDocumentsTool(indexer),
                                                 new McpGetDocumentTool(documentResource),
                                                 new McpListTasksTool(taskResource),
                                                 new McpStopTaskTool(taskResource, taskManager,
                                                                     TaskPolicyChecker.ALLOW_ALL),
                                                 new McpTagDocumentsTool(documentResource),
                                                 new McpUntagDocumentsTool(documentResource)));
```

Then add these imports at the top of the file:

```java
import org.icij.datashare.text.Tag;
import org.mockito.ArgumentCaptor;
```

And append these tests to the class:

```java
    @Test
    public void test_tag_documents_writes_to_the_repository_and_the_index() throws Exception {
        serveAs("local", "foo");
        // Mockito 5 captures a varargs parameter one element at a time, so the captor takes the element type.
        ArgumentCaptor<Tag> written = ArgumentCaptor.forClass(Tag.class);

        JsonNode result = payload(call("local", "tag_documents",
                                       "{\"project\":\"foo\",\"doc_ids\":[\"doc1\",\"doc2\"],\"tags\":[\"invoice\"]}"));

        org.mockito.Mockito.verify(repository).tag(eq(new Project("foo")), eq(List.of("doc1", "doc2")),
                                                   written.capture());
        org.mockito.Mockito.verify(indexer).tag(eq(new Project("foo")), eq(List.of("doc1", "doc2")), any(Tag[].class));
        assertThat(written.getValue().label).isEqualTo("invoice");
        assertThat(result.get("tagged").asInt()).isEqualTo(2);
    }

    @Test
    public void test_tag_documents_attributes_the_tags_to_the_caller() throws Exception {
        serveAs("local", "foo");
        ArgumentCaptor<Tag> written = ArgumentCaptor.forClass(Tag.class);

        call("local", "tag_documents", "{\"project\":\"foo\",\"doc_ids\":[\"doc1\"],\"tags\":[\"invoice\"]}");

        org.mockito.Mockito.verify(repository).tag(any(Project.class), eq(List.of("doc1")), written.capture());
        assertThat(written.getValue().user.id).isEqualTo("local");
    }

    // repository.tag writes the row whether or not the id exists, exactly like the REST route. The count is a count
    // of requested ids, not a proof the documents are there.
    @Test
    public void test_tag_documents_counts_unknown_ids_too() throws Exception {
        serveAs("local", "foo");

        JsonNode result = payload(call("local", "tag_documents",
                                       "{\"project\":\"foo\",\"doc_ids\":[\"nope\"],\"tags\":[\"invoice\"]}"));

        assertThat(result.get("tagged").asInt()).isEqualTo(1);
    }

    @Test
    public void test_untag_documents_removes_the_tags() throws Exception {
        serveAs("local", "foo");

        JsonNode result = payload(call("local", "untag_documents",
                                       "{\"project\":\"foo\",\"doc_ids\":[\"doc1\"],\"tags\":[\"invoice\"]}"));

        org.mockito.Mockito.verify(repository).untag(eq(new Project("foo")), eq(List.of("doc1")), any(Tag[].class));
        org.mockito.Mockito.verify(indexer).untag(eq(new Project("foo")), eq(List.of("doc1")), any(Tag[].class));
        assertThat(result.get("untagged").asInt()).isEqualTo(1);
    }

    @Test
    public void test_tag_documents_on_ungranted_project_is_forbidden() throws Exception {
        serveAs("local", "foo");

        JsonNode response = call("local", "tag_documents",
                                 "{\"project\":\"bar\",\"doc_ids\":[\"doc1\"],\"tags\":[\"invoice\"]}");

        assertThat(response.at("/result/isError").asBoolean()).isTrue();
        assertThat(response.at("/result/content/0/text").asText()).isEqualTo("forbidden: bar");
        org.mockito.Mockito.verifyNoInteractions(repository, indexer);
    }

    @Test
    public void test_untag_documents_on_ungranted_project_is_forbidden() throws Exception {
        serveAs("local", "foo");

        JsonNode response = call("local", "untag_documents",
                                 "{\"project\":\"bar\",\"doc_ids\":[\"doc1\"],\"tags\":[\"invoice\"]}");

        assertThat(response.at("/result/content/0/text").asText()).isEqualTo("forbidden: bar");
        org.mockito.Mockito.verifyNoInteractions(repository, indexer);
    }

    /** A JSON array of distinct document ids, so a test about the cap reads as a test about the cap. */
    private String manyDocIds(int count) {
        List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add("\"doc" + i + "\"");
        }
        return "[" + String.join(",", ids) + "]";
    }

    @Test
    public void test_tag_documents_accepts_100_ids() throws Exception {
        serveAs("local", "foo");

        JsonNode result = payload(call("local", "tag_documents", "{\"project\":\"foo\",\"doc_ids\":"
                                                                 + manyDocIds(100) + ",\"tags\":[\"invoice\"]}"));

        assertThat(result.get("tagged").asInt()).isEqualTo(100);
    }

    @Test
    public void test_tag_documents_above_100_ids_is_invalid() throws Exception {
        serveAs("local", "foo");

        JsonNode response = call("local", "tag_documents", "{\"project\":\"foo\",\"doc_ids\":" + manyDocIds(101)
                                                           + ",\"tags\":[\"invoice\"]}");

        assertThat(response.at("/error/code").asInt()).isEqualTo(-32602);
        org.mockito.Mockito.verifyNoInteractions(repository, indexer);
    }

    @Test
    public void test_tag_documents_rejects_bad_lists() throws Exception {
        serveAs("local", "foo");

        assertThat(call("local", "tag_documents", "{\"project\":\"foo\",\"doc_ids\":[],\"tags\":[\"a\"]}")
                           .at("/error/code").asInt()).isEqualTo(-32602);
        assertThat(call("local", "tag_documents", "{\"project\":\"foo\",\"doc_ids\":[1],\"tags\":[\"a\"]}")
                           .at("/error/code").asInt()).isEqualTo(-32602);
        assertThat(call("local", "tag_documents", "{\"project\":\"foo\",\"doc_ids\":[\"doc1\"],\"tags\":[\"\"]}")
                           .at("/error/code").asInt()).isEqualTo(-32602);
        assertThat(call("local", "tag_documents", "{\"project\":\"foo\",\"doc_ids\":[\"doc1\"]}")
                           .at("/error/code").asInt()).isEqualTo(-32602);
        assertThat(call("local", "tag_documents", "{\"doc_ids\":[\"doc1\"],\"tags\":[\"a\"]}")
                           .at("/error/code").asInt()).isEqualTo(-32602);
        org.mockito.Mockito.verifyNoInteractions(repository, indexer);
    }
```

- [ ] **Step 7: Run them to make sure they fail**

Run: `mvn -pl datashare-app test -Dtest=McpToolsTest`
Expected: compilation failure, "cannot find symbol: class McpTagDocumentsTool".

- [ ] **Step 8: Open up `BatchTagQuery`**

In `DocumentResource.java`, drop `private` from the nested class and from its constructor, and say why it is visible:

```java
    // Package-private: the MCP tagging tools build one of these to reuse this resource's grant check and the
    // user attribution in tagsAsArray.
    static class BatchTagQuery {
        final List<String> tags;
        final List<String> docIds;

        @JsonCreator
        BatchTagQuery(@JsonProperty("tags") List<String> tags, @JsonProperty("docIds") List<String> docIds) {
            this.tags = tags;
            this.docIds = docIds;
        }

        Tag[] tagsAsArray(User user) {
            return tags.stream().map(label -> new Tag(label, user)).toArray(Tag[]::new);
        }
    }
```

- [ ] **Step 9: Write `McpTagDocumentsTool`**

Create `datashare-app/src/main/java/org/icij/datashare/web/McpTagDocumentsTool.java`:

```java
package org.icij.datashare.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.Inject;
import net.codestory.http.Context;
import org.icij.datashare.web.errors.ForbiddenException;
import java.io.IOException;
import java.util.List;
import java.util.Map;

public class McpTagDocumentsTool implements McpTool {
    /** The safety rail on a write with no undo beyond the agent calling untag_documents itself. */
    static final int MAX_ITEMS = 100;
    private final DocumentResource documentResource;

    @Inject
    public McpTagDocumentsTool(DocumentResource documentResource) {
        this.documentResource = documentResource;
    }

    @Override
    public String name() {
        return "tag_documents";
    }

    @Override
    public String description() {
        return "Adds tags to up to 100 documents at once, by document id. The tags are attributed to the calling "
               + "user. An id that is not in the project is tagged anyway and still counted, so the count is not a "
               + "proof that the documents exist.";
    }

    @Override
    public Object call(JsonNode arguments, Context context) throws IOException {
        BatchTag batch = BatchTag.from(arguments);
        try {
            documentResource.groupTagDocument(batch.project, batch.query(), context);
        } catch (ForbiddenException e) {
            throw new McpToolException.Forbidden(batch.project, e);
        }
        return Map.of("project", batch.project, "tagged", batch.docIds.size(), "tags", batch.tags);
    }

    /** The arguments both tagging tools take. */
    record BatchTag(String project, List<String> docIds, List<String> tags) {
        static BatchTag from(JsonNode arguments) {
            return new BatchTag(McpArguments.requireText(arguments, "project"),
                                McpArguments.requireTexts(arguments, "doc_ids", MAX_ITEMS),
                                McpArguments.requireTexts(arguments, "tags", MAX_ITEMS));
        }

        DocumentResource.BatchTagQuery query() {
            return new DocumentResource.BatchTagQuery(tags, docIds);
        }
    }
}
```

- [ ] **Step 10: Write `McpUntagDocumentsTool`**

Create `datashare-app/src/main/java/org/icij/datashare/web/McpUntagDocumentsTool.java`:

```java
package org.icij.datashare.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.Inject;
import net.codestory.http.Context;
import org.icij.datashare.web.errors.ForbiddenException;
import java.io.IOException;
import java.util.Map;

public class McpUntagDocumentsTool implements McpTool {
    private final DocumentResource documentResource;

    @Inject
    public McpUntagDocumentsTool(DocumentResource documentResource) {
        this.documentResource = documentResource;
    }

    @Override
    public String name() {
        return "untag_documents";
    }

    @Override
    public String description() {
        return "Removes tags from up to 100 documents at once, by document id. A tag the document does not carry "
               + "is ignored.";
    }

    @Override
    public Object call(JsonNode arguments, Context context) throws IOException {
        McpTagDocumentsTool.BatchTag batch = McpTagDocumentsTool.BatchTag.from(arguments);
        try {
            documentResource.groupUntagDocument(batch.project(), batch.query(), context);
        } catch (ForbiddenException e) {
            throw new McpToolException.Forbidden(batch.project(), e);
        }
        return Map.of("project", batch.project(), "untagged", batch.docIds().size(), "tags", batch.tags());
    }
}
```

- [ ] **Step 11: Write the two schema files**

Create `datashare-app/src/main/resources/mcp/schemas/tag_documents.json`:

```json
{
  "type": "object",
  "properties": {
    "project": {"type": "string", "description": "project id"},
    "doc_ids": {
      "type": "array",
      "description": "document ids to tag",
      "items": {"type": "string"},
      "minItems": 1,
      "maxItems": 100
    },
    "tags": {
      "type": "array",
      "description": "tag labels to add",
      "items": {"type": "string"},
      "minItems": 1,
      "maxItems": 100
    }
  },
  "required": ["project", "doc_ids", "tags"]
}
```

Create `datashare-app/src/main/resources/mcp/schemas/untag_documents.json` with the same content, except the `doc_ids` description is `"document ids to untag"` and the `tags` description is `"tag labels to remove"`.

- [ ] **Step 12: Register the tools**

In `CommonMode.java`, add the imports `org.icij.datashare.web.McpTagDocumentsTool` and `org.icij.datashare.web.McpUntagDocumentsTool`, then extend `bindMcpTools`:

```java
        tools.addBinding().to(McpStopTaskTool.class);
        tools.addBinding().to(McpTagDocumentsTool.class);
        tools.addBinding().to(McpUntagDocumentsTool.class);
```

- [ ] **Step 13: Run the tests**

Run: `mvn -pl datashare-app test -Dtest='McpToolsTest+McpArgumentsTest+McpResourceTest+McpLocalModeTest'`
Expected: PASS. `McpLocalModeTest` boots the real Guice module, so it is the one that proves the schema files are on the classpath and the bindings resolve.

- [ ] **Step 14: Commit**

```bash
git add datashare-app/src/main/java/org/icij/datashare/web/McpTagDocumentsTool.java \
        datashare-app/src/main/java/org/icij/datashare/web/McpUntagDocumentsTool.java \
        datashare-app/src/main/resources/mcp/schemas/tag_documents.json \
        datashare-app/src/main/resources/mcp/schemas/untag_documents.json \
        datashare-app/src/main/java/org/icij/datashare/web/DocumentResource.java \
        datashare-app/src/main/java/org/icij/datashare/mode/CommonMode.java \
        datashare-app/src/test/java/org/icij/datashare/web/McpToolsTest.java
git commit -m "feat(mcp): tag and untag documents from an MCP client"
```

---

### Task 2: tags in `get_document`

> From the issue: "`DocumentSlice` gains a `tags` field from the existing `DocumentResource.getDocumentTags(project, docId, context)`. Closes the loop so the agent can check a document before tagging and confirm the write after."

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/web/McpGetDocumentTool.java` (the `call` method and the `DocumentSlice` record)
- Modify: `datashare-app/src/main/resources/mcp/schemas/get_document.json` (no argument change; nothing to do here, listed so the executor does not go looking for one)
- Test: `datashare-app/src/test/java/org/icij/datashare/web/McpToolsTest.java`

**Interfaces:**
- Consumes: `DocumentResource.getDocumentTags(String projectId, String docId, Context context) -> List<Tag>`, where `Tag` has a public `String label` field.
- Produces: `McpGetDocumentTool.DocumentSlice(String id, String path, String contentType, int offset, int maxOffset, List<String> tags, String text)` and `DocumentSlice.of(Document, ExtractedText, List<String>)`.

- [ ] **Step 1: Write the failing tests**

Append to `McpToolsTest`:

```java
    @Test
    public void test_get_document_lists_its_tags() throws Exception {
        serveAs("local", "foo");
        when(indexer.get("foo", "doc1", "doc1")).thenReturn(doc("hello world"));
        when(indexer.getExtractedText("foo", "doc1", null, 0, 11, null))
                .thenReturn(new ExtractedText("hello world", 0, 11, 11));
        when(repository.getTags(new Project("foo"), "doc1"))
                .thenReturn(List.of(new Tag("invoice"), new Tag("Reviewed")));

        JsonNode result = payload(call("local", "get_document", "{\"project\":\"foo\",\"id\":\"doc1\"}"));

        assertThat(result.at("/tags/0").asText()).isEqualTo("invoice");
        assertThat(result.at("/tags/1").asText()).isEqualTo("Reviewed");
    }

    @Test
    public void test_get_document_without_tags_returns_an_empty_list() throws Exception {
        serveAs("local", "foo");
        when(indexer.get("foo", "doc1", "doc1")).thenReturn(doc("hello world"));
        when(indexer.getExtractedText("foo", "doc1", null, 0, 11, null))
                .thenReturn(new ExtractedText("hello world", 0, 11, 11));

        JsonNode result = payload(call("local", "get_document", "{\"project\":\"foo\",\"id\":\"doc1\"}"));

        assertThat(result.get("tags").isArray()).isTrue();
        assertThat(result.get("tags").size()).isEqualTo(0);
    }
```

The second test leaves `repository.getTags` unstubbed on purpose: Mockito answers a `List`-returning method with an empty list, which is the no-tags case.

- [ ] **Step 2: Run them to make sure they fail**

Run: `mvn -pl datashare-app test -Dtest=McpToolsTest`
Expected: both new tests FAIL, the first on `result.at("/tags/0")` being empty, the second on `result.get("tags")` being null.

- [ ] **Step 3: Add the field**

In `McpGetDocumentTool.java`, add `import java.util.List;` and change `call` plus `DocumentSlice`:

```java
    @Override
    public Object call(JsonNode arguments, Context context) throws IOException {
        DocumentAddress address = DocumentAddress.from(arguments);
        Document document = readDocument(address, context);
        ExtractedText slice = readText(address, TextWindow.within(document, arguments), context);
        return DocumentSlice.of(document, slice, readTags(address, context));
    }

    // Tags come from the database, not from the index: it is the database that keeps their original casing.
    private List<String> readTags(DocumentAddress address, Context context) {
        return documentResource.getDocumentTags(address.project, address.id, context).stream()
                               .map(tag -> tag.label).toList();
    }
```

and

```java
    record DocumentSlice(String id, String path, String contentType, int offset, int maxOffset, List<String> tags,
                         String text) {
        static DocumentSlice of(Document document, ExtractedText slice, List<String> tags) {
            return new DocumentSlice(document.getId(), String.valueOf(document.getPath()), document.getContentType(),
                                     slice.offset, slice.maxOffset, tags, slice.content);
        }
    }
```

- [ ] **Step 4: Mention the field in the description**

In the same file, extend `description()`:

```java
    @Override
    public String description() {
        return "Reads a document's metadata, its tags and a slice of its extracted text.";
    }
```

- [ ] **Step 5: Run the tests**

Run: `mvn -pl datashare-app test -Dtest=McpToolsTest`
Expected: PASS, including the pre-existing `test_get_document_on_granted_project` and `test_get_document_never_reads_text_from_repository` (reading tags goes through `repository.getTags`, not `repository.getDocument`, so that last one stays true).

- [ ] **Step 6: Commit**

```bash
git add datashare-app/src/main/java/org/icij/datashare/web/McpGetDocumentTool.java \
        datashare-app/src/test/java/org/icij/datashare/web/McpToolsTest.java
git commit -m "feat(mcp): show a document's tags in get_document"
```

---

### Task 3: `list_tags`

> From the issue: "terms aggregation on the `tags` field, reusing the `executeRaw` path and project validation from `McpSearchDocumentsTool`. Lets the agent reuse the existing vocabulary instead of inventing `invoice` / `invoices` / `Invoice`." And: "`tags` is mapped as `{\"type\": \"keyword\", \"normalizer\": \"keyword_lowercase\"}`, so no mapping change is needed for the aggregation. That normalizer lowercases at index time while Postgres keeps the original casing, so `list_tags` returns normalized tags, not verbatim ones. The tool description should say so."

This task starts with a refactor: the raw search path moves out of `McpSearchDocumentsTool` into `McpIndexSearch`, so `list_tags` inherits the single-project check, the grant check and the failed-shard check instead of copying them.

**Files:**
- Create: `datashare-app/src/main/java/org/icij/datashare/web/McpIndexSearch.java`
- Create: `datashare-app/src/main/java/org/icij/datashare/web/McpListTagsTool.java`
- Create: `datashare-app/src/main/resources/mcp/schemas/list_tags.json`
- Modify: `datashare-app/src/main/java/org/icij/datashare/web/McpSearchDocumentsTool.java` (delegate to `McpIndexSearch`)
- Modify: `datashare-app/src/main/java/org/icij/datashare/mode/CommonMode.java:577-586` (register the tool)
- Test: `datashare-app/src/test/java/org/icij/datashare/web/McpToolsTest.java`

**Interfaces:**
- Consumes: `Indexer.executeRaw(String method, String path, String body) -> String`, `IndexAccessVerifier.checkIndices(String)` and `checkPath(String, Context)`, `McpArguments.IntArgument`.
- Produces: `McpIndexSearch(Indexer)` with `JsonNode search(String project, Map<String, Object> body, Context context) throws IOException` and `static String requireSingleProject(JsonNode arguments)`; `McpListTagsTool(McpIndexSearch)`; `McpListTagsTool.TagCount(String tag, long count)`.

- [ ] **Step 1: Extract `McpIndexSearch`**

This step is a pure move: the existing `McpSearchDocumentsTool` tests are the regression net, so no new test is written first. Create `datashare-app/src/main/java/org/icij/datashare/web/McpIndexSearch.java`:

```java
package org.icij.datashare.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.errors.UnauthorizedException;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.utils.IndexAccessVerifier;
import java.io.IOException;
import java.util.Map;

/**
 * The Elasticsearch access the MCP tools share: one named project, readable by the caller, and an answer no shard
 * failed to produce.
 */
class McpIndexSearch {
    private static final ObjectMapper MAPPER = JsonObjectMapper.getMapper();
    private final Indexer indexer;

    @Inject
    McpIndexSearch(Indexer indexer) {
        this.indexer = indexer;
    }

    // The path is sent to Elasticsearch as is, not through IndexResource: its URL builder appends the incoming
    // request's query string, and the MCP request's query string must never reach Elasticsearch.
    JsonNode search(String project, Map<String, Object> body, Context context) throws IOException {
        String path = project + "/_search";
        requireReadable(project, path, context);
        return requireAllShards(MAPPER.readTree(indexer.executeRaw("POST", path, MAPPER.writeValueAsString(body))));
    }

    static String requireSingleProject(JsonNode arguments) {
        String project = McpArguments.requireText(arguments, "project");
        if (!isSingleIndexName(project)) {
            throw McpToolException.InvalidArgument.invalid("project");
        }
        return project;
    }

    // checkIndices signals a malformed index list with IllegalArgumentException; here it only means "not one index".
    private static boolean isSingleIndexName(String project) {
        try {
            return !IndexAccessVerifier.checkIndices(project).contains(",");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void requireReadable(String project, String path, Context context) {
        try {
            IndexAccessVerifier.checkPath(path, context);
        } catch (UnauthorizedException e) {
            throw new McpToolException.Forbidden(project, e);
        }
    }

    private static JsonNode requireAllShards(JsonNode response) {
        int failedShards = response.at("/_shards/failed").asInt();
        if (failedShards > 0) {
            throw new McpToolException("search failed on " + failedShards + " shard(s)");
        }
        return response;
    }
}
```

- [ ] **Step 2: Rewrite `McpSearchDocumentsTool` on top of it**

Replace the whole file with:

```java
package org.icij.datashare.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.constants.HttpStatus;
import org.elasticsearch.client.ResponseException;
import java.io.IOException;
import java.util.List;
import java.util.Map;

public class McpSearchDocumentsTool implements McpTool {
    private static final McpArguments.IntArgument SIZE = new McpArguments.IntArgument("size", 10, 1, 50);
    private final McpIndexSearch index;

    @Inject
    public McpSearchDocumentsTool(McpIndexSearch index) {
        this.index = index;
    }

    @Override
    public String name() {
        return "search_documents";
    }

    @Override
    public String description() {
        return "Full-text search in the documents of one project.";
    }

    @Override
    public Object call(JsonNode arguments, Context context) throws IOException {
        String project = McpIndexSearch.requireSingleProject(arguments);
        Map<String, Object> body = searchBody(McpArguments.requireText(arguments, "query"), SIZE.readFrom(arguments));
        return McpSearchResult.from(search(project, body, context));
    }

    // Elasticsearch answers a malformed query_string with a 400 whose message carries the cluster address, so a
    // rejected query is reported as bad input instead. Anything else is a real failure and keeps its own path.
    private JsonNode search(String project, Map<String, Object> body, Context context) throws IOException {
        try {
            return index.search(project, body, context);
        } catch (ResponseException e) {
            if (e.getResponse().getStatusLine().getStatusCode() != HttpStatus.BAD_REQUEST) {
                throw e;
            }
            throw McpToolException.InvalidArgument.invalid("query");
        }
    }

    private static Map<String, Object> searchBody(String query, int size) {
        Map<String, Object> highlightContent =
                Map.of("max_analyzed_offset", 999999, "fragment_size", 280, "number_of_fragments", 2);
        Map<String, Object> documentsMatchingQuery =
                Map.of("must", Map.of("query_string", Map.of("query", query)), "filter",
                       Map.of("term", Map.of("type", "Document")));
        return Map.of("size", size, "_source", List.of("path", "contentType"), "query",
                      Map.of("bool", documentsMatchingQuery), "highlight",
                      Map.of("fields", Map.of("content", highlightContent)));
    }
}
```

- [ ] **Step 3: Point the test at the new constructor**

In `McpToolsTest.serveAs`, replace `new McpSearchDocumentsTool(indexer)` with `new McpSearchDocumentsTool(new McpIndexSearch(indexer))`.

- [ ] **Step 4: Run the existing search tests to prove the move changed nothing**

Run: `mvn -pl datashare-app test -Dtest=McpToolsTest`
Expected: PASS, all of `test_search_*` included.

- [ ] **Step 5: Commit the refactor on its own**

```bash
git add datashare-app/src/main/java/org/icij/datashare/web/McpIndexSearch.java \
        datashare-app/src/main/java/org/icij/datashare/web/McpSearchDocumentsTool.java \
        datashare-app/src/test/java/org/icij/datashare/web/McpToolsTest.java
git commit -m "refactor(mcp): share the raw index search between tools"
```

- [ ] **Step 6: Write the failing `list_tags` tests**

In `McpToolsTest.serveAs`, add the tool to the set:

```java
                                                 new McpListTagsTool(new McpIndexSearch(indexer)),
```

Append the tests:

```java
    private static final String ES_TAGS_RESPONSE = """
            {"hits":{"total":{"value":0},"hits":[]},
             "aggregations":{"tags":{"buckets":[{"key":"invoice","doc_count":12},{"key":"contract","doc_count":3}]}}}""";

    @Test
    public void test_list_tags_returns_the_buckets_in_order() throws Exception {
        serveAs("local", "foo");
        when(indexer.executeRaw(eq("POST"), eq("foo/_search"), any())).thenReturn(ES_TAGS_RESPONSE);

        JsonNode tags = payload(call("local", "list_tags", "{\"project\":\"foo\"}"));

        assertThat(tags.size()).isEqualTo(2);
        assertThat(tags.at("/0/tag").asText()).isEqualTo("invoice");
        assertThat(tags.at("/0/count").asLong()).isEqualTo(12);
        assertThat(tags.at("/1/tag").asText()).isEqualTo("contract");
    }

    @Test
    public void test_list_tags_sends_a_terms_aggregation_and_no_hits() throws Exception {
        serveAs("local", "foo");
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        when(indexer.executeRaw(eq("POST"), eq("foo/_search"), body.capture())).thenReturn(ES_TAGS_RESPONSE);

        call("local", "list_tags", "{\"project\":\"foo\",\"size\":5}");

        JsonNode sent = MAPPER.readTree(body.getValue());
        assertThat(sent.get("size").asInt()).isEqualTo(0);
        assertThat(sent.at("/aggs/tags/terms/field").asText()).isEqualTo("tags");
        assertThat(sent.at("/aggs/tags/terms/size").asInt()).isEqualTo(5);
    }

    @Test
    public void test_list_tags_on_a_project_without_tags_is_empty() throws Exception {
        serveAs("local", "foo");
        when(indexer.executeRaw(eq("POST"), eq("foo/_search"), any()))
                .thenReturn("{\"hits\":{\"total\":{\"value\":0},\"hits\":[]},"
                            + "\"aggregations\":{\"tags\":{\"buckets\":[]}}}");

        JsonNode tags = payload(call("local", "list_tags", "{\"project\":\"foo\"}"));

        assertThat(tags.isArray()).isTrue();
        assertThat(tags.size()).isEqualTo(0);
    }

    @Test
    public void test_list_tags_on_ungranted_project_is_forbidden() throws Exception {
        serveAs("local", "foo");

        JsonNode response = call("local", "list_tags", "{\"project\":\"bar\"}");

        assertThat(response.at("/result/content/0/text").asText()).isEqualTo("forbidden: bar");
        org.mockito.Mockito.verifyNoInteractions(indexer);
    }

    @Test
    public void test_list_tags_size_above_500_is_invalid() throws Exception {
        serveAs("local", "foo");

        assertThat(call("local", "list_tags", "{\"project\":\"foo\",\"size\":501}").at("/error/code").asInt())
                .isEqualTo(-32602);
        org.mockito.Mockito.verifyNoInteractions(indexer);
    }

    @Test
    public void test_list_tags_rejects_project_with_a_path() throws Exception {
        serveAs("local", "foo");

        assertThat(call("local", "list_tags", "{\"project\":\"foo,bar\"}").at("/error/code").asInt())
                .isEqualTo(-32602);
        org.mockito.Mockito.verifyNoInteractions(indexer);
    }
```

- [ ] **Step 7: Run them to make sure they fail**

Run: `mvn -pl datashare-app test -Dtest=McpToolsTest`
Expected: compilation failure, "cannot find symbol: class McpListTagsTool".

- [ ] **Step 8: Write `McpListTagsTool`**

Create `datashare-app/src/main/java/org/icij/datashare/web/McpListTagsTool.java`:

```java
package org.icij.datashare.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.Inject;
import net.codestory.http.Context;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class McpListTagsTool implements McpTool {
    private static final McpArguments.IntArgument SIZE = new McpArguments.IntArgument("size", 50, 1, 500);
    private final McpIndexSearch index;

    @Inject
    public McpListTagsTool(McpIndexSearch index) {
        this.index = index;
    }

    @Override
    public String name() {
        return "list_tags";
    }

    @Override
    public String description() {
        return "Lists the tags in use in one project, most used first. The index lowercases tags, so a tag saved as "
               + "\"Invoice\" is listed as \"invoice\". Use it to reuse the project's vocabulary instead of coining "
               + "a new spelling of an existing tag.";
    }

    @Override
    public Object call(JsonNode arguments, Context context) throws IOException {
        String project = McpIndexSearch.requireSingleProject(arguments);
        JsonNode response = index.search(project, aggregationBody(SIZE.readFrom(arguments)), context);
        return TagCount.from(response.at("/aggregations/tags/buckets"));
    }

    private static Map<String, Object> aggregationBody(int size) {
        return Map.of("size", 0, "aggs", Map.of("tags", Map.of("terms", Map.of("field", "tags", "size", size))));
    }

    record TagCount(String tag, long count) {
        // A missing aggregations node iterates zero times, which is the right answer for a project with no tags.
        static List<TagCount> from(JsonNode buckets) {
            List<TagCount> tags = new ArrayList<>();
            buckets.forEach(bucket -> tags.add(
                    new TagCount(bucket.path("key").asText(), bucket.path("doc_count").asLong())));
            return tags;
        }
    }
}
```

- [ ] **Step 9: Write the schema**

Create `datashare-app/src/main/resources/mcp/schemas/list_tags.json`:

```json
{
  "type": "object",
  "properties": {
    "project": {"type": "string", "description": "project id"},
    "size": {
      "type": "integer",
      "description": "how many distinct tags to return, most used first",
      "minimum": 1,
      "maximum": 500,
      "default": 50
    }
  },
  "required": ["project"]
}
```

- [ ] **Step 10: Register the tool**

In `CommonMode.java`, add the import `org.icij.datashare.web.McpListTagsTool` and the binding:

```java
        tools.addBinding().to(McpListTagsTool.class);
```

- [ ] **Step 11: Run the whole MCP suite**

Run: `mvn -pl datashare-app test -Dtest='Mcp*Test'`
Expected: PASS. `McpLocalModeTest` again proves the new binding and the new schema file resolve at startup.

- [ ] **Step 12: Commit**

```bash
git add datashare-app/src/main/java/org/icij/datashare/web/McpListTagsTool.java \
        datashare-app/src/main/resources/mcp/schemas/list_tags.json \
        datashare-app/src/main/java/org/icij/datashare/mode/CommonMode.java \
        datashare-app/src/test/java/org/icij/datashare/web/McpToolsTest.java
git commit -m "feat(mcp): list the tags in use in a project"
```

---

## Acceptance check

Run once at the end of Task 3, against the issue's acceptance list:

- [ ] An MCP client can tag and untag a list of documents in a project it has access to: `test_tag_documents_writes_to_the_repository_and_the_index`, `test_untag_documents_removes_the_tags`.
- [ ] Tags written over MCP are attributed to the authenticated user: `test_tag_documents_attributes_the_tags_to_the_caller`.
- [ ] A project the user cannot access returns a tool error, not a stack trace: `test_tag_documents_on_ungranted_project_is_forbidden`, `test_untag_documents_on_ungranted_project_is_forbidden`, `test_list_tags_on_ungranted_project_is_forbidden`.
- [ ] An empty or oversized `doc_ids` returns a `-32602` invalid-argument error: `test_tag_documents_above_100_ids_is_invalid`, `test_tag_documents_rejects_bad_lists`.
- [ ] `get_document` shows a document's current tags: `test_get_document_lists_its_tags`.
- [ ] `list_tags` returns the tags in use in a project: `test_list_tags_returns_the_buckets_in_order`.
- [ ] Full build: `mvn -pl datashare-app test -Dtest='Mcp*Test'`.

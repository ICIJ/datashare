# ENQUEUEIDX: carry routing id in queue entries

**Date:** 2026-07-07
**Status:** Approved

## Problem

`EnqueueFromIndexTask` (stage `ENQUEUEIDX`) enqueues bare document ids into a
`DocumentQueue<String>`. Downstream consumers (`ExtractNlpTask`, `CategorizeTask`,
`ArtifactTask`) fetch each document with `indexer.get(project, id)` — without a
routing id. Embedded (child) documents are routed in Elasticsearch by their root
document id, so these gets miss: the document is logged as "could not be retrieved"
and silently skipped. Embedded documents therefore never get NLP entities,
content-type categories, or artifacts through the queue-based pipeline.

The routing information is already available at enqueue time: both search paths in
`EnqueueFromIndexTask` include `rootDocument` in `_source`. It is simply dropped
when only `doc.getId()` is enqueued.

`Indexer` already exposes routing-aware overloads: `get(indexName, id, root)` and
`get(indexName, id, root, sourceExcludes)`. `CreateNlpBatchesFromIndex` already
solved the same problem for the batch path by carrying `rootDocument` in its
`BatchDocument` record.

## Decision

Encode the routing id into the queue entry string, separated by `|`:

- Root document → bare `<id>` (unchanged from today)
- Embedded document → `<id>|<rootId>`

Document ids are hex hashes (SHA-384 by default), so `|` cannot appear in an id.
Backward compatibility is structural: old in-flight entries are bare ids and parse
to "no routing", which is exactly today's behavior.

### Alternatives rejected

- **Structured JSON queue entries** (like `BatchDocument`): changes the queue
  element type, breaking in-flight queue compatibility and requiring codec work in
  the extract queue layer. Overkill for one extra field.
- **Consumer-side search fallback** (search by `_id` when `get` misses): extra ES
  round-trip per embedded doc, and conflates "missing routing" with genuine fetch
  errors since `indexer.get` returns null for both.

## Design

### `DocReference` value type

A record in `datashare-app`, package `org.icij.datashare.tasks`, next to
`PipelineTask`:

```java
public record DocReference(String id, String rootId) {
    public String toQueueEntry();          // id when rootId is null or equals id, else id + "|" + rootId
    public static DocReference parse(String entry);  // split on first '|'; 1 part -> (id, null), 2 parts -> (id, root)
    public static DocReference fromDocument(Document doc); // bare when doc.isRootDocument(), else (id, rootDocument)
}
```

- `parse` splits on the **first** `|` only.
- The poison entry (`STRING_POISON`, `"POISON"`) contains no `|` and passes
  through `parse` unchanged; consumers keep comparing the raw polled string
  against `STRING_POISON` before parsing, as today.
- `DocReference` owns the format entirely — no `split("\\|")` in task classes.

### Producer: `EnqueueFromIndexTask`

Replace `outputQueue.add(doc.getId())` with
`outputQueue.add(DocReference.fromDocument((Document) doc).toQueueEntry())`.

No search changes: the no-query path already uses `.withSource("rootDocument")`
and the query path excludes only `content`/`contentTranslated`.

### Consumers

Add a routing-aware helper to `PipelineTask`:

```java
protected Document getDocument(Indexer indexer, String projectName, DocReference ref)
protected Document getDocument(Indexer indexer, String projectName, DocReference ref, List<String> sourceExcludes)
```

dispatching to `indexer.get(project, id)` when `ref.rootId()` is null, and
`indexer.get(project, id, root)` (or the `sourceExcludes` variant) otherwise,
preserving the existing null-warning behavior.

Per task:

- **`ExtractNlpTask`**: `findNamedEntities` parses the polled entry and fetches
  with routing.
- **`CategorizeTask`**: parses the polled entry, fetches with routing, and
  forwards the **original encoded entry** (not the bare id) to its output queue so
  routing survives the categorize→nlp chain.
- **`ArtifactTask`**: parses the polled entry and fetches with routing via the
  `sourceExcludes` variant it already uses.

### Error handling

No new failure modes. A malformed entry (e.g., empty segment around `|`) results
in a failed get, handled by the existing "document could not be retrieved" warning
path, same as a stale id today.

## Testing

TDD, following existing test style:

- **`DocReference` unit tests**: `parse`/`toQueueEntry` round-trips; bare-id
  entries parse to null root (retro-compat); root-document references encode to
  bare id; split occurs on first `|` only.
- **`EnqueueFromIndexTaskTest`**: an embedded document is enqueued as
  `<id>|<rootId>`; a root document is enqueued as bare `<id>`.
- **Consumer tests** (`ExtractNlpTask`, `CategorizeTask`, `ArtifactTask`): an
  entry with routing triggers `indexer.get` with the root argument; a bare entry
  keeps today's behavior; `CategorizeTask` forwards the encoded entry to its
  output queue.

## Out of scope

- `CreateNlpBatchesFromIndex` / `BatchNlpTask` (already carry `rootDocument`).
- Path-based queues (`SCAN`/`INDEX` stages) — routing does not apply there.
- Changing the queue element type or extract-library serialization.

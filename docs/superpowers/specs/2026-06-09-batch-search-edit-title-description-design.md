# Edit batch search title & description via API

## Problem

The `PATCH /api/batch/search/:batchid` endpoint only lets a batch search's
owner change its `published` flag (visibility). Owners also need to edit the
batch search's **title** (`name`) and **description** through the same
endpoint.

## Goal

Allow the owner of a batch search to update any combination of `published`,
`name`, and `description` via the existing PATCH endpoint, using partial-update
semantics. Backend (API + repository + tests) only — no frontend, no DB
migration.

## Current state

- **Endpoint:** `BatchSearchResource.updateBatch(String batchId, Context, JsonData)`
  (`datashare-app/.../web/BatchSearchResource.java`). Reads `published` from the
  body and calls `batchSearchRepository.publish(user, batchId, published)`,
  returning `200` on success and `404` otherwise.
- **Repository:** `BatchSearchRepository.publish(User, String batchId, boolean)`
  (interface in `datashare-api`), implemented in
  `JooqBatchSearchRepository` as a single owner-scoped UPDATE:
  `UPDATE batch_search SET published = ? WHERE uuid = ? AND user_id = ?`,
  returning `execute() > 0`.
- **Model:** `BatchSearchRecord` has `name`, `description`, `published`.
- **DB:** `batch_search` table already has `name varchar(255)`,
  `description varchar(4096)`, `published integer`. No migration needed.
- **`JsonData`:** wraps a raw `Map<String,Object> data`. `get(key)` returns
  `null` when absent; `asBoolean(key)` casts and therefore throws on a missing
  or null key. Field *presence* must be checked with `data.containsKey(...)`.

## Design

### Update semantics

Partial update. Only the fields present in the request body are applied; any
omitted field keeps its stored value. Existing clients that send only
`published` continue to work unchanged.

### Repository layer — separate setters

Keep `publish(...)` exactly as-is. Add two sibling methods to
`BatchSearchRepository` and `JooqBatchSearchRepository`:

```java
boolean setName(User user, String batchId, String name);
boolean setDescription(User user, String batchId, String description);
```

Each performs a single owner-scoped jOOQ UPDATE mirroring `publish()`:

```java
DSL.using(dataSource, dialect).update(BATCH_SEARCH)
   .set(BATCH_SEARCH.NAME, name)            // or DESCRIPTION
   .where(BATCH_SEARCH.UUID.eq(batchId)
          .and(BATCH_SEARCH.USER_ID.eq(user.id)))
   .execute() > 0;
```

The `AND user_id = user.id` clause enforces ownership and provides the 404
signal: 0 rows affected means the batch is not owned by (or not visible to) the
requesting user. This relies on SQLite and Postgres both counting a matched row
as affected even when the new value equals the existing one — true for both
engines used here.

### Controller — `BatchSearchResource.updateBatch`

Rewrite the method:

1. Detect presence of each field via the underlying map
   (`data.data.containsKey("published" | "name" | "description")`).
2. If **none** of the three fields is present → `400 Bad Request` (nothing to
   update).
3. Validate present fields; return `400` on any failure:
   - `name`: must be non-empty / non-blank after trimming, and length ≤ 255.
   - `description`: length ≤ 4096. Empty string allowed.
   - (`published` needs no validation.)
4. For each present field, call the matching setter (`publish`, `setName`,
   `setDescription`).
5. If **every** applied setter returned `false` → `404 Not Found` (not owner /
   not found). Otherwise → `200 OK`.

This issues one SQL statement per present field rather than a single combined
UPDATE. It is intentionally **non-transactional**, consistent with the existing
code style: all setters share the identical owner-scoped `WHERE`, so a partial
failure is only possible under an infrastructure fault mid-request.

## Testing

### `BatchSearchResourceTest` (unit, mocked repository)

- Update `name` only → `200`; verifies `setName` called, `publish`/`setDescription` not.
- Update `description` only → `200`.
- Update `published` only → `200` (existing `test_update_batch_search` still passes).
- Update all three together → `200`.
- Empty/blank `name` → `400`.
- `name` longer than 255 chars → `400`.
- `description` longer than 4096 chars → `400`.
- Empty body (no updatable field) → `400`.
- Non-owner (all setters return `false`) → `404`.

### `JooqBatchSearchRepositoryTest` (integration, real DB)

- `test_set_name`: save a batch, `setName` succeeds, `get(...).name` reflects the new value.
- `test_set_description`: analogous for description.
- `test_set_name_unauthorized_user_does_nothing` / description equivalent:
  a non-owner call returns `false` and leaves the value unchanged.
  Mirrors existing `test_publish` / `test_publish_unauthorized_user_does_nothing`.

## Out of scope

- DB migration (columns already exist).
- Changes to `publish()` signature or behavior.
- Frontend / Vue client changes.
- Combining the updates into a single SQL statement or a DB transaction.

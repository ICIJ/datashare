# Batch search validation layering

## Problem

On the `feat/batch-search-edit-title-description` branch, all validation for editing a
batch search lives in the web layer (`BatchSearchResource.updateBatch` and its private
helpers). This conflates three different concerns and leaves the rules trapped in one
HTTP handler:

1. **JSON type-coercion** — is the JSON value a `String` / `Boolean`?
   (genuinely an HTTP-boundary concern)
2. **Domain invariants** — name not empty, name ≤ 255 chars, description ≤ 4096 chars
   (these are invariants of a batch search, not of an HTTP request)
3. **Orchestration** — validate every field before applying any, so an invalid field
   never leaves a partial update (a legitimate HTTP-boundary concern)

Three things feel wrong:

- **Wrong layer.** The domain invariants (#2) sit in the web resource even though this
  codebase already keeps batch-search invariants in the model — `BatchSearch` enforces
  `"queries cannot be empty"` in its constructor.
- **Not reusable.** The length/empty rules only protect the PATCH edit endpoint.
  Batch-search *creation* (`TaskResource.createBatchSearch` / `copySearch`) persists a
  name and description with no such checks.
- **Resource too long.** `updateBatch` plus six private helpers
  (`hasUpdatableField`, `applyEdits`, `validName`, `validDescription`, `validString`,
  `validBoolean`, `withinMaxLength`) and two constants bloat the resource class.

## Goal

Move the domain invariants to a single home in the model, enforce them at every
persistence write boundary so all callers are protected, and slim the web resources down
to pure HTTP-boundary concerns.

## Design (chosen approach)

Rules live on the `BatchSearch` model as the single source of truth, the repository
enforces them at every write boundary, and resources only translate a thrown
`IllegalArgumentException` into a 400.

### 1. Rules — single source of truth on `BatchSearch`

Add to `datashare-api/.../batch/BatchSearch.java`, alongside the existing
`"queries cannot be empty"` invariant:

```java
public static final int MAX_NAME_LENGTH = 255;
public static final int MAX_DESCRIPTION_LENGTH = 4096;

public static String validateName(String name) {
    if (name == null || name.trim().isEmpty())
        throw new IllegalArgumentException("Batch search name cannot be empty.");
    if (name.length() > MAX_NAME_LENGTH)
        throw new IllegalArgumentException("Batch search name exceeds " + MAX_NAME_LENGTH + " characters.");
    return name;
}

public static String validateDescription(String description) {
    String value = description == null ? "" : description;   // preserve current null -> "" behavior on edit
    if (value.length() > MAX_DESCRIPTION_LENGTH)
        throw new IllegalArgumentException("Batch search description exceeds " + MAX_DESCRIPTION_LENGTH + " characters.");
    return value;
}
```

These are **static methods, not constructor logic**. The `BatchSearch` constructor is
shared between building new objects and hydrating rows from the database (the
"retrieved from persistence" constructors). Validating in the constructor would
re-validate legacy DB rows on load and could fail to read a pre-existing over-long name.

### 2. Enforcement at every repository write boundary

In `datashare-db/.../db/JooqBatchSearchRepository.java`:

- `setName(user, batchId, name)` calls `BatchSearch.validateName(name)` before the SQL update.
- `setDescription(user, batchId, description)` calls `BatchSearch.validateDescription(description)` before the SQL update.
- `save(batchSearch)` calls `BatchSearch.validateName(...)` / `validateDescription(...)`
  before the insert. This covers both creation paths in `TaskResource`
  (`createBatchSearch` and `copySearch`).

Now the rules are enforced wherever a batch name/description is persisted, regardless of
which caller initiated it. This is the "reusable / right layer" payoff.

### 3. Resources only map the exception to 400

There is no global `IllegalArgumentException -> 400` mapper; in fluent-http an uncaught
exception becomes a 500. So each write endpoint must catch and translate.

- `BatchSearchResource.updateBatch` (edit) — already wraps `applyEdits` in a try/catch
  that maps `IllegalArgumentException` to 400. It keeps the HTTP-boundary concerns:
  JSON type-coercion (`validBoolean` / `validString`) and the validate-all-before-apply
  orchestration (it calls the model validators up-front so atomicity is preserved). It
  **drops**: `MAX_NAME_LENGTH`, `MAX_DESCRIPTION_LENGTH`, `validName`, `validDescription`,
  `withinMaxLength` — `validName` / `validDescription` collapse into calls to the model
  validators after type-coercion.

- `TaskResource.createBatchSearch` and `TaskResource.copySearch` (creation) — add a
  try/catch mapping `IllegalArgumentException -> 400`, mirroring `updateBatch`. The fresh
  create handler already returns 400 when `name == null`; this extends coverage to
  empty/whitespace and over-long name/description.

## Behavior change

Batch-search **creation** previously accepted over-long (and empty/whitespace)
names/descriptions silently. It will now return **400**. This is the intended
consequence of folding creation into the enforced contract.

The edit endpoint's external behavior is unchanged — the same inputs that returned 400
before still return 400.

## Out of scope

- No typed request DTO / bean-validation framework — that pattern is not used elsewhere
  in this codebase and would be churn beyond the stated goal.
- No change to the `null` description handling on creation: `save()` validates length
  only and leaves a `null` description as-is in the DB (pre-existing behavior). The
  `null -> ""` normalization continues to apply only on the edit path, where the
  validator's return value is used.

## Testing

- `BatchSearch` validator unit tests: empty name, whitespace-only name, name at and over
  255, description at and over 4096, `null` description.
- `JooqBatchSearchRepositoryTest`: `setName`, `setDescription`, and `save` reject invalid
  name/description.
- `BatchSearchResourceTest`: existing edit 400 cases stay green (endpoint behavior
  unchanged).
- `TaskResourceTest`: create and copy return 400 on empty/over-long name or description.

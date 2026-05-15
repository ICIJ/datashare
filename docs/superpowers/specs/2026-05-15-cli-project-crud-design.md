# CLI project CRUD: design

**Status:** approved (brainstorming)
**Date:** 2026-05-15
**Issue:** [ICIJ/datashare#2122](https://github.com/ICIJ/datashare/issues/2122)
**Branch:** `feat/cli-project-crud`
**Predecessor:** [2026-05-06-cli-user-crud-design.md](2026-05-06-cli-user-crud-design.md) (PR #2143)

## Scope

Add two picocli leaf commands to the existing `datashare` CLI:

- `datashare project create [<name>]`
- `datashare project delete [<name>]`

A new `ProjectAdminService` (in `datashare-app`) owns the full create / delete
cascade so the same code is reusable by REST in a follow-up PR.

Issue #2122 also proposes `project grant` and `project revoke`; those are out
of scope for this branch and tracked separately.

## Non-goals

- `datashare project grant` / `datashare project revoke`. Will reuse this
  spec's `Validators`, dispatcher plumbing, and exit-code conventions.
  `--creator` is included in this spec and auto-resolves from
  `defaultUserName` when omitted. Per-role grants and the full
  `project grant` / `project revoke` surface remain deferred to the next
  spec.
- Read-only commands (`project list`, `project get`).
- Refactoring `ProjectResource` to delegate to the new service. The service is
  built so that refactor is mechanical, but it is a separate PR.
- New REST endpoints.
- Liquibase migrations. The `project` and `casbin_rule` tables already exist;
  no schema change is needed.

## One new interface method

`Indexer.count(String indexName)` is added to the `datashare-api` interface
(thin wrapper over the existing `Searcher.totalHits()` machinery used by
`ElasticsearchSearcher`). This is purely additive: no existing call site
changes. Called only from `ProjectAdminServiceImpl.stats`.

## Inherits from the user-CRUD spec

- Typed sibling-key properties on the picocli to dispatcher boundary
  (no JSON blob). Matches the refactor in commit `24c87ec11`.
- Exit codes `0/1/2/3/4/5` (success / runtime / misuse / not-found / conflict
  / validation).
- `--no-input`, `--json`, `--if-not-exists` / `--if-exists` semantics.
- `Validators` helper file already exists; we add `projectName`, `allowFromMask`,
  and `uri` to it.

## Command surface

### `datashare project create`

| Flag | Required | Notes |
|------|----------|-------|
| `<name>` (positional) / `--name` | yes | Validated against `^[a-z0-9][a-z0-9-]{1,63}$` (same regex `Validators.groups` already enforces) |
| `--label` | no | Defaults to `<name>` (matches `Project(name)` constructor behavior) |
| `--description` | no | Free-form string |
| `--source-path` | no | Defaults to `/vault/<name>`. CLI does not verify the path against `DataDirVerifier`; that check is web-mode-only and CLI runs as a trusted operator |
| `--allow-from-mask` | no | Defaults to `*.*.*.*`. Validated against `^[\d*]{1,3}(\.[\d*]{1,3}){3}$` |
| `--source-url` | no | Validated as RFC 3986 URI if supplied |
| `--maintainer-name` | no | Free-form string |
| `--publisher-name` | no | Free-form string |
| `--logo-url` | no | Validated as RFC 3986 URI if supplied |
| `--creator` | no | User login to auto-grant PROJECT_ADMIN on the new project. Validated against `Validators.login`. If omitted, falls back to `defaultUserName` (the bash launcher injects this with the OS user; service-side grant is a no-op if the user is missing from `user_inventory`). |
| `--no-index` | no | Skip ES `createIndex`; only persist the project row |
| `--if-not-exists` | no | Exit 0 instead of 4 when the project already exists |
| `--no-input` | no | Disable interactive prompts; missing required field exits 2 |
| `--json` | no | Emit a JSON result on stdout |

Only `<name>` is ever prompted (it is the only required field).

### `datashare project delete`

| Flag | Required | Notes |
|------|----------|-------|
| `<name>` (positional) / `--name` | yes | |
| `--yes` / `-y` | no | Skip the typed-name confirmation |
| `--keep-index` | no | Skip the ES `indexer.deleteAll(name)` step; everything else still happens |
| `--if-exists` | no | Exit 0 instead of 3 when the project is missing |
| `--no-input` | no | Disable prompts (implies `--yes`); missing name exits 2 |
| `--json` | no | Emit a JSON result on stdout |

### `datashare project`

With no subcommand: print usage and exit 2 (matches what `feat/cli-user-crud`
landed on for `datashare user` in commit `e12320d8d`).

### Flag-name note on (a)symmetry

`--no-index` on create and `--keep-index` on delete read in opposite directions
but each is natural for its verb ("don't create an index", "keep the existing
index around"). Using one name for both would force an awkward phrasing on one
side. Calling this out so it isn't a review surprise.

## Architecture

Three layers, matching the user-CRUD shape:

```
┌────────────────────────────────────────────────┐
│  picocli leaf commands  (datashare-cli)        │
│   ProjectCommand                               │
│   ProjectCreateCommand : DatashareSubcommand   │
│   ProjectDeleteCommand : DatashareSubcommand   │
│  - Parse flags + interactive prompts + validate│
│  - Emit Properties via getSubcommandProperties │
│  - No DB / index / casbin code                 │
└────────────────┬───────────────────────────────┘
                 │  Properties: typed sibling keys
                 │  ("projectCreate" + projectCreate.name,
                 │   projectCreate.label, ...). Mirrors
                 │   the userCreate.* pattern from 24c87ec11.
                 ▼
┌────────────────────────────────────────────────┐
│  CliApp dispatcher  (datashare-app)            │
│  - Two new `if` blocks in runTaskWorker        │
│  - Resolves ProjectAdminService from CommonMode│
│  - For `delete`: queries service for stats     │
│    BEFORE prompting, then prompts, then calls  │
│    service.delete(...)                         │
│  - Maps exceptions to exit codes               │
│  - Formats stdout (text or JSON)               │
└────────────────┬───────────────────────────────┘
                 │
                 ▼
┌────────────────────────────────────────────────┐
│  ProjectAdminService  (datashare-app)          │
│  interface ProjectAdminService {               │
│    ProjectCreated create(ProjectCreateRequest) │
│      throws ProjectExistsException,            │
│              ValidationException;              │
│    ProjectStats stats(String name)             │
│      throws ProjectNotFoundException;          │
│    boolean delete(String name,                 │
│                   ProjectDeleteOptions)        │
│      throws ProjectNotFoundException;          │
│  }                                             │
│  Impl: ProjectAdminServiceImpl(                │
│    Repository, Indexer, Authorizer,            │
│    DocumentCollectionFactory<Path>,            │
│    PropertiesProvider)                         │
└────────────────┬───────────────────────────────┘
                 │
                 ▼
┌──────────────────────────────────────────────────────┐
│  Existing dependencies                               │
│  - Repository.save(Project) / getProject / deleteAll │
│  - Indexer.createIndex / .deleteAll                  │
│  - Authorizer.getGroupPermissions(domain, projectId) │
│  - DocumentCollectionFactory.createQueue / .createMap│
│                                                      │
│  One new method on Indexer:                          │
│  - long count(String indexName)                      │
│    (used by stats(); thin wrapper over the existing  │
│     Searcher.totalHits() machinery)                  │
└──────────────────────────────────────────────────────┘
```

### Why a service (and not inline in `CliApp`)

- The cascade for `project delete` touches five subsystems (DB, ES, queues,
  report map, artifact dir). Inline `if` blocks in `CliApp.runTaskWorker`
  would balloon that method.
- `ProjectAdminService.delete` and `ProjectResource.projectDelete` then share
  exactly one implementation. The current REST code duplicates this logic
  inline; a follow-up PR can have REST call the service.
- Unit tests can drive create/delete against mocked `Repository`, `Indexer`,
  `Authorizer` without booting Guice.

### Why no new `Repository` method

Unlike user CRUD (which needed `Repository.deleteUser`), project deletion's
cascade already exists end-to-end:

- `Repository.deleteAll(projectId)` (DB rows including the `project` row
  itself, wrapped in one transaction; see
  `datashare-db/.../JooqRepository.java:404`).
- `Indexer.deleteAll(projectId)` (ES index).
- Queue / report-map / artifact-dir cleanup, currently inline in
  `ProjectResource`.

So this PR adds no new DB method, no new SQL, no liquibase change. Only Java
code under `datashare-app` and `datashare-cli`.

### The `stats(name)` method: why a separate call

The dispatcher needs index size and member count to render the confirmation
prompt:

```
Project 'foo' has 12,431 indexed documents and 3 members.
To confirm deletion, type the project name:
```

Folding stats into `delete` would force the service to do the prompt itself or
return stats from a not-yet-executed deletion. Keeping `stats` as its own
method means: dispatcher calls `stats`, renders prompt, reads input, on
confirm calls `delete`. The two methods are independent; tests exercise them
separately.

`stats` is also called when `--yes` / `--no-input` is set (skipping the
prompt) so an audit log of the CLI run records the scale of the action
("deleting project 'foo' (12,431 docs, 3 members)") before the destructive
call.

## Data model

### `ProjectCreateRequest`

```java
public record ProjectCreateRequest(
    String name,
    String label,            // nullable; service defaults to name
    String description,      // nullable
    Path   sourcePath,       // nullable; service defaults to /vault/<name>
    String allowFromMask,    // nullable; service defaults to "*.*.*.*"
    String sourceUrl,        // nullable
    String maintainerName,   // nullable
    String publisherName,    // nullable
    String logoUrl,          // nullable
    boolean createIndex      // true unless --no-index
) {}
```

Defaults are applied inside the service, not the command. The command emits
sibling-key properties; absent flags emit absent keys. The service is the
single source of truth for "what does a project look like when only `--name`
was given." This also keeps `--json` debug output legible (you see what the
operator supplied, not what the defaults filled in).

### `ProjectCreated`

```java
public record ProjectCreated(
    String name,
    String label,
    String description,
    Path   sourcePath,
    String allowFromMask,
    String sourceUrl,
    String maintainerName,
    String publisherName,
    String logoUrl,
    boolean indexCreated,    // true if ES index was created this call
    boolean noop             // true if --if-not-exists matched an existing project
) {}
```

`indexCreated=false` happens in two cases: `--no-index` was set, or
`--if-not-exists` matched and we short-circuited. `noop=true` always implies
`indexCreated=false`.

### `ProjectStats`

```java
public record ProjectStats(
    String name,
    long   indexedDocuments, // Indexer.count(name); -1 if --keep-index or index missing
    int    memberCount       // distinct user ids in casbin rules scoped to this project
) {}
```

`indexedDocuments = -1` is the sentinel for "we didn't ask ES." The prompt
renders this as `(index check skipped)` so operators see it explicitly rather
than seeing `0`.

### `ProjectDeleteOptions`

```java
public record ProjectDeleteOptions(boolean keepIndex) {}
```

Single-flag record today; structured so future deletion knobs (e.g.
`--keep-artifacts`) don't change the method signature.

### `ProjectDeleted`

```java
public record ProjectDeleted(
    String  name,
    boolean dbDeleted,
    boolean indexDeleted,
    boolean queuesDeleted,
    boolean reportMapDeleted,
    boolean artifactsDeleted,
    boolean noop             // true if --if-exists matched a missing project
) {}
```

Each cascade step reports its own outcome. This powers the verbose text
output ("deleted project 'foo': db OK, index OK, queues OK, report-map OK,
artifacts OK"). In `--json` mode the same booleans land in the payload, so
scripts can audit which subsystems actually changed.

### Exceptions

New classes under `org.icij.datashare.project`:

- `ProjectExistsException extends RuntimeException`. Thrown by `create` when
  the project row already exists and `--if-not-exists` was not set.
- `ProjectNotFoundException extends RuntimeException`. Thrown by `stats` and
  `delete` when the project row is missing and `--if-exists` was not set.

`ValidationException` already exists from the user CRUD spec; we reuse it.

### Persistence shape

A created project maps to one `project` row (existing schema;
`Repository.save(Project)` is an upsert on `PROJECT.ID`). The ES index is
created via `indexer.createIndex(name)` (matching
`ProjectResource.createIndexOnce`). When `--creator` resolves to a non-null
user, one `casbin_rule` row is written via
`Authorizer.addProjectAdmin(user, Domain("datashare"), project)` and the user's
`user_inventory.details["groups_by_applications"]["datashare"]` list gains the
project name. Full `project grant` / `project revoke` (per-role, non-admin)
remains a separate spec.

## Cascade semantics

### `create`: order of operations

Inside `ProjectAdminServiceImpl.create(ProjectCreateRequest)`:

1. Validate `name` against `^[a-z0-9][a-z0-9-]{1,63}$`. Validate
   `allowFromMask`, `sourceUrl`, `logoUrl` if non-null. Throw
   `ValidationException` on failure.
2. `repository.getProject(name)`. If non-null:
   - If `--if-not-exists` is set: return a
     `ProjectCreated{noop=true, indexCreated=false}`. No further side-effects.
   - Else: throw `ProjectExistsException`.
3. Apply defaults (label to name, sourcePath to `/vault/<name>`, allowFromMask
   to `*.*.*.*`). `creationDate` and `updateDate` remain null, matching the
   current REST POST behavior (`ProjectResource.projectCreate` does not stamp
   them either, and `JooqRepository.save(Project)` accepts null dates). If we
   later want server-side timestamps, that is a separate change touching both
   call sites.
4. Build `Project` and call `repository.save(project)`. If `false`, wrap as a
   runtime error (a `false` from `save` means DB failure; existing REST code
   does the same).
5. If `createIndex` is true: call `indexer.createIndex(name)`. If it throws,
   roll back the DB row (`repository.deleteAll(name)`) and rethrow. Rationale:
   leaving a DB row pointing at a non-existent index is the worst possible
   end-state. It makes the project visible everywhere but unusable.
6. Return `ProjectCreated{noop=false, indexCreated=createIndex}`.

There is no transaction across DB and ES (no XA, no two-phase commit
available). Step 5's compensating delete is the closest we can get; document
it in code with one short comment because it's the kind of failure mode a
future reader will doubt.

### `delete`: order of operations

Inside `ProjectAdminServiceImpl.delete(name, options)`:

1. `repository.getProject(name)`. If null:
   - If `--if-exists` is set: return `ProjectDeleted{noop=true,
     everything-else=false}`.
   - Else: throw `ProjectNotFoundException`.

   Note: the CLI dispatcher short-circuits this case earlier (its
   `stats(name)` call already throws `ProjectNotFoundException` for a missing
   project, and the dispatcher handles `--if-exists` there). The branch above
   is for direct service callers (e.g. the future REST refactor) that have
   not already consulted `stats`.
2. ES index (`options.keepIndex()` is false): `indexer.deleteAll(name)`.
   Record `indexDeleted = true` on success, `false` on the "index didn't
   exist" no-op path. Other failures bubble up.
3. DB rows: `repository.deleteAll(name)`. Already cascades through
   `document_tag`, `document_user_star`, `document_user_recommendation`,
   `user_history_project`, `user_history`, and the `project` row itself,
   inside one transaction.
4. Document queues: resolve the project's `queue` and `dirty` queues via
   `DocumentCollectionFactory.createQueue(...).delete()` (matches
   `ProjectResource.deleteQueues`).
5. Report map: `DocumentCollectionFactory.createMap(...).delete()` (matches
   `ProjectResource.deleteReportMap`).
6. Artifact dir: if `propertiesProvider.get(ARTIFACT_DIR_OPT)` is set, delete
   `<artifactDir>/<name>` recursively (matches
   `ProjectResource.projectDelete`).
7. Casbin rules are not touched. When the project is gone, leftover grouping
   policies referencing its name are inert (the project no longer exists, so
   the rules can never grant access). Cleaning them is a project-grant
   concern and belongs in the grant/revoke spec, where we'll have a clear
   semantic for "removing all members from project X."

Steps 2 through 6 run in source order. Each step records its boolean outcome
on `ProjectDeleted`. A failure in any non-final step is logged and propagated;
we do not swallow partial failures. The first IO/ES exception aborts the
cascade; subsequent steps don't run. This mirrors `ProjectResource.projectDelete`
which also propagates exceptions (the artifact-dir delete is the only step
wrapped in try/catch; we do the same).

### `--keep-index`

Sets `ProjectDeleteOptions.keepIndex = true`. Step 2 is skipped;
`indexDeleted=false` on the result. Stats step also skips the
`indexer.count(name)` call and returns `indexedDocuments = -1` so the prompt
says "(index check skipped)".

### `--no-index` on create

Sets `ProjectCreateRequest.createIndex = false`. Step 5 is skipped.
`indexCreated=false` on the result. The compensating-delete in step 5 doesn't
apply.

### Idempotency invariants

| Flag | Behavior on missing/existing | Exit code |
|---|---|---|
| `project create` no flag | exists -> conflict | 4 |
| `project create --if-not-exists` | exists -> no-op, `noop=true` | 0 |
| `project delete` no flag | missing -> not-found | 3 |
| `project delete --if-exists` | missing -> no-op, `noop=true` | 0 |

A no-op never produces side-effects: no ES call, no queue / report / artifact
touch.

## Interactive prompts and validation

### TTY detection

```
prompting-allowed = (System.console() != null) && !flagNoInput
```

Same predicate the user-CRUD `Prompter` already uses.

### Prompt order: `project create`

Only one prompt is ever needed (only `<name>` is required):

1. `name` (if no positional / `--name`)

All other flags omit silently and the service applies defaults.

### Prompt order: `project delete`

1. `name` (if no positional / `--name`)
2. Stats lookup: `service.stats(name)`. Runs before the confirmation so the
   prompt can render counts. If this throws `ProjectNotFoundException`:
   - With `--if-exists`: exit 0, "project 'foo' does not exist (no-op)".
   - Without: exit 3.
3. Confirmation:

   ```
   Project 'foo' has 12,431 indexed documents and 3 members.
   This will permanently delete the project, its index, document queues,
   report map, and artifact directory. This cannot be undone.
   To confirm, type the project name:
   ```

   The user must type the exact project name (case-sensitive, trimmed).
   Anything else counts as one failed attempt. Skipped entirely if `--yes` or
   `--no-input` is set, or if `--if-exists` matched a missing project before
   we reached this step.

   With `--keep-index`, the first line replaces "indexed documents" with
   "(index check skipped)" so the operator knows the index is staying
   behind.

### Retries

Each prompted field (`name`, confirmation) gets up to 3 attempts. After the
third invalid input, exit 5 with a message naming the field. Per-field
counter, not global. Matches the user-CRUD `Prompter` behavior.

### Validation source of truth

Extend the existing `Validators` helper in `datashare-cli`:

```java
class Validators {
    // already present from user CRUD spec:
    static void login(String) throws ValidationException;
    static void email(String) throws ValidationException;
    static void provider(String) throws ValidationException;
    static List<String> groups(String csv) throws ValidationException;

    // new in this spec:
    static void projectName(String) throws ValidationException;       // ^[a-z0-9][a-z0-9-]{1,63}$
    static void allowFromMask(String) throws ValidationException;     // ^[\d*]{1,3}(\.[\d*]{1,3}){3}$
    static void uri(String) throws ValidationException;               // java.net.URI parse + scheme non-null
}
```

The same helper runs:

- once on flag values during picocli parsing. Invalid flag exits 5
  immediately, no prompt.
- once inside the prompt loop. Invalid input re-prompts up to 3 times.

`projectName` is identical to what `Validators.groups` already enforces
per-entry, so we factor that regex into a shared constant.

### Confirmation match comparison

```java
boolean confirmed = typed.trim().equals(projectName);
```

Case-sensitive, leading/trailing whitespace stripped. Names are constrained to
`[a-z0-9-]` so case folding would be a no-op anyway; we keep the strict
compare so future relaxations of the regex don't silently weaken the guard.

## Output and exit codes

### Text format (default)

```
$ datashare project create my-project --label "My Project" --description "leak archive"
created project 'my-project' (label='My Project', source-path=/vault/my-project, allow-from-mask=*.*.*.*, index=created)

$ datashare project create my-project --if-not-exists
project 'my-project' already exists (no-op)

$ datashare project create my-project --no-index
created project 'my-project' (label='my-project', source-path=/vault/my-project, allow-from-mask=*.*.*.*, index=skipped)

$ datashare project delete my-project --yes
deleted project 'my-project' (db OK, index OK, queues OK, report-map OK, artifacts OK)

$ datashare project delete my-project --yes --keep-index
deleted project 'my-project' (db OK, index skipped, queues OK, report-map OK, artifacts OK)

$ datashare project delete absent --if-exists
project 'absent' does not exist (no-op)
```

`OK` and `FAIL` are plain ASCII tokens. We avoid non-ASCII markers so any
downstream script can parse the output without worrying about codepage
quirks.

### JSON format (`--json`)

```json
{"created":true,"noop":false,"name":"my-project","label":"My Project","description":"leak archive","sourcePath":"/vault/my-project","allowFromMask":"*.*.*.*","sourceUrl":null,"maintainerName":null,"publisherName":null,"logoUrl":null,"indexCreated":true}
{"deleted":true,"noop":false,"name":"my-project","dbDeleted":true,"indexDeleted":true,"queuesDeleted":true,"reportMapDeleted":true,"artifactsDeleted":true}
{"created":false,"noop":true,"name":"my-project","label":"My Project","description":"leak archive","sourcePath":"/vault/my-project","allowFromMask":"*.*.*.*","sourceUrl":null,"maintainerName":null,"publisherName":null,"logoUrl":null,"indexCreated":false}
{"deleted":false,"noop":true,"name":"absent","dbDeleted":false,"indexDeleted":false,"queuesDeleted":false,"reportMapDeleted":false,"artifactsDeleted":false}
```

`created` / `deleted` are always boolean; combined with `noop` they describe
the outcome unambiguously. Scripts that only care about "did anything change"
can `jq` `.noop`. The noop branches populate the same payload shape: for
`create --if-not-exists` we copy the existing project's fields onto the
result (read from the `getProject(name)` lookup); for `delete --if-exists` we
emit the cascade booleans as `false`. Camel-case keys to match the existing
user-CRUD JSON.

### Errors

- Text: one line on stderr, `error: <message>`.
- JSON: `{"error":"<code>","message":"<msg>","name":"<projectName>"}` where
  `<code>` is one of `not_found`, `conflict`, `validation`, `misuse`,
  `runtime`.

### Exit codes

| Code | Meaning | Triggered by |
|------|---------|--------------|
| 0 | success or idempotent no-op | normal path; `--if-not-exists` hits existing; `--if-exists` hits missing |
| 1 | runtime error | DB / ES / IO failure, anything not classified below; includes compensating-delete failures |
| 2 | misuse | `--no-input` + missing required field; picocli rejects unknown flag |
| 3 | not found | `project delete` on missing project without `--if-exists` |
| 4 | conflict | `project create` on existing project without `--if-not-exists` |
| 5 | validation | regex / URI / mask rejected after retries; flag value rejected immediately |

### Exception to exit-code mapping

```
ProjectNotFoundException -> 3
ProjectExistsException   -> 4
ValidationException      -> 5
(other Exception)        -> 1
```

### Compensating-delete failure on create

If `indexer.createIndex(name)` throws and the compensating
`repository.deleteAll(name)` also fails, we are in an inconsistent state: the
DB row points at a missing index. We log both stack traces at `ERROR` level
and exit 1. The text output makes the inconsistency explicit:

```
error: project 'my-project' was created in the DB but index creation failed AND
       rollback also failed. The project row exists with no matching index.
       Run 'datashare project delete my-project --keep-index' to remove the row.
```

This is the worst-case path we can reach. Making it loud is the whole point.

### Logging vs stdout

- Primary results: `System.out` (text or JSON).
- Diagnostics (slf4j): default INFO level on stderr. Each cascade step in
  `delete` logs its own line at INFO so `--json` callers can still see
  progress on stderr without parsing stdout.
- The casbin-related lookup for member-count is at DEBUG (not interesting to
  most operators).

## Files touched

### New

- `datashare-cli/src/main/java/org/icij/datashare/cli/command/project/ProjectCommand.java`
- `datashare-cli/src/main/java/org/icij/datashare/cli/command/project/ProjectCreateCommand.java`
- `datashare-cli/src/main/java/org/icij/datashare/cli/command/project/ProjectDeleteCommand.java`
- `datashare-app/src/main/java/org/icij/datashare/project/ProjectAdminService.java`
- `datashare-app/src/main/java/org/icij/datashare/project/ProjectAdminServiceImpl.java`
- `datashare-app/src/main/java/org/icij/datashare/project/ProjectCreateRequest.java`
- `datashare-app/src/main/java/org/icij/datashare/project/ProjectCreated.java`
- `datashare-app/src/main/java/org/icij/datashare/project/ProjectDeleteOptions.java`
- `datashare-app/src/main/java/org/icij/datashare/project/ProjectDeleted.java`
- `datashare-app/src/main/java/org/icij/datashare/project/ProjectStats.java`
- `datashare-app/src/main/java/org/icij/datashare/project/ProjectExistsException.java`
- `datashare-app/src/main/java/org/icij/datashare/project/ProjectNotFoundException.java`
- Test files mirroring the above (see Testing).

### Modified

- `datashare-cli/src/main/java/org/icij/datashare/cli/command/DatashareCommand.java`. Register `ProjectCommand.class`.
- `datashare-cli/src/main/java/org/icij/datashare/cli/DatashareCliOptions.java`. Add `PROJECT_CREATE_OPT`, `PROJECT_DELETE_OPT` constants.
- `datashare-cli/src/main/java/org/icij/datashare/cli/Validators.java`. Add `projectName`, `allowFromMask`, `uri`; factor the shared `PROJECT_NAME_REGEX` constant.
- `datashare-app/src/main/java/org/icij/datashare/CliApp.java`. Two new `if` blocks in `runTaskWorker` (`projectCreate`, `projectDelete`). For `projectDelete` the dispatcher calls `stats` then prompts before `delete`.
- DI module wiring. Bind `ProjectAdminService` to `ProjectAdminServiceImpl` (singleton). Module file is whichever one already binds `UserAdminService` from the prior PR.

### Modified (datashare-api / datashare-index)

- `datashare-api/src/main/java/org/icij/datashare/text/indexing/Indexer.java`. Add `long count(String indexName) throws IOException`.
- `datashare-index/src/main/java/org/icij/datashare/text/indexing/elasticsearch/ElasticsearchIndexer.java`. Implement the new method by running a `limit(0)` search and returning `totalHits()`.

### Files not touched in this PR (notable)

- `datashare-app/src/main/java/org/icij/datashare/web/ProjectResource.java`. REST resource keeps its inline cascade. A follow-up PR can delegate to `ProjectAdminService`; mechanically clean, but out of scope here.
- `datashare-api/src/main/java/org/icij/datashare/Repository.java`. No new methods.
- `datashare-db/...`. No new SQL, no liquibase change.

## Testing

### Layer 1: picocli command tests

`datashare-cli/src/test/.../command/project/ProjectCommandTest.java`, in the
`DatashareCommandTest` style. Fast; no app boot, no DB.

- `project create my-project` emits `projectCreate` properties with
  `name=my-project` and no other keys.
- `project create my-project --label "My P" --description d --source-path /v/my --allow-from-mask 10.*.*.* --source-url https://x/ --maintainer-name m --publisher-name p --logo-url https://x/l.png` all 9 keys propagate as typed siblings.
- Positional vs `--name`: both accepted, equivalent.
- Invalid flag values exit 5 with no prompt (one test per validator:
  `projectName`, `allowFromMask`, `uri`).
- `--no-input` + missing positional exits 2.
- `--if-not-exists` / `--no-index` / `--json` propagate as boolean keys.
- `project delete` parsing symmetry: `--yes` / `--keep-index` / `--if-exists`
  / `--no-input` / `--json`.
- `project` with no subcommand exits 2 with usage on stdout (mirrors `user`).

### Layer 2: service tests

`datashare-app/src/test/.../project/ProjectAdminServiceImplTest.java`.
Instantiate with mocks for `Repository`, `Indexer`, `Authorizer`,
`DocumentCollectionFactory`, `PropertiesProvider`.

For `create`:

- Happy path: `repository.save(Project)` called with all the defaulted
  fields; `indexer.createIndex(name)` called.
- `createIndex=false`: no indexer call.
- Project exists: `ProjectExistsException`; nothing else called.
- `if-not-exists` matching existing: returns `noop=true,
  indexCreated=false`; no save, no indexer call.
- Compensating delete: `indexer.createIndex` throws ->
  `repository.deleteAll(name)` called -> original exception rethrown.
  Verify call order with an `InOrder` verifier.
- Compensating delete fails too: both throw -> composite log message,
  original exception rethrown.
- Validation errors propagate as `ValidationException`.

For `delete`:

- Happy path: all 5 cascade steps called in source order (`InOrder` verifier).
- Project missing without `if-exists`: `ProjectNotFoundException`; nothing
  else called.
- `if-exists` matching missing: returns `noop=true`, all-false; no
  side-effects.
- `keepIndex=true`: indexer NOT called; everything else called.
- IO failure on step N: steps N+1 through 5 NOT called; exception bubbles
  up; `ProjectDeleted` not returned.

For `stats`:

- Happy path: returns `indexer.count(name)` and casbin member count.
- Project missing: `ProjectNotFoundException`.
- The `keepIndex=true` flow that sets `indexedDocuments=-1` is owned by the
  dispatcher (it just doesn't call `stats` for the index field). Tested at
  the dispatcher layer, not here.

### Layer 3: end-to-end smoke

Either appended to `DatashareCommandTest` or a new `CliAppProjectTest`.

- Mock `CommonMode` injector to return a stub `ProjectAdminService`.
- Drive the full path picocli -> properties -> dispatcher -> service for one
  happy create, one happy delete, and one error case each (create conflict
  without `--if-not-exists`, delete missing without `--if-exists`).
- Assert exit code, stdout content, stderr content.

Just enough to prove the wiring; no real DB.

### Out of scope

- No integration test against a running Datashare server.
- No end-to-end "created project shows up in REST `GET /api/project`" test
  (system-test concern beyond CLI work).
- No `JooqRepositoryTest` additions. We add no new SQL.

## Risk and rollback

- **Risk: ES failure during create leaves the DB row.** Mitigated by the
  compensating delete in step 5 of `create`. The error message and exit-1
  path are explicit when even the compensating delete fails. Acceptable: this
  is the same failure window the REST path already has (REST does not
  compensate at all today), so we are strictly better.
- **Risk: casbin grouping policies referencing the deleted project linger.**
  Documented in the Cascade semantics section. They are inert because the
  project row is gone; the grant/revoke spec is where we'll add cleanup. No
  data corruption.
- **Risk: typed-name confirmation is annoying in operator workflows.**
  Mitigated by `--yes` and the `--no-input` implication. CI scripts already
  pass one of those.
- **Risk: `--keep-index` orphans an ES index pointing at a deleted DB row.**
  Intentional. The flag's whole purpose is to preserve the index for a
  separate restore or migration step. Document it in the command's `--help`
  text.
- **Rollback**: revert is clean. The new code is additive (one new picocli
  group, one new service, no schema change). No migration to undo.

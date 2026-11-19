# API keys scoped to projects (#2532)

Stacked on #2559 (`feat/2531-api-key-expiration`, API key expiration dates).

## Intent

Today an API key grants everything its user can do, on every project the user can reach. A leaked key therefore exposes all of them. A user should be able to create a key limited to one or more projects, so a leak only exposes those projects.

## Goals

- A user can hold several API keys at once, each with its own optional project scope and its own expiration date (from #2559).
- A key with a scope only reaches the projects in its scope that the user can still reach. Its access is the user's current access narrowed to the key's projects, computed on every request.
- A key without a scope behaves exactly like today: it reaches all of the user's projects. Every existing key stays unscoped.
- Backend (schema, auth filter, REST) and CLI.

## Non-goals

- Client UI (datashare-client): follow-up PR. The client's current single-key page must keep working unchanged against the routes it uses today (`GET`, `PUT` and `DELETE /api/key/:userId`, see `datashare-client/src/api/index.js:548-556`).
- A name or label per key. Keys are told apart by hash, projects and dates. Add a name when the client needs one.
- Scoping by role or action (read-only keys, etc.).
- Editing a key's scope after creation. Create a new key and delete the old one instead.

## Anti-goals (a failure even if it works)

- A scoped key that ends up reaching more than its scope through any path: a project deletion that empties the list, a role-based route, or a key-management route.
- Breaking existing keys, the client's key page, or `datashare api-key create|get|delete alice` scripts that only use the documented arguments.
- The legacy "regenerate" action (`PUT /api/key/:userId`) leaving the previous key alive.

## Approaches considered

1. **Narrow the user once, in `ApiKeyFilter` (picked).** Wrap the user in a scoped user whose `getProjects()` returns the intersection. About 20 call sites read access through `User.getProjects()`, `getProjectNames()` or `isGranted()` (`User.java:115-182`), so they all inherit the scope. The two role annotations and the owner-keyed paths below do not go through `getProjects()`, so they get targeted checks.
2. **Check scope route by route.** About 20 edits, and the first missed route is a leak. Rejected.
3. **Parse the project out of the URL in a filter.** Fragile across route shapes (path, query, JSON body, comma-separated index lists). Rejected.

Storage, picked by Pierre: an **`api_key_project` join table** (the same pattern as `batch_search_project`, changelog `027`), rather than a nullable comma-separated column on `api_key` (smaller diff, but untyped and not queryable by project).

## Design

### Data

- New migration `054-…yml`. Changeset ids continue after #2559's `90`/`91`.
- New table `api_key_project(api_key_id varchar(96) not null, prj_id varchar(96) not null)` with a unique index on `(api_key_id, prj_id)`.
- **No foreign key to `project`.** Deleting a project must not delete scope rows, because a key whose rows all disappear would read as unscoped and reach every project. A scope row for a deleted project simply never matches.
- No scope rows for a key means unscoped. Deleting a key deletes its scope rows in the same transaction. No orphan rows.
- Drop the unique constraint on `api_key.user_id` and keep a plain index on `user_id` for lookups. On PostgreSQL that is a constraint drop. On SQLite it is a table rebuild (create a temporary table, copy, drop, rename, recreate the index), like changesets 51-55 in `027`. The rebuild must keep `expiration_date` from #2559.

### Domain and repository

- `ApiKey` gains `List<String> getProjects()`. An empty list means unscoped.
- `ApiKeyRepository`:
  - `save` becomes a plain insert. The `onConflict(USER_ID)` upsert from #2559 goes away.
  - It gains: list a user's keys, delete one key by user and hash (so one user cannot delete another user's key), and replace all of a user's keys with one new key in a single transaction (used by the legacy `PUT`).
  - `get(base64Key)` returns the key with its projects.
  - `get(User)` returns the user's newest key (the legacy single-key read).
  - `delete(User)` deletes all of the user's keys.

### Authentication

- `ApiKeyStore` returns the matching non-expired `ApiKey` instead of only its login, so the filter can see the scope.
- `ApiKeyFilter`, after `users.find(login)`:
  - If the key is scoped, the filter sets the current user to a scoped user: a `DatashareUser` subclass whose `getProjects()` returns the real user's projects intersected with the key's projects. That covers both DB projects and OAuth application projects, since `getProjects()` merges them (`User.java:120`). The cached user object must not be mutated.
  - `PostLoginEnroller.enroll` receives the real, unscoped user, because it reconciles roles against the identity provider's full grant list.
  - When a session cookie is present the key is ignored, as today (`ApiKeyFilter.apply`, first branch).

### Role-based routes (`@Policy`, `@TaskPolicy`)

`PolicyAnnotation` and `TaskPolicyAnnotation` authorize through `authorizer.can(user.id, domain, projectId, role)` and never read `getProjects()`. For a scoped user both annotations also require:

- The project id is not `*`. Instance-wide, domain-wide and "any project" checks are refused, so a scoped key can never use instance-admin or domain-admin routes.
- `user.isGranted(projectId)` holds for every project id checked (batch searches and batch downloads check several).

Refusals return the same 403 body as an insufficient role. Unscoped keys and sessions are unchanged. The check must sit where `TaskPolicyChecker.isAllowed` (used by MCP tools) also reaches it.

### Owner-keyed paths (decided by Pierre: close them all)

Some routes filter by user id, not by project, so the scoped user alone does not narrow them. A builder check confirmed these. For a scoped user, each must only reach projects in `getProjects()`:

- **Task lookup and results.** `TaskFinder.findVisibleTaskFor` (`TaskFinder.java:73-75`) returns any task the user owns. Owned tasks on a project outside the scope must be treated as not visible, for example `GET /api/task/:id/result` on an old batch-download zip.
- **Task lists.** `GET /api/task`, `/all` and the MCP `list_tasks` tool list all of the user's tasks. Tasks on projects outside the scope are filtered out. Batch searches and batch downloads are only visible when every one of their projects is in scope.
- **Generic task creation.** `PUT /api/task/:id` (`TaskResource.java:173-197`) starts a task on whatever project the request body names. A project outside the scope gets a 403.
- **Batch searches.** `JooqBatchSearchRepository.getRecords` (`:245`) uses the client's `project` filter instead of the user's projects when one is sent. That filter must be intersected with the user's projects.
- **User history.** `/api/users/me/history` (`UserResource.java:515-540`), reads and the bulk `DELETE`, is limited to in-scope projects.
- **`GET /api/users/me`.** It returns `getDetails()`, which still holds the full OAuth project list (`groups_by_applications.datashare`, `UserResource.java:481-483`). For a scoped user, that list in the response is narrowed to the scope.

The builder audits the other routes for the same pattern (filtering by owner, or by a client-supplied project list) and narrows any it finds. Each such fix gets a test that uses a scoped key.

### Key management

- The #2559 rule (`PUT /api/key/:userId` answers 403 when the request is authenticated with an API key) extends to every write route on keys: `PUT`, `POST` and both `DELETE`s. Read routes stay open to key-authenticated requests, as `GET` is today.
- REST routes (all under `/api/key/:userId`, owner only, as today via `verifyOwnership`):

| Route | Behavior |
|---|---|
| `GET /keys` | New. Lists the user's keys: `hashedKey`, `projects`, `creationDate`, effective `expirationDate` (as #2559 computes it). |
| `POST /keys` | New. Adds a key. Optional JSON body `{"projects": [...], "expirationDate": "..."}`. `201 {"apiKey": "..."}`. |
| `DELETE /keys/:hashedKey` | New. Deletes that one key. `204`, or `404` if the user has no such key. |
| `PUT` | Legacy. Regenerate: in one transaction, delete all the user's keys and create one unscoped key. Keeps #2559's optional `expirationDate` body. |
| `GET` | Legacy. Same response shape as #2559, for the newest key. |
| `DELETE` | Legacy. Deletes all the user's keys. |

- Validating `projects` on create: every project must be granted to the creating user (`isGranted`). Otherwise `400` naming the refused projects. Duplicates are collapsed. An empty list or a missing field means unscoped.

### CLI

- `datashare api-key create alice --project a --project b [--expiration-date …]` **adds** a scoped key. Wired the same way #2559 wired `--expiration-date` (picocli option to a property read by `CliApp.handleApiKeyCreate`).
- `datashare api-key create alice` without `--project` (and the legacy `-k alice`) **keeps rotating** (decided by Pierre). It deletes all of the user's keys and creates one unscoped key, exactly like the legacy REST `PUT`, so existing rotation scripts behave as today.
- `datashare api-key list alice` (new) prints one key per line: hash, projects (`*` when unscoped), expiration.
- `datashare api-key get alice` is unchanged: it prints the newest key's hash.
- `datashare api-key delete alice` is unchanged (it deletes all keys). `--key <hash>` deletes one key.
- On the CLI, a `--project` naming a project that does not exist in the project table is an error with a non-zero exit. The CLI has no logged-in user to check grants against.

## Calls made without asking (check these)

1. A scoped key is refused on every instance-wide or domain-wide role check, even when its user is an admin.
2. Key-authenticated requests cannot create or delete keys. They can still list them.
3. A REST-created scope is validated against the creator's grants, a CLI-created scope only against project existence.
4. Tasks started with a scoped key are authorized at request time (including the new check on `PUT /api/task/:id`). The task then runs as the stored, unscoped user, as tasks do today. The scoped user is unwrapped before it is stored in task arguments, and no scope travels with the task.
5. REST `POST /keys` always adds, even without projects. Only the legacy `PUT` and the CLI without `--project` rotate.

## Left to the builder

- Class and method names, including the scoped user class and the repository method signatures.
- Whether the new REST routes call the repository directly (as #2559's `GET` does) or go through tasks.
- The SQLite rebuild SQL, provided it keeps every `api_key` column and the data.
- The CLI `list` output format beyond the fields listed above.

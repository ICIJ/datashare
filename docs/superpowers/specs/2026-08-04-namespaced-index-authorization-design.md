# Proxy authorization for namespaced indices

Issue: [#2199](https://github.com/ICIJ/datashare/issues/2199), part of epic [#2194](https://github.com/ICIJ/datashare/issues/2194).

## Problem

The Elasticsearch search proxy grants access to an index only when the index name equals,
character for character, a project the current user is granted. The epic introduces a derived
index per project, `<project>.entities`, so a user granted `myproject` is refused on
`myproject.entities` twice over: the index-name validator rejects the dot, and the grant check
finds no project by that name.

## Approach

Two edits in the proxy's access check, plus one in the async-search ownership record that the
check relies on.

### 1. Index-name grammar allows an inner dot

`IndexAccessVerifier.checkIndices` validates the comma-separated index segment against a single
pattern. The name grammar becomes `[-a-zA-Z0-9_]+(\.[-a-zA-Z0-9_]+)*`: a dot may separate
segments but can never lead, trail, or double.

The leading-dot exclusion is the load-bearing part. Elasticsearch keeps its own state in
dot-prefixed indices (`.kibana`, `.security`), and a grammar that accepted a leading dot would
put them one request away from any authenticated user.

### 2. Grant check maps an index back to its project

A new `IndexAccessVerifier.baseProject(String)` returns the project an index authorizes against:
the name itself, or the name with a known suffix removed. `.entities` is the only known suffix.
`isAuthorizedRequest` maps every index in the request through it before the existing
`allMatch(currentUser::isGranted)`.

Suffix stripping, never prefix matching. `User.isGranted` is exact-string containment over
granted project names (`datashare-api/src/main/java/org/icij/datashare/user/User.java:188`), and
that property has to survive: under prefix matching, a grant on `myproject` would leak into
`myproject-other`.

The mapping is unambiguous in both directions because `Project.NAME_REGEX` is
`^[a-z0-9][a-z0-9-]{1,63}$` (`datashare-api/src/main/java/org/icij/datashare/text/Project.java:20`).
No project name can contain a dot, so no real project is ever named `foo.entities`, and stripping
a suffix can never shadow a name a user legitimately holds.

### 3. Async-search ownership records the base project

`IndexResource.recordAsyncSearchOwnership` stores the projects an async search spans, and every
later poll or cancel re-checks the current user's grants against that stored list
(`isOwnedByCurrentUser`). It stored the raw index segment. Left alone, a search submitted on
`p.entities` would record `p.entities` and then 404 for its own owner on the first poll, because
the poll asks `isGranted("p.entities")`, which is false.

It now maps the segment through `baseProject`, so submit and poll authorize against the same
string.

## Scope

The search proxy's access check, as the issue states, plus one guard the grammar change forces on
the REST project-creation paths (see the sixth caller below). Project-management *permissions* are
untouched: nothing gains or loses a grant, and the only new rejection is a project name that
already violated `Project.NAME_REGEX`.

The wider name grammar is shared with six other `checkIndices` callers: `createIndex`,
`createIndexPreflight`, `_close`, `_open`, `esOptions`, and `ProjectResource.createIndexOnce`. None
of the first five performs a project-grant check today, and none gains or loses one here. They
accepted any name matching the old grammar and now accept any name matching the new one, so the
change adds no authorization surface there.

The sixth, `ProjectResource.createIndexOnce`, is different: `checkIndices` doubles there as the
de-facto project-name validator for the REST creation paths (`projectCreate` and the create branch
of `projectUpdate`), which otherwise check only non-emptiness. Before this branch, `checkIndices`
rejected every dot, so a project named `victim.entities` was impossible; after it, that name would
pass and become readable by every member of project `victim`. A guard against `Project.NAME_REGEX`
on both creation paths, the same pattern already enforced by `ProjectAdminServiceImpl` and the CLI
validators, is what keeps the "adds no authorization surface" claim true for this caller too.

## Testing

`IndexAccessVerifierTest`, unit, no Elasticsearch:

- `foo.entities` and `bar,foo.entities` validate.
- `.kibana`, `foo.`, and `foo..entities` are rejected.
- A user granted `foo` reaches `foo.entities/_search`.
- The same user is refused `bar.entities/_search`, `foo.unknown/_search` (unknown suffix),
  `foobar/_search` (prefix match on a bare name), `myproject-other.entities/_search` (prefix match
  *after* the suffix strip, the mutation the "suffix, never prefix" rule exists to prevent), and
  `foo,bar.entities/_search` (a foreign index piggybacked on a granted one).
- A name with a trailing line terminator, `"foo\n"`, is rejected: `$` alone would accept it, so the
  matcher has to be `matches()`, not `find()`.

`IndexResourceTest`, against the test Elasticsearch:

- An async search submitted on `cecile-datashare.entities` records `cecile-datashare` as its
  project and is then polled successfully by its submitter. This is the regression guard for
  edit 3: assert on the stored list, not only on the poll's status code, so a future change that
  breaks the mapping fails loudly instead of passing for an unrelated reason.

`ProjectResourceTest`, for the sixth caller's guard:

- Creating a project named `foo.entities` returns 400, on both `projectCreate` and the create branch
  of `projectUpdate`. Before the guard both returned 500, because `createIndexOnce` swallowed the
  `IllegalArgumentException` and reported failure rather than a bad request.

Every new test must be shown failing against the unmodified production code before the
implementation is accepted. Two assertions are exempt because the behavior they guard already
existed and is correct: the boundary cases in `test_check_invalid_dotted_index` (the old grammar
rejected every dot) and the `myproject-other.entities` refusal. Both are regression guards, and both
are mutation-sensitive: they fail if the grammar is loosened to `[-a-zA-Z0-9_.]+` or if the strip
becomes a prefix match.

## Follow-ups, out of scope here

- `esHead` (`IndexResource.java:107`) and `esOptions` (`:273`) forward the caller's path to
  Elasticsearch with no grant check; `createIndex` (`:82`) has none either. Pre-existing.
- A search spanning `p,p.entities` records `p` twice in the ownership list. Harmless:
  `allMatch` is indifferent to duplicates.
- The `.entities` suffix constant stays private to `IndexAccessVerifier`. #2205 creates the index
  and can lift the constant somewhere shared when it needs it.

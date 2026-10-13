# Namespaced Index Authorization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land the working-tree implementation of [#2199](https://github.com/ICIJ/datashare/issues/2199) after proving its tests fail without the production change.

**Architecture:** The production and test code already exist, uncommitted, in the working tree, and both test classes are green. So this plan does not write code. It does the two things that are missing: prove the new tests were red before the production hunks existed, and land the change as a PR against #2199.

**Tech Stack:** Java 17, Maven multi-module, JUnit 4, Mockito, fest-assert, `net.codestory` HTTP layer, Elasticsearch test instance at `elasticsearch:9200`.

**Spec:** `docs/superpowers/specs/2026-08-04-namespaced-index-authorization-design.md`

## Global Constraints

- No new dependencies. The change is three edits to two existing files.
- Working-tree files under change, all four already modified, none created:
  - `datashare-app/src/main/java/org/icij/datashare/utils/IndexAccessVerifier.java`
  - `datashare-app/src/main/java/org/icij/datashare/web/IndexResource.java`
  - `datashare-app/src/test/java/org/icij/datashare/utils/IndexAccessVerifierTest.java`
  - `datashare-app/src/test/java/org/icij/datashare/web/IndexResourceTest.java`
- Test command shape is fixed: `-Dtest` values comma-separated, `-am` never dropped, `-Dsurefire.failIfNoSpecifiedTests=false` always present. Dropping `-am` or using a space-separated `-Dtest` list produces a fake green.
- Never block on Maven. Run it in the background and poll, reporting each check.
- Scratchpad for all intermediate files: `/tmp/claude-501/-home-dev-Repositories-datashare/c4e31458-ecde-4a9c-bceb-cb02a7caeb33/scratchpad`
- Four test classes in this module fail for environment reasons on any branch. If a failure appears outside `IndexAccessVerifierTest` or `IndexResourceTest`, check it against a clean tree before attributing it to this change.
- No `ponytail:` comments in anything committed.

---

### Task 1: Prove the new tests are red without the production change

The six new test methods have only ever been run against the finished implementation. This task establishes that five of them actually exercise the new behavior, by reverting only the two production files and watching them fail.

**Files:**
- Temporarily revert: `datashare-app/src/main/java/org/icij/datashare/utils/IndexAccessVerifier.java`
- Temporarily revert: `datashare-app/src/main/java/org/icij/datashare/web/IndexResource.java`
- Leave untouched: both test files.

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: two log files in the scratchpad, `red.log` and `green.log`, plus a restored working tree byte-identical to the current one.

Reverting both production files together is what keeps the tree compiling: the uncommitted `IndexResource.recordAsyncSearchOwnership` calls `IndexAccessVerifier.baseProject`, which only exists in the uncommitted verifier. Revert one and the module will not compile, which proves nothing.

- [ ] **Step 1: Back up the two production files, then revert them**

```bash
SCRATCH=/tmp/claude-501/-home-dev-Repositories-datashare/c4e31458-ecde-4a9c-bceb-cb02a7caeb33/scratchpad
cp datashare-app/src/main/java/org/icij/datashare/utils/IndexAccessVerifier.java "$SCRATCH/IndexAccessVerifier.java.impl"
cp datashare-app/src/main/java/org/icij/datashare/web/IndexResource.java "$SCRATCH/IndexResource.java.impl"
git checkout -- \
  datashare-app/src/main/java/org/icij/datashare/utils/IndexAccessVerifier.java \
  datashare-app/src/main/java/org/icij/datashare/web/IndexResource.java
git status --porcelain
```

Expected: `git status --porcelain` lists exactly the two **test** files as modified, and neither production file.

- [ ] **Step 2: Run both test classes and expect RED**

```bash
SCRATCH=/tmp/claude-501/-home-dev-Repositories-datashare/c4e31458-ecde-4a9c-bceb-cb02a7caeb33/scratchpad
mvn -pl datashare-app -am test \
  -Dtest=IndexAccessVerifierTest,IndexResourceTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dmaven.test.failure.ignore=true > "$SCRATCH/red.log" 2>&1
```

Run in the background and poll. `-Dmaven.test.failure.ignore=true` keeps the build from aborting so the summary and both surefire reports get written.

- [ ] **Step 3: Read the RED output and check it against the expected failures**

```bash
SCRATCH=/tmp/claude-501/-home-dev-Repositories-datashare/c4e31458-ecde-4a9c-bceb-cb02a7caeb33/scratchpad
grep -E "Tests run:|FAIL|ERROR\]   " "$SCRATCH/red.log" | head -40
```

Expected, five failures, all in the new tests:

| Test | Expected failure against the old code |
| --- | --- |
| `IndexAccessVerifierTest.test_check_namespaced_index` | `IllegalArgumentException: Bad format for indices : 'foo.entities'` — the old grammar rejects the dot |
| `IndexAccessVerifierTest.test_check_path_grants_entities_index_of_granted_project` | same `IllegalArgumentException`, thrown out of `checkPath` instead of returning the path |
| `IndexAccessVerifierTest.test_check_path_refuses_entities_index_of_other_project` | `assertThrows(UnauthorizedException.class, …)` fails: the old code throws `IllegalArgumentException` on the dot before it ever reaches the grant check |
| `IndexAccessVerifierTest.test_check_path_refuses_unknown_suffix_and_prefix_match` | same substitution for `foo.unknown/_search`. The `foobar/_search` half passes on both old and new code |
| `IndexResourceTest.test_async_search_on_entities_index_is_polled_by_its_owner` | `NullPointerException` at `IndexResourceTest.java:332`: the submit returns 400, so the response body has no `id` for `readTree(...).get("id")` |

The sixth new method, `IndexAccessVerifierTest.test_check_invalid_dotted_index`, **passes on the old code too**, and that is expected: the old grammar rejected `.kibana`, `foo.`, and `foo..entities` by rejecting every dot. It is a boundary guard against a future author loosening the grammar to `[-a-zA-Z0-9_.]+`, not a driver of new behavior. Report it as green, do not try to make it fail.

If any of the five expected failures does *not* appear, stop. A test that passes without the implementation is not testing the implementation, and the reason has to be understood before landing.

- [ ] **Step 4: Restore the implementation and verify the tree is back**

```bash
SCRATCH=/tmp/claude-501/-home-dev-Repositories-datashare/c4e31458-ecde-4a9c-bceb-cb02a7caeb33/scratchpad
cp "$SCRATCH/IndexAccessVerifier.java.impl" datashare-app/src/main/java/org/icij/datashare/utils/IndexAccessVerifier.java
cp "$SCRATCH/IndexResource.java.impl" datashare-app/src/main/java/org/icij/datashare/web/IndexResource.java
git diff --stat
```

Expected `git diff --stat`, matching the tree at the start of this task:

```
 .../icij/datashare/utils/IndexAccessVerifier.java  | 18 ++++++--
 .../java/org/icij/datashare/web/IndexResource.java |  6 ++-
 .../datashare/utils/IndexAccessVerifierTest.java   | 50 ++++++++++++++++++++++
 .../org/icij/datashare/web/IndexResourceTest.java  | 17 +++++++-
 4 files changed, 85 insertions(+), 6 deletions(-)
```

- [ ] **Step 5: Run both test classes again and expect GREEN**

```bash
SCRATCH=/tmp/claude-501/-home-dev-Repositories-datashare/c4e31458-ecde-4a9c-bceb-cb02a7caeb33/scratchpad
mvn -pl datashare-app -am test \
  -Dtest=IndexAccessVerifierTest,IndexResourceTest \
  -Dsurefire.failIfNoSpecifiedTests=false > "$SCRATCH/green.log" 2>&1
echo "EXIT=$?"
```

Run in the background and poll.

- [ ] **Step 6: Confirm the counts from the surefire reports, not from the log tail**

```bash
grep -h -o 'name="[^"]*" time="[^"]*" tests="[0-9]*" errors="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' \
  datashare-app/target/surefire-reports/TEST-org.icij.datashare.utils.IndexAccessVerifierTest.xml \
  datashare-app/target/surefire-reports/TEST-org.icij.datashare.web.IndexResourceTest.xml
```

Expected: `IndexAccessVerifierTest` `tests="12" errors="0" skipped="0" failures="0"`, `IndexResourceTest` `tests="42" errors="0" skipped="0" failures="0"`.

A zero exit code alone is not sufficient evidence here. `-Dtest` typos silently run nothing, and the assertion worth making is that 54 tests ran and none failed.

- [ ] **Step 7: No commit in this task**

This task changes nothing that survives it. Its deliverable is the RED evidence and a restored tree. Report the RED table and the GREEN counts to the reviewer before Task 2.

---

### Task 2: Land the change as a PR against #2199

**Files:**
- Commit: the four files listed in Global Constraints, and nothing else.
- Do not commit: `docs/superpowers/`, which is gitignored at `.gitignore:23`.

**Interfaces:**
- Consumes: the restored working tree and the RED/GREEN evidence from Task 1.
- Produces: branch `refactor/2199-namespaced-index-authorization` pushed to `origin`, and an open PR.

`main` is one commit ahead of `origin/main` with `ffa6b9e4d chore(git): ignore graphify-out knowledge graph output`, which is unrelated to #2199. Branching from `origin/main` rather than `main` keeps that commit out of this PR. The four files are byte-identical between `main` and `origin/main`, verified with `git diff --name-only origin/main..main` over those paths returning nothing, so `git checkout -b` carries the uncommitted changes across with no conflict.

- [ ] **Step 1: Create the branch from origin/main, carrying the working-tree changes**

```bash
git checkout -b refactor/2199-namespaced-index-authorization origin/main
git status --porcelain
```

Expected: the same four modified files, now on the new branch. If `git checkout -b` reports a conflict, stop: the assumption that the four files are identical between `main` and `origin/main` has gone stale, and it needs re-checking before anything is committed.

- [ ] **Step 2: Hostile-review the staged diff one more time**

```bash
git add datashare-app/src/main/java/org/icij/datashare/utils/IndexAccessVerifier.java \
        datashare-app/src/main/java/org/icij/datashare/web/IndexResource.java \
        datashare-app/src/test/java/org/icij/datashare/utils/IndexAccessVerifierTest.java \
        datashare-app/src/test/java/org/icij/datashare/web/IndexResourceTest.java
git diff --cached
grep -rn "ponytail:" datashare-app/src/main/java/org/icij/datashare/utils/IndexAccessVerifier.java \
                    datashare-app/src/main/java/org/icij/datashare/web/IndexResource.java || echo "no ponytail markers"
```

Check, and stop if any is wrong:

- The index-name grammar allows a dot only *between* segments. A leading dot must stay rejected, which is what keeps `.kibana` and `.security` out of reach.
- `baseProject` strips the `.entities` suffix. It does not prefix-match, because prefix matching would leak a grant on `myproject` into `myproject-other`.
- `recordAsyncSearchOwnership` stores the result of `baseProject`, so a later poll re-checks the same string the submit authorized.
- Nothing outside these four files is staged, and `git status --porcelain` shows no other staged path.

- [ ] **Step 3: Commit**

Use the `/commit` skill. It runs lint and tests and keeps superpowers artifacts out of the commit. Intended message:

```
refactor(index): authorize a "<project>.entities" index against its base project

The search proxy granted an index only on an exact match against a project
name, so a user granted "myproject" was refused on "myproject.entities":
the name validator rejected the dot, and no project carried that name.

Index names now allow a dot between segments but never a leading one, so
the elasticsearch system indices stay out of reach, and the grant check
maps an index back to its project by stripping a known ".entities" suffix.
Suffix, never prefix, or a grant on "myproject" would leak into
"myproject-other". Async-search ownership records the base project too, so
a poll re-checks the string its submit authorized.

Closes #2199
```

No `Co-Authored-By` trailer and no Claude attribution.

- [ ] **Step 4: Push and open the PR**

```bash
git push -u origin refactor/2199-namespaced-index-authorization
```

Then open the PR with this body, short summary plus tagged bullets:

```markdown
Lets a user who can access a project also reach that project's `<project>.entities`
index through the search proxy. Part of #2194.

- refactor: allow a dot between index-name segments, never leading, so the
  elasticsearch system indices stay unreachable
- refactor: map an index back to its project by stripping a known `.entities`
  suffix before the grant check, exact match on the remainder
- fix: record async-search ownership against the base project, so a poll
  re-checks the string its submit authorized
- test: a user reaches their own `.entities` index, and is refused another
  project's, an unknown suffix, and a prefix match

Closes #2199
```

- [ ] **Step 5: Report the follow-ups, do not implement them**

Post these as a comment on the PR or hand them to the reviewer, from the spec's follow-ups section:

- `esHead` (`IndexResource.java:107`) and `esOptions` (`:273`) forward the caller's path to Elasticsearch with no grant check; `createIndex` (`:82`) has none either. Pre-existing.
- `checkIndices` uses `find()` against a `^…$` pattern, and Java's `$` also matches before a final line terminator, so `"foo\n"` validates. Not an authorization bypass, since `isGranted` is exact. `matches()` is the correct call.
- A search spanning `p,p.entities` records `p` twice in the ownership list. Harmless, `allMatch` is indifferent to duplicates.
- The `.entities` constant stays private to `IndexAccessVerifier`. #2205 creates the index and can lift it somewhere shared when it needs to.

---

## Self-Review

**Spec coverage.** Every spec section maps to a task. The three production edits and both test sections are already implemented in the working tree, verified green in Task 1 Step 6 and reviewed against the spec's requirements in Task 2 Step 2. The spec's "Every new test must be shown failing against the unmodified production code" is Task 1 Steps 1 to 3. The spec's Scope section, which claims the wider grammar adds no authorization surface to the five other `checkIndices` callers, is the diff review in Task 2 Step 2. The follow-ups section is Task 2 Step 5.

**Placeholder scan.** No TBDs. Every command is literal and runnable, every expected output is stated as a concrete string or count, and the one judgment call, branching from `origin/main` instead of `main`, carries its reason and its stop condition.

**Type consistency.** One production symbol is introduced across the whole change, `IndexAccessVerifier.baseProject(String)` returning `String`, named identically in the spec, in both call sites, and in every task here.

**Known deviation from the plan template.** There is no write-test-then-implement cycle, because the code is already written. Task 1 recovers the missing half of that cycle by reconstructing the RED state rather than by rewriting the code from scratch.

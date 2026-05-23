# Datashare KC Swarm Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build and execute a 21-worker RuFlo swarm that ingests datashare, datashare-client, and datashare-docs into the Karpathy LLM-Wiki at `~/Obsidian/Datashare/`.

**Architecture:** Two-phase hierarchical swarm (discover then write, queen-merged) per `docs/superpowers/specs/2026-05-23-datashare-kc-swarm-design.md`. Orchestration artifacts (slice manifest, prompt templates, runbook) live under `scripts/kc-swarm/` in this repo. Workers are spawned via `mcp__ruflo__agent_spawn`; shared state lives in `mcp__ruflo__memory_store` namespaces; queen runs dedup, write coordination, and merge.

**Tech Stack:** RuFlo MCP tools (`swarm_init`, `agent_spawn`, `memory_store`, `embeddings_search`, `hive_mind_init`), JSON for slice manifest, markdown for prompts, `jq` for manifest validation, git for vault checkpointing.

---

## File Structure

Created in this plan (under `/home/dev/Repositories/datashare/scripts/kc-swarm/`):

```
scripts/kc-swarm/
├── slices.json                # 21-slice manifest: paths, expected page types
├── validate.sh                # jq-based manifest validator + path-existence check
├── prompts/
│   ├── worker-discover.md     # Phase-1 worker prompt template
│   ├── worker-write.md        # Phase-2 worker prompt template
│   ├── queen-dedup.md         # Queen dedup analysis prompt
│   └── queen-merge.md         # Queen merge-phase prompt
└── RUN.md                     # Operator runbook (exact MCP call sequence)
```

Output written by the swarm (under `/home/dev/Obsidian/Datashare/`):

```
Datashare/
├── CLAUDE.md                  # One-line patch to source-of-truth section
├── index.md                   # Rebuilt by queen
├── log.md                     # One new ingest entry appended by queen
└── wiki/
    ├── entities/<slug>.md     # ~50-80 pages, one owner per file
    ├── concepts/<slug>.md     # ~20-40 pages
    ├── sources/source-<slice-id>.md  # 21 pages, one per slice
    └── syntheses/<slug>.md    # ~5-15 pages (cross-cutting + carryovers)
```

The plan is split into five phases (Phase A builds the artifacts; B validates; C-E execute). Each phase has bite-sized tasks; commit after each phase.

---

## Phase A: Build orchestration artifacts

### Task 1: Create the slice manifest

**Files:**
- Create: `scripts/kc-swarm/slices.json`

- [ ] **Step 1: Write slices.json**

```json
{
  "schemaVersion": 1,
  "vault": "/home/dev/Obsidian/Datashare",
  "repos": {
    "datashare":        { "path": "/home/dev/Repositories/datashare",        "commit": "abbdb5c5c" },
    "datashare-client": { "path": "/home/dev/Repositories/datashare-client", "commit": "b323bd75c" },
    "datashare-docs":   { "path": "/home/dev/Repositories/datashare-docs",   "commit": "d94877a1b" }
  },
  "slices": [
    { "id": "api-core",       "repo": "datashare",        "paths": ["datashare-api/src/main/java/org/icij/datashare/"],                                                                                       "sourceSlug": "source-datashare-api",         "softCap": 12 },
    { "id": "db-schema",      "repo": "datashare",        "paths": ["datashare-db/src/main/", "datashare-db/src/main/resources/db/changelog/"],                                                              "sourceSlug": "source-datashare-db",          "softCap": 12 },
    { "id": "index-es",       "repo": "datashare",        "paths": ["datashare-index/src/main/java/", "datashare-index/src/main/resources/"],                                                                "sourceSlug": "source-datashare-index",       "softCap": 15 },
    { "id": "app-rest",       "repo": "datashare",        "paths": ["datashare-app/src/main/java/org/icij/datashare/web/"],                                                                                  "sourceSlug": "source-datashare-app-rest",    "softCap": 15 },
    { "id": "tasks",          "repo": "datashare",        "paths": ["datashare-tasks/src/main/java/"],                                                                                                       "sourceSlug": "source-datashare-tasks",       "softCap": 12 },
    { "id": "cli",            "repo": "datashare",        "paths": ["datashare-cli/src/main/", "datashare-app/src/main/java/org/icij/datashare/mode/"],                                                       "sourceSlug": "source-datashare-cli",         "softCap": 10 },
    { "id": "nlp",            "repo": "datashare",        "paths": ["datashare-nlp-corenlp/src/main/"],                                                                                                      "sourceSlug": "source-datashare-nlp",         "softCap": 8  },

    { "id": "client-api",     "repo": "datashare-client", "paths": ["src/api/"],                                                                                                                              "sourceSlug": "source-client-api",            "softCap": 10 },
    { "id": "client-stores",  "repo": "datashare-client", "paths": ["src/store/"],                                                                                                                            "sourceSlug": "source-client-stores",         "softCap": 15 },
    { "id": "client-views",   "repo": "datashare-client", "paths": ["src/views/", "src/router/"],                                                                                                             "sourceSlug": "source-client-views",          "softCap": 12 },
    { "id": "doc-viewer",     "repo": "datashare-client", "paths": ["src/components/Document/", "src/components/DocumentViewer/"],                                                                            "sourceSlug": "source-client-doc-viewer",     "softCap": 12 },
    { "id": "search-ui",      "repo": "datashare-client", "paths": ["src/components/Search/", "src/components/Filter/"],                                                                                      "sourceSlug": "source-client-search-ui",      "softCap": 12 },
    { "id": "insights",       "repo": "datashare-client", "paths": ["src/components/Widget/"],                                                                                                                "sourceSlug": "source-client-insights",       "softCap": 10 },
    { "id": "core-plugins",   "repo": "datashare-client", "paths": ["src/core/", "src/mixins/", "src/plugins/"],                                                                                              "sourceSlug": "source-client-core-plugins",   "softCap": 10 },

    { "id": "docs-local",     "repo": "datashare-docs",   "paths": ["local-mode/"],                                                                                                                           "sourceSlug": "source-docs-local",            "softCap": 8  },
    { "id": "docs-server",    "repo": "datashare-docs",   "paths": ["server-mode/"],                                                                                                                          "sourceSlug": "source-docs-server",           "softCap": 10 },
    { "id": "docs-usage",     "repo": "datashare-docs",   "paths": ["usage/"],                                                                                                                                "sourceSlug": "source-docs-usage",            "softCap": 12 },
    { "id": "docs-dev",       "repo": "datashare-docs",   "paths": ["developers/"],                                                                                                                           "sourceSlug": "source-docs-dev",              "softCap": 10 },
    { "id": "docs-concepts",  "repo": "datashare-docs",   "paths": ["concepts/"],                                                                                                                             "sourceSlug": "source-docs-concepts",         "softCap": 8  },

    { "id": "overview",       "repo": "datashare",        "paths": ["README.md", "datashare-client/README.md", "datashare-client/AGENTS.md", "datashare-docs/README.md", "datashare-docs/SUMMARY.md"],         "sourceSlug": "source-overview",              "softCap": 8,  "extraSyntheses": ["project-datashare"] },
    { "id": "openapi",        "repo": "datashare",        "paths": [],                                                                                                                                        "sourceSlug": "source-openapi",               "softCap": 6,  "extraSyntheses": ["rest-api-catalog"], "fetch": { "url": "https://github.com/ICIJ/datashare/releases/latest/download/datashare_openapi.json", "to": "scripts/kc-swarm/.cache/datashare_openapi.json" } }
  ],
  "dedup": {
    "embeddingsThreshold": 0.85,
    "namespaceDiscovery": "kc-discovery",
    "namespaceWrite": "kc-write",
    "namespaceDeltas": "kc-deltas",
    "namespaceMerge": "kc-merge"
  },
  "limits": {
    "perWorkerInputTokens": 30000,
    "perWorkerOutputTokens": 10000,
    "queenInputTokens": 50000,
    "queenOutputTokens": 5000
  }
}
```

- [ ] **Step 2: Commit Phase A scaffold init**

```bash
git add scripts/kc-swarm/slices.json
git commit -m "feat(kc-swarm): add 21-slice manifest for KC ingest"
```

---

### Task 2: Create the manifest validator

**Files:**
- Create: `scripts/kc-swarm/validate.sh`

- [ ] **Step 1: Write validate.sh**

```bash
#!/usr/bin/env bash
# Validates scripts/kc-swarm/slices.json against the file system.
# Exits 0 on success, 1 on any failure.

set -euo pipefail

MANIFEST="$(dirname "$0")/slices.json"

if [[ ! -f "$MANIFEST" ]]; then
  echo "FAIL: $MANIFEST not found"
  exit 1
fi

# 1. Schema sanity
jq -e '.schemaVersion == 1' "$MANIFEST" >/dev/null || { echo "FAIL: schemaVersion mismatch"; exit 1; }
jq -e '(.slices | length) == 21' "$MANIFEST" >/dev/null || { echo "FAIL: expected exactly 21 slices"; exit 1; }

# 2. Every slice id is unique
DUPES=$(jq -r '.slices[].id' "$MANIFEST" | sort | uniq -d)
[[ -z "$DUPES" ]] || { echo "FAIL: duplicate slice ids: $DUPES"; exit 1; }

# 3. Every sourceSlug is unique
DUPE_SLUGS=$(jq -r '.slices[].sourceSlug' "$MANIFEST" | sort | uniq -d)
[[ -z "$DUPE_SLUGS" ]] || { echo "FAIL: duplicate sourceSlugs: $DUPE_SLUGS"; exit 1; }

# 4. Vault exists with expected scaffolding
VAULT=$(jq -r '.vault' "$MANIFEST")
for f in CLAUDE.md index.md log.md raw/sources.md; do
  [[ -f "$VAULT/$f" ]] || { echo "FAIL: vault missing $f"; exit 1; }
done
for d in wiki/entities wiki/concepts wiki/sources wiki/syntheses; do
  [[ -d "$VAULT/$d" ]] || { echo "FAIL: vault missing $d/"; exit 1; }
done

# 5. Every slice path resolves under its repo (skip empty paths arrays, e.g. openapi)
FAIL=0
while IFS=$'\t' read -r id repo path; do
  REPO_PATH=$(jq -r --arg r "$repo" '.repos[$r].path' "$MANIFEST")
  FULL="$REPO_PATH/$path"
  if [[ ! -e "$FULL" ]]; then
    echo "FAIL: slice=$id repo=$repo path does not exist: $FULL"
    FAIL=1
  fi
done < <(jq -r '.slices[] | .id as $id | .repo as $r | .paths[]? | "\($id)\t\($r)\t\(.)"' "$MANIFEST")

# 6. Every repo path exists and is at the pinned commit
while IFS=$'\t' read -r repo path expected; do
  if [[ ! -d "$path/.git" ]]; then
    echo "FAIL: repo $repo at $path is not a git repo"; FAIL=1; continue
  fi
  actual=$(git -C "$path" rev-parse --short=9 HEAD)
  if [[ "$actual" != "$expected"* && "$expected" != "$actual"* ]]; then
    echo "WARN: repo $repo HEAD=$actual but manifest pins $expected (run still allowed but pin will be updated)"
  fi
done < <(jq -r '.repos | to_entries[] | "\(.key)\t\(.value.path)\t\(.value.commit)"' "$MANIFEST")

[[ $FAIL -eq 0 ]] || exit 1
echo "OK: manifest valid, 21 slices, all paths resolved, vault scaffolded"
```

- [ ] **Step 2: Make executable and run**

Run:
```bash
chmod +x scripts/kc-swarm/validate.sh
./scripts/kc-swarm/validate.sh
```

Expected output (last line):
```
OK: manifest valid, 21 slices, all paths resolved, vault scaffolded
```

If any FAIL line appears, fix the offending slice path in `slices.json` before continuing. A WARN about pinned commit is acceptable; the runbook updates the pin during the actual run.

- [ ] **Step 3: Commit**

```bash
git add scripts/kc-swarm/validate.sh
git commit -m "feat(kc-swarm): add manifest validator with path + vault checks"
```

---

### Task 3: Create the discovery-phase worker prompt

**Files:**
- Create: `scripts/kc-swarm/prompts/worker-discover.md`

- [ ] **Step 1: Write worker-discover.md**

```markdown
# Worker prompt: discovery phase

You are KC worker `{{SLICE_ID}}`, one of 21 in a hierarchical RuFlo swarm performing the **discovery phase** of an ingest into the Karpathy LLM-Wiki at `/home/dev/Obsidian/Datashare`.

## Your slice

- Slice ID: `{{SLICE_ID}}`
- Repo: `{{REPO_PATH}}` pinned at commit `{{REPO_COMMIT}}`
- Paths to read: `{{PATHS_JSON}}`
- Soft cap on candidates: `{{SOFT_CAP}}` (drop low-signal items)
- Your source page slug: `{{SOURCE_SLUG}}` (you will write this in the next phase)

## Schema you must obey

The full schema lives at `/home/dev/Obsidian/CLAUDE.md` and the project overlay at `/home/dev/Obsidian/Datashare/CLAUDE.md`. Read both before producing output.

Key constraints:
- Every wiki page has exactly one type: `entity`, `concept`, `source`, or `synthesis`.
- File names are kebab-case, lowercase, no spaces, no numeric prefixes, vault-wide unique.
- Sub-categories under entities/concepts are headings in `index.md`, not folders.

## What you do in this phase

1. Read the slice's READMEs and top-level files first (skim, not full text).
2. Walk the slice's main paths. Group what you find by candidate page topic.
3. For each candidate, decide:
   - **entity** when it names a specific instance (a class, a store, a mapping, a doc page, a CLI command).
   - **concept** when it names a pattern or principle without a single referent.
4. Cap your candidate list at `{{SOFT_CAP}}` items. Drop low-signal candidates (one-off DTOs, single-use utilities, anything you would not look up again).
5. For each candidate, gather 1-3 concrete evidence file paths (relative to the repo root) you would cite later when writing the page.
6. Draft a `summary` (one sentence, under 25 words) per candidate. This summary is used for cross-worker dedup via embeddings, so write it as a definition, not as a description of your work.

## What you do NOT do in this phase

- Do NOT write to the vault. Phase 2 handles writes.
- Do NOT call `Write`, `Edit`, or `Bash`.
- Do NOT read `index.md` or `log.md` (the queen owns those).
- Do NOT spawn other agents.

## Output

Emit exactly one `memory_store` call with namespace `kc-discovery`:

```
memory_store({
  namespace: "kc-discovery",
  key: "kc-discovery:{{SLICE_ID}}",
  value: {
    candidates: [
      {
        slug: "<kebab-case-slug>",
        type: "entity" | "concept",
        summary: "<one-sentence definition, under 25 words>",
        evidence_paths: ["<repo-relative path>", ...]
      }
    ],
    source_page: {
      slug: "{{SOURCE_SLUG}}",
      summary: "<one-sentence definition of what this slice covers>"
    }
  }
})
```

Then emit a single done marker:

```
memory_store({ namespace: "kc-discovery", key: "kc-discovery:done:{{SLICE_ID}}", value: true })
```

After that, return control. Do not produce any other output.
```

- [ ] **Step 2: Commit**

```bash
git add scripts/kc-swarm/prompts/worker-discover.md
git commit -m "feat(kc-swarm): add discovery phase worker prompt"
```

---

### Task 4: Create the write-phase worker prompt

**Files:**
- Create: `scripts/kc-swarm/prompts/worker-write.md`

- [ ] **Step 1: Write worker-write.md**

```markdown
# Worker prompt: write phase

You are KC worker `{{SLICE_ID}}`, one of 21 in a hierarchical RuFlo swarm performing the **write phase** of an ingest into the Karpathy LLM-Wiki at `/home/dev/Obsidian/Datashare`.

## Your slice (unchanged from discovery)

- Slice ID: `{{SLICE_ID}}`
- Repo: `{{REPO_PATH}}` pinned at commit `{{REPO_COMMIT}}`
- Paths to read: `{{PATHS_JSON}}`
- Source page slug to write: `{{SOURCE_SLUG}}`

## Your assignment (NEW: produced by queen dedup)

The queen has resolved candidate slugs across all 21 workers. You receive:

- `owned`: the list of canonical slugs you must write as files this phase.
- `contributing`: slugs owned by other workers, where your slice has something useful to add. You emit deltas, not files.

Both come from `memory_store({ namespace: "kc-write", key: "assignments" })`, filtered to this worker.

## Schema you must obey

Same as discovery: read `/home/dev/Obsidian/CLAUDE.md` and `/home/dev/Obsidian/Datashare/CLAUDE.md` before producing output.

Frontmatter for every written page (entity, concept, synthesis):

```yaml
---
type: entity | concept | synthesis
aliases: []          # from the assignment map; can be empty
tags: []             # self-derived, kebab-case
created: {{TODAY}}
updated: {{TODAY}}
sources: [{{SOURCE_SLUG}}]   # plus other source slugs you cite
---
```

Source page frontmatter:

```yaml
---
type: source
raw: {{REPO_PATH}}@{{REPO_COMMIT}}
created: {{TODAY}}
updated: {{TODAY}}
---
```

## What you do in this phase

For each `owned` slug:

1. Read the slice files in depth for that topic.
2. Write `wiki/{type}/<slug>.md` with frontmatter as above.
3. Body structure:
   - H1 = the page title in human-readable form (e.g. `# Document (ES parent type)`).
   - One sentence under H1: a definition. Not "this page describes...". The definition itself.
   - Sections scaled to topic complexity (no padding).
   - Every claim about code cites a repo-relative file path inline (e.g. `datashare-index/src/main/java/.../ElasticsearchIndexer.java`).
   - Every other slug you mention becomes a `[[wiki-link]]`.
4. Mark done:

```
memory_store({ namespace: "kc-write", key: "kc-write:done:<slug>", value: true })
```

For each `contributing` slug:

1. Read this slice's files relevant to that slug.
2. Emit a delta:

```
memory_store({
  namespace: "kc-deltas",
  key: "kc-deltas:<slug>:{{SLICE_ID}}",
  value: {
    from_slice: "{{SLICE_ID}}",
    source_page: "{{SOURCE_SLUG}}",
    paragraph: "<2-4 sentences>",
    file_citations: ["<repo-relative path>", ...],
    merge_mode: "append"   // use "inline" only if the delta truly belongs in body prose
  }
})
```

3. Do NOT read or write the contributor's page file.

For your source page:

1. Write `wiki/sources/{{SOURCE_SLUG}}.md`.
2. Body: one paragraph naming the slice (repo + paths + commit), then a bulleted list of every entity/concept/synthesis slug derived from this slice (each as a `[[wiki-link]]`).

## What you do NOT do in this phase

- Do NOT write to `index.md` or `log.md`. The queen builds those in merge phase.
- Do NOT write outside `/home/dev/Obsidian/Datashare/wiki/`.
- Do NOT spawn other agents.
- Do NOT modify another worker's owned page; emit a delta instead.
- Do NOT call `Bash` or `WebFetch`.

## Concurrency guarantee

Your `owned` list is disjoint from every other worker's `owned` list. Two workers will never write the same file. This is enforced upstream by the queen's assignment map.

## Output

After writing all owned pages and emitting all deltas, return control. No final summary; the merge phase reads from `memory_store`.
```

- [ ] **Step 2: Commit**

```bash
git add scripts/kc-swarm/prompts/worker-write.md
git commit -m "feat(kc-swarm): add write phase worker prompt with delta protocol"
```

---

### Task 5: Create the queen dedup prompt

**Files:**
- Create: `scripts/kc-swarm/prompts/queen-dedup.md`

- [ ] **Step 1: Write queen-dedup.md**

```markdown
# Queen prompt: dedup phase

You are the queen of a 21-worker RuFlo swarm. Phase 1 (discovery) just completed. You now produce the assignment map that drives phase 2 (write).

## Inputs

- `memory_search({ namespace: "kc-discovery" })` returns 21 worker manifests, each with `candidates` and `source_page`.
- `embeddings_search` with namespace `kc-discovery` lets you find semantically similar `summary` strings across workers.
- The schema at `/home/dev/Obsidian/CLAUDE.md` (read it first).

## Algorithm

```
canonical = {}   # slug -> assignment record

# Step A: pre-seed forced syntheses from slices.json `extraSyntheses` (slices 20, 21)
For each slice in slice-manifest with non-empty extraSyntheses:
  For each forced_slug in slice.extraSyntheses:
    canonical[forced_slug] = {
      type:           "synthesis",
      owner:          slice.id,
      contributors:   [slice.id],
      aliases:        [],
      summary:        "<placeholder; worker writes the real summary>",
      evidence_paths: []
    }

# Step B: dedup candidates across the 21 worker manifests
For each candidate across all 21 workers:
  similar = embeddings_search(candidate.summary, threshold 0.85, namespace "kc-discovery")
  similar = [s for s in similar if s.candidate.type == candidate.type]  # entity vs concept disjoint

  If similar contains slugs already in canonical:
    pick the existing canonical_slug whose summary is closest
    add candidate.slug to its aliases
    if candidate.evidence_paths > current owner's evidence: transfer ownership
    add candidate.worker to contributors
    union evidence_paths
  Else:
    canonical[candidate.slug] = {
      type:           candidate.type,
      owner:          candidate.worker,
      contributors:   [candidate.worker],
      aliases:        [],
      summary:        candidate.summary,
      evidence_paths: candidate.evidence_paths
    }

Tiebreak ownership ties by alphabetical worker id.
```

## Outputs

1. Write the assignment map to:

```
memory_store({
  namespace: "kc-write",
  key: "assignments",
  value: <canonical>
})
```

2. Print an allocation report (this is for human review at the gate). Format:

```
KC discovery results
====================
Workers reporting: 21/21
Total raw candidates: <N>
Canonical pages after dedup: <C>
Slugs collapsed: <M>

Pages per worker (owned):
  api-core: <n>
  db-schema: <n>
  ...

Collapses (top 20 most-merged):
  canonical=<slug>  aliases=<list>  contributors=<list>

Orphans (single-worker, <2 evidence paths):
  <slug>  worker=<id>  summary=<...>
```

3. STOP. Do not proceed to write phase. Return control to the operator (human) for the eyeball gate.
```

- [ ] **Step 2: Commit**

```bash
git add scripts/kc-swarm/prompts/queen-dedup.md
git commit -m "feat(kc-swarm): add queen dedup prompt with allocation report"
```

---

### Task 6: Create the queen merge prompt

**Files:**
- Create: `scripts/kc-swarm/prompts/queen-merge.md`

- [ ] **Step 1: Write queen-merge.md**

```markdown
# Queen prompt: merge phase

You are the queen of a 21-worker RuFlo swarm. Phase 2 (write) just completed. You run four ordered passes alone (no workers spawned in this phase).

## Pass 1: apply deltas to owned pages

```
For each slug in assignment map:
  page = read /home/dev/Obsidian/Datashare/wiki/<type>/<slug>.md
  deltas = memory_search({ namespace: "kc-deltas", prefix: "kc-deltas:<slug>:" })
  If deltas is non-empty:
    For each delta with merge_mode == "append":
      append "## See also from <delta.from_slice>" section with delta.paragraph + delta.file_citations as a list
    For each delta with merge_mode == "inline":
      invoke a single small LLM call to merge delta.paragraph into the page's body prose; preserve all citations
    union delta.source_page slugs into the page's frontmatter sources: list
    bump frontmatter updated: to today
    write back
```

## Pass 2: rebuild index.md from scratch

```
inventory = scan /home/dev/Obsidian/Datashare/wiki/{entities,concepts,sources,syntheses}/*.md
For each page: read frontmatter (type, aliases) and the first prose line after H1 (the one-sentence definition).

Group entities by sub-category (infer from tags + slug prefixes; e.g. tag "rest-resource" + slug "*-resource" group together).
Group concepts similarly.
Sort sources alphabetically by slug.
Sort syntheses by frontmatter created date (newest last).

Write index.md following the exact format in /home/dev/Obsidian/CLAUDE.md:

---
type: index
updated: {{TODAY}}
---
# Datashare — Index

## Entities

### <Sub-category>
- [[slug]] — <one-line summary>

## Concepts

### <Sub-category>
- [[slug]] — <one-line summary>

## Sources
- [[slug]] — <one-line summary>

## Syntheses
- [[slug]] — <one-line summary>
```

## Pass 3: append one log.md entry

Append exactly one new section to `/home/dev/Obsidian/Datashare/log.md`. Use the counts you measured in passes 1 and 2:

```markdown

## [{{TODAY}}] ingest | datashare/datashare-client/datashare-docs full sweep

- Spawned 21-worker RuFlo swarm (hierarchical, raft consensus) for full-sweep shallow ingest.
- Sliced sources: 7 backend modules, 7 frontend areas, 5 docs sections, 2 cross-cutting.
- Discovery phase: 21 workers proposed <N> candidates; dedup collapsed <M> slugs across workers.
- Write phase: 21 workers produced <K> owned pages, <L> deltas.
- Merge phase: applied <L> deltas, rebuilt index.md (<E> entities, <C> concepts, <S> sources, <Y> syntheses).
- Source pins: each source page references its repo at commit SHA.
- Follow-up flagged: <comma-separated list of low-evidence orphans for human review>.
```

## Pass 4: lint (read-only, report only)

```
For every page in wiki/:
  - count inbound [[links]] from all other pages -> orphans = pages with 0 inbound (excluding index.md, log.md)
  - check every [[slug]] resolves to an existing file -> dangling links
  - find slugs co-occurring in 3+ pages without [[…]] linking -> missing cross-refs
  - validate frontmatter (required fields, parseable YAML)
```

Print a lint report to console. Do NOT auto-fix anything.

## One-line patch to project CLAUDE.md

Edit `/home/dev/Obsidian/Datashare/CLAUDE.md`: replace the line `**TBD** — drop sources into ...` in the Source of truth section with:

```markdown
- datashare backend: `/home/dev/Repositories/datashare` @ `{{COMMIT_DATASHARE}}`
- datashare-client: `/home/dev/Repositories/datashare-client` @ `{{COMMIT_CLIENT}}`
- datashare-docs:   `/home/dev/Repositories/datashare-docs` @ `{{COMMIT_DOCS}}`
```

This is the only write outside `wiki/`, `index.md`, and `log.md`.

## Refuse-to-finish guards

Before declaring done:
- Run `git -C /home/dev/Obsidian status` and verify changes only under `Datashare/wiki/`, `Datashare/index.md`, `Datashare/log.md`, `Datashare/CLAUDE.md`. If any other path is dirty, REFUSE to write the log entry and halt.
- Verify every slug in `assignments` exists as a file. If any missing, list them and halt without writing index/log.
```

- [ ] **Step 2: Commit**

```bash
git add scripts/kc-swarm/prompts/queen-merge.md
git commit -m "feat(kc-swarm): add queen merge prompt with 4 passes + guards"
```

---

### Task 7: Create the operator runbook

**Files:**
- Create: `scripts/kc-swarm/RUN.md`

- [ ] **Step 1: Write RUN.md**

```markdown
# KC Swarm Runbook

Operator (a human running an MCP-enabled Claude session) follows these steps in order. Each step has explicit MCP calls and expected outputs.

## Prerequisites

- All three repos cloned at the pinned commits in `scripts/kc-swarm/slices.json`.
- Vault at `/home/dev/Obsidian/Datashare` is at a clean git state (no uncommitted changes).
- `mcp__ruflo__*` tools are available in your MCP client.
- `./scripts/kc-swarm/validate.sh` exits 0.

## Phase B: validate

1. Run the validator:

```bash
./scripts/kc-swarm/validate.sh
```

Expected last line: `OK: manifest valid, 21 slices, all paths resolved, vault scaffolded`.

2. Dry-run one slice (api-core, discovery only) to sanity check prompt + tool wiring. See plan Task 8.

## Phase C: init + discovery

3. Init swarm:

```
mcp__ruflo__swarm_init({ topology: "hierarchical", maxAgents: 22, strategy: "specialized" })
mcp__ruflo__hive_mind_init({ topology: "hierarchical", consensus: "raft" })
mcp__ruflo__embeddings_init({ namespace: "kc-discovery" })
mcp__ruflo__embeddings_init({ namespace: "kc-deltas" })
mcp__ruflo__memory_store({ namespace: "kc-discovery", key: "schema",         value: <vault CLAUDE.md text> })
mcp__ruflo__memory_store({ namespace: "kc-discovery", key: "slice-manifest", value: <slices.json content> })
```

4. Spawn 21 discovery workers in parallel. For each slice in `slices.json`, in ONE message with 21 tool calls:

```
mcp__ruflo__agent_spawn({
  agentType: "researcher",
  model: "sonnet",
  agentId: "kc-worker-<slice.id>",
  task: <render prompts/worker-discover.md with slice variables>,
  config: { phase: "discover", slice: <slice object> }
})
```

5. Wait for all 21 done markers:

```
mcp__ruflo__memory_search({ namespace: "kc-discovery", query: "kc-discovery:done:" })
# expect 21 hits
```

## Phase C (cont.): queen dedup + human gate

6. Run queen dedup. Spawn one queen agent or call inline:

```
mcp__ruflo__agent_spawn({
  agentType: "coordinator",
  model: "sonnet",
  agentId: "kc-queen-dedup",
  task: <render prompts/queen-dedup.md>
})
```

7. Read the allocation report printed by queen.

8. **HUMAN GATE.** Operator (pirhoo) reviews. Approve or edit `memory_store["kc-write:assignments"]`. If unhappy, abort here; the only state on disk is the slices.json and prompts, which is clean.

## Phase D: write

9. Spawn 21 write workers in parallel. Same shape as step 4 but with `prompts/worker-write.md` and `phase: "write"`. Each worker also reads `memory_retrieve("kc-write:assignments")` filtered to its slice.

10. Wait for all owned-slug done markers:

```
mcp__ruflo__memory_search({ namespace: "kc-write", query: "kc-write:done:" })
# expect one hit per canonical slug
```

## Phase E: merge

11. Run queen merge:

```
mcp__ruflo__agent_spawn({
  agentType: "coordinator",
  model: "sonnet",
  agentId: "kc-queen-merge",
  task: <render prompts/queen-merge.md>
})
```

12. Inspect output:
- Lint report on console.
- Vault git status should show changes only under `Datashare/wiki/`, `Datashare/index.md`, `Datashare/log.md`, `Datashare/CLAUDE.md`.

13. Quality sample (operator):
```
ls /home/dev/Obsidian/Datashare/wiki/entities/*.md | shuf | head -5 | xargs -I{} sh -c 'echo "=== {} ===" && cat {}'
```

Check each: H1 + one-sentence definition + at least one citation + frontmatter parses + all `[[links]]` resolve.

14. If quality is acceptable, commit the vault (operator runs this in `~/Obsidian`):

```bash
cd /home/dev/Obsidian
git add Datashare/
git commit -m "ingest: full-sweep KC populate via 21-worker RuFlo swarm"
```

## Failure recovery

- Any worker failed in discovery: rerun step 4 with only the failed slice ids (queen reads existing done markers and skips).
- Worker failed in write: rerun step 9 with the same trick.
- Queen merge halted on guard: read its output, fix the issue (typically a stray file outside `wiki/`), rerun.
- Want a clean restart: delete the relevant `memory_store` namespaces (`kc-discovery`, `kc-write`, `kc-deltas`, `kc-merge`) and `git checkout -- .` inside the vault.

## Cost ceiling reminder

Hard budget configured in slices.json `limits`. Queen halts and dumps progress if overrun. To resume after a cost halt: bump the limits and re-call the merge phase.
```

- [ ] **Step 2: Commit**

```bash
git add scripts/kc-swarm/RUN.md
git commit -m "feat(kc-swarm): add operator runbook with phase-by-phase MCP calls"
```

---

## Phase B: Validate

### Task 8: Single-slice dry-run (discovery only)

This proves the prompts work and the tool wiring is correct before we fire all 21 workers.

**Files:**
- Read: `scripts/kc-swarm/prompts/worker-discover.md`
- Memory write: `memory_store["kc-discovery:api-core"]`
- Memory read: `memory_search` against `kc-discovery`

- [ ] **Step 1: Run prerequisites**

```bash
./scripts/kc-swarm/validate.sh
```

Expected last line: `OK: manifest valid, ...`

- [ ] **Step 2: Init only the namespaces needed for dry-run**

```
mcp__ruflo__embeddings_init({ namespace: "kc-discovery" })
mcp__ruflo__memory_store({ namespace: "kc-discovery", key: "schema", value: <contents of /home/dev/Obsidian/CLAUDE.md + /home/dev/Obsidian/Datashare/CLAUDE.md> })
```

- [ ] **Step 3: Spawn one discovery worker for slice api-core**

```
mcp__ruflo__agent_spawn({
  agentType: "researcher",
  model: "sonnet",
  agentId: "kc-worker-api-core",
  task: <render prompts/worker-discover.md substituting:
    SLICE_ID=api-core
    REPO_PATH=/home/dev/Repositories/datashare
    REPO_COMMIT=abbdb5c5c
    PATHS_JSON=["datashare-api/src/main/java/org/icij/datashare/"]
    SOFT_CAP=12
    SOURCE_SLUG=source-datashare-api>
})
```

- [ ] **Step 4: Verify manifest landed**

```
mcp__ruflo__memory_retrieve({ namespace: "kc-discovery", key: "kc-discovery:api-core" })
mcp__ruflo__memory_retrieve({ namespace: "kc-discovery", key: "kc-discovery:done:api-core" })
```

Expected:
- The first call returns an object with `candidates` (a non-empty array) and `source_page`.
- The second call returns `true`.
- Every `candidate.evidence_paths` entry, prefixed with `/home/dev/Repositories/datashare/`, points to a real file.

- [ ] **Step 5: Manual quality check**

Read 3 random candidates from the result and check:
- `slug` is kebab-case, no spaces, no numeric prefix.
- `type` is `entity` or `concept` only.
- `summary` is one sentence, defines (not describes), under 25 words.
- `evidence_paths` resolve under the repo root.

If any fail: revise `prompts/worker-discover.md` (fix prompt clarity, not the worker output), commit, rerun this task.

- [ ] **Step 6: Cleanup before full run**

```
mcp__ruflo__memory_delete({ namespace: "kc-discovery", key: "kc-discovery:api-core" })
mcp__ruflo__memory_delete({ namespace: "kc-discovery", key: "kc-discovery:done:api-core" })
```

- [ ] **Step 7: Commit any prompt revisions (if step 5 forced changes)**

```bash
git add scripts/kc-swarm/prompts/worker-discover.md
git commit -m "fix(kc-swarm): tighten discovery prompt based on dry-run feedback"
```

If no changes, skip this commit.

---

## Phase C: Execute discovery and human gate

### Task 9: Full discovery phase

**Files:**
- Read: `scripts/kc-swarm/slices.json`, `scripts/kc-swarm/prompts/worker-discover.md`

- [ ] **Step 1: Vault snapshot before run**

```bash
cd /home/dev/Obsidian && git status && git log -1 --oneline
```

Expected: clean working tree. If dirty, commit or stash first.

- [ ] **Step 2: Init swarm and shared state**

In ONE message, call:

```
mcp__ruflo__swarm_init({ topology: "hierarchical", maxAgents: 22, strategy: "specialized" })
mcp__ruflo__hive_mind_init({ topology: "hierarchical", consensus: "raft" })
mcp__ruflo__embeddings_init({ namespace: "kc-discovery" })
mcp__ruflo__embeddings_init({ namespace: "kc-deltas" })
mcp__ruflo__memory_store({ namespace: "kc-discovery", key: "schema", value: <concatenated text of /home/dev/Obsidian/CLAUDE.md and /home/dev/Obsidian/Datashare/CLAUDE.md> })
mcp__ruflo__memory_store({ namespace: "kc-discovery", key: "slice-manifest", value: <contents of scripts/kc-swarm/slices.json> })
```

Expected: each call returns success with a session/swarm id.

- [ ] **Step 3: Spawn 21 discovery workers in one parallel batch**

In ONE message with 21 tool calls (one per slice in `slices.json`), spawn each worker with the rendered prompt. For slice with `id: "openapi"` and a `fetch` block, fetch the OpenAPI JSON to its `to` path with `curl -fsSL --create-dirs -o <to> <url>` before spawning, and include the local path in `PATHS_JSON`.

- [ ] **Step 4: Wait and check completion**

Poll every 30 seconds:

```
mcp__ruflo__memory_search({ namespace: "kc-discovery", query: "kc-discovery:done:" })
```

Expected: 21 hits. If after 10 minutes any slice is missing its done marker, retrieve `mcp__ruflo__agent_logs({ agentId: "kc-worker-<slice-id>" })`, diagnose, respawn just that worker. After two failed respawns, drop the slice and continue.

- [ ] **Step 5: Inspect raw counts**

```
mcp__ruflo__memory_list({ namespace: "kc-discovery" })
```

Expected: 1 schema + 1 manifest + 21 manifests + 21 done = 44 entries.

### Task 10: Queen dedup and human gate

- [ ] **Step 1: Spawn queen-dedup**

```
mcp__ruflo__agent_spawn({
  agentType: "coordinator",
  model: "sonnet",
  agentId: "kc-queen-dedup",
  task: <render prompts/queen-dedup.md>
})
```

- [ ] **Step 2: Wait for queen to print allocation report**

The report appears on stdout. Save it:

```
mcp__ruflo__agent_logs({ agentId: "kc-queen-dedup" }) > /tmp/kc-allocation-report.txt
```

- [ ] **Step 3: HUMAN GATE**

Operator reads the report. Checks:
- Total canonical pages between 80 and 220 (sanity range for a 21-slice full sweep).
- No slice has 0 owned pages (would mean its discovery went wrong).
- Top collapses look semantically correct (e.g. `document` and `es-document-mapping` collapsing or staying separate is a deliberate choice).
- Orphans list is reviewed; operator decides which to drop.

If edits are needed:

```
mcp__ruflo__memory_retrieve({ namespace: "kc-write", key: "assignments" })
# edit JSON locally
mcp__ruflo__memory_store({ namespace: "kc-write", key: "assignments", value: <edited>, upsert: true })
```

If unhappy entirely: STOP. Reset via the failure-recovery section of `RUN.md`.

---

## Phase D: Write

### Task 11: Full write phase

- [ ] **Step 1: Spawn 21 write workers in one parallel batch**

In ONE message with 21 tool calls, spawn each worker with the rendered `worker-write.md` prompt and `phase: "write"`.

- [ ] **Step 2: Poll for owned-slug completion**

```
mcp__ruflo__memory_search({ namespace: "kc-write", query: "kc-write:done:" })
```

Expected count: equal to the canonical page count from Task 10 step 4.

- [ ] **Step 3: Spot-check 3 written pages mid-run**

Pick 3 random slugs from the assignment map. For each:

```bash
cat /home/dev/Obsidian/Datashare/wiki/<type>/<slug>.md
```

Verify: frontmatter parses, H1 present, one-sentence definition, at least one file-path citation.

If any fail egregiously, halt the run; fix `prompts/worker-write.md`; respawn affected workers.

- [ ] **Step 4: Verify no writes outside wiki/**

```bash
cd /home/dev/Obsidian && git status
```

Expected: only `Datashare/wiki/` paths are dirty. `index.md`, `log.md`, `CLAUDE.md`, and other folders should be unchanged.

If any other path is dirty: `git -C /home/dev/Obsidian checkout -- <offending-path>`, investigate the offending worker via `agent_logs`, tighten the prompt, respawn.

---

## Phase E: Merge, lint, and finalize

### Task 12: Queen merge

- [ ] **Step 1: Spawn queen-merge**

```
mcp__ruflo__agent_spawn({
  agentType: "coordinator",
  model: "sonnet",
  agentId: "kc-queen-merge",
  task: <render prompts/queen-merge.md substituting TODAY=2026-05-23 and the three commit SHAs from slices.json>
})
```

- [ ] **Step 2: Inspect queen output**

```
mcp__ruflo__agent_logs({ agentId: "kc-queen-merge" })
```

Expected sections in the log:
- "Pass 1: applied <L> deltas to <P> pages."
- "Pass 2: rebuilt index.md (<E> entities, <C> concepts, <S> sources, <Y> syntheses)."
- "Pass 3: appended log entry."
- "Pass 4: lint report:" followed by orphans, dangling links, missing cross-refs, frontmatter issues.

- [ ] **Step 3: Verify guards passed**

```bash
cd /home/dev/Obsidian && git status
```

Expected dirty set: only under `Datashare/wiki/`, `Datashare/index.md`, `Datashare/log.md`, `Datashare/CLAUDE.md`. If anything else, queen failed its guard; investigate before continuing.

### Task 13: Quality sample

- [ ] **Step 1: Read 5 random pages**

```bash
ls /home/dev/Obsidian/Datashare/wiki/entities/*.md /home/dev/Obsidian/Datashare/wiki/concepts/*.md \
  | shuf | head -5 | xargs -I{} sh -c 'echo "=== {} ===" && cat {}'
```

- [ ] **Step 2: Verify for each sample page**

- H1 is the page title.
- The first prose line after H1 is a one-sentence definition.
- At least one repo-relative file-path citation per major claim.
- Frontmatter parses; `created` and `updated` are today; `sources` is non-empty.
- Every `[[slug]]` resolves to a real file in `wiki/`.

```bash
# Quick check: every [[link]] resolves
grep -rohE '\[\[([a-z0-9-]+)\]\]' /home/dev/Obsidian/Datashare/wiki/ \
  | sort -u | while read link; do
      slug=$(echo "$link" | sed 's/^\[\[//; s/\]\]$//')
      if ! find /home/dev/Obsidian/Datashare/wiki -name "${slug}.md" -print -quit | grep -q .; then
        echo "DANGLING: [[$slug]]"
      fi
    done
```

Expected: no DANGLING lines. If any, decide per slug whether to add a stub page or remove the link.

- [ ] **Step 3: Verify lint report from queen**

Re-read the queen output from Task 12 Step 2. Convert the "follow-up flagged" orphans into a TODO list for future ingests (the orphans are NOT auto-fixed).

### Task 14: Commit the vault

- [ ] **Step 1: Final git status**

```bash
cd /home/dev/Obsidian && git status
```

- [ ] **Step 2: Stage and commit the vault**

```bash
cd /home/dev/Obsidian
git add Datashare/
git commit -m "ingest: full-sweep KC populate via 21-worker RuFlo swarm

- 21 slices across datashare, datashare-client, datashare-docs
- <C> canonical pages: <E> entities, <C2> concepts, <S> sources, <Y> syntheses
- <L> cross-slice deltas applied during merge
- Source pins: datashare@<sha>, datashare-client@<sha>, datashare-docs@<sha>
"
```

Fill in actual counts from Task 12 Step 2.

---

## Acceptance criteria (from spec section 13)

After Task 14, verify all of:

- [ ] Every slice has a source page in `wiki/sources/`. Count: `ls /home/dev/Obsidian/Datashare/wiki/sources/*.md | wc -l` returns 21.
- [ ] Assignment map was reviewed at the Task 10 human gate.
- [ ] Every entity/concept slug in the assignment map exists as a file.
- [ ] `index.md` lists every page in the vault. No `wiki/` file is missing from the index.
- [ ] `log.md` has exactly one new entry, action = `ingest`, with real counts.
- [ ] Lint report shows zero dangling `[[links]]`.
- [ ] Project `CLAUDE.md` source-of-truth section is filled in (no more TBD).
- [ ] Vault `git status` shows changes only under `Datashare/wiki/`, `Datashare/index.md`, `Datashare/log.md`, `Datashare/CLAUDE.md`.

If any fail: do not declare done. Patch via targeted respawn or manual edit, then re-verify.

---

## Resolved open questions from spec section 15

- **Per-slice path globs:** Locked in `slices.json` Task 1.
- **Embeddings threshold:** 0.85 baseline, exposed in `slices.json` `dedup.embeddingsThreshold` for post-dry-run calibration.
- **OpenAPI fetch source:** Slice `openapi` declares a `fetch` block pointing at the GitHub Releases URL, downloaded to `scripts/kc-swarm/.cache/datashare_openapi.json` in Task 9 Step 3.
- **22nd slice for env/docker/packaging:** Folded into slice 20 (`overview`); no separate slice added. Keeps 21:21 worker-to-source-page mapping.

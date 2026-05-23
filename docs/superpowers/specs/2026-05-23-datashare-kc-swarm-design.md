---
title: Datashare Knowledge Corpus via RuFlo Swarm
date: 2026-05-23
status: design
author: pirhoo (with Claude)
---

# Datashare Knowledge Corpus via RuFlo Swarm

> A RuFlo swarm ingest that populates the existing Karpathy LLM-Wiki at `~/Obsidian/Datashare/` from three source repositories.

## 1. Goal

Build the user's personal working-memory knowledge corpus on Datashare by running a RuFlo swarm of ~21 parallel agents that ingest three repositories (datashare, datashare-client, datashare-docs) into the existing Obsidian vault at `/home/dev/Obsidian/Datashare/`. The vault is already scaffolded per Karpathy's LLM-Wiki pattern: `CLAUDE.md` schema, `index.md` catalog, `log.md` append-only log, `raw/`, and `wiki/{entities,concepts,sources,syntheses}/`.

**Out of scope:** ingesting GitHub issues / PRs / external articles; deepening pages beyond shallow first-pass coverage; modifying any non-`wiki/` file other than a one-line `CLAUDE.md` source-of-truth patch and the `index.md`/`log.md` updates owned by the schema; pushing to git.

**Success looks like:** ~100-200 markdown pages organized as entities / concepts / sources / syntheses, every page citing file paths in the source repos pinned at known commit SHAs, `index.md` listing every page, one `log.md` ingest entry, zero dangling wiki-links, no writes outside the vault.

## 2. Source repositories (pinned)

| Repo | Path | Commit |
|------|------|--------|
| datashare (backend) | `/home/dev/Repositories/datashare` | `abbdb5c5c` |
| datashare-client (frontend) | `/home/dev/Repositories/datashare-client` | `b323bd75c` |
| datashare-docs (user docs) | `/home/dev/Repositories/datashare-docs` | `d94877a1b` |

Each worker's source page records its repo + commit in frontmatter so the wiki is time-pinned and reproducible.

## 3. Why a swarm

Karpathy's pattern says "a single ingest typically touches 5-15 wiki pages." A full sweep of three repos would be one mega-ingest of ~100-200 pages and would not fit a single agent's context. Splitting into 21 slices (one per major area) lets each worker stay within Karpathy's 5-15-page envelope while the swarm covers the whole codebase. Parallel execution is incidental; the real reason for slicing is contextual tractability.

RuFlo MCP tools provide three primitives the design relies on:

- `swarm_init` + `agent_spawn`: hierarchical topology with a queen coordinator and 21 workers.
- `memory_store` + `embeddings_search`: persistent, semantically searchable shared state for the discover-then-write protocol.
- `hive_mind_init` (raft consensus): single-queen-owned merge phase that consolidates worker output.

## 4. Slicing (21 workers)

### Backend (datashare): 7 slices

| ID | Slice | Path | Expected page types |
|----|-------|------|---------------------|
| 1 | api-core | `datashare-api/` | Document, NamedEntity, Project, User, Tag, Pipeline interfaces |
| 2 | db-schema | `datashare-db/` | Liquibase migrations, jOOQ tables, BatchSearch storage |
| 3 | index-es | `datashare-index/` | ES mappings, Indexer, Searcher, Spewer, analyzers |
| 4 | app-rest | `datashare-app/src/main/java/.../web/` | 21 REST resources (DocumentResource etc.) |
| 5 | tasks | `datashare-tasks/` | PipelineTask framework, IndexTask, ExtractNlpTask, etc. |
| 6 | cli | `datashare-cli/` + `datashare-app/.../mode/` | CLI modes, stages, options |
| 7 | nlp | `datashare-nlp-corenlp/` | CoreNLP adapter, pipeline interface contract |

### Frontend (datashare-client): 7 slices

| ID | Slice | Path | Expected page types |
|----|-------|------|---------------------|
| 8 | client-api | `src/api/` | Api class, resources, ES browser client |
| 9 | client-stores | `src/store/modules/` | Pinia stores |
| 10 | client-views | `src/views/` + Router | Route map, page-level views |
| 11 | doc-viewer | `src/components/Document/`, `DocumentViewer/` | PDF/Image/Spreadsheet/Text viewers |
| 12 | search-ui | `src/components/Search/`, `Filter/` | Search box, facets, filters |
| 13 | insights | `src/components/Widget/` | Widget system |
| 14 | core-plugins | `src/core/`, mixins, `src/plugins/` | Core class, Hook system, plugin contract |

### Docs (datashare-docs): 5 slices

| ID | Slice | Path | Expected page types |
|----|-------|------|---------------------|
| 15 | docs-local | `local-mode/` | Install per OS, embedded ES |
| 16 | docs-server | `server-mode/` | Auth modes, OAuth, multi-user |
| 17 | docs-usage | `usage/` | Search, batch search, tags, NER UX |
| 18 | docs-dev | `developers/` | API, extensions, Tarentula CLI |
| 19 | docs-concepts | `concepts/` | Running modes, CLI stages, NER |

### Cross-cutting: 2 slices

| ID | Slice | Input | Expected output |
|----|-------|-------|---------------------|
| 20 | overview | 3 READMEs + top-level configs across all three repos | One source page (`source-overview`) and one synthesis (`project-datashare`) sketching how the three repos fit together |
| 21 | openapi | OpenAPI JSON from the latest release | One source page (`source-openapi`) and one synthesis (`rest-api-catalog`) indexing every endpoint with one-line summaries |

Each of the 21 slices produces exactly one source page; cross-cutting slices additionally produce one synthesis each. The 21:21 worker-to-source-page mapping in section 8 holds without exception.

## 5. Topology and primitives

Selected approach: **Two-phase hierarchical (discover then write, queen-merged)**. Rejected alternatives:

- *Single-phase with claims_board locking*: cheaper but page merging under contention is fragile.
- *Map-reduce with queen writing everything*: cleanest output but parallelism is partly cosmetic; queen becomes the bottleneck.

The two-phase approach makes write conflicts structurally impossible: every page has exactly one owner, decided before any write happens. Workers that touched another worker's page contribute deltas through `memory_store` rather than file writes.

### Swarm initialization

```
swarm_init({ topology: "hierarchical", maxAgents: 22, strategy: "specialized" })
hive_mind_init({ topology: "hierarchical", consensus: "raft" })
embeddings_init({ namespace: "kc-discovery" })
embeddings_init({ namespace: "kc-deltas" })
memory_store({ namespace: "kc-discovery", key: "schema", value: <vault CLAUDE.md text> })
memory_store({ namespace: "kc-discovery", key: "slice-manifest", value: <21 slices> })
```

### Memory namespaces

| Namespace | Purpose | Keys |
|-----------|---------|------|
| `kc-discovery` | Phase-1 candidate manifests | `kc-discovery:<slice-id>`, `kc-discovery:source:<slice-id>`, `kc-discovery:done:<slice-id>` |
| `kc-write` | Phase-2 assignments and progress | `kc-write:assignments`, `kc-write:done:<slug>` |
| `kc-deltas` | Cross-slice contributions | `kc-deltas:<slug>` (one per slug with deltas) |
| `kc-merge` | Queen progress | `kc-merge:phase` (deltas / index / log / lint / complete) |

## 6. Worker contract

### Spawn parameters

```
mcp__ruflo__agent_spawn({
  agentType: "researcher",
  model: "sonnet",
  agentId: "kc-worker-<slice-id>",
  task: "<slice description>",
  config: {
    phase: "discover" | "write",
    slice: { id, repo, paths[], readme_path },
    schema: <path to vault CLAUDE.md>
  }
})
```

### Tools allowed

- Read, Grep (read-only on the three source repos).
- `memory_store`, `memory_search` (only in the worker's permitted namespace).
- In write phase only: Write, Edit, restricted to paths under `/home/dev/Obsidian/Datashare/wiki/`.

### Tools forbidden

- Bash, WebFetch, Agent (no recursive spawning).
- Any write outside `/home/dev/Obsidian/Datashare/wiki/`.
- Any read or write of `index.md`, `log.md`, `raw/`, or the project `CLAUDE.md`.

### Hard rules (in the worker prompt)

- Frontmatter must match the schema in `/home/dev/Obsidian/CLAUDE.md` exactly.
- File naming: kebab-case, lowercase, no spaces, no numeric prefixes.
- Every page is one type only (entity / concept / source / synthesis). Mixed pages get split per the schema's splitting rule.
- Every claim about code cites a file path (relative to the repo root).
- Every other slug a page mentions becomes a `[[wiki-link]]`.
- No prose padding: a one-sentence definition under the H1, then sections scaled to topic complexity.

## 7. Phase 1: discovery

For each of the 21 workers, in parallel:

1. Read the slice's READMEs and top-level files (skim, not full text).
2. Walk the slice's main paths and group what's there by candidate page topic.
3. For each candidate, classify as entity (specific named thing) or concept (pattern / principle) per the schema.
4. Cap at 15 candidates per worker. Drop low-signal items (one-off DTOs, single-use utility classes).
5. Emit the manifest to `memory_store["kc-discovery:<slice-id>"]`:

```json
{
  "candidates": [
    {
      "slug": "es-document-mapping",
      "type": "entity",
      "summary": "Elasticsearch mapping for the parent Document type.",
      "evidence_paths": ["datashare-index/src/main/resources/datashare-index-mappings.json"]
    }
  ],
  "source_page": {
    "slug": "source-datashare-index",
    "summary": "..."
  }
}
```

6. Mark done via `memory_store["kc-discovery:done:<slice-id>"] = true`.

### Queen dedup (after all 21 done)

```
all = memory_search({ namespace: "kc-discovery", query: "*" })

For each candidate:
  similar = embeddings_search(candidate.summary, threshold 0.85, namespace "kc-discovery")
  If similar across workers:
    canonical_slug = shortest / most general
    owner          = worker with most evidence file paths
    aliases        = collapsed slugs
    contributors   = all proposing workers
  Else:
    owner          = proposing worker
    contributors   = [owner]
```

The result is the **assignment map**, written to `memory_store["kc-write:assignments"]`:

```json
{
  "es-document-mapping": {
    "type": "entity",
    "owner": "kc-worker-index-es",
    "contributors": ["kc-worker-app-rest", "kc-worker-client-api"],
    "aliases": ["document-es-schema", "document-mapping"],
    "summary": "...",
    "evidence_paths": ["..."]
  }
}
```

### Human eyeball gate

Before the write phase fires, the queen prints an allocation report:
- Total canonical pages.
- Pages per worker (load balance check).
- Slugs that collapsed (with what they collapsed into).
- Orphans (single-worker thin-evidence proposals) flagged for review or drop.

The user (pirhoo) confirms or edits the assignment map. This is the only synchronous gate in the run; everything else is autonomous.

## 8. Phase 2: write

For each of the 21 workers, in parallel, with a fresh prompt:

Inputs: own slice + the full assignment map filtered to owned + contributor slugs.

For each **owned** slug:

1. Read the slice files in depth for that topic.
2. Write `wiki/<type>/<slug>.md` with frontmatter matching the schema exactly:

```yaml
---
type: entity | concept | synthesis
aliases: [<from assignment map>]
tags: [<self-derived, kebab-case>]
created: 2026-05-23
updated: 2026-05-23
sources: [<source-page-slugs cited>]
---
```

3. Body: one-sentence H1 definition, then sections, then inline file-path citations, then `[[wiki-links]]` to every other slug mentioned.
4. Mark done via `memory_store["kc-write:done:<slug>"] = true`.

For each **contributor** slug (owned by another worker):

1. Read this slice's files relevant to that slug.
2. Emit a delta to `memory_store["kc-deltas:<slug>"]`:

```json
{
  "from_slice": "kc-worker-app-rest",
  "source_page": "source-datashare-app",
  "paragraph": "<2-4 sentences>",
  "file_citations": ["..."],
  "merge_mode": "append"
}
```

3. Never reads or writes the contributor's page directly.

For the **source page** (`wiki/sources/source-<slice-id>.md`):

- Each worker writes its own.
- One source page per slice (not per repo), keeping a clean 21:21 worker-to-source-page mapping.
- Frontmatter includes `raw: <repo path>@<commit-sha>` for time pinning.

### Concurrency invariants

- Every `.md` file is written by exactly one worker. Enforced structurally: ownership is in the assignment map, and contributors only emit deltas.
- `index.md` and `log.md` are never touched by workers.
- Deltas accumulate in `memory_store`, not on disk.

## 9. Phase 3: merge (queen alone, no workers)

### Pass 1: apply deltas

```
For each slug in assignment map:
  page   = read wiki/<type>/<slug>.md
  deltas = memory_search({ namespace: "kc-deltas", prefix: "kc-deltas:<slug>" })
  If deltas:
    append "## See also from <slice>" sections (one per delta) or, when merge_mode == "inline", invoke a small LLM call to merge into prose
    union source_page slugs into the page's `sources:` frontmatter
    bump `updated:`
    write back
```

The default merge mode is the mechanical append path. Inline merging is opt-in per delta and costs one extra LLM call per page that opts in.

### Pass 2: build `index.md` from scratch

```
inventory = scan wiki/{entities,concepts,sources,syntheses}/*.md
For each page: read frontmatter (type, aliases) and the first prose line after H1.
Group entities and concepts by sub-category inferred from tags and slug prefixes.
Sort sources alphabetically. Sort syntheses by created date.
Write index.md per the schema's exact format. Bump `updated:`.
```

### Pass 3: append `log.md`

One single ingest entry. The letters below (`N`, `M`, `K`, `L`, `E`, `C`, `S`, `Y`) are runtime placeholders filled in by the queen from actual counts at merge time. The template itself is part of the spec:

```markdown
## [2026-05-23] ingest | datashare/datashare-client/datashare-docs full sweep

- Spawned 21-worker RuFlo swarm (hierarchical, raft consensus) for full-sweep shallow ingest.
- Sliced sources: 7 backend modules, 7 frontend areas, 5 docs sections, 2 cross-cutting.
- Discovery phase: 21 workers proposed N candidates; dedup collapsed M slugs across workers.
- Write phase: 21 workers produced K owned pages, L deltas.
- Merge phase: applied L deltas, rebuilt index.md (E entities, C concepts, S sources, Y syntheses).
- Source pins: each source page references its repo at commit SHA.
- Follow-up flagged: <low-evidence orphans for human review>.
```

### Pass 4: lint (read-only, reports only)

- Orphans (entity/concept pages with 0 inbound links).
- Dangling `[[links]]` (slugs without files).
- Missing cross-refs (slugs co-occurring in 3+ pages without `[[…]]`).
- Frontmatter validation (required fields, parseable YAML).

Findings printed to console. No auto-fix.

### One-line patch to project `CLAUDE.md`

The TBD source-of-truth section in `/home/dev/Obsidian/Datashare/CLAUDE.md` is filled in with the three repo paths and pinned commit SHAs. This is the only edit outside `wiki/`, `index.md`, and `log.md`.

## 10. Resumability and failure handling

Checkpoints:

- `kc-discovery:done:<slice-id>` per worker.
- `kc-write:assignments` once, after dedup.
- `kc-write:done:<slug>` per page.
- `kc-merge:phase` (deltas / index / log / lint / complete).

Restart: queen reads checkpoints, only spawns workers for slices / slugs missing their done key.

Failure modes:

| Failure | Detection | Response |
|---------|-----------|----------|
| Worker dies during discovery | `kc-discovery:done:<id>` never lands after 10 min | Queen respawns once; if second attempt fails, run dedup without it and log the dropped slice |
| Worker dies during write | Missing `kc-write:done:<slug>` for owned slugs | Respawn with same slice + missing-slug subset |
| Malformed frontmatter | Lint phase parse error | Flag in report, leave file, manual fix |
| Write outside `wiki/` | Lint scans `git status` against the vault | Refuse to write `log.md` entry, halt run |

## 11. Observability

- `swarm_status` polled by queen every 30s.
- `agent_logs` per worker, surfaced on failure.
- Live scratch file at `/home/dev/Obsidian/Datashare/.obsidian/.swarm/kc-run.md` (gitignored): phase, workers running, candidates, pages written, ETA. `tail -f`-able from another shell.

## 12. Cost ceiling

- Per worker (discovery): ~30k input tokens / ~5k output tokens.
- Per worker (write): ~30k input tokens / ~10k output tokens.
- Queen (merge): ~50k input tokens / ~5k output tokens.
- Total budget: ~21 × (60k input + 15k output) + queen ≈ ~1.3M input + ~320k output, plus optional inline-merge LLM calls.

Hard ceiling configured at the swarm level. Queen halts and dumps progress if overrun.

## 13. Acceptance criteria

1. Every slice has produced a source page in `wiki/sources/`.
2. The assignment map was reviewed (the human gate) before write phase.
3. Every entity/concept slug in the assignment map exists as a file.
4. `index.md` lists every page in the vault. No `wiki/` file is missing from the index.
5. `log.md` has exactly one new entry, action = `ingest`, with real counts.
6. Lint report shows zero dangling `[[links]]`.
7. Project `CLAUDE.md` source-of-truth section is filled in.
8. `git status` inside the vault shows changes only under `Datashare/wiki/`, `Datashare/index.md`, `Datashare/log.md`, `Datashare/CLAUDE.md`.

Quality sample (5 random pages):

- H1 is the page title, kebab-case-derived.
- One-sentence definition follows H1.
- At least one file-path citation per major claim.
- Frontmatter parses, dates are today, `sources:` is non-empty.
- Every `[[slug]]` resolves to a real file.

If any sample fails, the run is not done; patch via targeted respawn or manual edit.

## 14. Out of scope (explicit)

- Deepening any single page beyond shallow first-pass treatment.
- Ingesting GitHub issues, PRs, external articles.
- Touching other vault projects (`Blueprint Chart/`, `Clippings/`, `Private/`).
- Automated tests for the swarm (this is a one-off run, not a recurring pipeline).
- Pushing to the vault's git remote. Queen leaves changes uncommitted for the user to review.

## 15. Open questions for the implementation plan

- Which exact path globs go into each slice's `paths[]` (drill-down done during the writing-plans skill).
- Whether the `embeddings_search` threshold (0.85) is right for code-derived summaries; may need calibration on a dry run.
- Whether the OpenAPI slice (21) downloads the JSON from the GitHub release or reads a locally-cached copy.
- Whether to add a 22nd "config/env" slice for `.env*`, `docker-compose`, packaging, etc., or fold it into slice 20 (overview).

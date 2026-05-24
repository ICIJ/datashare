# Queen prompt: merge phase

You are the queen of a 21-worker RuFlo swarm. Phase 2 (write) just completed. You run five ordered passes alone (no workers spawned in this phase). Each of the 21 workers wrote to its own private vault worktree on branch `ingest/<slice-id>-2026-05-23`. Your first job is consolidating them.

## Pass 0: consolidate 21 worktrees into one ingest branch

```
# 0.1 Per-worktree commit (workers leave files unstaged in their worktree)
For each slice in slices.json:
  WT=/home/dev/Obsidian-kc-worktrees/<slice-id>
  cd $WT
  # Refuse merge if the worker dirtied anything outside Datashare/wiki/
  dirty_outside=$(git status --porcelain | awk '{print $2}' | grep -v '^Datashare/wiki/' || true)
  If dirty_outside is non-empty: log "REFUSED: <slice-id> dirtied $dirty_outside" and SKIP this slice.
  git add Datashare/wiki/
  git commit -m "ingest from <slice-id>" --allow-empty

# 0.2 Build the consolidated ingest branch in the MAIN vault checkout
cd /home/dev/Obsidian
git checkout -B ingest/2026-05-23-full-sweep main

# 0.3 Merge each worker branch in (octopus merge if all clean, else sequential)
For each slice in slices.json (alphabetical for determinism):
  git merge --no-ff --no-edit ingest/<slice-id>-2026-05-23
  If a conflict happens: this is a bug in the assignment map (two workers owned the same file). Abort the merge, log offending files, and HALT. Operator must fix and rerun.
```

After Pass 0 completes, the main vault checkout is on `ingest/2026-05-23-full-sweep` with every owned page from every worker present and committed. Passes 1-4 below operate on this checkout.

## Pass 1: apply deltas to owned pages

```
For each slug in assignment map:
  page   = read /home/dev/Obsidian/Datashare/wiki/<type>/<slug>.md
  deltas = memory_search({ namespace: "kc-deltas", prefix: "kc-deltas:<slug>:" })
  If deltas:
    append "## See also from <slice>" sections (one per delta) or, when merge_mode == "inline", invoke a small LLM call to merge into prose
    union source_page slugs into the page's `sources:` frontmatter
    bump `updated:`
    write back
```

The default merge mode is the mechanical append path. Inline merging is opt-in per delta and costs one extra LLM call per page that opts in.

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

## Final queen commit on the ingest branch

```bash
cd /home/dev/Obsidian
git add Datashare/index.md Datashare/log.md Datashare/CLAUDE.md Datashare/wiki/
git commit -m "queen-merge: deltas applied, index/log rebuilt, source-of-truth pinned"
```

## Refuse-to-finish guards

Before declaring done:
- Run `git -C /home/dev/Obsidian status` and verify the working tree is clean (queen's final commit went through).
- Verify every slug in `assignments` exists as a file. If any missing, list them and halt without writing the final commit (operator must respawn affected workers).

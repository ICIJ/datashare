# KC Swarm Runbook

Operator (a human running an MCP-enabled Claude session) follows these steps in order. Each step has explicit MCP calls and expected outputs.

## Prerequisites

- All three repos cloned at the pinned commits in `scripts/kc-swarm/slices.json`.
- Vault at `/home/dev/Obsidian/Datashare` is at a clean git state (no uncommitted changes), on branch `main`.
- `mcp__ruflo__*` tools are available in your MCP client.
- `./scripts/kc-swarm/validate.sh` exits 0.
- Orchestration worktree exists at `.worktrees/kc-swarm` on branch `kc-swarm/2026-05-23` (this runbook is read from there).

## Worktree pattern (dual)

- **Orchestration worktree:** `.worktrees/kc-swarm` (this repo). All plan artifacts and the runbook live here.
- **Vault worktrees (per worker):** `/home/dev/Obsidian-kc-worktrees/<slice-id>`, branch `ingest/<slice-id>-2026-05-23`. Created in Phase D step 8.5, destroyed in Phase E cleanup.

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

8.5. Create the 21 vault worktrees on a clean main:

```bash
cd /home/dev/Obsidian && git status   # expect clean
mkdir -p /home/dev/Obsidian-kc-worktrees
VAULT_BASE=$(git -C /home/dev/Obsidian rev-parse HEAD)
for slice in $(jq -r '.slices[].id' scripts/kc-swarm/slices.json); do
  git -C /home/dev/Obsidian worktree add \
    "/home/dev/Obsidian-kc-worktrees/$slice" \
    -b "ingest/$slice-2026-05-23" "$VAULT_BASE"
done
```

9. Spawn 21 write workers in parallel. Same shape as step 4 but with `prompts/worker-write.md` and `phase: "write"`. Each worker spawn passes `WORKTREE_PATH=/home/dev/Obsidian-kc-worktrees/<slice-id>` and reads `memory_retrieve("kc-write:assignments")` filtered to its slice.

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
- Pass 0: 21 worker commits merged into `ingest/2026-05-23-full-sweep`.
- Lint report on console.
- Vault git status should be clean on `ingest/2026-05-23-full-sweep` (queen committed all changes).

13. Quality sample (operator):
```
ls /home/dev/Obsidian/Datashare/wiki/entities/*.md | shuf | head -5 | xargs -I{} sh -c 'echo "=== {} ===" && cat {}'
```

Check each: H1 + one-sentence definition + at least one citation + frontmatter parses + all `[[links]]` resolve.

14. If quality is acceptable, merge the ingest branch to main:

```bash
cd /home/dev/Obsidian
git checkout main
git merge --no-ff ingest/2026-05-23-full-sweep -m "ingest: full-sweep KC populate via 21-worker RuFlo swarm"
```

15. Cleanup: remove the 21 vault worktrees and their merged branches; merge the orchestration branch back to datashare main and remove the orchestration worktree. See plan Task 15 for the exact commands.

## Failure recovery

- Any worker failed in discovery: rerun step 4 with only the failed slice ids (queen reads existing done markers and skips).
- Worker failed in write: rerun step 9 with the same trick (the same vault worktree is reused; partial writes are overwritten).
- Queen merge halted on Pass 0 conflict: this means the assignment map gave two workers the same file. Re-inspect the assignment map, fix the duplicate ownership, respawn the affected workers, rerun queen merge.
- Want a clean restart: delete the relevant `memory_store` namespaces (`kc-discovery`, `kc-write`, `kc-deltas`, `kc-merge`), remove the 21 vault worktrees with `git worktree remove`, and delete the `ingest/*-2026-05-23` branches.

## Cost ceiling reminder

Hard budget configured in slices.json `limits`. Queen halts and dumps progress if overrun. To resume after a cost halt: bump the limits and re-call the merge phase.

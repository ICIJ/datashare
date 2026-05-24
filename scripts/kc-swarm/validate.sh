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

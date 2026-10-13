# SDD ledger — plan: docs/superpowers/plans/2026-08-13-markdown-source-structure.md

Spec: docs/superpowers/specs/2026-08-13-markdown-source-structure-design.md (read, binding authority)
Worktree: .worktrees/markdown-structure on fix/structure-markdown-source
BASE (branch start): 378961456

## Baseline

`mvn -pl datashare-index -am test` before any change: 291 run, 1 failure.
Failure is `ExtensionLoaderTest.test_load_jars` in datashare-api (expects an unreadable jar to be
skipped; the container runs as root so both jars are readable). Pre-existing and environmental, not
in a module this plan touches. Every task's "expected PASS" is against this baseline.

## Pre-flight conflict scan

Cross-task rows (every pair sharing a file or an interface):

| Pair | Produces / consumes | Finding |
|---|---|---|
| T1 → T2 | T1 produces `MARKDOWN_OPTIONS` with `List.of(TablesExtension.create())`; T2 replaces it with the two-extension list | Agrees. T2 restates the whole assignment, no partial edit. |
| T1 → T2 | T1 leaves `SAFELIST` untouched; T2 adds `.addTags("del")` | Agrees. |
| T2 → T3 | T3's `SAFELIST` snippet includes T2's `.addTags("del")` plus `.addAttributes("code", "class")` | Agrees, and T3 states what to write if T2 was reverted. |
| T1 → T3 | T1's `test_a_fenced_code_block_keeps_its_content_including_html` asserts md contains `ls -l` and the literal script text; T3 changes indented block to fence | No conflict: both assertions hold under a fence. |
| T1 → T4 | T1's `test_markdown_lists_and_links_survive_without_escaping` asserts md contains `item one`; T4 changes the list marker | No conflict: T1 asserts the item text, never the marker. |
| T4 → StructureArtifactTest | T4 changes every unordered list marker; `test_the_stored_page_set_is_pinned:115` pins `* one\n* two` | Real, and the plan already carries the replacement line in T4 Step 5. |
| T1, T2 → poms | Both add entries to the same two blocks | Agrees: T2 appends beside T1's entries. |

Per-task self-agreement rows:

| Task | Tests vs code it specifies | Finding |
|---|---|---|
| T1 | 8 tests against the `extract` branch, `toXhtml(InputStream, Metadata)`, `isMarkdown`, `parseAsMarkdown` | Agrees. Helper `extract(InputStream, String)` used by test 7 exists at test:47; `extractor.extract(3-arg)` is unchanged public API. |
| T2 | `<del>` in xhtml and `~~` in md, against extension + safelist tag | Agrees, with a named fallback (`EXT_INLINE_DEL`) and a revert instruction. |
| T3 | `class="language-bash"` in xhtml and a fence in md, against one safelist attribute | Agrees, verify-or-drop stated. |
| T4 | md contains `- one`, excludes `* one`, against `UNORDERED_LIST_DELIMITER` | Agrees. |

Scan clean: no ruling needed before Task 1. The only cross-task collision (T4 vs the golden-bytes
test) is already handled in the plan's own text.

## Progress

Task 1: complete (commits 3789614..0777170, review clean, spec ✅)
Task 1: ⚠️ resolved by controller — commit message is a bare subject line, no co-author or AI
  trailer; `git show --stat` confirms only the four planned files, nothing from the git-ignored
  workspace dirs.
Task 1: minor (deferred): no test drives a generic/mismatched content-type hint through the
  detection path, so "an embedded .md keeps routing correctly" is verified structurally, not by test.
Task 1: minor (deferred): only `text/x-web-markdown` of the three MARKDOWN_TYPES aliases is exercised.

Task 2: review returned one Important (plan-mandated) finding: that `flexmark-ext-tables` and
  `flexmark-ext-gfm-strikethrough` are NOT transitively on the classpath, so declaring them downloads
  new artifacts and both poms' comments ("same transitive-but-used case") are inaccurate.
Task 2: Ruling: finding rejected, no code change. The reviewer read only
  `flexmark-html2md-converter`'s own pom and the base `flexmark` pom, and missed the resolution path
  that actually supplies both artifacts: html2md-converter -> flexmark-ext-emoji ->
  flexmark-jira-converter -> {flexmark-ext-tables, flexmark-ext-gfm-strikethrough}, all compile
  scope. Evidence: `mvn -pl datashare-index dependency:tree -Dincludes=com.vladsch.flexmark` run on
  `main` before any pom change listed both under flexmark-jira-converter; the same command now lists
  them as direct deps and the jira-converter subtree has shrunk to wikilink/ins/superscript,
  which is Maven mediation reattributing an already-resolved artifact, not a new download. The
  comments are accurate as written. Cost if wrong: two pom entries would pull ~100KB of jars that a
  clean CI cache does not already fetch, which no test or runtime behaviour depends on.
Task 2: ⚠️ resolved by controller — the safelist comment's claim that the client's sanitizer allows
  `<del>` holds: `hast-util-sanitize/lib/schema.js:158` lists `del` in the GitHub default schema, and
  datashare-client's `markdown.js:12` removes only img/picture/source/svg/video/audio/iframe/object/embed
  from that clone.
Task 2: complete (commits 0777170..bf6fa0f, 1 Important parked with ruling)

Task 3: complete (commits bf6fa0f..d86f5ed, review clean, spec ✅). Verify-or-drop resolved as
  KEPT: the converter does re-emit a fence from the preserved `class`, shown by RED failing on the
  xhtml assertion and GREEN passing both.
Task 3: ⚠️ resolved by controller — `git log --format=%B` over all three commits shows bare subject
  lines, no body, no co-author or AI-attribution trailer.

Task 4: complete (commits d86f5ed..171ed9f, review clean, spec ✅). Golden-bytes expectation moved
  only on the list marker, confirmed from the diff hunk.
Task 4: minor (deferred): test name `test_unordered_lists_keep_the_dash_marker_a_markdown_source_used`
  is grammatically malformed (came from the plan, not the implementer).

## Final whole-branch review (opus)

Verdict: merge with fixes. One Important, five Minor. It independently confirmed the Task 2 ruling
(the flexmark transitive chain) by reading each pom in the hop chain, and probed the sanitization
argument against constructs no test covers (script in table cells and list items, javascript:/data:/
vbscript: hrefs, iframes, HTML comments, entity smuggling, autolinks): all neutralized.

Important: `.addAttributes("code", "class")` from Task 3 is an unsanitized text channel. jsoup cannot
filter attribute values, and html2md writes the class verbatim into the fence info string, so a class
value carrying a newline plus a closing fence terminates the fence early and drops the rest into the
payload as a raw HTML block. Newly introduced by d86f5ed, applies to every document type, and defeats
the spec's binding rule that the payload cannot rely on one consumer's renderer for its safety. The
same raw newline also lands in an XML attribute in the xhtml, where normalization collapses it, so
the two formats disagree about content.

Ruling: fix rather than revert Task 3. Keeping the fence language is a real fidelity win for exactly
the documents this branch targets, the fix is a ~10-line pre-pass in `sanitize()` that also closes the
xhtml/md divergence, and reverting would lose the language in both formats. Cost if wrong: a slightly
larger safelist surface than a revert would leave, guarded by a regression test.

Ruling: also fix the `hr` loss (review Minor 2), which the spec never considered. A thematic break
`---` is dropped entirely because `Safelist.relaxed()` has no `hr`. It is the same case the spec
already decided for `<del>`: formatting-only, no attributes, no active content, and common in real
READMEs. Cost if wrong: one extra safelist tag that no document needed.

Ruling: the spec's image bullet is wrong and will be corrected in the design doc (controller edit, the
doc is git-ignored and uncommitted). Images do not merely lose their URL in the `.md`, they vanish
entirely, which is a regression against `main` for Markdown sources, where the image at least survived
as escaped text. Cost if wrong: a design doc that overstates a loss nobody reads.


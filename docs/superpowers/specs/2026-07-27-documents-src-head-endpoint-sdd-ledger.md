# SDD ledger — plan: docs/superpowers/plans/2026-07-27-documents-src-head-endpoint.md
BASE (branch start) = f3feeb897
baseline: 85 tests green (DocumentVerifierTest 4, DocumentResourceTest 63, SourceExtractorTest 18)
Task 1: dispatched (implementer task1-impl, haiku, BASE f3feeb897)
Task 1: complete (commits f3feeb897..2d26a8b08, review clean)
Task 1: minor (deferred): method comment is 4 lines, consistent with neighbouring useOcr comment
Task 1: minor (deferred): test_has_cached_embedded_source_false_when_dir_exists_without_raw_file comment says 'manifest-only state' but creates only the dir, no manifest file
Task 2: dispatched (implementer task2-impl, haiku, BASE 2d26a8b08)
Task 2: complete (commits 2d26a8b08..dd468608f, review clean)
Task 2: minor (deferred): isRootDocumentSizeAllowed javadoc still describes only the size check, not the artifact bypass
Task 3: dispatched (implementer task3-impl, sonnet, BASE dd468608f)
spec corrected: fluent-http DOES fall HEAD back onto @Get routes (RouteWithPattern.matchMethod); my earlier 'no implicit HEAD-on-GET' claim was wrong. Pre-change, HEAD on the src route ran full extraction. New route wins by param count (3<4), pinned by the cached-artifact test.
Task 3: fix round 1/5 dispatched (2 Important: undocumented route precedence, invisible extraction guard) - resumed task3-impl, FIX_BASE c8a58c9d9
spec corrected (2): HEAD does send Content-Length: 0 from PayloadWriter's null-content branch; the rule is about index-derived values, and ok() cannot avoid it. Reviewer's non-blocking warning, resolved in doc not code.
Task 3: fix round 1/5 (2 addressed, 0 open; commits c8a58c9d9..0564c164b)
Task 3: complete (commits dd468608f..0564c164b, review clean)
Task 3: minor (deferred): report phrasing credits ok() rather than PayloadWriter for Content-Length: 0 (report text only, no code impact)
final review: READY TO MERGE, 0 critical, 0 important, 5 minor, nothing must-fix (see final-review.md)
final review: all 4 ledger minors triaged fine-to-leave
final fix wave dispatched (final-fix, sonnet, FIX_BASE 0564c164b): M1 raw.json sidecar in the cache predicate (verified in extract-lib source: missing sidecar => live root parse), M2 GET 404 assertion, javadoc on the widened gate
final fix wave: commit ab438cd25 (5 files), SourceExtractorTest 22/22, DocumentVerifierTest 7/7, DocumentResourceTest 69/69 - verified from surefire
spec corrected (3): cache-hit condition needs raw AND raw.json; the 'no partial-file window' claim only ever held for the payload
final fix wave re-review: all 3 ADDRESSED, no new breakage (see final-fix-rereview.md)
deferred after triage (none must-fix): task1 'manifest-only state' comment wording; 4-line comments on hasCachedEmbeddedSource; no test for the inverse partial write (raw.json without raw)
carry to the frontend follow-up: isRootTooBig should treat ANY non-200 from HEAD as cannot-download, not switch on 413, so 403/404 don't read as 'too big'

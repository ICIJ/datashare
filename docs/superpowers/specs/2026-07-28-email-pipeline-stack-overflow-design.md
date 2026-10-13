# EMAIL pipeline StackOverflowError (issue #2290)

## Problem

`ExtractNlpTask` logs `java.lang.StackOverflowError` with a `java.util.regex.Pattern`
stack (`GroupTail.match`, `Loop.match`, `Branch.match`, `BranchConn.match`) a few
milliseconds after starting EMAIL extraction on some documents. The document gets no
named entities. The outer loop catches `Throwable`, so the task survives, but the log
line (`error in ExtractNlpTask loop`) does not name the document, which makes the
failure impossible to reproduce from production logs.

## Root cause

`EmailPipeline.pattern` (`datashare-app/src/main/java/org/icij/datashare/nlp/EmailPipeline.java`)
contains three unbounded repetitions applied to *groups*:

| construct | position | content that triggers it |
| --- | --- | --- |
| `(?:\.[local-chars]+)*` | local part | `a.a.a.a.…` |
| `(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+` | domain | `user@a.a.a.a.…` |
| `(?:[chars]\|\\[chars])*` | quoted local part | unterminated `"` then a long run |

Java's `java.util.regex` matches a group repetition by recursion, one frame set per
iteration. Stack depth therefore grows linearly with the number of iterations, and a few
thousand iterations exhaust the thread stack. Measured on this JDK against the exact
production pattern: the local-part and domain loops overflow at roughly 5 000 iterations,
the quoted-local loop at roughly 5 000 characters, and the failing frame is
`Pattern$GroupTail.match(Pattern.java:5000)`, matching the frames in the issue.

This is not about total content size. One megabyte of ordinary prose is fine; a few
thousand dot-separated tokens is fatal, which is consistent with the 3 ms delay between
the "extracting EMAIL entities" line and the error.

### Second defect found while probing

The same pattern is quadratic in time on a long unbroken run of local-part characters
(letters, digits, and the RFC specials) that contains no `@`. Every `find()` restart
rescans the remainder of the run. Measured today: 200 000 characters take **246 seconds**.
The `maxContentLength` chunk default is 1 MB, so a single document can hold a CPU for
well over an hour. Fixing only the overflow would convert some 3 ms failures into that
hang, so both defects are addressed together.

## Fix

### 1. Bound every group repetition, and anchor the local part

```
local part  : (?<![local-chars.]) [local-chars]+ (?:\.[local-chars]+){0,63}
quoted      : " (?:[qchars]|\\[qchars]){0,255} "
domain      : (?:label\.){1,63} label
ip literal  : (?:[ipchars]|\\[ipchars]){1,255}
```

Bounds come from RFC 5321: 64 characters for a local part, 253 for a domain name. No
address that can actually be delivered has more than 63 dot-separated labels on either
side, so nothing real is lost, and recursion depth is capped at a constant.

The lookbehind removes the quadratic scan: a match attempt that starts in the middle of a
local-part run fails in constant time instead of rescanning to the end of the run. It is
applied to the local-part alternative only, leaving the quoted alternative's start
semantics untouched. Its character class is the local-part class plus `.`; `@` is
deliberately excluded so that `user@bad@x.co.uk` still yields `bad@x.co.uk` as it does
today.

The local-part character class appears three times after this change (lookbehind, first
run, dot group), so it becomes a single `LOCAL_PART_CHARS` constant instead of being
written out three times. The repetition bounds are named constants with the RFC as their
justification. The rest of the pattern stays a literal.

### 2. Name the document when a regex blows the stack

`ExtractNlpTask.findNamedEntities` gains a `catch (StackOverflowError e)` beside its
existing `catch (IOException e)`, logging the queue entry. Behaviour is unchanged: the
document is skipped and the task keeps consuming its queue, exactly as the outer
`catch (Throwable)` already does. The gain is diagnostic, so the next occurrence of this
class of bug arrives with a document reference attached.

## Verification

Measured with the production pattern versus the fixed pattern:

| input | current | fixed |
| --- | --- | --- |
| 100 000 dot-separated labels in content | overflow, 2 ms | 15 ms |
| `user@` plus 100 000 dot-separated labels | overflow, 1 ms | 8 ms |
| unterminated quote, 200 000 characters | overflow, 2 ms | 10 ms |
| 1 000 000 unbroken local-part characters | ~1.5 h (246 s at 200 000) | 27 ms |
| `email.eml` test resource | 10 matches | identical matches and offsets |
| dotted local, IP literal, accented prefix, angle brackets, double `@`, mixed case | | identical |

## Tests

New tests in `EmailPipelineTest`, each failing before the fix:

1. content with 100 000 dot-separated labels does not overflow the stack
2. domain with 100 000 dot-separated labels does not overflow the stack
3. unterminated quoted local part followed by a long run does not overflow the stack
4. 1 MB unbroken character run ending in a real address still yields that address, under
   `@Test(timeout = 10_000)` so the quadratic scan cannot come back unnoticed

New test in `ExtractNlpTaskTest`: a pipeline that throws `StackOverflowError` does not
propagate out of `findNamedEntities` and does not reach the indexer. The log line itself is
not asserted, since capturing an appender would cost more than the line is worth.

Existing `EmailPipelineTest` assertions, including the `test_acceptance` offsets over
`email.eml`, stay unchanged.

## Out of scope

Replacing the regex with a parser or a validation library, and capping content length
before extraction. Both are larger behaviour changes and neither is needed once the
repetitions are bounded and the scan is anchored.

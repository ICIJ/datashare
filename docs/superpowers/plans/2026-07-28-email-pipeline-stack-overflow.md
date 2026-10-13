# EMAIL pipeline StackOverflowError Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop `EmailPipeline`'s address regex from overflowing the stack (and from scanning quadratically) on adversarial document content, and name the offending document when a regex does blow the stack.

**Architecture:** Two independent changes. First, `EmailPipeline.pattern` gets bounded group repetitions (Java's `java.util.regex` recurses once per group iteration, so unbounded `*`/`+` over a group makes stack depth a function of input) plus a lookbehind that stops `find()` from restarting inside a local-part run. Second, `ExtractNlpTask.findNamedEntities` catches `StackOverflowError` and logs the queue entry, so the next occurrence is diagnosable.

**Tech Stack:** Java 17, JUnit 4, Mockito, fest-assert, Maven.

## Global Constraints

- Repetition bounds are RFC 5321 derived: 63 dot-separated labels per side, 255 characters inside a quoted local part or an IP literal tail. Do not tighten them further.
- The lookbehind character class is the local-part class plus `.`, and must **not** contain `@`: `user@bad@x.co.uk` has to keep yielding `bad@x.co.uk`.
- The lookbehind goes on the local-part alternative only, never in front of the whole alternation, so the quoted-local branch keeps its current start semantics.
- Existing `EmailPipelineTest` assertions must not be edited, `test_acceptance`'s offsets over `email.eml` included.
- Test command (this repo needs the extra flag or an unmatched `-Dtest` silently passes):
  `mvn -pl datashare-app -am test -Dtest=EmailPipelineTest -Dsurefire.failIfNoSpecifiedTests=false`
  Multiple classes are comma separated. Never drop `-am`.

---

### Task 1: Bound the regex repetitions and anchor the local part

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/nlp/EmailPipeline.java:51-57`
- Test: `datashare-app/src/test/java/org/icij/datashare/nlp/EmailPipelineTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `EmailPipeline.pattern` keeps its current type (`final Pattern`) and package-private visibility, and `process(Document)` / `process(Document, int, int)` keep their signatures. Task 2 depends on neither.

- [ ] **Step 1: Write the four failing tests**

Append to `EmailPipelineTest`:

```java
    @Test(timeout = 10_000)
    public void test_long_dotted_local_part_does_not_overflow_the_stack() {
        String content = "hello " + "a.".repeat(100_000) + "b end";

        List<NamedEntity> annotations = pipeline.process(createDocument(content, "docId", Language.ENGLISH));

        assertThat(annotations).isEmpty();
    }

    @Test(timeout = 10_000)
    public void test_long_dotted_domain_does_not_overflow_the_stack() {
        String content = "user@" + "a.".repeat(100_000) + "b end";

        List<NamedEntity> annotations = pipeline.process(createDocument(content, "docId", Language.ENGLISH));

        assertThat(annotations).hasSize(1);
    }

    @Test(timeout = 10_000)
    public void test_unterminated_quoted_local_part_does_not_overflow_the_stack() {
        String content = "hello \"" + "a".repeat(200_000) + " end";

        List<NamedEntity> annotations = pipeline.process(createDocument(content, "docId", Language.ENGLISH));

        assertThat(annotations).isEmpty();
    }

    @Test(timeout = 10_000)
    public void test_finds_email_after_a_very_long_character_run() {
        String content = "a".repeat(1_000_000) + " email@domain.com";

        List<NamedEntity> annotations = pipeline.process(createDocument(content, "docId", Language.ENGLISH));

        assertThat(annotations).hasSize(1);
        assertThat(annotations.get(0).getMention()).isEqualTo("email@domain.com");
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -pl datashare-app -am test -Dtest=EmailPipelineTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: the three overflow tests fail with `java.lang.StackOverflowError`, and
`test_finds_email_after_a_very_long_character_run` fails on the 10 s timeout (the current
pattern needs roughly 1.5 h for a 1 MB run).

- [ ] **Step 3: Replace the pattern**

In `EmailPipeline`, replace the `pattern` field with the bounded, anchored version. Bounds
are named so the RFC justification survives:

```java
    // RFC 5321 caps a local part at 64 characters and a domain name at 253, so no
    // deliverable address carries more than 63 dot separated labels on either side.
    // The bounds matter: java.util.regex recurses once per group iteration, so an
    // unbounded repetition over a group turns long dotted runs into a StackOverflowError
    // (see https://github.com/ICIJ/datashare/issues/2290).
    private static final int MAX_DOT_SEPARATED_LABELS = 63;
    private static final int MAX_QUOTED_CHARS = 255;
    private static final String LOCAL_PART_CHARS = "[a-z0-9!#$%&'*+/=?^_`{|}~-]";
    // a match attempt starting inside a local part run can never be the leftmost match,
    // so refusing it here keeps find() linear instead of rescanning every run.
    // '@' is left out on purpose: user@bad@host.com must still yield bad@host.com.
    private static final String NOT_PRECEDED_BY_LOCAL_PART = "(?<![a-z0-9!#$%&'*+/=?^_`{|}~.-])";

    final Pattern pattern = Pattern.compile("(?:" + NOT_PRECEDED_BY_LOCAL_PART + LOCAL_PART_CHARS + "+(?:\\." +
            LOCAL_PART_CHARS + "+){0," + MAX_DOT_SEPARATED_LABELS + "}|\"(?:[\\x01-\\x08\\x0b" +
            "\\x0c\\x0e-\\x1f\\x21\\x23-\\x5b\\x5d-\\x7f]|\\\\[\\x01-\\x09\\x0b\\x0c\\x0e-\\x7f])" +
            "{0," + MAX_QUOTED_CHARS + "}\")@" +
            "(?:(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.){1," + MAX_DOT_SEPARATED_LABELS + "}[a-z0-9](?:[a-z0-9-]*[a-z0-9])?|" +
            "\\[(?:(?:(2(5[0-5]|[0-4][0-9])|1[0-9][0-9]|[1-9]?[0-9]))\\.){3}" +
            "(?:(2(5[0-5]|[0-4][0-9])|1[0-9][0-9]|[1-9]?[0-9])|[a-z0-9-]*[a-z0-9]:" +
            "(?:[\\x01-\\x08\\x0b\\x0c\\x0e-\\x1f\\x21-\\x5a\\x53-\\x7f]|" +
            "\\\\[\\x01-\\x09\\x0b\\x0c\\x0e-\\x7f]){1," + MAX_QUOTED_CHARS + "})\\])");
```

Three edits to memorise while doing it: `(?:\.[chars]+)*` becomes `{0,63}`, the quoted and
IP-literal inner loops become `{0,255}` and `{1,255}`, and the domain label `+` becomes
`{1,63}`.

- [ ] **Step 4: Run the whole test class to verify the new tests pass and the old ones still do**

Run: `mvn -pl datashare-app -am test -Dtest=EmailPipelineTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS, all tests, `test_acceptance` included (it asserts exact offsets over
`email.eml`, which is the regression guard on match semantics).

- [ ] **Step 5: Commit**

```bash
git add datashare-app/src/main/java/org/icij/datashare/nlp/EmailPipeline.java \
        datashare-app/src/test/java/org/icij/datashare/nlp/EmailPipelineTest.java
git commit -m "fix(nlp): bound email regex repetitions to stop StackOverflowError"
```

---

### Task 2: Name the document when a regex blows the stack

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/tasks/ExtractNlpTask.java:100-131`
- Test: `datashare-app/src/test/java/org/icij/datashare/tasks/ExtractNlpTaskTest.java`

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces: `ExtractNlpTask.findNamedEntities(Project, String)` keeps its signature
  (`void`, `throws InterruptedException`) and stays package private.

- [ ] **Step 1: Write the failing test**

Append to `ExtractNlpTaskTest`. It relies on the mocked `pipeline` and `indexer` fields and
the `nlpTask` built in the existing `setUp`:

```java
    @Test
    public void test_on_message_does_not_propagate_stack_overflow_from_pipeline() throws Exception {
        when(pipeline.initialize(any())).thenReturn(true);
        Document doc = createDoc("content").build();
        when(pipeline.process(doc)).thenThrow(new StackOverflowError());
        when(indexer.get("projectName", doc.getId(), doc.getId())).thenReturn(doc);

        nlpTask.findNamedEntities(project("projectName"), doc.getId());

        verify(indexer, never()).bulkAdd(anyString(), any(), any(), any());
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -pl datashare-app -am test -Dtest=ExtractNlpTaskTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: FAIL, the `StackOverflowError` propagates out of `findNamedEntities`.

- [ ] **Step 3: Catch it and log the document**

In `ExtractNlpTask.findNamedEntities`, add a catch beside the existing `IOException` one:

```java
        } catch (IOException e) {
            logger.error("cannot extract entities of doc {}", queueEntry, e);
        } catch (StackOverflowError e) {
            logger.error("stack overflow while extracting {} entities of doc {}, skipping it",
                nlpPipeline.getType(), queueEntry, e);
        }
```

The outer loop in `call()` already catches `Throwable`, so the document was skipped before
this change too. What the catch adds is the document reference in the log line, which the
production trace in issue #2290 lacked.

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -pl datashare-app -am test -Dtest=ExtractNlpTaskTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS, whole class.

- [ ] **Step 5: Commit**

```bash
git add datashare-app/src/main/java/org/icij/datashare/tasks/ExtractNlpTask.java \
        datashare-app/src/test/java/org/icij/datashare/tasks/ExtractNlpTaskTest.java
git commit -m "fix(nlp): log the document when a regex blows the stack"
```

---

### Task 3: Confirm no collateral damage on the NLP surface

**Files:**
- Test only, no source changes expected.

**Interfaces:**
- Consumes: the pattern from Task 1 and the catch from Task 2.
- Produces: nothing.

- [ ] **Step 1: Run every test that touches the email pipeline**

Run: `mvn -pl datashare-app -am test -Dtest=EmailPipelineTest,ExtractNlpTaskTest,TaskResourceTest -Dsurefire.failIfNoSpecifiedTests=false`

`TaskResourceTest` is in the list because it references `EmailPipeline` directly. Note that
four classes in this module fail for environment reasons on any branch; if a failure
appears, check it against `main` before treating it as a regression.

Expected: PASS for the three classes above.

- [ ] **Step 2: Commit nothing**

Nothing to commit. If this task turned up a regression, fix it in the task that caused it.

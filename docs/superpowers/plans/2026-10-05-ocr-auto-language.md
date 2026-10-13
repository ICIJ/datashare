# OCR auto language Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A default Datashare OCRs each image with the model of its detected script (extract 9.17.0), and a read tesseract scores low gives no language from its content.

**Architecture:** Bump extract, expose two integer options on both CLI surfaces, ship the six tesseract script models linked under `tessdata/script/` (Docker, deb). A new default method on `LanguageGuesser` blanks the text of an untrusted OCR read before the existing content-then-file-name guess; INDEX (`ElasticsearchSpewer`) and LANGUAGE (`LanguageDetectTask`) both call it.

**Tech Stack:** Java 21, Maven, extract-lib 9.17.0, JOpt + picocli, JUnit 4 + fest-assert + Mockito, Docker (Ubuntu noble), jdeb.

**Spec:** `docs/superpowers/specs/2026-10-05-ocr-auto-language-design.md`

## Global Constraints

- Branch `feat/ocr-auto-language`. Work in a worktree; copy the gitignored `datashare-devenv.properties` and `docs/superpowers/` into it or jOOQ codegen fails with "No JDBC Connection configured".
- `extract.version` becomes `9.17.0` (published on Maven Central).
- Option names: `ocrRetryConfidence` (read by extract, default 60 in extract), `ocrMinConfidence` (read by Datashare, default 60). Integers 0 to 100.
- No JOpt `defaultsTo` and no picocli `defaultValue` on either option: a default clobbers a value set in the `-s` settings file.
- No range clamp on either option.
- `ocr:confidence` is 0 to 1; `ocrMinConfidence` is 0 to 100, hence `* 100`. A read is untrusted when `confidence * 100 < minOcrConfidence` (strict).
- A malformed or absent confidence means "no confidence": behavior unchanged, the document never fails.
- The rule applies whenever a confidence is recorded, whatever produced it: `--ocrType TESS4J` records one even with a fixed `--ocrLanguage` (extract `Tess4JOCRParser.java:652`). Intended, do not special-case it.
- ES metadata key for the confidence: `tika_metadata_ocr_confidence`.
- Script models: `Latin`, `HanS`, `Cyrillic`, `Arabic`, `Japanese`, `Hangul`. Ubuntu packages: `tesseract-ocr-script-latn`, `-hans`, `-cyrl`, `-arab`, `-jpan`, `-hang`. Tessdata root: `/usr/share/tesseract-ocr/5/tessdata/`.
- deb `postinst` never fails the install.
- Maven: always keep `-am`, list several tests with commas (`-Dtest=A,B`, never `+`), add `-Dsurefire.failIfNoSpecifiedTests=false`. Run builds in the background and poll.
- Comments: none unless they carry a non-obvious why. No `ponytail:` comments.
- Commit with the `/commit` skill. No Claude credit in messages.

## Review Focus

1. **ES hands back the confidence as a number, or as garbage.** `_source` keeps the JSON string the spewer wrote, but a reindex or another writer can store `0.3` as a number or a malformed string. Expect: number works, garbage counts as absent. Pinned in Task 3 (`ocrConfidence` parsing) and Task 5 (numeric metadata value).
2. **A read exactly at the threshold.** `0.6` with `ocrMinConfidence=60` must be trusted (`0.6 * 100` is exactly `60.0` in Java). Pinned in Task 3.
3. **`ocrMinConfidence=0`.** Disables the rule: every read trusted, even confidence `0.0`. Pinned in Task 3.
4. **One spewer, several tasks.** The spewer is configured per task; a task without `ocrMinConfidence` after one with `20` must go back to 60, not inherit 20. Pinned in Task 4.
5. **deb postinst ordering and re-runs.** `postinst` can run before the script models are unpacked (Recommends ordering is not guaranteed) and runs again on every upgrade. Expect: exit 0 with nothing to link, then idempotent once models exist. Pinned in Task 2.

---

### Task 1: Bump extract to 9.17.0 and add the OCR confidence options

**Files:**
- Modify: `pom.xml:99`
- Modify: `datashare-cli/src/main/java/org/icij/datashare/cli/DatashareCliOptions.java` (constants near line 103, `ocrLanguage` at 643, new methods after it)
- Modify: `datashare-cli/src/main/java/org/icij/datashare/cli/DatashareCli.java:131`
- Modify: `datashare-cli/src/main/java/org/icij/datashare/cli/command/PipelineOptions.java:47-48`, `:110`
- Test: `datashare-cli/src/test/java/org/icij/datashare/cli/DatashareCliTest.java`
- Test: `datashare-cli/src/test/java/org/icij/datashare/cli/command/StageRunCommandTest.java`
- Test: `datashare-app/src/test/java/org/icij/datashare/tasks/IndexTaskTest.java`

**Interfaces:**
- Produces, in `DatashareCliOptions`:
  - `public static final String OCR_RETRY_CONFIDENCE_OPT = "ocrRetryConfidence";`
  - `public static final String OCR_MIN_CONFIDENCE_OPT = "ocrMinConfidence";`
  - `public static final int DEFAULT_OCR_MIN_CONFIDENCE = 60;`

- [ ] **Step 0: Create the worktree and branch**

```bash
git worktree add ../datashare-ocr-auto-language -b feat/ocr-auto-language main
cp datashare-devenv.properties ../datashare-ocr-auto-language/
cp -r docs/superpowers ../datashare-ocr-auto-language/docs/
cd ../datashare-ocr-auto-language
```

All later paths are relative to this worktree.

- [ ] **Step 1: Write the failing JOpt tests**

Append to `DatashareCliTest`:

```java
    @Test
    public void test_ocr_confidence_options() {
        Properties properties = cli.asProperties(
                cli.createParser().parse("--ocrRetryConfidence", "40", "--ocrMinConfidence", "70"), null);
        assertThat(properties).includes(entry("ocrRetryConfidence", "40"), entry("ocrMinConfidence", "70"));
    }

    @Test
    public void test_ocr_confidence_options_absent_by_default() {
        Properties properties = cli.asProperties(cli.createParser().parse(), null);
        assertThat(properties.containsKey("ocrRetryConfidence")).isFalse();
        assertThat(properties.containsKey("ocrMinConfidence")).isFalse();
    }
```

`createParser().parse` is used on purpose: `parseArguments` calls `System.exit(1)` on an unknown option, which would kill the surefire fork instead of failing the test.

- [ ] **Step 2: Write the failing picocli tests**

Append to `StageRunCommandTest`:

```java
    @Test
    public void test_ocr_confidence_options() {
        Properties props = parse("stage", "run", "--stages", "SCAN,INDEX",
                                 "--ocrRetryConfidence", "40", "--ocrMinConfidence", "70");
        assertThat(props).includes(entry("ocrRetryConfidence", "40"), entry("ocrMinConfidence", "70"));
    }

    @Test
    public void test_ocr_confidence_options_absent_by_default() {
        Properties props = parse("stage", "run", "--stages", "SCAN,INDEX");
        assertThat(props.containsKey("ocrRetryConfidence")).isFalse();
        assertThat(props.containsKey("ocrMinConfidence")).isFalse();
    }
```

- [ ] **Step 3: Write the failing extract-bump test**

`IndexTask` keeps only the options its `@OptionsClass(Extractor.class)` declares (`Options.createFrom` filters on declared names), so `ocrRetryConfidence` reaches extract only once 9.17.0 declares it. Append to `IndexTaskTest`, next to `test_options_include_ocr_language`:

```java
    @Test
    public void test_options_include_ocr_retry_confidence() throws Exception {
        ElasticsearchSpewer spewer = mock(ElasticsearchSpewer.class);
        Mockito.when(spewer.configure(Mockito.any())).thenReturn(spewer);
        IndexTask indexTask = new IndexTask(spewer, mock(DocumentCollectionFactory.class), new UpstreamGate.Factory(taskRepository), new Task<>(IndexTask.class.getName(), nullUser(), new HashMap<>(){{
            put("queueName", "test:queue");
        }}), null);
        Options<String> options = indexTask.options();
        assertThat(options.toString()).contains("ocrRetryConfidence=");
    }
```

- [ ] **Step 4: Run the tests to verify they fail**

Run in the background:

```bash
mvn -pl datashare-cli -am test -Dtest=DatashareCliTest,StageRunCommandTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -pl datashare-app -am test -Dtest=IndexTaskTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected:
- `DatashareCliTest.test_ocr_confidence_options` errors with `joptsimple.UnrecognizedOptionException: ocrRetryConfidence is not a recognized option`.
- `StageRunCommandTest.test_ocr_confidence_options` fails: the properties do not include `ocrRetryConfidence=40`.
- `IndexTaskTest.test_options_include_ocr_retry_confidence` fails: the options string lacks `ocrRetryConfidence=`.
- The two `_absent_by_default` tests pass already; they guard against adding a default.

Show this output to the reviewer before Step 5.

- [ ] **Step 5: Bump extract**

`pom.xml:99`:

```xml
        <extract.version>9.17.0</extract.version>
```

- [ ] **Step 6: Add the constants**

In `DatashareCliOptions`, after `OCR_LANGUAGE_OPT` (line 103):

```java
    public static final String OCR_RETRY_CONFIDENCE_OPT = "ocrRetryConfidence";
    public static final String OCR_MIN_CONFIDENCE_OPT = "ocrMinConfidence";
```

After `DEFAULT_OCR_TIMEOUT` (line 261):

```java
    public static final int DEFAULT_OCR_MIN_CONFIDENCE = 60;
```

- [ ] **Step 7: Wire JOpt**

Replace `ocrLanguage(OptionParser)` (line 643) and add two methods after it:

```java
    static void ocrLanguage(OptionParser parser) {
        parser.acceptsAll(List.of(OCR_LANGUAGE_OPT),
                          "OCR languages for tesseract: 3-character ISO 639-2 codes joined by + (\"eng+fra\"). " +
                          "When empty, the language is chosen per image from its detected script if the osd and " +
                          "script models are installed, eng otherwise. A mapping such as " +
                          "\"Cyrillic:rus+ukr,Han:chi_sim\" keeps that choice but reads each listed script with " +
                          "the given languages.")
              .withRequiredArg().ofType(String.class);
    }

    static void ocrRetryConfidence(OptionParser parser) {
        parser.acceptsAll(List.of(OCR_RETRY_CONFIDENCE_OPT),
                          "When the OCR language is chosen per image, read an image again with every script model " +
                          "if the first read's confidence is below this value, from 0 to 100. Defaults to 60.")
              .withRequiredArg().ofType(Integer.class);
    }

    static void ocrMinConfidence(OptionParser parser) {
        parser.acceptsAll(List.of(OCR_MIN_CONFIDENCE_OPT),
                          "OCR confidence, from 0 to 100, under which an image's text is not used to detect its " +
                          "language. Defaults to 60.")
              .withRequiredArg().ofType(Integer.class);
    }
```

In `DatashareCli.createParser()`, after `DatashareCliOptions.ocrLanguage(parser);` (line 131):

```java
        DatashareCliOptions.ocrRetryConfidence(parser);
        DatashareCliOptions.ocrMinConfidence(parser);
```

- [ ] **Step 8: Wire picocli**

In `PipelineOptions`, replace the `--ocrLanguage` option (lines 47-48) with:

```java
    @Option(names = {"--ocrLanguage"}, description = "OCR languages for tesseract: 3-character ISO 639-2 codes joined by + (\"eng+fra\"). When empty, the language is chosen per image from its detected script if the osd and script models are installed, eng otherwise. A mapping such as \"Cyrillic:rus+ukr,Han:chi_sim\" keeps that choice but reads each listed script with the given languages.")
    String ocrLanguage;
    @Option(names = {"--ocrRetryConfidence"}, description = "When the OCR language is chosen per image, read an image again with every script model if the first read's confidence is below this value, from 0 to 100. Defaults to 60.")
    Integer ocrRetryConfidence;
    @Option(names = {"--ocrMinConfidence"}, description = "OCR confidence, from 0 to 100, under which an image's text is not used to detect its language. Defaults to 60.")
    Integer ocrMinConfidence;
```

In `toProperties()`, after the `OCR_LANGUAGE_OPT` line (110):

```java
        DatashareOptions.putIfNotNull(props, OCR_RETRY_CONFIDENCE_OPT, ocrRetryConfidence);
        DatashareOptions.putIfNotNull(props, OCR_MIN_CONFIDENCE_OPT, ocrMinConfidence);
```

- [ ] **Step 9: Run the tests to verify they pass**

Same two commands as Step 4, in the background. Expected: all green. Then run the whole CLI module, which covers `RetroCompatibilityTest` and `SettingsDefaultsTest`:

```bash
mvn -pl datashare-cli -am test -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `BUILD SUCCESS`. A failure in a class this task did not touch: run it on `main` to tell a pre-existing env failure from a regression.

- [ ] **Step 10: Commit**

`/commit` with message `feat: bump extract to 9.17.0 and add the OCR confidence options`.

---

### Task 2: Install and link the tesseract script models (Docker, deb)

**Files:**
- Modify: `datashare-dist/src/main/docker/Dockerfile:5-11`
- Modify: `datashare-dist/src/main/deb/control/postinst`

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces: `tesseract --list-langs` lists `osd` and `script/Arabic`, `script/Cyrillic`, `script/HanS`, `script/Hangul`, `script/Japanese`, `script/Latin` in the image and on a deb install with `tesseract-ocr-all`.

- [ ] **Step 1: Build the current OCR layer and see it fail the check**

The full image needs `make dist`; the OCR part is the first `RUN`, so build only the lines before `# add user`:

```bash
sed '/^# add user/,$d' datashare-dist/src/main/docker/Dockerfile > /tmp/ocr-models.Dockerfile
docker build -q -t ds-ocr-models:before -f /tmp/ocr-models.Dockerfile /tmp
docker run --rm ds-ocr-models:before tesseract --list-langs
```

Expected: lists `eng` and `osd`, no `script/` line. That is the RED.

- [ ] **Step 2: Install and link the models in the Dockerfile**

Replace the first `RUN` (lines 5-11) with:

```dockerfile
RUN apt update && apt install -y \
  tesseract-ocr \
  tesseract-ocr-script-arab \
  tesseract-ocr-script-cyrl \
  tesseract-ocr-script-hang \
  tesseract-ocr-script-hans \
  tesseract-ocr-script-jpan \
  tesseract-ocr-script-latn \
  fonts-dejavu-core \
  fonts-droid-fallback \
  fonts-freefont-ttf \
  fonts-liberation \
  fonts-ubuntu

# Ubuntu installs the script models at the tessdata root, extract only finds them under tessdata/script/
RUN cd /usr/share/tesseract-ocr/5/tessdata && mkdir -p script && \
    for model in Latin HanS Cyrillic Arabic Japanese Hangul; do \
      ln -s ../$model.traineddata script/$model.traineddata; \
    done
```

No guard in the Docker loop on purpose: if a package moves its model, the build fails instead of shipping an image that silently OCRs in `eng`.

- [ ] **Step 3: Rebuild and check, then measure the size**

```bash
sed '/^# add user/,$d' datashare-dist/src/main/docker/Dockerfile > /tmp/ocr-models.Dockerfile
docker build -q -t ds-ocr-models:after -f /tmp/ocr-models.Dockerfile /tmp
docker run --rm ds-ocr-models:after tesseract --list-langs
docker image ls ds-ocr-models --format '{{.Tag}} {{.Size}}'
```

Expected: `osd` plus the six `script/*` lines. Note the `after - before` size for the PR (spec estimate: up to about 150 MB).

- [ ] **Step 4: Write the postinst**

Replace `datashare-dist/src/main/deb/control/postinst` (today only `#!/bin/sh`) with:

```sh
#!/bin/sh
tessdata=/usr/share/tesseract-ocr/5/tessdata
for model in Latin HanS Cyrillic Arabic Japanese Hangul; do
  if [ -f "$tessdata/$model.traineddata" ] && [ ! -e "$tessdata/script/$model.traineddata" ]; then
    mkdir -p "$tessdata/script" && ln -s "../$model.traineddata" "$tessdata/script/$model.traineddata"
  fi
done
exit 0
```

- [ ] **Step 5: Check the postinst in a clean noble container (Review Focus 5)**

```bash
docker run --rm -v "$PWD/datashare-dist/src/main/deb/control/postinst:/postinst:ro" ubuntu:noble sh -c '
  sh /postinst configure; echo "before models: exit $?"
  apt-get update -qq && apt-get install -y -qq tesseract-ocr tesseract-ocr-script-latn tesseract-ocr-script-hans \
    tesseract-ocr-script-cyrl tesseract-ocr-script-arab tesseract-ocr-script-jpan tesseract-ocr-script-hang >/dev/null
  sh /postinst configure; echo "first run: exit $?"
  sh /postinst configure; echo "second run: exit $?"
  tesseract --list-langs | grep -c script/'
```

Expected: `before models: exit 0`, `first run: exit 0`, `second run: exit 0`, then `6`.

- [ ] **Step 6: Commit**

`/commit` with message `build: install and link the tesseract script models (Docker, deb)`. Body: the measured image size delta.

---

### Task 3: Ignore low-confidence OCR text in `LanguageGuesser`

**Files:**
- Modify: `datashare-api/src/main/java/org/icij/datashare/text/indexing/LanguageGuesser.java`
- Test: `datashare-app/src/test/java/org/icij/datashare/nlp/LinguaLanguageGuesserTest.java` (real Lingua guesser, next to the existing file-name tests)

**Interfaces:**
- Produces, on `LanguageGuesser`:
  - `default Language guess(String text, Path path, Double ocrConfidence, int minOcrConfidence)`: `ocrConfidence` is 0 to 1 or null, `minOcrConfidence` is 0 to 100.
  - `static Double ocrConfidence(Object value)`: the recorded confidence as a `Double`, null when `value` is null or not a number. Accepts a `String` (Tika metadata, ES `_source`) or a `Number`.

- [ ] **Step 1: Write the failing tests**

Append to `LinguaLanguageGuesserTest` (add `import org.icij.datashare.text.indexing.LanguageGuesser;`):

```java
    private static final String FRENCH_PROSE =
            "Le petit chat noir dort paisiblement sur le canape pres de la fenetre ensoleillee.";

    @Test(timeout = 30000)
    public void test_low_confidence_ocr_text_gives_no_language() {
        assertThat(GUESSER.guess(FRENCH_PROSE, Paths.get("/tmp/IMG_0042.png"), 0.3, 60)).isEqualTo(Language.UNKNOWN);
    }

    @Test(timeout = 30000)
    public void test_confident_ocr_text_keeps_its_language() {
        assertThat(GUESSER.guess(FRENCH_PROSE, Paths.get("/tmp/IMG_0042.png"), 0.9, 60)).isEqualTo(Language.FRENCH);
    }

    @Test(timeout = 30000)
    public void test_ocr_confidence_at_the_threshold_is_trusted() {
        assertThat(GUESSER.guess(FRENCH_PROSE, Paths.get("/tmp/IMG_0042.png"), 0.6, 60)).isEqualTo(Language.FRENCH);
    }

    @Test(timeout = 30000)
    public void test_a_zero_threshold_trusts_every_read() {
        assertThat(GUESSER.guess(FRENCH_PROSE, Paths.get("/tmp/IMG_0042.png"), 0.0, 0)).isEqualTo(Language.FRENCH);
    }

    @Test(timeout = 30000)
    public void test_no_ocr_confidence_keeps_the_content_language() {
        assertThat(GUESSER.guess(FRENCH_PROSE, Paths.get("/tmp/IMG_0042.png"), null, 60)).isEqualTo(Language.FRENCH);
    }

    @Test(timeout = 30000)
    public void test_low_confidence_ocr_text_falls_back_to_a_long_file_name() {
        assertThat(GUESSER.guess("The quick brown fox jumps over the lazy dog near the river bank every morning.",
                                 Paths.get("/tmp/Contrat_de_travail_et_conditions_generales.png"), 0.3, 60))
                .isEqualTo(Language.FRENCH);
    }

    @Test
    public void test_reads_the_recorded_ocr_confidence() {
        assertThat(LanguageGuesser.ocrConfidence("0.3")).isEqualTo(0.3);
        assertThat(LanguageGuesser.ocrConfidence(0.3)).isEqualTo(0.3);
        assertThat(LanguageGuesser.ocrConfidence(null)).isNull();
        assertThat(LanguageGuesser.ocrConfidence("not a number")).isNull();
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

In the background:

```bash
mvn -pl datashare-app -am test -Dtest=LinguaLanguageGuesserTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `COMPILATION ERROR` in `LinguaLanguageGuesserTest`: `method guess in interface LanguageGuesser cannot be applied to given types` and `cannot find symbol ... ocrConfidence`. Show it to the reviewer before Step 3.

- [ ] **Step 3: Implement**

In `LanguageGuesser`, after `guess(String text, Path path)`:

```java
    /**
     * The guess for a document whose text may come from OCR: a read tesseract scores under
     * {@code minOcrConfidence} (0 to 100) is mostly noise and detects as a random language, so it counts as no
     * content and the file name decides. {@code ocrConfidence} is 0 to 1, null when no OCR confidence was recorded.
     */
    default Language guess(String text, Path path, Double ocrConfidence, int minOcrConfidence) {
        boolean untrusted = ocrConfidence != null && ocrConfidence * 100 < minOcrConfidence;
        return guess(untrusted ? "" : text, path);
    }

    /** The OCR confidence a document's metadata records, or null when it records none that reads as a number. */
    static Double ocrConfidence(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Double.valueOf(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Same command as Step 2. Expected: all `LinguaLanguageGuesserTest` tests pass.

- [ ] **Step 5: Commit**

`/commit` with message `feat: ignore low-confidence OCR text when detecting the language`.

---

### Task 4: INDEX applies the rule (`ElasticsearchSpewer`, `IndexTask`)

**Files:**
- Modify: `datashare-index/src/main/java/org/icij/datashare/text/indexing/elasticsearch/ElasticsearchSpewer.java` (fields ~106-113, `getDocument` line 293, `configure` line 415)
- Modify: `datashare-app/src/main/java/org/icij/datashare/tasks/IndexTask.java:52-53`
- Test: `datashare-index/src/test/java/org/icij/datashare/text/indexing/elasticsearch/ElasticsearchSpewerTest.java`
- Test: `datashare-app/src/test/java/org/icij/datashare/tasks/IndexTaskTest.java`

**Interfaces:**
- Consumes: `LanguageGuesser.guess(String, Path, Double, int)`, `LanguageGuesser.ocrConfidence(Object)` (Task 3); `OCR_MIN_CONFIDENCE_OPT`, `DEFAULT_OCR_MIN_CONFIDENCE` (Task 1); `org.icij.extract.ocr.ParserWithConfidence.OCR_CONFIDENCE` (extract 9.17.0, `Property.externalReal("ocr:confidence")`).
- Produces: an indexed document's `metadata.tika_metadata_ocr_confidence` (string, as Tika wrote it), read by Task 5.

- [ ] **Step 1: Write the failing spewer tests**

In `ElasticsearchSpewerTest`, add imports `import static org.icij.extract.ocr.ParserWithConfidence.OCR_CONFIDENCE;` and `import static org.fest.assertions.MapAssert.entry;` if missing, then:

```java
    @Test
    public void test_write_low_confidence_ocr_image_has_unknown_language() throws Exception {
        Map<String, Object> indexed = indexedOcrImage("IMG_0042.png", 0.3, Map.of());
        assertThat(indexed.get("language")).isEqualTo("UNKNOWN");
        // the LANGUAGE stage reads the confidence back under this key
        assertThat((Map<String, Object>) indexed.get("metadata")).includes(entry("tika_metadata_ocr_confidence", "0.3"));
    }

    @Test
    public void test_write_reads_the_ocr_min_confidence_option() throws Exception {
        assertThat(indexedOcrImage("IMG_0044.png", 0.3, Map.of("ocrMinConfidence", "20")).get("language"))
                .isEqualTo("ENGLISH");
    }

    @Test
    public void test_a_task_without_ocr_min_confidence_goes_back_to_the_default() throws Exception {
        try (ElasticsearchSpewer ocrSpewer = textOnlySpewer()) {
            ocrSpewer.configure(Options.from(Map.of("defaultProject", es.getIndexName(), "ocrMinConfidence", "20")));
            ocrSpewer.configure(Options.from(Map.of("defaultProject", es.getIndexName())));
            assertThat(writeOcrImage(ocrSpewer, "IMG_0046.png", 0.3).get("language")).isEqualTo("UNKNOWN");
        }
    }

    private Map<String, Object> indexedOcrImage(String fileName, double confidence, Map<String, String> options) throws Exception {
        try (ElasticsearchSpewer ocrSpewer = textOnlySpewer()) {
            Map<String, String> taskOptions = new HashMap<>(options);
            taskOptions.put("defaultProject", es.getIndexName());
            ocrSpewer.configure(Options.from(taskOptions));
            return writeOcrImage(ocrSpewer, fileName, confidence);
        }
    }

    // ENGLISH for any text, UNKNOWN for none: the language only depends on whether the content was kept.
    private ElasticsearchSpewer textOnlySpewer() {
        return new ElasticsearchSpewer(new ElasticsearchIndexer(es.client, new PropertiesProvider()).withRefresh(Refresh.True),
                documentQueueFactory, text -> text == null || text.isBlank() ? Language.UNKNOWN : Language.ENGLISH,
                new FieldNames(), new PropertiesProvider(Map.of("defaultProject", es.getIndexName())));
    }

    private Map<String, Object> writeOcrImage(ElasticsearchSpewer ocrSpewer, String fileName, double confidence) throws Exception {
        TikaDocument image = new DocumentFactory().withIdentifier(new PathIdentifier()).create(get(fileName));
        image.getMetadata().set(OCR_CONFIDENCE, confidence);
        image.setReader(new ParsingReader(new ByteArrayInputStream("Tbe qvick hrown f0x".getBytes())));
        ocrSpewer.write(image);
        GetResponse<ObjectNode> indexed = es.client.get(doc -> doc.index(es.getIndexName()).id(image.getId()), ObjectNode.class);
        return nodeToMap(indexed.source());
    }
```

The file names are short camera codes (under 15 letters), so the file-name fallback stays UNKNOWN and the language only depends on the content being kept.

- [ ] **Step 2: Write the failing IndexTask test**

`IndexTask` drops undeclared options before configuring the spewer, so `ocrMinConfidence` must be declared. Append to `IndexTaskTest`:

```java
    @Test
    public void test_configure_passes_ocr_min_confidence_to_spewer() throws Exception {
        ElasticsearchSpewer spewer = mock(ElasticsearchSpewer.class);
        Mockito.when(spewer.configure(Mockito.any())).thenReturn(spewer);

        new IndexTask(spewer, mock(DocumentCollectionFactory.class), new UpstreamGate.Factory(taskRepository), new Task<>(IndexTask.class.getName(), nullUser(), Map.of("ocrMinConfidence", "70")), null);

        ArgumentCaptor<Options> captor = ArgumentCaptor.forClass(Options.class);
        verify(spewer).configure(captor.capture());
        Option<String> option = new Option<>("ocrMinConfidence", StringOptionParser::new).update("70");
        assertThat(captor.getValue()).contains(option);
    }
```

- [ ] **Step 3: Run the tests to verify they fail**

In the background:

```bash
mvn -pl datashare-index -am test -Dtest=ElasticsearchSpewerTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -pl datashare-app -am test -Dtest=IndexTaskTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected:
- `test_write_low_confidence_ocr_image_has_unknown_language` and `test_a_task_without_ocr_min_confidence_goes_back_to_the_default` fail: expected `UNKNOWN`, got `ENGLISH`.
- `test_write_reads_the_ocr_min_confidence_option` passes already: it guards the trusted path.
- `test_configure_passes_ocr_min_confidence_to_spewer` fails: the captured options do not contain `ocrMinConfidence`.

Show this output to the reviewer before Step 4.

- [ ] **Step 4: Implement in the spewer**

In `ElasticsearchSpewer`, add imports:

```java
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_OCR_MIN_CONFIDENCE;
import static org.icij.datashare.cli.DatashareCliOptions.OCR_MIN_CONFIDENCE_OPT;
import static org.icij.extract.ocr.ParserWithConfidence.OCR_CONFIDENCE;
```

Field, next to `manifestRecorder`:

```java
    // volatile: set by the task thread in configure, read by every consumer thread
    private volatile int minOcrConfidence = DEFAULT_OCR_MIN_CONFIDENCE;
```

In `getDocument`, replace line 293:

```java
            Double ocrConfidence = LanguageGuesser.ocrConfidence(document.getMetadata().get(OCR_CONFIDENCE));
            builder.with(languageGuesser.guess(content, document.getPath(), ocrConfidence, minOcrConfidence));
```

In `configure(Options<String>)`, before `return this;`:

```java
        minOcrConfidence = options.valueIfPresent(OCR_MIN_CONFIDENCE_OPT).map(Integer::parseInt)
                                  .orElse(DEFAULT_OCR_MIN_CONFIDENCE);
```

- [ ] **Step 5: Declare the option on IndexTask**

In `IndexTask`, after `@Option(name = "projectName", ...)` (line 53):

```java
@Option(name = OCR_MIN_CONFIDENCE_OPT, description = "OCR confidence, from 0 to 100, under which an image's text is not used to detect its language")
```

`DatashareCliOptions.*` is already statically imported (line 46).

- [ ] **Step 6: Run the tests to verify they pass**

Same two commands as Step 3. Expected: all green.

- [ ] **Step 7: Commit**

`/commit` with message `feat: drop low-confidence OCR text from the INDEX language guess`.

---

### Task 5: LANGUAGE applies the rule (`LanguageDetectTask`)

**Files:**
- Modify: `datashare-app/src/main/java/org/icij/datashare/tasks/LanguageDetectTask.java` (javadoc line 31-34, constructor, `updateLanguage` line 91)
- Test: `datashare-app/src/test/java/org/icij/datashare/tasks/LanguageDetectTaskTest.java`

**Interfaces:**
- Consumes: `LanguageGuesser.guess(String, Path, Double, int)`, `LanguageGuesser.ocrConfidence(Object)` (Task 3); `OCR_MIN_CONFIDENCE_OPT`, `DEFAULT_OCR_MIN_CONFIDENCE` (Task 1); ES metadata key `tika_metadata_ocr_confidence` (pinned by Task 4).

- [ ] **Step 1: Write the failing tests**

Append to `LanguageDetectTaskTest`:

```java
    @Test(timeout = 30000)
    public void test_low_confidence_ocr_text_gives_no_language() throws Exception {
        when(mockEs.get("prj", "imgId", "imgId", EXCLUDES)).thenReturn(ocrImage("0.3"));
        enqueue("imgId");

        assertThat(runTask()).isEqualTo(1);

        verify(mockEs).update("prj", "imgId", Map.of("language", "UNKNOWN"), "imgId");
    }

    @Test(timeout = 30000)
    public void test_reads_a_numeric_ocr_confidence() throws Exception {
        when(mockEs.get("prj", "imgId", "imgId", EXCLUDES)).thenReturn(ocrImage(0.3));
        enqueue("imgId");

        assertThat(runTask()).isEqualTo(1);

        verify(mockEs).update("prj", "imgId", Map.of("language", "UNKNOWN"), "imgId");
    }

    @Test(timeout = 30000)
    public void test_reads_the_ocr_min_confidence_option() throws Exception {
        when(mockEs.get("prj", "imgId", "imgId", EXCLUDES)).thenReturn(ocrImage("0.3"));
        enqueue("imgId");

        assertThat(runTask(factory, Map.of("defaultProject", "prj", "ocrMinConfidence", "20"))).isEqualTo(1);

        verify(mockEs).update("prj", "imgId", Map.of("language", "FRENCH"), "imgId");
    }

    @Test(timeout = 30000)
    public void test_a_malformed_ocr_confidence_is_ignored() throws Exception {
        when(mockEs.get("prj", "imgId", "imgId", EXCLUDES)).thenReturn(ocrImage("not a number"));
        enqueue("imgId");

        assertThat(runTask()).isEqualTo(1);

        verify(mockEs).update("prj", "imgId", Map.of("language", "FRENCH"), "imgId");
    }

    // Indexed as FINNISH, the random language garbage OCR used to get.
    private Document ocrImage(Object ocrConfidence) {
        return DocumentBuilder.createDoc("imgId").with(new Project("prj")).with(FRENCH_CONTENT)
                .with(Paths.get("/corpus/IMG_0042.png")).with(Language.FINNISH)
                .with(Map.<String, Object>of("tika_metadata_ocr_confidence", ocrConfidence)).build();
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

In the background:

```bash
mvn -pl datashare-app -am test -Dtest=LanguageDetectTaskTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `test_low_confidence_ocr_text_gives_no_language` and `test_reads_a_numeric_ocr_confidence` fail with Mockito `Argument(s) are different! Wanted: ... {language=UNKNOWN} ... Actual: ... {language=FRENCH}`. The two FRENCH tests pass already. Show it to the reviewer before Step 3.

- [ ] **Step 3: Implement**

In `LanguageDetectTask`, add imports:

```java
import static java.util.Optional.ofNullable;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_OCR_MIN_CONFIDENCE;
import static org.icij.datashare.cli.DatashareCliOptions.OCR_MIN_CONFIDENCE_OPT;
```

Fields:

```java
    private static final String OCR_CONFIDENCE_KEY = "tika_metadata_ocr_confidence";
    private final int minOcrConfidence;
```

End of the constructor:

```java
        minOcrConfidence = propertiesProvider.get(OCR_MIN_CONFIDENCE_OPT).map(Integer::parseInt)
                                             .orElse(DEFAULT_OCR_MIN_CONFIDENCE);
```

In `updateLanguage`, replace line 91:

```java
        Double ocrConfidence = LanguageGuesser.ocrConfidence(
                ofNullable(document.getMetadata()).map(metadata -> metadata.get(OCR_CONFIDENCE_KEY)).orElse(null));
        Language guess = languageGuesser.guess(document.getContent(), document.getPath(), ocrConfidence,
                                               minOcrConfidence);
```

In the class javadoc, replace `({@link LanguageGuesser#guess(String, java.nio.file.Path)})` with `({@link LanguageGuesser#guess(String, java.nio.file.Path, Double, int)})`.

- [ ] **Step 4: Run the tests to verify they pass**

Same command as Step 2. Expected: all `LanguageDetectTaskTest` tests pass.

- [ ] **Step 5: Run the touched modules' suites**

In the background:

```bash
mvn -pl datashare-app -am test -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `BUILD SUCCESS`, except the classes that fail for environment reasons on any branch: re-run any failing class on `main` and report which failures are pre-existing.

- [ ] **Step 6: Commit**

`/commit` with message `feat: drop low-confidence OCR text from the LANGUAGE stage guess`.

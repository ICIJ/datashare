# QR code extraction, Part A: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Behind an opt-in `qrCodes` option, decode 2D codes in images during INDEX and add `QR code: <value>` lines to the content plus a `qr_codes` metadata list; expose it in datashare as `--qrCodes`.

**Architecture:** extract-lib gets a stateless `QRCodeDecoder` (ZXing) and a `QRCodeParserDecorator` that wraps every Tika `AbstractImageParser` subclass, so it runs with OCR on or off and on the deferred-OCR pool path. The decorator decodes the spooled file before delegating, then injects one `<p>` per value just before the delegate closes `<body>`. `Extractor.enableQrCodes()` installs the decorator and keeps PDF inline images extracted when OCR is off. Datashare only wires the option on both CLI surfaces, maps the new field as `keyword`, and bumps `extract.version`.

**Tech Stack:** Java 17, Tika 3.3.0, ZXing `com.google.zxing:core`, JUnit 4 + fest-assert (extract-lib), JUnit 4 + AssertJ-style fest (datashare), Maven.

**Spec:** `docs/superpowers/specs/2026-11-17-qr-code-extraction-design.md` (datashare repo). Part A only; Part B (catch-up stage) gets its own plan.

## Global Constraints

- extract-lib work: new worktree `~/Repositories/extract/.worktrees/qr-codes`, branch `feat/qr-codes`, from `origin/master` (currently `657cc3ca [release] 9.23.0`). The main extract checkout is on another branch: do not touch it.
- datashare work: this worktree, branch `feat/qr-code-extraction`.
- Option name `qrCodes` in extract-lib, `--qrCodes` in datashare. Default off. No JOpt `defaultsTo`, no picocli `defaultValue`: an absent flag must emit no key (it would clobber a `-s` settings file).
- Formats: QR code, Data Matrix, Aztec, PDF417. No 1D barcodes.
- Content line: exactly `QR code: <value>`, one per decoded value, same label for every format.
- Metadata: Tika property named `qr_codes`, multi-valued, spewed as `tika_metadata_qr_codes`. Mapped as `keyword` in `datashare_index_mappings.json`.
- An image with no code gets neither the line nor the metadata.
- With `qrCodes` off, nothing changes: no parser is wrapped and `extractInlineImages` is computed as today.
- Inline-image rule: `extractInlineImages = (!ocrDisabled || qrCodes) && strategy == NO_OCR`.
- A decoding failure never throws and never skips OCR.
- No WebP support (no new ImageIO plugin).
- Test fixtures are generated in the test (ZXing writers, `ImageIO`, PDFBox). Never real corpus files.
- Commits go through the `/commit` skill. Datashare: `make format` then `make lint` before any push.
- Datashare single-test runs: `-Dsurefire.failIfNoSpecifiedTests=false -Dmaven.gitcommitid.skip=true`, keep `-am`, comma-separate `-Dtest` classes.

## Review Focus

1. **Transparent-background PNG** (most QR generators emit one): transparent pixels must read as white, not black, so the code still decodes. Test in Task 1.
2. **Very large scan** (6000×6000 px): decodes without allocating a full-resolution ARGB `int[]`; subsampled read keeps memory bounded. Test in Task 1.
3. **QR PNG inside a zip, OCR on, fan-out on** (deferred OCR pool, metadata merged at reader-read time): the child still gets the line and the metadata. Test in Task 3.
4. **HTML output format**: the QR paragraph lands inside `<body>`, not after `</html>`. Test in Task 2.
5. **Every covered format** (PNG, JPEG, GIF, BMP, TIFF) goes through the decorator, since Tika splits them across `ImageParser`, `JpegParser`, `TiffParser`. Test in Task 3.

---

## Part A1: extract-lib

### Task 1: `QRCodeDecoder`

**Files:**
- Modify: `extract-lib/pom.xml` (add `com.google.zxing:core`, latest 3.5.x on Maven Central, 3.5.3 at writing)
- Create: `extract-lib/src/main/java/org/icij/extract/qrcode/QRCodeDecoder.java`
- Create: `extract-lib/src/test/java/org/icij/extract/qrcode/QRCodeImages.java` (test helper, reused by Tasks 2 to 4)
- Test: `extract-lib/src/test/java/org/icij/extract/qrcode/QRCodeDecoderTest.java`

**Interfaces:**
- Produces: `public final class QRCodeDecoder { public static List<String> decode(Path image) }`: values in reading order (top to bottom, then left to right), deduplicated, blank values dropped, empty list on any failure. Path, not stream: every caller already holds a file (Tika spool, artifact cache).
- Produces (test): `QRCodeImages.code(BarcodeFormat format, String text, int size) -> BufferedImage` (white background, black modules, `TYPE_BYTE_GRAY`), `QRCodeImages.canvas(int width, int height) -> BufferedImage` (white), `QRCodeImages.write(BufferedImage image, String format, Path file) -> Path` (`ImageIO.write`), `QRCodeImages.pdfWithImage(BufferedImage image, Path file) -> Path` (PDFBox: one page, `LosslessFactory.createFromImage`, `drawImage`).

- [ ] **Step 1: Create the worktree and branch**

```bash
cd ~/Repositories/extract && git fetch origin && git worktree add .worktrees/qr-codes -b feat/qr-codes origin/master
```

- [ ] **Step 2: Add the ZXing dependency to `extract-lib/pom.xml`, then write `QRCodeImages` and the failing tests**

Tests in `QRCodeDecoderTest` (all images written to a `TemporaryFolder`):

```java
@Test public void test_image_without_code_returns_empty()            // white 400x400 PNG -> isEmpty()
@Test public void test_one_qr_code()                                  // "https://example.org/qr/one" -> containsExactly(it)
@Test public void test_several_codes_in_reading_order()               // canvas 1400x500, QR "…/three" at x=950, "…/one" at x=50, "…/two" at x=500, all y=50
                                                                      // -> containsExactly("…/one", "…/two", "…/three")
@Test public void test_duplicate_codes_are_returned_once()            // same QR drawn twice side by side -> containsExactly(value)
@Test public void test_data_matrix_aztec_and_pdf417()                 // one image per format: "DM-12345", "AZTEC-42", "PDF417-7"
@Test public void test_rotated_code()                                 // QR rotated 90 degrees -> containsExactly(value)
@Test public void test_transparent_background()                       // TYPE_INT_ARGB, transparent background, opaque black modules -> containsExactly(value)
@Test public void test_large_scan_is_subsampled()                     // 6000x6000 white canvas, 1200 px QR at (2400,2400) -> containsExactly(value)
@Test public void test_non_image_returns_empty()                      // text file "hello" -> isEmpty(), no exception
@Test public void test_truncated_image_returns_empty()                // first 100 bytes of a QR PNG -> isEmpty(), no exception
```

- [ ] **Step 3: Run the tests to see them fail**

Run: `cd ~/Repositories/extract/.worktrees/qr-codes && mvn -q -pl extract-lib test -Dtest=QRCodeDecoderTest`
Expected: compilation FAIL, `cannot find symbol: class QRCodeDecoder`. Show this output to Pierre before Step 4.

- [ ] **Step 4: Implement `QRCodeDecoder.decode(Path)`**

1. Open `ImageIO.createImageInputStream(file)`, take the first `ImageReader`; none means empty.
2. Read width and height from the reader; subsampling factor `f = max(1, ceil(sqrt(w * h / 16_000_000.0)))`, constant `MAX_PIXELS = 16_000_000`; `reader.read(0, param)` with `setSourceSubsampling(f, f, 0, 0)`.
3. Draw onto a new `TYPE_BYTE_GRAY` image filled white first (handles transparency); wrap its `DataBufferByte` data in `PlanarYUVLuminanceSource(data, w, h, 0, 0, w, h, false)`; `BinaryBitmap(new HybridBinarizer(source))`. No `int[]` ARGB copy.
4. `new GenericMultipleBarcodeReader(new MultiFormatReader()).decodeMultiple(bitmap, hints)` with `POSSIBLE_FORMATS = [QR_CODE, DATA_MATRIX, AZTEC, PDF417]` and `TRY_HARDER = true`. `NotFoundException` means empty.
5. Sort by the minimum `y`, then minimum `x`, of each result's `getResultPoints()`; collect `getText()` into a `LinkedHashSet`, skip blank.
6. Catch `IOException` and `RuntimeException` around the whole thing: log at debug with the path, return empty. Dispose the reader in `finally`.

- [ ] **Step 5: Run the tests to see them pass**

Run: `mvn -q -pl extract-lib test -Dtest=QRCodeDecoderTest`
Expected: `Tests run: 10, Failures: 0, Errors: 0`. If `test_several_codes_in_reading_order` misses a code, raise the canvas spacing, do not drop the assertion.

- [ ] **Step 6: Commit** (`/commit`, message `feat(qrcode): decode 2D codes from image files`)

---

### Task 2: `QRCodeParserDecorator`

**Files:**
- Create: `extract-lib/src/main/java/org/icij/extract/parser/QRCodeParserDecorator.java`
- Test: `extract-lib/src/test/java/org/icij/extract/parser/QRCodeParserDecoratorTest.java`

**Interfaces:**
- Consumes: `QRCodeDecoder.decode(Path)`, `QRCodeImages` (Task 1).
- Produces: `public class QRCodeParserDecorator extends ParserDecorator` with `public QRCodeParserDecorator(Parser parser)` and `public static final Property QR_CODES = Property.internalTextBag("qr_codes")`.

- [ ] **Step 1: Write the failing tests**

Each test parses a generated file through `new QRCodeParserDecorator(new ImageParser())` with `metadata.set(CONTENT_TYPE, "image/png")` and a fresh `ParseContext`:

```java
@Test public void test_adds_one_line_and_one_metadata_value_per_code()
    // two QRs "…/one", "…/two"; handler = new BodyContentHandler(-1)
    // text contains "QR code: https://example.org/qr/one\nQR code: https://example.org/qr/two\n"
    // metadata.getValues(QR_CODES) == ["…/one", "…/two"]
@Test public void test_image_without_code_is_untouched()
    // white PNG: text does not contain "QR code", metadata.get(QR_CODES) is null
@Test public void test_html_output_keeps_the_line_inside_body()
    // handler = new ToXMLContentHandler(); output contains "<p>QR code: https://example.org/qr/one</p>"
    // and indexOf that paragraph < indexOf("</body>")
@Test public void test_spewed_field_name()
    // new FieldNames().forMetadata(QR_CODES.getName()) == "tika_metadata_qr_codes"
```

- [ ] **Step 2: Run them to see them fail**

Run: `mvn -q -pl extract-lib test -Dtest=QRCodeParserDecoratorTest`
Expected: compilation FAIL, `cannot find symbol: class QRCodeParserDecorator`.

- [ ] **Step 3: Implement `parse(InputStream, ContentHandler, Metadata, ParseContext)`**

Spool with `TikaInputStream.get(stream, tmp, metadata)` (own `TemporaryResources`, closed in `finally`), decode `tis.getPath()` first, then `super.parse(tis, handler', metadata, context)` where `handler'` is the given handler when no value was found, else a private `ContentHandlerDecorator` that, on the first `endElement` with namespace `XHTMLContentHandler.XHTML` and local name `body`, emits per value: `startElement(XHTML, "p", "p", new AttributesImpl())`, `characters("QR code: " + value)`, `endElement(XHTML, "p", "p")`, `ignorableWhitespace("\n")`, adds the value to `QR_CODES`, then forwards the `body` end. Metadata is written only together with the lines, so a delegate that fails before closing `<body>` leaves neither.

- [ ] **Step 4: Run them to see them pass**

Run: `mvn -q -pl extract-lib test -Dtest=QRCodeParserDecoratorTest`
Expected: `Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit** (`/commit`, message `feat(parser): add QR code lines to image text`)

---

### Task 3: `qrCodes` option on `Extractor`

**Files:**
- Modify: `extract-lib/src/main/java/org/icij/extract/extractor/Extractor.java` (`@Option` list near line 166, fields near line 199, `configure` near line 317, `setOcrStrategy` line 647, `disableOcr` lines 675-686)
- Regenerate: `extract-lib/src/main/java/org/icij/extract/extractor/ExtractorOptions.java`
- Test: `extract-lib/src/test/java/org/icij/extract/extractor/ExtractorQrCodesTest.java`

**Interfaces:**
- Consumes: `QRCodeParserDecorator(Parser)`, `QR_CODES` (Task 2), `QRCodeImages` (Task 1).
- Produces: option `qrCodes` (read with `options.get("qrCodes", "false").parse().isOn()`); `public void enableQrCodes()` (idempotent); `ExtractorOptions.qrCodes()` (`String`, between `pstParseParallelism` and `spewQueueCapacity`). Datashare observes the option through the existing `isExtractInlineImages()`.

- [ ] **Step 1: Write the failing tests**

Option matrix (`new Extractor(Options.from(map))`, assert `isExtractInlineImages()`):

```java
@Test public void test_inline_images_kept_when_ocr_off_and_qr_codes_on()       // {ocr:false, qrCodes:true} -> true
@Test public void test_inline_images_off_when_ocr_off_and_qr_codes_off()       // {ocr:false} -> false
@Test public void test_rendering_strategy_still_disables_inline_images()       // {qrCodes:true, ocrStrategy:AUTO} -> false
@Test public void test_ocr_off_forces_no_ocr_so_qr_codes_keep_inline_images()  // {ocr:false, qrCodes:true, ocrStrategy:AUTO} -> true
```

Extraction (generated fixtures in a `TemporaryFolder`, value `"https://example.org/qr/one"`; "tree text" means the `spewTreeText` pattern from `ExtractorTest:864-876`: `PrintStreamSpewer` with `outputMetadata(false)`, then close the root reader):

```java
@Test public void test_standalone_png_ocr_on()           // same, OCR on (Tesseract required, like ExtractorTest.testOcr)
@Test public void test_every_covered_format()            // png, jpg, gif, bmp, tif written with ImageIO, OCR off: each root text contains "QR code: …/one", root metadata QR_CODES == [value]
@Test public void test_image_without_code()              // white PNG, qrCodes on: no "QR code", QR_CODES null
@Test public void test_option_off_by_default()           // QR PNG, OCR off, no qrCodes: no "QR code", QR_CODES null
@Test public void test_pdf_inline_image_ocr_off()        // pdfWithImage, OCR off, qrCodes on: tree text contains the line
@Test public void test_pdf_inline_image_ocr_on()         // same with OCR on
@Test public void test_pdf_inline_image_option_off()     // OCR off, qrCodes off: tree text has no "QR code"
@Test public void test_zip_child_on_deferred_ocr_path()  // zip (java.util.zip) holding qr.png, OCR on, ocrFanout default:
                                                         // tree text contains the line; after reading, the child's metadata QR_CODES == [value]
```

- [ ] **Step 2: Run them to see them fail**

Run: `mvn -q -pl extract-lib test -Dtest=ExtractorQrCodesTest`
Expected: compilation FAIL first (`enableQrCodes` and `QR_CODES` unknown if referenced); once it compiles, `test_inline_images_kept_when_ocr_off_and_qr_codes_on` and `test_ocr_off_forces_no_ocr_so_qr_codes_keep_inline_images` fail with expected true, was false, and every extraction test fails on the missing `QR code:` line. `test_inline_images_off_when_ocr_off_and_qr_codes_off`, `test_rendering_strategy_still_disables_inline_images`, `test_option_off_by_default`, `test_image_without_code` and `test_pdf_inline_image_option_off` already pass: they guard the off path.

- [ ] **Step 3: Implement**

- `@Option(name = "qrCodes", description = "Decode QR codes, Data Matrix, Aztec and PDF417 codes found in images and add their values to the document text. Off by default. With OCR off, PDF inline images are then extracted as child documents.")`
- `private boolean qrCodes = false;`
- `public void enableQrCodes()`: when not yet on, `replaceParser(AbstractImageParser.class, QRCodeParserDecorator::new)`, set the flag, recompute inline images.
- `private void updateExtractInlineImages()` applying the Global Constraints rule against `pdfConfig.getOcrStrategy()`; call it at the end of `setOcrStrategy` (replacing line 647), at the end of `disableOcr` (replacing line 681, after the strategy is forced to `NO_OCR`) and in `enableQrCodes`.
- `configure`: after the `disableOcr()` block, `if (options.get("qrCodes", "false").parse().isOn()) enableQrCodes();`
- Regenerate the record: `rm extract-lib/src/main/java/org/icij/extract/extractor/ExtractorOptions.java && mvn -q -pl extract-lib compile exec:java@generate-options -Doptions.class=org.icij.extract.extractor.Extractor`, then check `git diff` shows only the `qrCodes` field and its three lines.

- [ ] **Step 4: Run them to see them pass**

Run: `mvn -q -pl extract-lib test -Dtest=ExtractorQrCodesTest,ExtractorTest,ExtractorOcrPoolConfigTest`
Expected: all pass. `ExtractorTest.testDisableOcrOnEmbed` and `testIgnoreEmbeds` still expect `"\n\n\n\n"`: proof the off path is unchanged.

- [ ] **Step 5: Commit** (`/commit`, message `feat(extractor): add qrCodes option`)

---

### Task 4: on-demand extraction of PDF inline images

**Files:**
- Modify: `extract-lib/src/main/java/org/icij/extract/extractor/EmbeddedDocumentExtractor.java:78-87` (`newParseContext`)
- Test: `extract-lib/src/test/java/org/icij/extract/extractor/EmbeddedDocumentExtractorInlineImageTest.java`

**Interfaces:**
- Consumes: `Extractor.enableQrCodes()` (Task 3), `QRCodeImages.pdfWithImage` (Task 1).
- Produces: `EmbeddedDocumentExtractor.extract(root, digest)` finds PDF inline images. Part B relies on it.

- [ ] **Step 1: Write the failing test**

```java
@Test public void test_extracts_a_pdf_inline_image_by_id()
    // Extractor with DocumentFactory().withIdentifier(new DigestIdentifier("SHA-256", UTF_8)),
    // setDigester(new UpdatableDigester("prj", "SHA-256")), disableOcr(), enableQrCodes();
    // extract pdfWithImage(QR), read and close the root reader, id = root.getEmbeds().get(0).getId()
    // new EmbeddedDocumentExtractor(new UpdatableDigester("prj", "SHA-256")).extract(root, id)
    //   -> source metadata CONTENT_TYPE starts with "image/"
```

- [ ] **Step 2: Run it to see it fail**

Run: `mvn -q -pl extract-lib test -Dtest=EmbeddedDocumentExtractorInlineImageTest`
Expected: FAIL with `ContentNotFoundException: <…> embedded document not found in root document`.

- [ ] **Step 3: Implement** in `newParseContext()`: set a `PDFParserConfig` with `setExtractInlineImages(true)` and `setExtractUniqueInlineImagesOnly(false)` (same as `Extractor` constructor line 269, so ids match index time).

- [ ] **Step 4: Run it plus the existing on-demand tests**

Run: `mvn -q -pl extract-lib test -Dtest='EmbeddedDocument*Test'`
Expected: all pass.

- [ ] **Step 5: Commit** (`/commit`, message `fix(extractor): extract PDF inline images on demand`)

- [ ] **Step 6: Full extract-lib suite in the background**

Run: `mvn -pl extract-lib test` in the background, poll and report. Expected: no new failures against `origin/master` (run master in the same env if anything fails, to separate pre-existing ones).

### Checkpoint: extract release (Pierre)

Push `feat/qr-codes`, open the extract PR, merge, then `make release NEW_VERSION=9.24.0` on master (or whatever version is next). Datashare Task 6 waits for this artifact on the ICIJ Maven repo.

---

## Part A2: datashare

### Task 5: `--qrCodes` on both CLI surfaces, and the `keyword` mapping

No extract dependency: can run before the release.

**Files:**
- Modify: `datashare-cli/src/main/java/org/icij/datashare/cli/DatashareCliOptions.java` (constant near line 110, method next to `ocrStrategy` line 702)
- Modify: `datashare-cli/src/main/java/org/icij/datashare/cli/DatashareCli.java:136` (register after `ocrStrategy`)
- Modify: `datashare-cli/src/main/java/org/icij/datashare/cli/command/PipelineOptions.java` (field after `ocrStrategy` line 70, `putIfNotNull` after line 126)
- Modify: `datashare-index/src/main/resources/datashare_index_mappings.json` (`metadata.properties`)
- Test: `datashare-cli/src/test/java/org/icij/datashare/cli/DatashareCliTest.java`, `datashare-cli/src/test/java/org/icij/datashare/cli/command/StageRunCommandTest.java`, `datashare-index/src/test/java/org/icij/datashare/text/indexing/elasticsearch/ElasticsearchIndexerTest.java`

**Interfaces:**
- Produces: `DatashareCliOptions.QR_CODES_OPT = "qrCodes"`, `static void qrCodes(OptionParser)`; picocli `@Option(names = {"--qrCodes"}, arity = "1") Boolean qrCodes;`. Property key `qrCodes` = `"true"`/`"false"`, absent when not passed. Help text on both: `Decode QR codes, Data Matrix, Aztec and PDF417 codes in images and add their values to the document text. Off by default. With OCR off, PDF inline images are then indexed as child documents.`

- [ ] **Step 1: Write the failing tests**

```java
// DatashareCliTest
@Test public void test_qr_codes()                                // parseArguments("--qrCodes=true") -> includes entry("qrCodes", "true")
@Test public void test_qr_codes_absent_by_default()              // parseArguments("--stages", "INDEX") -> !containsKey("qrCodes")
@Test public void test_qr_codes_from_settings_file_is_kept()     // settingsFile("qrCodes=true\n"), parseArguments("-s", it) -> includes entry("qrCodes", "true")
// StageRunCommandTest
@Test public void test_qr_codes()                                // parse("stage","run","--stages","SCAN,INDEX","--qrCodes","true") -> entry("qrCodes","true")
@Test public void test_qr_codes_absent_by_default()              // -> !containsKey("qrCodes")
// ElasticsearchIndexerTest
@Test public void test_qr_codes_metadata_is_mapped_as_keyword()  // createIndex("test-qr-codes"), GET /test-qr-codes/_mapping
                                                                 // contains "\"tika_metadata_qr_codes\":{\"type\":\"keyword\"}"
```

- [ ] **Step 2: Run them to see them fail**

Run: `mvn -q -pl datashare-cli,datashare-index -am test -Dtest=DatashareCliTest,StageRunCommandTest,ElasticsearchIndexerTest -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.gitcommitid.skip=true`
Expected: `test_qr_codes` fails on both surfaces (unrecognized option), the mapping test fails on the missing field. The `absent_by_default` and `settings_file_is_kept` tests pass already: they guard against someone adding a default later.

- [ ] **Step 3: Implement** following `artifactsForce` (`DatashareCliOptions.java:470-477`, `PipelineOptions.java:26-33`): `withRequiredArg().ofType(Boolean.class)` and no default on JOpt; nullable `Boolean`, `arity = "1"`, no `defaultValue` on picocli. Mapping: `"tika_metadata_qr_codes": { "type": "keyword" }`.

- [ ] **Step 4: Run them to see them pass** (same command). Expected: all pass.

- [ ] **Step 5: `make format && make lint`, then commit** (`/commit`, message `feat(cli): add --qrCodes option`)

### Task 6: bump extract and prove the option reaches the `Extractor`

Blocked on the extract release checkpoint.

**Files:**
- Modify: `pom.xml:100` (`extract.version` `9.19.0` to the released version; this also pulls in extract 9.20 to 9.23)
- Test: `datashare-app/src/test/java/org/icij/datashare/tasks/IndexTaskTest.java` (next to `ocrStrategy` test, line ~110)

**Interfaces:**
- Consumes: extract `qrCodes` option and `Extractor.isExtractInlineImages()` (Task 3), property key `qrCodes` (Task 5).

- [ ] **Step 1: Write the failing test**

```java
@Test public void test_qr_codes_reaches_extractor()
    // indexTask.options().createFrom(Options.from(Map.of("qrCodes", "true", "ocr", "false", "queueName", "test:queue")))
    // new Extractor(new DocumentFactory().configure(bound), bound).isExtractInlineImages() == true
```

- [ ] **Step 2: Run it to see it fail** (before the bump)

Run: `mvn -q -pl datashare-app -am test -Dtest=IndexTaskTest -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.gitcommitid.skip=true`
Expected: FAIL, `expected:<true> but was:<false>` (old extract ignores `qrCodes`).

- [ ] **Step 3: Bump `extract.version`** in `pom.xml:100`.

- [ ] **Step 4: Run it to see it pass**, then the datashare-app suite in the background (poll and report; separate the known env failures from new ones).

- [ ] **Step 5: `make format && make lint`, then commit** (`/commit`, message `feat: index QR code values with --qrCodes`)

---

## Suggested follow-ups (not in this plan)

- Part B plan: `QRCODES` catch-up stage (reuses `QRCodeDecoder.decode(Path)` and Task 4).
- Existing indexes need `tika_metadata_qr_codes` added to their mapping before `--qrCodes` writes to them; Part B owns the put-mapping.

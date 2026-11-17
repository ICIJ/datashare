# QR code extraction design

Date: 2026-11-17
Repos: `extract` (extract-lib) and `datashare`

## Intent

Some documents carry information only inside a QR code: a whole image that is a QR code, or a QR code inside a PDF, a Word file or an email attachment. Today that information is invisible. Nothing in datashare or extract-lib decodes QR codes or barcodes, and Tesseract (the only image reader) does not read them.

A journalist should be able to:

1. **Search** for a value that only exists inside a QR code (a URL, a wallet address, an ID) and find the document.
2. **See** the decoded value when reading the document.

## Goals

- Decode 2D codes found in images during indexing and add their values to the document's text.
- Work whether OCR is on or off, behind its own opt-in option.
- Offer a catch-up pass for projects already indexed, so they do not need a full reindex to get QR text on images they already hold.

## Non-goals

- 1D barcodes (EAN, Code 128, ...): high false-positive rate on text-heavy scans.
- QR codes on PDF pages rendered for OCR (`--ocrStrategy` `AUTO`, `OCR_AND_TEXT_EXTRACTION` or `OCR_ONLY`). Those strategies render whole pages and turn off inline image extraction (`Extractor.java:647`), so no image document exists to decode. Follow-up if needed.
- Any client (UI) change, and any ES mapping change beyond the one field below.
- WebP images (Claude's call): Java's `ImageIO` has no WebP reader, and adding one registers it JVM-wide, changing indexing for projects with the option off.
- Parsing or following the decoded values (no URL fetching, no vCard/Wi-Fi parsing).

## Anti-goals

- Changing what gets indexed for projects that do not turn the option on. With the option off, the index must be byte-for-byte what it is today.
- Duplicated `QR code:` lines when the catch-up pass runs twice, or runs on documents already decoded at index time.
- Making OCR slower or less reliable. A decoding failure must never fail the document or skip its OCR.

## Decisions

### Formats

QR code, Data Matrix, Aztec and PDF417, through ZXing (`com.google.zxing:core`, pure Java, Apache 2.0). These are the 2D formats ZXing's `MultiFormatReader` supports, at no extra cost.

### Storage

Two places, both written for every decoded value:

1. **`content`**: one line per value appended after the document's existing text: `QR code: <value>`. The label stays `QR code` for every 2D format, because that is what users call them. Search and the viewer already use `content`, so this covers both goals with no mapping or client change.
2. **`metadata.tika_metadata_qr_codes`**: a multi-valued list of raw decoded values (Tika `Property` named `qr_codes`, spewed with the usual `tika_metadata_` prefix). It records that the image was decoded, lets the catch-up pass skip already-done documents, and allows filtering later. It gets an explicit `keyword` mapping in `datashare_index_mappings.json` (Claude's call): `metadata.*` is otherwise mapped dynamically with date detection on (`datashare_index_mappings.json:4-9`), so a first value that looks like a date would type the field as `date` and silently drop every later URL. This is the only mapping change; existing indexes need the field added to their mapping before the catch-up stage writes it.

An image with no code gets neither the line nor the metadata field.

### Option

- extract-lib option `qrCodes`, default `false`.
- datashare option `--qrCodes`, default off. It must be wired on both CLI surfaces: JOpt (`DatashareCliOptions` + `DatashareCli`) and picocli (`PipelineOptions`), and reach the `Extractor` through `IndexTask` the same way `ocrStrategy` does. Do not give it a JOpt `defaultsTo`: that clobbers a value set in a `-s` settings file.

## Approaches

| | Approach | Trade-off | Picked |
|---|---|---|---|
| A | Parser decorator in extract-lib, decoding images during INDEX | Covers standalone images and images inside documents; existing projects need a reindex | Yes, first |
| B | Datashare catch-up stage over image documents already in the index | No reindex; cannot see PDF images that were never extracted (OCR-off projects) | Yes, second |
| - | Reuse a Tika barcode parser | Tika ships none | Dropped |
| - | Post-process OCR output | Ties QR decoding to OCR being on, against the decision above | Dropped |

Pierre picked both: A first, then B, sharing one decoder class from extract-lib.

## Part A: extract-lib (ships first)

### Decoder

A small class in extract-lib (suggested `org.icij.extract.qrcode.QRCodeDecoder`) that takes an image (`BufferedImage` or stream) and returns the list of decoded strings, empty when none. It uses ZXing's multi-code reader so several codes in one image are all returned, in reading order, deduplicated. It never throws on undecodable or unreadable images: it returns empty and logs at debug level. Both A and B call it.

### Hook

Images reach Tika's image parsers (`ImageParser`, `JpegParser`, `TiffParser`, ...) whether OCR is on or off. With OCR on, `AbstractImageParser` then routes the same image to the OCR parser through a synthetic `image/ocr-*` type (see the comment in `OCRParserAdapter.java:28`). When OCR is off, `disableOcr()` excludes the OCR parsers (`Extractor.java:675`) and only the image parsers run.

A decorator around the image parsers, applied with `replaceParser` like `CacheParserDecorator`, works in both cases:

1. Spool the input to a `TikaInputStream` file so it can be read twice.
2. Delegate to the wrapped parser (metadata, plus OCR when on), writing to the handler as today.
3. Decode the image, then write each `QR code: <value>` line to the handler as its own paragraph and add the values to the metadata.

The builder may choose another hook if it reads the image once and keeps both properties: it works with OCR off, and it does not depend on OCR fan-out (`ocrFanout`) or the deferred OCR pool.

### Inline images when OCR is off

Today `disableOcr()` and `setOcrStrategy()` turn off `extractInlineImages` when OCR is off (`Extractor.java:647`, `:681`). With `qrCodes` on, inline images must be extracted when the strategy is `NO_OCR`, even with OCR off:

`extractInlineImages = (!ocrDisabled || qrCodes) && strategy == NO_OCR`

Consequence, to be stated in the option's help text: turning `--qrCodes` on for an OCR-off project adds every PDF inline image to the index as a child document.

### On-demand embedded extraction of PDF images

`EmbeddedDocumentExtractor` (used by datashare's `SourceExtractor` to read an embedded document from its root) builds its own `ParseContext` without a `PDFParserConfig` (`EmbeddedDocumentExtractor.java:62-86`), and Tika's default leaves `extractInlineImages` off. So PDF inline images cannot be re-extracted today. Part B needs them: set a `PDFParserConfig` with `extractInlineImages=true` there, in the same extract-lib release. Side effect: opening or downloading a PDF inline-image child document without an artifact cache starts working too.

### Release

Release extract-lib with a minor version bump, then bump `extract.version` in datashare's root `pom.xml` (currently `9.19.0`, `pom.xml:100`).

## Part B: datashare catch-up stage (ships second)

A new pipeline `Stage` (suggested `QRCODES`, not a main stage) running over the project's image documents. Covers standalone images and images embedded in other files, including PDF inline images (Pierre's decision).

1. Select image documents: `contentType` starts with `image/`. The stage applies this filter itself; it does not rely on a user-supplied `--searchQuery` like the ENQUEUEIDX-fed stages (`EnqueueFromIndexTask.java:63-79`).
2. Skip any document whose `_source` already has `metadata.tika_metadata_qr_codes`. This per-document check, not the query, is what guarantees no duplicate lines.
3. Read the image bytes:
   - Standalone image: read the file.
   - Embedded image with an artifact cache (`--artifactDir`): read from the cache.
   - Embedded image without a cache: group the remaining images by root document and re-parse each root once, decoding every image embed it yields. Never one root parse per image: `SourceExtractor.getEmbeddedSource` re-parses the whole root per call (`SourceExtractor.java:95-140`), which on a mailbox with thousands of images is thousands of full parses.
4. Decode with the extract-lib decoder.
5. When values are found: append the `QR code:` lines to `content`, set `contentTextLength` to the new `content.length()` (`Document.java:226`; leave `contentLength`, which is the file size), and set `metadata.tika_metadata_qr_codes`, in one partial update.

Documents with no code are left untouched, so a second run re-decodes them. That is accepted. Documents decoded during INDEX (Part A) are skipped by step 2, so there is no duplication. A root that fails to re-parse is logged and counted; the stage moves on.

B does not need `--qrCodes`; running the stage is the opt-in.

## Left to the builder

- Image size handling: a downscale cap or ZXing hints (`TRY_HARDER`) for large scans, picked from measured cost on real images.
- Which image media types to decorate, as long as JPEG, PNG, TIFF, BMP and GIF are covered.
- How the B stage matches re-parsed embeds to index documents (document id from the same digester, as `SourceExtractor` does).
- How the `qr_codes` keyword mapping reaches existing indexes (put-mapping at stage start, or the existing mapping-update path if there is one).
- Batch size and progress reporting for the B stage, following `LanguageDetectTask`.
- Where the B stage plugs into the CLI and task API, following how `LANGUAGE` is exposed.

## Testing

- Decoder: images with zero, one and several codes, a rotated code, a non-image or truncated input (returns empty, no exception).
- extract-lib: a standalone QR PNG and a PDF holding a QR inline image, each with OCR on and OCR off, with `qrCodes` on and off. With `qrCodes` off, output matches today's.
- extract-lib: `EmbeddedDocumentExtractor` returns a PDF inline image by id.
- datashare: option wiring on both CLI surfaces; B-stage tests proving it appends once, is a no-op on a second run, and parses a root holding several QR images only once (standalone, cached embed, uncached embed in a PDF).
- Test fixtures must be generated QR images, never real corpus files.

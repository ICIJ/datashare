# OCR auto language in Datashare: design

Date: 2026-10-05. Branch: `feat/ocr-auto-language`.

## Goal

A Datashare with default settings OCRs each image with the model of its detected script (extract 9.17.0), so a Chinese screenshot is indexed as Chinese text, with no OCR flag set. A read that tesseract itself scores low is not trusted for language detection, so garbage OCR no longer gets random languages (Finnish, Yoruba, Welsh...).

## Background

Extract 9.17.0 (ICIJ/extract PRs #82 to #86 and #81) routes OCR per image when `ocrLanguage` is empty and the `osd` model plus the six script models (`Latin`, `HanS`, `Cyrillic`, `Arabic`, `Japanese`, `Hangul`) are installed under `tessdata/script/`. Otherwise it falls back to `eng` and warns once. It adds:

- `ocrRetryConfidence` (0 to 100, default 60): below it, the image is read again with every script model.
- A script mapping form for `ocrLanguage`, e.g. `Cyrillic:rus+ukr,Han:chi_sim`, which keeps routing but reads listed scripts with the given languages.
- Per-image metadata: `ocr:script`, `ocr:script_confidence`, `ocr:model`, `ocr:confidence` (0 to 1).

Datashare facts this design relies on:

- `ocrLanguage` has no default on either CLI surface (`DatashareCliOptions.java:643`, `PipelineOptions.java:47`), so after the bump an absent value turns routing on.
- Task args reach `new Extractor(documentFactory, options)` as-is (`IndexTask.java:84-87`).
- Every Tika metadata key is copied to ES by `Spewer.getMetadata` (extract-lib `Spewer.java:220`).
- Each embedded image is its own ES document, and language is guessed per document with that document's metadata in hand (`ElasticsearchSpewer.java:284-294`).
- Ubuntu noble (`eclipse-temurin:21-noble`): `tesseract-ocr` pulls `osd`; the `tesseract-ocr-script-*` packages install models at `/usr/share/tesseract-ocr/5/tessdata/` root, not under `script/` (checked in a container, tesseract 5.3.4).

## Part A: bump, CLI, packaging

### Version

`pom.xml:99`: `extract.version` 9.16.2 to 9.17.0.

### CLI options, on both surfaces

Wire on JOpt (`DatashareCliOptions` + `DatashareCli`) and picocli (`PipelineOptions` + its `putIfNotNull` block).

| Option | Type | Read by | Default (in code) |
|---|---|---|---|
| `ocrRetryConfidence` | integer 0 to 100 | extract | 60 (extract) |
| `ocrMinConfidence` | integer 0 to 100 | Datashare (Part B) | 60 |

- No JOpt `defaultsTo`: it would clobber a value set in the `-s` settings file.
- No range clamp, matching extract (PR #86 dropped its clamp).
- `ocrLanguage` help text on both surfaces: empty means the language is chosen per image from its detected script when the models are installed, `eng` otherwise; document the `Script:lang+lang,...` mapping form.

### Docker image

`datashare-dist/src/main/docker/Dockerfile`:

1. Add `tesseract-ocr-script-latn`, `-hans`, `-cyrl`, `-arab`, `-jpan`, `-hang` to the `apt install`.
2. Add a `RUN` that links each of the six models from the tessdata root into `tessdata/script/`.

Expected cost: up to about 150 MB of image size (the whole tessdata tree measured 153 MB); measure the real delta at build time.

### deb package

`control` already Recommends `tesseract-ocr-all`, which installs the script models at the tessdata root. `postinst` creates the same six links when the source model exists and the link does not, so it never fails the install.

### Tests

- One test per CLI surface: both options reach the task properties.
- Docker build check: `tesseract --list-langs` inside the image lists `osd` and the six `script/*` entries.

## Part B: low-confidence OCR gives no language from content

### Rule

A new default method on `LanguageGuesser` (`datashare-api/src/main/java/org/icij/datashare/text/indexing/LanguageGuesser.java`):

```java
default Language guess(String text, Path path, Double ocrConfidence, int minOcrConfidence) {
    boolean untrusted = ocrConfidence != null && ocrConfidence * 100 < minOcrConfidence;
    return guess(untrusted ? "" : text, path);
}
```

- An untrusted read counts as empty content, so the existing file-name fallback still applies: a long or self-naming file name keeps a language, a camera code stays UNKNOWN.
- No recorded confidence (fixed `ocrLanguage`, documents indexed before the bump, non-image documents): behavior unchanged.
- `ocr:confidence` is 0 to 1 (`AutoLanguageOCRParser.java:153`); the option is 0 to 100 like `ocrRetryConfidence`, hence `* 100`.
- A malformed confidence value is treated as absent and never fails the document.

### Call sites

1. INDEX, `ElasticsearchSpewer.java:293`: confidence from the Tika metadata key `ocr:confidence`; threshold from `configure(Options)`, which `IndexTask` calls with the task options (`IndexTask.java:85`).
2. LANGUAGE, `LanguageDetectTask.java:91`: confidence from the document's metadata map, key `tika_metadata_ocr_confidence`; threshold from the task's `propertiesProvider`.

Both default to 60 when `ocrMinConfidence` is absent. The backfill only changes documents indexed with extract 9.17.0 or later; older ones carry no confidence and need re-extraction (SCANQUERY) to benefit.

### Tests (TDD, failing first)

- `LanguageGuesser`: below threshold, above threshold, absent confidence, low confidence with a long file name.
- Spewer: an image document with `ocr:confidence=0.3` is indexed with language UNKNOWN.
- LANGUAGE stage: the same document read back from ES stays UNKNOWN; this also pins the `tika_metadata_ocr_confidence` key name.

## Behavior changes after release

| Install | Before | After |
|---|---|---|
| Docker | OCR in `eng` | OCR per image from detected script |
| deb with `tesseract-ocr-all` | OCR in `eng` | per image (via `postinst` links) |
| `--ocrLanguage` set | that language | unchanged |
| Models missing | `eng` | `eng`, one warning at startup |

Cost per image (measured in the extract brainstorm): about 0.23 s detection plus 0.2 to 0.5 s OCR, about 3 s when the retry runs; 2 to 4x the OCR time on image-heavy corpora. `--ocrLanguage eng` restores the old behavior exactly. Both go in the release notes.

## Commits

1. `feat`: bump extract to 9.17.0 and add the OCR confidence options.
2. `build`: install and link the tesseract script models (Docker, deb).
3. `feat`: ignore low-confidence OCR text when detecting the language.

## Out of scope (follow-ups)

- `datashare-installer` (Mac, Windows) and snap packaging of the script models.
- `.devcontainer` and CircleCI tesseract setup.
- Using `ocr:script` to pick or narrow the language (rejected for now: correct OCR text already detects well).

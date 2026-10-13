# Design: tabular source reader (`RowSource` SPI)

Issue [#2198](https://github.com/ICIJ/datashare/issues/2198), part of epic
[#2194](https://github.com/ICIJ/datashare/issues/2194) (import structured entities from tabular data
using a data schema). Designed 2026-08-20 against `main` at `f404bb5e2`. No dependencies: per the
epic's 2026-08-19 state check this is the one sub-issue startable with nothing landed before it.

Consumers: the mapping executor #2204 (rows in, statements out), which #2206's task drives. Neither
exists yet.

## Goal

Read rows out of a tabular file with the columns intact, deliberately avoiding the normal
text-extraction path that flattens a table into a wall of characters. One interface, several
implementations chosen by file type and overridable by the mapping, so a format we do not read today
is a new class rather than a change to the ones we do.

## Current state

No epic code exists on `main`: zero hits for `RowSource`, `TargetModel`, `extraction_mapping` or
`statement`. The only FollowTheMoney code is `org.icij.datashare.ftm.FtmDocument` plus the
`FtmResource` endpoint, both in `datashare-app`, which is also the only module holding `ftm.java`,
`extract-lib`, `datashare-index` and `datashare-db` together.

Four facts about `main` that this design leans on, all verified rather than assumed:

- **A document's content type is stored bare.** `ElasticsearchSpewer` reads Tika's `Content-Type`
  and strips everything after the first `;` (`ElasticsearchSpewer.java:248-251`) before
  `Document.getContentType()` (`Document.java:215`) sees it. Parameters do not survive there.
- **The parameters do survive in metadata.** extract-lib's `MetadataTransformer` passes values
  through unchanged and normalizes names via `FieldNames.forMetadata`, which lowercases and replaces
  non-word characters with `_`, under the prefix `tika_metadata_`
  (`DocumentMetadataConstants.java:8`). `Document.java:252` already reads a key that way. Tika's
  `TextAndCSVParser` writes `csv:delimiter`, `csv:num_columns` and `csv:num_rows`, so a delimited
  document carries `tika_metadata_csv_delimiter` holding one of `comma`, `tab`, `pipe`,
  `semicolon` (the default map in Tika's `TextAndCSVConfig`).
- **Bytes for a document are already solved, including embedded ones.**
  `SourceExtractor.getSource(project, document)` (`SourceExtractor.java:63`) returns a stream for a
  root document from its path and for an embedded document by re-extracting it from its container.
  For a root document that stream is a `TikaInputStream` (`SourceExtractor.java:84`), which exposes
  `hasFile()` and `getPath()`.
- **A table-intact rendering of Tika-parsed documents already exists.**
  `StructureMarkdownExtractor.extract` (`:153`) returns `Page(String xhtml, String markdown)`
  (`:146`) per page, and `StructureArtifact` persists both (`StructureArtifact.java:204-208`). The
  jsoup `Safelist` is `relaxed()` minus `<u>` plus `del`/`hr` (`StructureMarkdownExtractor.java:76-80`),
  and relaxed keeps `table`, `thead`, `tbody`, `tr`, `th`, `td` with `colspan`/`rowspan`.

## Design

Two tiers behind one interface. Tier 1 parses the three formats where fidelity matters with a
format-specific library. Tier 2 is a single fallback over Tika's XHTML that buys every other format
whose parser emits a table, at the cost of lower value fidelity and a size ceiling.

### The contract

In `datashare-api`, package `org.icij.datashare.tabular`. No new dependency lands in that module.

```java
public interface RowSource {
    boolean supports(String contentType);
    Stream<Row> rows(InputStream source, RowSourceOptions options) throws IOException;
}

public record Row(long number, Map<String, String> values) {}

public record RowSourceOptions(String contentType, Charset charset, Character delimiter,
                               Character quote, String sheet, Integer table) {}
```

`Stream<Row>` rather than a bespoke `RowStream` type: it is already `AutoCloseable`, `onClose`
releases the workbook, parser and underlying stream, and it composes with `limit`/`flatMap` in #2204
without an iterator wrapper of ours. Callers use try-with-resources.

Every cell value is a `String`. FollowTheMoney property values are strings, and the mapping format
already carries a `format:` value function for dates, so #2204 never branches on where a row came
from. The one place this needs care is Excel, below.

`Row.number` is the 1-based data row, header excluded. #2202 needs it for provenance and every
error message needs it to be legible.

A null field in `RowSourceOptions` means "reader default", and a field a given reader has no use for
is ignored rather than rejected: `delimiter` and `quote` are `DelimitedRowSource` only, `sheet` is
`WorkbookRowSource` only, `table` is `TikaTableRowSource` only, and `charset` does not reach tier 2
at all because Tika resolves encoding itself. #2201 populates the record from the persisted mapping,
so **nothing in #2198 parses mapping YAML**.

One rule holds across every reader: a column whose header is blank is dropped, and a duplicate
header name fails loudly naming the column.

### Tier 1: native readers

All three live in `datashare-app`, alongside the existing `org.icij.datashare.ftm` package.

**`DelimitedRowSource`** (commons-csv), for `text/csv`, `text/tab-separated-values` and
`text/plain`. The first record is the header. Delimiter resolution order: the mapping's override,
then `tika_metadata_csv_delimiter` off the document, then comma. **No sniffer of our own.** Tika
already sniffed with a confidence-scored `CSVSniffer` at index time and recorded the answer, so
reading that field costs nothing, needs no tests of its own, and cannot disagree with the delimiter
Tika used to produce the document's indexed text. Charset comes from the mapping's override, then
`Document.getContentEncoding()` (`Document.java:213`).

**`WorkbookRowSource`** (POI `WorkbookFactory`), for `.xlsx`, `.xls` and `.xlsm`. One code path
covers XSSF and HSSF. The first sheet unless `options.sheet()` names one. That field resolves by
name first and only then, if no sheet carries that name and the string parses as an integer, as a
1-based index, so a sheet literally named `2` still wins over position 2.

Cells go through `DataFormatter` with a formula evaluator, **except** cells where
`DateUtil.isCellDateFormatted` is true, which render as ISO-8601: `ISO_LOCAL_DATE` when the time
component is midnight, `ISO_LOCAL_DATE_TIME` otherwise. Without that exception `DataFormatter`
renders a date using the spreadsheet's own display style, which would make a mapping's `format:`
depend on how somebody styled the file. Blank cells are `""`, never null. A row whose every cell is
empty is skipped, with the count logged, because real workbooks trail hundreds of them.

The whole workbook is loaded in memory, roughly 5 to 10 times the file size in heap. This is
accepted rather than overlooked: the contract is streaming-shaped, so replacing this with an
`XSSFReader` plus `XSSFSheetXMLHandler` reader later touches neither the interface nor #2204. The
alternative today costs two Excel code paths (HSSF has no practical streaming equivalent) and
hand-wiring `StylesTable` and `SharedStrings` to reproduce cell formatting, for a format that is
usually small.

**`JsonRowSource`** (Jackson), for `application/json` and NDJSON. Peek the first token: `START_ARRAY`
means an array of objects, anything else means line-delimited or concatenated objects, so one reader
covers both shapes. Nested values dot-flatten (`{"addr":{"city":"Paris"}}` becomes column
`addr.city`), arrays of scalars join on a fixed `|`, arrays of objects are skipped. The separator is
a constant rather than an option so a mapping can rely on splitting it; if multi-valued properties
turn out to matter, the right fix is a multi-value seam in the contract, not a cleverer separator.
Records may carry
different keys; keying by column name makes that a non-issue, and #2204 treats a missing column as
absent.

### Tier 2: `TikaTableRowSource`

Reuses `StructureMarkdownExtractor.extract(stream, contentType, filename, OcrSettings.NONE)` and
selects `<table>` out of each `Page.xhtml()` with jsoup. Roughly 30 lines. Reuse rather than a fresh
Tika setup because that class already owns the resilient PST parser swap, the output cap, the page
splitting and the sanitizer, which are exactly the parts worth not duplicating. `Page.markdown()` is
ignored; if the wasted flexmark conversion ever shows up in a profile, that class gains an
xhtml-only method rather than this one gaining a parser.

OCR is off (`OcrSettings.NONE`): a tabular source does not need it, and leaving it off keeps the
call cheap and deterministic.

**It claims a verified allowlist, not everything unclaimed.** Only the six content types whose
parser was confirmed to emit `table`/`tr`/`td`. A permissive claim would have this reader own
`application/pdf` and then fail on it, forcing a future PDF reader to displace an incumbent. An
allowlist is also less code: a `Set.of(...)` membership test instead of parse-then-hunt-for-tables
plus its error path.

Table selection is by 1-based index (`options.table()`), not by name. The Safelist strips a sheet's
name attribute, so ODS sheet names are unavailable on this path. Name-based selection stays an Excel
feature. Keeping `table` in `RowSourceOptions` as a format-agnostic field is deliberate: any future
reader over a document with N unnamed tables wants the same selector.

The first `<tr>` is the header, `<th>` and `<td>` both counting as cells. `colspan` and `rowspan`
survive the Safelist and make column alignment undefined, so **a header row carrying either fails
loudly** rather than silently misaligning every value under it.

Two costs, both accepted and documented rather than hidden:

1. **Lower value fidelity.** Cells arrive pre-formatted with the cell type discarded, so a date is
   whatever the source rendered and the mapping's `format:` has to handle it. Tier 1 keeps the
   ISO-8601 guarantee; tier 2 does not. The format matrix below says which tier a format is on.
2. **A size ceiling, but a loud one.** `toXhtml` wraps the handler in
   `WriteOutContentHandler(xhtmlHandler, maxOutputChars)` (`StructureMarkdownExtractor.java:243-249`,
   default 16,000,000 characters at `:93`) and `extract` does not catch what that throws, so an
   oversized document **fails** instead of importing half a file. Silent truncation would be
   unacceptable in a data import; an exception is not. Memory is the whole document buffered several
   times over, which is why large files belong on tier 1.

### Format matrix

Selection keys on the effective content type. Tier 1 readers are consulted first, tier 2 last.

| Content type | Extensions | Reader | Tier |
|---|---|---|---|
| `text/csv` | `.csv` | `DelimitedRowSource` | 1 |
| `text/tab-separated-values` | `.tsv` | `DelimitedRowSource` | 1 |
| `text/plain` | `.txt`, `.psv`, extensionless | `DelimitedRowSource` | 1 |
| `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` | `.xlsx` | `WorkbookRowSource` | 1 |
| `application/vnd.ms-excel` | `.xls`, `.xlm`, `.xlt`, `.xlw` | `WorkbookRowSource` | 1 |
| `application/vnd.ms-excel.sheet.macroenabled.12` | `.xlsm` | `WorkbookRowSource` | 1 |
| `application/json` | `.json` | `JsonRowSource` | 1 |
| `application/x-ndjson` (synthetic, see below) | `.jsonl`, `.ndjson` | `JsonRowSource` | 1 |
| `application/vnd.oasis.opendocument.spreadsheet` | `.ods` | `TikaTableRowSource` | 2 |
| `application/vnd.ms-excel.sheet.binary.macroenabled.12` | `.xlsb` | `TikaTableRowSource` | 2 |
| `application/vnd.openxmlformats-officedocument.wordprocessingml.document` | `.docx` | `TikaTableRowSource` | 2 |
| `application/msword` | `.doc` | `TikaTableRowSource` | 2 |
| `text/html`, `application/xhtml+xml` | `.html`, `.htm` | `TikaTableRowSource` | 2 |
| `application/vnd.apple.numbers` | `.numbers` | `TikaTableRowSource` | 2 |

Tier 2 entries were verified by confirming the parser class emits table elements:
`odf/OpenDocumentBodyHandler`, `ooxml/XSSFBExcelExtractorDecorator`,
`ooxml/XWPFWordExtractorDecorator`, `microsoft/WordExtractor`, `html/DefaultHtmlMapper`'s safe-element
set, and `iwork/NumbersContentHandler`. `.numbers` is best-effort: that handler covers the older
iWork package format, while newer IWA-based files are detected but not reliably parsed.

`.xlsm` is a free add, being plain OOXML that `WorkbookFactory` opens; only the macros differ.
`.xlsb` cannot go on tier 1 because `WorkbookFactory` does not open it, but Tika reads it, so it
lands on tier 2 instead of being unsupported.

**Two collisions the matrix has to resolve.** `text/csv` has no magic bytes in Tika, only the
`*.csv` glob and `sub-class-of text/plain`, so a comma-separated file named `.txt` or with no
extension arrives as `text/plain`. And Tika 3.3.0 has no mimetype at all for `jsonl` or `ndjson`, so
those arrive as `text/plain` as well, where the delimited reader would otherwise claim them first.

The effective content type is therefore: the mapping's override first; else, when the stored type is
generic (`text/plain` or `application/octet-stream`), an extension lookup on `Document.getName()`
(`Document.java:314`); else the stored type. The extension table only covers the formats above, so it
stays around ten entries, and it maps `.jsonl` and `.ndjson` to the de facto `application/x-ndjson`,
which `JsonRowSource` claims. That string is ours, not Tika's, and never appears on an indexed
document; it exists only as the internal token connecting an extension to a reader.

### Resolution: document id to rows

`TabularRowReader` in `datashare-app`, constructor-injected with `Indexer`, `SourceExtractor`, a
Guice-multibound `Set<RowSource>` of the tier 1 readers, and the tier 2 fallback **as a separate
constructor parameter**. Ordering is then expressed by the constructor ("try the specific readers,
else the general one") rather than by priority integers on the interface or by iteration order of a
set, which is unspecified.

1. `indexer.get(project.getName(), documentId, rootId)` (`Indexer.java:57`), where `rootId` defaults
   to `documentId`. A root document therefore needs nothing extra, and an embedded one works by
   naming its root, which ES routing requires anyway.
2. Effective content type and charset resolved as described above.
3. First tier 1 reader whose `supports` answers true, else the fallback if it claims the type, else
   fail naming the type and listing what is supported.
4. `sourceExtractor.getSource(project, document)` for the bytes, so a CSV inside a ZIP or a workbook
   attached to an email works with no extra code. The returned `Stream`'s `onClose` closes it.

Because the mapping names a document rather than a path, there is no user-supplied path and no
traversal surface on this route. `DataDirVerifier.allowed()` (`DataDirVerifier.java:29`) compares
lexically without `normalize()`, so `dataDir/../../etc` passes it today; that is a real weakness but
it is not on this path and is deliberately not fixed here. Authorization on the project belongs to
#2199 and #2207, not to this reader.

### Errors

Fail the whole read rather than skip rows. A partial import that reports success is worse than a
refusal, and #2206 owns whatever partial-progress story it wants.

- Unsupported content type: names the type and the supported set.
- Malformed record (an unterminated quote, for instance): reports the row number.
- Missing or unreachable document: reports the id.
- No header row: fails. A valid header with zero data rows yields an empty stream, which is not an
  error.
- Duplicate header name: fails naming the column.
- Header row with `colspan`/`rowspan` on tier 2: fails.
- Tier 2 output cap exceeded: fails, per the reuse of `StructureMarkdownExtractor`.

## Testing

TDD, with failing output shown before any production code.

The issue's last checklist item drives the shape: one logical table materialized as `companies.csv`,
a semicolon-delimited `companies.csv`, `companies.tsv`, `companies.xlsx`, `companies.xls`,
`companies.json`, `companies.jsonl` and `companies.ods`, then one parameterized test asserting every
reader returns an identical `List<Row>`. The `.ods` twin puts tier 2 under the same equivalence
assertion as tier 1.

Per-reader edges: a quoted field containing both the delimiter and a newline; UTF-8 against
windows-1252; the delimiter taken from `tika_metadata_csv_delimiter`; an Excel date cell rendering
ISO-8601; a formula cell rendering its computed value; trailing blank rows skipped; a named sheet;
nested JSON dot-flattening; a JSON array against NDJSON; a tier 2 table selected by index; a tier 2
header row with `colspan` refused; a `.xlsb` read through tier 2.

Resolution tests mock the `Indexer` over a real temp file, so no Elasticsearch is needed and the
suite stays fast. Note the local gotchas: `-Dtest` needs commas rather than `+`, `-am` must not be
dropped, and a single-test run needs `-Dsurefire.failIfNoSpecifiedTests=false`.

## Out of scope, and one open slot

Deferred behind the same interface, needing no change to it: Parquet, ORC and Avro (no Tika parser
exists).

**PDF and RTF are unclaimed rather than out of scope.** The whole `tika-parser-pdf-module` contains
no table emission, and `rtf/TextExtractor` emits none either, so neither can ride tier 2. A mapping
over a PDF fails today with "no reader supports application/pdf". A tool that genuinely detects
tables in a PDF, docling being the candidate, would arrive as a new `RowSource` and one `addBinding`
line, with no edit to any existing reader. Nothing here forecloses it:

- The `InputStream` signature does not, even though such a tool runs as a Python subprocess or HTTP
  service and wants a file. `SourceExtractor` returns a `TikaInputStream` for root documents
  (`SourceExtractor.java:84`), and that type exposes `hasFile()` and `getPath()`, so a sidecar reader
  passes the real file through when one exists and spools to a temp file only for an embedded
  document.
- The `table` index selector does not: a PDF also yields N unnamed tables, so it wants exactly that
  field.

No sidecar plumbing, HTTP client or header-detection abstraction is built now. Such a tool returns
span-aware cells and real header metadata, and designing an interface for that before running it
would be guesswork. What it needs from us is `supports(String)` and `Stream<Row> rows(...)`, which
tier 1 and tier 2 already share. The epic already carries a Python sidecar as a deferred item.

Also out: mapping YAML parsing (#2201), authorization (#2199, #2207), and SAX streaming for XLSX.

## Dependencies

No new runtime dependency, but two declarations to make explicit in `datashare-app/pom.xml`:

- `commons-csv`, already version-managed at `pom.xml:396-400` (1.14.0, pinned over jcasbin's
  transitive 1.12.0).
- POI (`poi`, `poi-ooxml`, `poi-scratchpad` for legacy HSSF), which today reaches `datashare-app`
  only transitively through `extract-lib`. Declaring it explicitly means an extract-lib bump cannot
  silently remove it.

Jackson is already a direct `datashare-api` dependency, and `datashare-app` already depends on
`datashare-index` for `StructureMarkdownExtractor` and `SourceExtractor`.

**First plan step is verification, not assertion:** run `mvn dependency:tree` for `datashare-app` and
read the POI version that actually resolves. Two versions sit in the local repository (5.4.0 and
5.5.1) and the resolved one has not been established.

## Decisions taken during design

Recorded because each closes a question that would otherwise reopen during implementation.

| Decision | Rationale |
|---|---|
| Cell values are `String`, never `Object` | FtM property values are strings and the mapping has `format:`. Typed values would give CSV a permanently different code path from Excel in #2204. |
| Excel date cells render ISO-8601 | `DataFormatter` otherwise renders a date using the spreadsheet's own style, making a mapping depend on how the file was styled. |
| Whole workbook in memory | Streaming costs two Excel code paths and manual styles wiring. The streaming-shaped contract keeps the upgrade free. |
| Source named by document id, not path | Buys embedded sources through `SourceExtractor`, plus the content type and charset Tika already detected, and removes the path-traversal surface entirely. |
| First sheet, mapping may name one | One sheet is one table, which is what a column mapping means. Concatenating sheets is silently wrong whenever their columns differ. |
| JSON nesting dot-flattens | Makes real API dumps mappable without a jq pre-processing step, for about 20 lines, and column names stay strings. |
| Injected set plus separate fallback, not `ServiceLoader` | Three in-tree readers need no discovery mechanism. `ServiceLoader` would force no-arg constructors and classpath games in tests. |
| No delimiter sniffer of our own | Tika already sniffed and recorded the answer in `tika_metadata_csv_delimiter`. |
| Tier 2 claims an allowlist, not everything unclaimed | Otherwise it owns `application/pdf` and a future PDF reader has to displace an incumbent. Also less code. |

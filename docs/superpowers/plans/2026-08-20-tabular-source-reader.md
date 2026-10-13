# Tabular Source Reader Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Read rows out of a tabular file with columns intact, behind one interface with several implementations chosen by content type.

**Architecture:** Two tiers behind a `RowSource` interface. Tier 1 parses CSV with commons-csv, Excel with POI and JSON with Jackson, keeping full value fidelity. Tier 2 is one fallback that reuses `StructureMarkdownExtractor` to pull `<table>` out of Tika's XHTML, covering ODS, XLSB, DOCX/DOC, HTML and Numbers. A `TabularRowReader` resolves a document id to bytes through `Indexer` and `SourceExtractor`, picks the reader, and returns a `Stream<Row>`.

**Tech Stack:** Java 17, commons-csv 1.14.0, Apache POI 5.5.1, Jackson, jsoup 1.22.1, Tika 3.3.0 (via extract-lib), Guice, JUnit 4, fest-assert.

**Spec:** `docs/superpowers/specs/2026-08-20-tabular-source-reader-design.md`

## Global Constraints

- Every cell value is a `String`. No `Object`, no per-format typed values.
- Excel date cells render ISO-8601: `ISO_LOCAL_DATE` when the time component is midnight, `ISO_LOCAL_DATE_TIME` otherwise.
- `Row.number` is the 1-based data row, header excluded.
- Across every reader: a blank header name means the column is dropped; a duplicate header name fails loudly naming the column.
- A reader ignores `RowSourceOptions` fields it has no use for rather than rejecting them.
- No mapping YAML parsing anywhere in this issue. Options arrive already populated.
- No new Maven dependency. Only explicit declaration of already-resolved ones.
- Fail the whole read rather than skip rows. Mid-stream failures use `UncheckedIOException` because a `Spliterator` cannot throw a checked `IOException`.
- Tests: JUnit 4 (`org.junit.Test`), fest-assert (`org.fest.assertions.Assertions.assertThat`), method names `test_snake_case`.
- Test command: `mvn -pl datashare-app -am test -Dtest=X -Dsurefire.failIfNoSpecifiedTests=false`. Multiple classes are comma-separated. Never drop `-am`.

## Two deviations from the spec, both verified

1. **POI version is 5.5.1.** The spec left this as the first verification step. `mvn -pl datashare-app dependency:tree` resolves `org.apache.poi:poi`, `poi-scratchpad`, `poi-ooxml` and `poi-ooxml-full` all at 5.5.1, compile scope, and `commons-csv` at 1.14.0.
2. **No Guice multibinding.** The spec called for a multibound `Set<RowSource>`. There is no `Multibinder` or `MapBinder` anywhere in the repo, and the established convention is hand-wiring collaborators at the call site. So `TabularRowReader` builds its own reader list in its public constructor, with a package-private constructor taking the list for tests. No Guice code is added by this issue at all; #2206 constructs the reader when it needs one.

## File Structure

| File | Responsibility |
|---|---|
| `datashare-api/src/main/java/org/icij/datashare/tabular/RowSource.java` | The interface. Two methods. |
| `datashare-api/src/main/java/org/icij/datashare/tabular/Row.java` | One row: number plus column-to-value map. |
| `datashare-api/src/main/java/org/icij/datashare/tabular/RowSourceOptions.java` | Per-read knobs, all nullable. |
| `datashare-app/src/main/java/org/icij/datashare/tabular/DelimitedRowSource.java` | CSV and delimited variants. |
| `datashare-app/src/main/java/org/icij/datashare/tabular/WorkbookRowSource.java` | XLSX, XLSM, XLS. |
| `datashare-app/src/main/java/org/icij/datashare/tabular/JsonRowSource.java` | JSON array, NDJSON, dot-flattening. |
| `datashare-app/src/main/java/org/icij/datashare/tabular/TikaTableRowSource.java` | Tier 2 fallback over Tika XHTML tables. |
| `datashare-app/src/main/java/org/icij/datashare/tabular/TabularRowReader.java` | Document id to rows: content-type resolution, reader selection, bytes. |
| `datashare-app/pom.xml` | Declare commons-csv, POI, jsoup explicitly. |

Header parsing is shared by `DelimitedRowSource` and `TikaTableRowSource` (drop blanks, reject duplicates). It is 6 lines and lives as a static helper on `Row` so both tiers apply one rule, rather than as a fourth API type.

---

### Task 1: Contract, pom declarations, and the delimited reader

**Files:**
- Create: `datashare-api/src/main/java/org/icij/datashare/tabular/RowSource.java`
- Create: `datashare-api/src/main/java/org/icij/datashare/tabular/Row.java`
- Create: `datashare-api/src/main/java/org/icij/datashare/tabular/RowSourceOptions.java`
- Create: `datashare-app/src/main/java/org/icij/datashare/tabular/DelimitedRowSource.java`
- Modify: `datashare-app/pom.xml`
- Test: `datashare-app/src/test/java/org/icij/datashare/tabular/DelimitedRowSourceTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `RowSource.supports(String)` and `RowSource.rows(InputStream, RowSourceOptions)`; `Row(long number, Map<String,String> values)` plus `Row.headers(List<String>)`; `RowSourceOptions(String contentType, Charset charset, Character delimiter, Character quote, String sheet, Integer table)` plus `RowSourceOptions.defaults()`; `DelimitedRowSource()` no-arg constructor.

- [ ] **Step 1: Write the contract types**

`RowSource.java`:

```java
package org.icij.datashare.tabular;

import java.io.IOException;
import java.io.InputStream;
import java.util.stream.Stream;

/**
 * Streams the rows of a tabular source with its columns intact. Implementations are stateless and
 * reusable; each call to {@link #rows} owns the stream it is given and releases it when the returned
 * stream is closed, so callers must use try-with-resources.
 */
public interface RowSource {
    boolean supports(String contentType);

    Stream<Row> rows(InputStream source, RowSourceOptions options) throws IOException;
}
```

`Row.java`:

```java
package org.icij.datashare.tabular;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** One data row. {@code number} is 1-based and excludes the header row. */
public record Row(long number, Map<String, String> values) {

    /**
     * Applies the one header rule every reader shares: a blank name means the column is dropped
     * (represented by a null entry the caller skips), a duplicate name is a mapping the reader
     * cannot honour unambiguously and so fails.
     */
    public static List<String> headers(List<String> rawNames) {
        List<String> headers = new ArrayList<>();
        for (String rawName : rawNames) {
            String name = rawName == null ? "" : rawName.strip();
            if (name.isEmpty()) {
                headers.add(null);
                continue;
            }
            if (headers.contains(name)) {
                throw new IllegalArgumentException("duplicate column name in header: " + name);
            }
            headers.add(name);
        }
        return headers;
    }
}
```

`RowSourceOptions.java`:

```java
package org.icij.datashare.tabular;

import java.nio.charset.Charset;

/**
 * Per-read knobs, every one nullable, a null meaning the reader's own default. A reader ignores the
 * fields it has no use for: {@code delimiter} and {@code quote} are delimited-text only, {@code
 * sheet} is Excel only, {@code table} is the Tika fallback only, and {@code charset} does not reach
 * the fallback, which lets Tika resolve encoding itself.
 */
public record RowSourceOptions(String contentType, Charset charset, Character delimiter,
                               Character quote, String sheet, Integer table) {

    public static RowSourceOptions defaults() {
        return new RowSourceOptions(null, null, null, null, null, null);
    }

    public RowSourceOptions withContentType(String newContentType) {
        return new RowSourceOptions(newContentType, charset, delimiter, quote, sheet, table);
    }

    public RowSourceOptions withCharset(Charset newCharset) {
        return new RowSourceOptions(contentType, newCharset, delimiter, quote, sheet, table);
    }

    public RowSourceOptions withDelimiter(Character newDelimiter) {
        return new RowSourceOptions(contentType, charset, newDelimiter, quote, sheet, table);
    }
}
```

- [ ] **Step 2: Declare the dependencies explicitly**

In `datashare-app/pom.xml`, add these inside `<dependencies>`. No `<version>` on any of them: commons-csv is managed at `pom.xml:396-400`, jsoup at `pom.xml:463-466`, and POI comes from Tika's managed set. They reach `datashare-app` transitively through `extract-lib` today, so declaring them means an extract-lib bump cannot silently remove them.

```xml
        <dependency>
            <groupId>org.apache.commons</groupId>
            <artifactId>commons-csv</artifactId>
        </dependency>
        <dependency>
            <groupId>org.apache.poi</groupId>
            <artifactId>poi</artifactId>
        </dependency>
        <dependency>
            <groupId>org.apache.poi</groupId>
            <artifactId>poi-ooxml</artifactId>
        </dependency>
        <dependency>
            <groupId>org.apache.poi</groupId>
            <artifactId>poi-scratchpad</artifactId>
        </dependency>
        <dependency>
            <groupId>org.jsoup</groupId>
            <artifactId>jsoup</artifactId>
        </dependency>
```

- [ ] **Step 3: Write the failing test**

`DelimitedRowSourceTest.java`:

```java
package org.icij.datashare.tabular;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

import static org.fest.assertions.Assertions.assertThat;

public class DelimitedRowSourceTest {
    private final DelimitedRowSource source = new DelimitedRowSource();

    private List<Row> read(String content, RowSourceOptions options) throws Exception {
        return read(content.getBytes(StandardCharsets.UTF_8), options);
    }

    private List<Row> read(byte[] content, RowSourceOptions options) throws Exception {
        try (InputStream stream = new ByteArrayInputStream(content);
             Stream<Row> rows = source.rows(stream, options)) {
            return rows.toList();
        }
    }

    @Test
    public void test_supports_delimited_content_types() {
        assertThat(source.supports("text/csv")).isTrue();
        assertThat(source.supports("text/tab-separated-values")).isTrue();
        assertThat(source.supports("text/plain")).isTrue();
        assertThat(source.supports("application/json")).isFalse();
    }

    @Test
    public void test_reads_comma_separated_rows_with_header() throws Exception {
        List<Row> rows = read("id,name\n1,ACME\n2,Globex\n", RowSourceOptions.defaults());

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).number()).isEqualTo(1L);
        assertThat(rows.get(0).values().get("name")).isEqualTo("ACME");
        assertThat(rows.get(1).number()).isEqualTo(2L);
        assertThat(rows.get(1).values().get("id")).isEqualTo("2");
    }

    @Test
    public void test_uses_the_delimiter_from_options() throws Exception {
        List<Row> rows = read("id;name\n1;ACME\n",
                RowSourceOptions.defaults().withDelimiter(';'));

        assertThat(rows.get(0).values().get("name")).isEqualTo("ACME");
    }

    @Test
    public void test_quoted_field_keeps_delimiter_and_newline() throws Exception {
        List<Row> rows = read("id,name\n1,\"ACME, Inc.\nsecond line\"\n", RowSourceOptions.defaults());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).values().get("name")).isEqualTo("ACME, Inc.\nsecond line");
    }

    @Test
    public void test_reads_with_the_given_charset() throws Exception {
        byte[] latin1 = "id,name\n1,Café\n".getBytes(Charset.forName("windows-1252"));

        List<Row> rows = read(latin1,
                RowSourceOptions.defaults().withCharset(Charset.forName("windows-1252")));

        assertThat(rows.get(0).values().get("name")).isEqualTo("Café");
    }

    @Test
    public void test_drops_blank_named_columns() throws Exception {
        List<Row> rows = read("id,,name\n1,ignored,ACME\n", RowSourceOptions.defaults());

        assertThat(rows.get(0).values()).hasSize(2);
        assertThat(rows.get(0).values().get("name")).isEqualTo("ACME");
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_duplicate_header_fails() throws Exception {
        read("id,name,name\n1,a,b\n", RowSourceOptions.defaults());
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_no_header_row_fails() throws Exception {
        read("", RowSourceOptions.defaults());
    }

    @Test
    public void test_header_with_no_data_rows_is_empty_not_an_error() throws Exception {
        assertThat(read("id,name\n", RowSourceOptions.defaults())).isEmpty();
    }
}
```

- [ ] **Step 4: Run the test to verify it fails**

Run: `mvn -pl datashare-app -am test -Dtest=DelimitedRowSourceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL at compilation, `cannot find symbol: class DelimitedRowSource`.

- [ ] **Step 5: Write the implementation**

`DelimitedRowSource.java`. Note the header is read manually rather than through commons-csv's own header support: commons-csv rejects duplicates with its own message and has separate switches for blank names, and doing it by hand is what makes the one shared `Row.headers` rule apply here and in tier 2 identically.

```java
package org.icij.datashare.tabular;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class DelimitedRowSource implements RowSource {
    private static final Set<String> SUPPORTED =
            Set.of("text/csv", "text/tab-separated-values", "text/plain");

    private static final char DEFAULT_DELIMITER = ',';

    @Override
    public boolean supports(String contentType) {
        return SUPPORTED.contains(contentType);
    }

    @Override
    public Stream<Row> rows(InputStream source, RowSourceOptions options) throws IOException {
        Charset charset = options.charset() == null ? StandardCharsets.UTF_8 : options.charset();
        // CSVParser.parse over CSVParser.builder(): the builder's setReader is inherited from
        // commons-io and returns the supertype, so the fluent chain does not typecheck.
        CSVParser parser = CSVParser.parse(source, charset, format(options));

        Iterator<CSVRecord> records = parser.iterator();
        if (!records.hasNext()) {
            parser.close();
            throw new IllegalArgumentException("no header row: the source is empty");
        }
        List<String> headers = Row.headers(records.next().toList());

        return stream(records, headers).onClose(() -> close(parser));
    }

    private static CSVFormat format(RowSourceOptions options) {
        CSVFormat.Builder builder = CSVFormat.DEFAULT.builder()
                .setDelimiter(options.delimiter() == null ? DEFAULT_DELIMITER : options.delimiter());
        if (options.quote() != null) {
            builder.setQuote(options.quote());
        }
        return builder.get();
    }

    private static Stream<Row> stream(Iterator<CSVRecord> records, List<String> headers) {
        Iterator<Row> rows = new Iterator<>() {
            private long number = 0;

            @Override
            public boolean hasNext() {
                return records.hasNext();
            }

            @Override
            public Row next() {
                return new Row(++number, values(records.next(), headers, number));
            }
        };
        return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(rows, Spliterator.ORDERED | Spliterator.NONNULL),
                false);
    }

    // A malformed record surfaces as an UncheckedIOException from commons-csv's own iterator; the row
    // number is added because that is the only thing making the message actionable to whoever wrote
    // the file. A Spliterator cannot throw a checked IOException, so unchecked is the only option.
    private static Map<String, String> values(CSVRecord record, List<String> headers, long number) {
        Map<String, String> values = new HashMap<>();
        for (int column = 0; column < headers.size() && column < record.size(); column++) {
            if (headers.get(column) != null) {
                values.put(headers.get(column), record.get(column));
            }
        }
        return values;
    }

    private static void close(CSVParser parser) {
        try {
            parser.close();
        } catch (IOException e) {
            throw new UncheckedIOException("closing the delimited source failed", e);
        }
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `mvn -pl datashare-app -am test -Dtest=DelimitedRowSourceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, 9 tests.

- [ ] **Step 7: Commit**

```bash
git add datashare-api/src/main/java/org/icij/datashare/tabular datashare-app/src/main/java/org/icij/datashare/tabular datashare-app/src/test/java/org/icij/datashare/tabular datashare-app/pom.xml
git commit -m "feat(tabular): add the RowSource contract and the delimited reader"
```

---

### Task 2: Excel reader

**Files:**
- Create: `datashare-app/src/main/java/org/icij/datashare/tabular/WorkbookRowSource.java`
- Test: `datashare-app/src/test/java/org/icij/datashare/tabular/WorkbookRowSourceTest.java`

**Interfaces:**
- Consumes: `RowSource`, `Row`, `Row.headers(List<String>)`, `RowSourceOptions` from Task 1.
- Produces: `WorkbookRowSource()` no-arg constructor.

**Naming hazard:** POI has its own `org.apache.poi.ss.usermodel.Row`, which collides with `org.icij.datashare.tabular.Row`. Import ours, fully qualify POI's everywhere it appears.

- [ ] **Step 1: Write the failing test**

Fixtures are generated with POI rather than committed as binaries, so the test states what the file contains instead of hiding it in a blob.

```java
package org.icij.datashare.tabular;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import static org.fest.assertions.Assertions.assertThat;

public class WorkbookRowSourceTest {
    private final WorkbookRowSource source = new WorkbookRowSource();

    private List<Row> read(byte[] workbook, RowSourceOptions options) throws Exception {
        try (InputStream stream = new ByteArrayInputStream(workbook);
             Stream<Row> rows = source.rows(stream, options)) {
            return rows.toList();
        }
    }

    private static byte[] bytes(Workbook workbook) throws Exception {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.write(out);
            workbook.close();
            return out.toByteArray();
        }
    }

    private static void fill(Sheet sheet) {
        sheet.createRow(0).createCell(0).setCellValue("id");
        sheet.getRow(0).createCell(1).setCellValue("name");
        sheet.createRow(1).createCell(0).setCellValue("1");
        sheet.getRow(1).createCell(1).setCellValue("ACME");
    }

    @Test
    public void test_supports_excel_content_types() {
        assertThat(source.supports("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).isTrue();
        assertThat(source.supports("application/vnd.ms-excel")).isTrue();
        assertThat(source.supports("application/vnd.ms-excel.sheet.macroenabled.12")).isTrue();
        assertThat(source.supports("application/vnd.ms-excel.sheet.binary.macroenabled.12")).isFalse();
        assertThat(source.supports("text/csv")).isFalse();
    }

    @Test
    public void test_reads_xlsx_rows() throws Exception {
        XSSFWorkbook workbook = new XSSFWorkbook();
        fill(workbook.createSheet("companies"));

        List<Row> rows = read(bytes(workbook), RowSourceOptions.defaults());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).number()).isEqualTo(1L);
        assertThat(rows.get(0).values().get("name")).isEqualTo("ACME");
    }

    @Test
    public void test_reads_legacy_xls_rows() throws Exception {
        HSSFWorkbook workbook = new HSSFWorkbook();
        fill(workbook.createSheet("companies"));

        List<Row> rows = read(bytes(workbook), RowSourceOptions.defaults());

        assertThat(rows.get(0).values().get("name")).isEqualTo("ACME");
    }

    @Test
    public void test_reads_the_first_sheet_by_default() throws Exception {
        XSSFWorkbook workbook = new XSSFWorkbook();
        fill(workbook.createSheet("first"));
        Sheet second = workbook.createSheet("second");
        second.createRow(0).createCell(0).setCellValue("id");
        second.createRow(1).createCell(0).setCellValue("999");

        List<Row> rows = read(bytes(workbook), RowSourceOptions.defaults());

        assertThat(rows.get(0).values().get("id")).isEqualTo("1");
    }

    @Test
    public void test_reads_the_sheet_named_in_the_options() throws Exception {
        XSSFWorkbook workbook = new XSSFWorkbook();
        fill(workbook.createSheet("first"));
        Sheet second = workbook.createSheet("second");
        second.createRow(0).createCell(0).setCellValue("id");
        second.createRow(1).createCell(0).setCellValue("999");

        List<Row> rows = read(bytes(workbook),
                new RowSourceOptions(null, null, null, null, "second", null));

        assertThat(rows.get(0).values().get("id")).isEqualTo("999");
    }

    @Test
    public void test_renders_a_date_cell_as_iso_date() throws Exception {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("dates");
        sheet.createRow(0).createCell(0).setCellValue("born");
        CellStyle dateStyle = workbook.createCellStyle();
        dateStyle.setDataFormat(workbook.createDataFormat().getFormat("dd/mm/yyyy"));
        Cell cell = sheet.createRow(1).createCell(0);
        cell.setCellValue(LocalDateTime.of(1984, 3, 17, 0, 0));
        cell.setCellStyle(dateStyle);

        List<Row> rows = read(bytes(workbook), RowSourceOptions.defaults());

        assertThat(rows.get(0).values().get("born")).isEqualTo("1984-03-17");
    }

    @Test
    public void test_renders_a_datetime_cell_as_iso_datetime() throws Exception {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("dates");
        sheet.createRow(0).createCell(0).setCellValue("seen");
        CellStyle style = workbook.createCellStyle();
        style.setDataFormat(workbook.createDataFormat().getFormat("dd/mm/yyyy hh:mm"));
        Cell cell = sheet.createRow(1).createCell(0);
        cell.setCellValue(LocalDateTime.of(1984, 3, 17, 14, 30));
        cell.setCellStyle(style);

        List<Row> rows = read(bytes(workbook), RowSourceOptions.defaults());

        assertThat(rows.get(0).values().get("seen")).isEqualTo("1984-03-17T14:30");
    }

    @Test
    public void test_renders_a_formula_cell_as_its_computed_value() throws Exception {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("sums");
        sheet.createRow(0).createCell(0).setCellValue("total");
        sheet.createRow(1).createCell(0).setCellFormula("2+3");

        List<Row> rows = read(bytes(workbook), RowSourceOptions.defaults());

        assertThat(rows.get(0).values().get("total")).isEqualTo("5");
    }

    @Test
    public void test_skips_entirely_blank_rows() throws Exception {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("companies");
        fill(sheet);
        sheet.createRow(2).createCell(0).setCellValue("");
        sheet.createRow(3).createCell(0).setCellValue("2");
        sheet.getRow(3).createCell(1).setCellValue("Globex");

        List<Row> rows = read(bytes(workbook), RowSourceOptions.defaults());

        assertThat(rows).hasSize(2);
        assertThat(rows.get(1).values().get("name")).isEqualTo("Globex");
        assertThat(rows.get(1).number()).isEqualTo(2L);
    }

    @Test
    public void test_blank_cell_is_the_empty_string() throws Exception {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("companies");
        sheet.createRow(0).createCell(0).setCellValue("id");
        sheet.getRow(0).createCell(1).setCellValue("name");
        sheet.createRow(1).createCell(0).setCellValue("1");

        List<Row> rows = read(bytes(workbook), RowSourceOptions.defaults());

        assertThat(rows.get(0).values().get("name")).isEqualTo("");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -pl datashare-app -am test -Dtest=WorkbookRowSourceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL at compilation, `cannot find symbol: class WorkbookRowSource`.

- [ ] **Step 3: Write the implementation**

```java
package org.icij.datashare.tabular;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Reads a workbook with POI. The whole workbook is loaded in memory, roughly 5 to 10 times the file
 * size in heap: streaming would mean two Excel code paths, since HSSF has no practical streaming
 * equivalent, plus hand-wiring StylesTable and SharedStrings to reproduce cell formatting. The
 * RowSource contract is stream-shaped, so replacing this with an XSSFReader implementation later
 * touches neither the interface nor its callers.
 */
public class WorkbookRowSource implements RowSource {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkbookRowSource.class);

    private static final Set<String> SUPPORTED = Set.of(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-excel",
            "application/vnd.ms-excel.sheet.macroenabled.12");

    private final DataFormatter formatter = new DataFormatter();

    @Override
    public boolean supports(String contentType) {
        return SUPPORTED.contains(contentType);
    }

    @Override
    public Stream<Row> rows(InputStream source, RowSourceOptions options) throws IOException {
        Workbook workbook = WorkbookFactory.create(source);
        try {
            Sheet sheet = selectSheet(workbook, options.sheet());
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            return read(sheet, evaluator).stream().onClose(() -> close(workbook));
        } catch (RuntimeException failure) {
            close(workbook);
            throw failure;
        }
    }

    /**
     * By name first, so a sheet literally named "2" wins over position 2, and only then as a 1-based
     * index when the name matches nothing and parses as an integer.
     */
    private static Sheet selectSheet(Workbook workbook, String requested) {
        if (requested == null) {
            return workbook.getSheetAt(0);
        }
        Sheet byName = workbook.getSheet(requested);
        if (byName != null) {
            return byName;
        }
        try {
            return workbook.getSheetAt(Integer.parseInt(requested.strip()) - 1);
        } catch (NumberFormatException | IllegalArgumentException notAnIndex) {
            throw new IllegalArgumentException("no such sheet: " + requested);
        }
    }

    // Materialized rather than lazily streamed: the workbook is already fully in memory, so a lazy
    // spliterator over it would buy nothing and would keep the workbook open for the caller to leak.
    private List<Row> read(Sheet sheet, FormulaEvaluator evaluator) {
        java.util.Iterator<org.apache.poi.ss.usermodel.Row> sheetRows = sheet.iterator();
        if (!sheetRows.hasNext()) {
            throw new IllegalArgumentException("no header row: the sheet is empty");
        }
        List<String> headers = Row.headers(cells(sheetRows.next(), evaluator));

        List<Row> rows = new ArrayList<>();
        long blank = 0;
        while (sheetRows.hasNext()) {
            List<String> values = cells(sheetRows.next(), evaluator);
            if (values.stream().allMatch(String::isEmpty)) {
                blank++;
                continue;
            }
            rows.add(new Row(rows.size() + 1L, map(headers, values)));
        }
        if (blank > 0) {
            LOGGER.info("skipped {} blank rows in sheet {}", blank, sheet.getSheetName());
        }
        return rows;
    }

    private List<String> cells(org.apache.poi.ss.usermodel.Row row, FormulaEvaluator evaluator) {
        List<String> values = new ArrayList<>();
        for (int column = 0; column < row.getLastCellNum(); column++) {
            values.add(value(row.getCell(column), evaluator));
        }
        return values;
    }

    /**
     * DataFormatter renders a date cell with the spreadsheet's own display style, which would make a
     * mapping's date format depend on how somebody styled the file. Date cells are therefore rendered
     * ISO-8601 instead, dropping the time part when it is midnight.
     */
    private String value(Cell cell, FormulaEvaluator evaluator) {
        if (cell == null) {
            return "";
        }
        if (isDate(cell, evaluator)) {
            LocalDateTime moment = cell.getLocalDateTimeCellValue();
            return moment.toLocalTime().equals(LocalTime.MIDNIGHT)
                    ? moment.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
                    : moment.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        }
        return formatter.formatCellValue(cell, evaluator);
    }

    private static boolean isDate(Cell cell, FormulaEvaluator evaluator) {
        CellType type = cell.getCellType() == CellType.FORMULA
                ? evaluator.evaluateFormulaCell(cell)
                : cell.getCellType();
        return type == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell);
    }

    private static Map<String, String> map(List<String> headers, List<String> values) {
        Map<String, String> mapped = new HashMap<>();
        for (int column = 0; column < headers.size(); column++) {
            if (headers.get(column) != null) {
                mapped.put(headers.get(column), column < values.size() ? values.get(column) : "");
            }
        }
        return mapped;
    }

    private static void close(Workbook workbook) {
        try {
            workbook.close();
        } catch (IOException e) {
            throw new UncheckedIOException("closing the workbook failed", e);
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -pl datashare-app -am test -Dtest=WorkbookRowSourceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, 11 tests.

- [ ] **Step 5: Commit**

```bash
git add datashare-app/src/main/java/org/icij/datashare/tabular/WorkbookRowSource.java datashare-app/src/test/java/org/icij/datashare/tabular/WorkbookRowSourceTest.java
git commit -m "feat(tabular): read xlsx, xlsm and legacy xls with POI"
```

---

### Task 3: JSON reader

**Files:**
- Create: `datashare-app/src/main/java/org/icij/datashare/tabular/JsonRowSource.java`
- Test: `datashare-app/src/test/java/org/icij/datashare/tabular/JsonRowSourceTest.java`

**Interfaces:**
- Consumes: `RowSource`, `Row`, `RowSourceOptions` from Task 1.
- Produces: `JsonRowSource()` no-arg constructor; the constant `JsonRowSource.NDJSON_CONTENT_TYPE` (`"application/x-ndjson"`), which Task 5 maps extensions onto.

- [ ] **Step 1: Write the failing test**

```java
package org.icij.datashare.tabular;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

import static org.fest.assertions.Assertions.assertThat;

public class JsonRowSourceTest {
    private final JsonRowSource source = new JsonRowSource();

    private List<Row> read(String content) throws Exception {
        try (InputStream stream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
             Stream<Row> rows = source.rows(stream, RowSourceOptions.defaults())) {
            return rows.toList();
        }
    }

    @Test
    public void test_supports_json_content_types() {
        assertThat(source.supports("application/json")).isTrue();
        assertThat(source.supports(JsonRowSource.NDJSON_CONTENT_TYPE)).isTrue();
        assertThat(source.supports("text/csv")).isFalse();
    }

    @Test
    public void test_reads_an_array_of_objects() throws Exception {
        List<Row> rows = read("[{\"id\":1,\"name\":\"ACME\"},{\"id\":2,\"name\":\"Globex\"}]");

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).number()).isEqualTo(1L);
        assertThat(rows.get(0).values().get("name")).isEqualTo("ACME");
        assertThat(rows.get(1).values().get("id")).isEqualTo("2");
    }

    @Test
    public void test_reads_line_delimited_objects() throws Exception {
        List<Row> rows = read("{\"id\":1,\"name\":\"ACME\"}\n{\"id\":2,\"name\":\"Globex\"}\n");

        assertThat(rows).hasSize(2);
        assertThat(rows.get(1).values().get("name")).isEqualTo("Globex");
    }

    @Test
    public void test_flattens_nested_objects_to_dotted_columns() throws Exception {
        List<Row> rows = read("[{\"id\":1,\"addr\":{\"city\":\"Paris\",\"zip\":\"75002\"}}]");

        assertThat(rows.get(0).values().get("addr.city")).isEqualTo("Paris");
        assertThat(rows.get(0).values().get("addr.zip")).isEqualTo("75002");
        assertThat(rows.get(0).values().get("addr")).isNull();
    }

    @Test
    public void test_joins_arrays_of_scalars() throws Exception {
        List<Row> rows = read("[{\"id\":1,\"tags\":[\"a\",\"b\"]}]");

        assertThat(rows.get(0).values().get("tags")).isEqualTo("a|b");
    }

    @Test
    public void test_skips_arrays_of_objects() throws Exception {
        List<Row> rows = read("[{\"id\":1,\"kids\":[{\"n\":1}]}]");

        assertThat(rows.get(0).values().get("kids")).isNull();
        assertThat(rows.get(0).values().get("id")).isEqualTo("1");
    }

    @Test
    public void test_null_becomes_the_empty_string() throws Exception {
        List<Row> rows = read("[{\"id\":1,\"name\":null}]");

        assertThat(rows.get(0).values().get("name")).isEqualTo("");
    }

    @Test
    public void test_records_may_carry_different_keys() throws Exception {
        List<Row> rows = read("[{\"id\":1},{\"id\":2,\"name\":\"Globex\"}]");

        assertThat(rows.get(0).values().get("name")).isNull();
        assertThat(rows.get(1).values().get("name")).isEqualTo("Globex");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -pl datashare-app -am test -Dtest=JsonRowSourceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL at compilation, `cannot find symbol: class JsonRowSource`.

- [ ] **Step 3: Write the implementation**

```java
package org.icij.datashare.tabular;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Reads flat records out of JSON. One reader covers both shapes a dump comes in: peeking the first
 * token tells an array of objects apart from line-delimited or concatenated objects, and Jackson
 * iterates both identically once positioned.
 */
public class JsonRowSource implements RowSource {
    /** Tika 3.3.0 has no mimetype for jsonl or ndjson, so this is the de facto type, used only as
     *  the internal token connecting an extension to this reader. It never appears on a document. */
    public static final String NDJSON_CONTENT_TYPE = "application/x-ndjson";

    private static final Set<String> SUPPORTED = Set.of("application/json", NDJSON_CONTENT_TYPE);

    private static final String SCALAR_ARRAY_SEPARATOR = "|";

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public boolean supports(String contentType) {
        return SUPPORTED.contains(contentType);
    }

    @Override
    public Stream<Row> rows(InputStream source, RowSourceOptions options) throws IOException {
        JsonParser parser = mapper.createParser(source);
        // Positioning inside a root array is what makes readValues yield its elements rather than the
        // array as a single value; for NDJSON the parser is already where it needs to be.
        if (parser.nextToken() == JsonToken.START_ARRAY) {
            parser.nextToken();
        }
        MappingIterator<JsonNode> records = mapper.readerFor(JsonNode.class).readValues(parser);

        Iterator<Row> rows = new Iterator<>() {
            private long number = 0;

            @Override
            public boolean hasNext() {
                return records.hasNext();
            }

            @Override
            public Row next() {
                Map<String, String> values = new HashMap<>();
                flatten("", records.next(), values);
                return new Row(++number, values);
            }
        };
        return StreamSupport.stream(
                        Spliterators.spliteratorUnknownSize(rows,
                                Spliterator.ORDERED | Spliterator.NONNULL), false)
                .onClose(() -> close(parser));
    }

    private static void flatten(String prefix, JsonNode node, Map<String, String> values) {
        node.properties().forEach(field -> {
            String column = prefix.isEmpty() ? field.getKey() : prefix + "." + field.getKey();
            JsonNode value = field.getValue();
            if (value.isObject()) {
                flatten(column, value, values);
            } else if (value.isArray()) {
                joinScalars(column, value, values);
            } else {
                values.put(column, value.isNull() ? "" : value.asText());
            }
        });
    }

    /**
     * An array of scalars joins on a fixed separator so a mapping can rely on splitting it. An array
     * of objects is skipped: there is no column name that would describe it. If multi-valued
     * properties turn out to matter, the fix is a multi-value seam in Row, not a cleverer separator.
     */
    private static void joinScalars(String column, JsonNode array, Map<String, String> values) {
        StringBuilder joined = new StringBuilder();
        for (JsonNode element : array) {
            if (element.isContainerNode()) {
                return;
            }
            if (!joined.isEmpty()) {
                joined.append(SCALAR_ARRAY_SEPARATOR);
            }
            joined.append(element.isNull() ? "" : element.asText());
        }
        values.put(column, joined.toString());
    }

    private static void close(JsonParser parser) {
        try {
            parser.close();
        } catch (IOException e) {
            throw new UncheckedIOException("closing the json source failed", e);
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -pl datashare-app -am test -Dtest=JsonRowSourceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, 8 tests.

- [ ] **Step 5: Commit**

```bash
git add datashare-app/src/main/java/org/icij/datashare/tabular/JsonRowSource.java datashare-app/src/test/java/org/icij/datashare/tabular/JsonRowSourceTest.java
git commit -m "feat(tabular): read json arrays and line-delimited json"
```

---

### Task 4: Tika table fallback

**Files:**
- Create: `datashare-app/src/main/java/org/icij/datashare/tabular/TikaTableRowSource.java`
- Test: `datashare-app/src/test/java/org/icij/datashare/tabular/TikaTableRowSourceTest.java`

**Interfaces:**
- Consumes: `RowSource`, `Row`, `Row.headers(List<String>)`, `RowSourceOptions` from Task 1; `StructureMarkdownExtractor` and its `Page` record and `OcrSettings.NONE` from `datashare-index`.
- Produces: `TikaTableRowSource()` no-arg constructor; `TikaTableRowSource.SUPPORTED` (a `Set<String>` of the six tier-2 content types) for Task 5's error messages.

- [ ] **Step 1: Write the failing test**

HTML and DOCX are the fixtures because both are authorable in the test and both go through exactly the code path ODS and XLSB take: Tika XHTML, then jsoup table selection. ODS gets its own test in Task 6, where a minimal package is built by hand.

```java
package org.icij.datashare.tabular;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

import static org.fest.assertions.Assertions.assertThat;

public class TikaTableRowSourceTest {
    private final TikaTableRowSource source = new TikaTableRowSource();

    private List<Row> read(byte[] content, RowSourceOptions options) throws Exception {
        try (InputStream stream = new ByteArrayInputStream(content);
             Stream<Row> rows = source.rows(stream, options)) {
            return rows.toList();
        }
    }

    private List<Row> readHtml(String html, RowSourceOptions options) throws Exception {
        return read(html.getBytes(StandardCharsets.UTF_8), options.withContentType("text/html"));
    }

    @Test
    public void test_supports_the_verified_tier_two_types_only() {
        assertThat(source.supports("application/vnd.oasis.opendocument.spreadsheet")).isTrue();
        assertThat(source.supports("application/vnd.ms-excel.sheet.binary.macroenabled.12")).isTrue();
        assertThat(source.supports("application/vnd.openxmlformats-officedocument.wordprocessingml.document")).isTrue();
        assertThat(source.supports("application/msword")).isTrue();
        assertThat(source.supports("text/html")).isTrue();
        assertThat(source.supports("application/vnd.apple.numbers")).isTrue();
    }

    @Test
    public void test_does_not_claim_pdf_or_rtf_or_tier_one_types() {
        assertThat(source.supports("application/pdf")).isFalse();
        assertThat(source.supports("application/rtf")).isFalse();
        assertThat(source.supports("text/csv")).isFalse();
        assertThat(source.supports("application/vnd.ms-excel")).isFalse();
    }

    @Test
    public void test_reads_an_html_table() throws Exception {
        List<Row> rows = readHtml(
                "<html><body><table>"
                        + "<tr><th>id</th><th>name</th></tr>"
                        + "<tr><td>1</td><td>ACME</td></tr>"
                        + "<tr><td>2</td><td>Globex</td></tr>"
                        + "</table></body></html>",
                RowSourceOptions.defaults());

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).number()).isEqualTo(1L);
        assertThat(rows.get(0).values().get("name")).isEqualTo("ACME");
        assertThat(rows.get(1).values().get("id")).isEqualTo("2");
    }

    @Test
    public void test_reads_the_first_table_by_default() throws Exception {
        List<Row> rows = readHtml(
                "<html><body>"
                        + "<table><tr><th>id</th></tr><tr><td>first</td></tr></table>"
                        + "<table><tr><th>id</th></tr><tr><td>second</td></tr></table>"
                        + "</body></html>",
                RowSourceOptions.defaults());

        assertThat(rows.get(0).values().get("id")).isEqualTo("first");
    }

    @Test
    public void test_reads_the_table_at_the_index_in_the_options() throws Exception {
        List<Row> rows = readHtml(
                "<html><body>"
                        + "<table><tr><th>id</th></tr><tr><td>first</td></tr></table>"
                        + "<table><tr><th>id</th></tr><tr><td>second</td></tr></table>"
                        + "</body></html>",
                new RowSourceOptions(null, null, null, null, null, 2));

        assertThat(rows.get(0).values().get("id")).isEqualTo("second");
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_header_row_with_colspan_fails() throws Exception {
        readHtml("<html><body><table>"
                + "<tr><th colspan=\"2\">both</th></tr>"
                + "<tr><td>1</td><td>ACME</td></tr>"
                + "</table></body></html>", RowSourceOptions.defaults());
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_no_table_at_all_fails() throws Exception {
        readHtml("<html><body><p>no table here</p></body></html>", RowSourceOptions.defaults());
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_table_index_out_of_range_fails() throws Exception {
        readHtml("<html><body><table><tr><th>id</th></tr></table></body></html>",
                new RowSourceOptions(null, null, null, null, null, 9));
    }

    @Test
    public void test_reads_a_table_from_a_docx() throws Exception {
        byte[] docx = docxWithTable();

        List<Row> rows = read(docx, RowSourceOptions.defaults().withContentType(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).values().get("name")).isEqualTo("ACME");
    }

    private static byte[] docxWithTable() throws Exception {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFTable table = document.createTable(2, 2);
            table.getRow(0).getCell(0).setText("id");
            table.getRow(0).getCell(1).setText("name");
            table.getRow(1).getCell(0).setText("1");
            table.getRow(1).getCell(1).setText("ACME");
            document.write(out);
            return out.toByteArray();
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -pl datashare-app -am test -Dtest=TikaTableRowSourceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL at compilation, `cannot find symbol: class TikaTableRowSource`.

- [ ] **Step 3: Write the implementation**

```java
package org.icij.datashare.tabular;

import org.apache.tika.exception.TikaException;
import org.icij.datashare.text.structure.StructureMarkdownExtractor;
import org.icij.datashare.text.structure.StructureMarkdownExtractor.OcrSettings;
import org.icij.datashare.text.structure.StructureMarkdownExtractor.Page;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The tier-2 fallback: rows out of the tables Tika renders, covering every format whose parser emits
 * table markup. Reuses StructureMarkdownExtractor rather than setting up its own parse, because that
 * class already owns the resilient PST parser swap, the output cap, the page splitting and the
 * sanitizer. Its Markdown rendering is ignored here; if that shows up in a profile, the fix is an
 * xhtml-only method on that class rather than a parser in this one.
 *
 * Two things differ from tier 1, by nature rather than by omission. Cell values arrive already
 * formatted with the cell type discarded, so a date is whatever the source rendered it as and the
 * mapping's own date format has to handle it. And the extractor's output cap means an oversized
 * document throws rather than importing a truncated table, which is the behaviour a data import
 * needs; large files belong on tier 1.
 */
public class TikaTableRowSource implements RowSource {
    /** Only the types whose Tika parser was confirmed to emit table markup. Deliberately not a
     *  catch-all: claiming every unclaimed type would make this reader own application/pdf and force
     *  a future PDF table extractor to displace an incumbent. */
    public static final Set<String> SUPPORTED = Set.of(
            "application/vnd.oasis.opendocument.spreadsheet",
            "application/vnd.ms-excel.sheet.binary.macroenabled.12",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/msword",
            "text/html",
            "application/vnd.apple.numbers");

    private final StructureMarkdownExtractor extractor = new StructureMarkdownExtractor();

    @Override
    public boolean supports(String contentType) {
        return SUPPORTED.contains(contentType);
    }

    @Override
    public Stream<Row> rows(InputStream source, RowSourceOptions options) throws IOException {
        Element table = selectTable(tables(source, options), options.table());
        Elements tableRows = table.select("tr");
        if (tableRows.isEmpty()) {
            throw new IllegalArgumentException("no header row: the table is empty");
        }
        List<String> headers = Row.headers(cells(tableRows.get(0)));

        List<Row> rows = new ArrayList<>();
        for (int index = 1; index < tableRows.size(); index++) {
            List<String> values = cells(tableRows.get(index));
            if (values.stream().allMatch(String::isEmpty)) {
                continue;
            }
            rows.add(new Row(rows.size() + 1L, map(headers, values)));
        }
        return rows.stream();
    }

    // OCR off: a tabular source does not need it, and leaving it off keeps the parse cheap and its
    // output deterministic.
    private List<Element> tables(InputStream source, RowSourceOptions options) throws IOException {
        List<Page> pages;
        try {
            pages = extractor.extract(source, options.contentType(), null, OcrSettings.NONE);
        } catch (SAXException | TikaException parseFailure) {
            throw new IOException("parsing the tabular source failed", parseFailure);
        }
        List<Element> tables = new ArrayList<>();
        for (Page page : pages) {
            tables.addAll(Jsoup.parse(page.xhtml()).select("table"));
        }
        return tables;
    }

    private static Element selectTable(List<Element> tables, Integer requested) {
        if (tables.isEmpty()) {
            throw new IllegalArgumentException(
                    "the source holds no table, so it has no rows to read");
        }
        int index = requested == null ? 1 : requested;
        if (index < 1 || index > tables.size()) {
            throw new IllegalArgumentException(
                    "no table at index " + index + ": the source holds " + tables.size());
        }
        return tables.get(index - 1);
    }

    /**
     * A merged cell makes column alignment undefined, and silently misaligning every value under a
     * header is worse than refusing the file, so a span anywhere in a row is rejected. Both th and td
     * count as cells: a header row uses either depending on who wrote the document.
     */
    private static List<String> cells(Element tableRow) {
        List<String> values = new ArrayList<>();
        for (Element cell : tableRow.select("th, td")) {
            if (cell.hasAttr("colspan") || cell.hasAttr("rowspan")) {
                throw new IllegalArgumentException(
                        "merged cells make the columns ambiguous: remove the colspan or rowspan");
            }
            values.add(cell.text().strip());
        }
        return values;
    }

    private static Map<String, String> map(List<String> headers, List<String> values) {
        Map<String, String> mapped = new HashMap<>();
        for (int column = 0; column < headers.size(); column++) {
            if (headers.get(column) != null) {
                mapped.put(headers.get(column), column < values.size() ? values.get(column) : "");
            }
        }
        return mapped;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -pl datashare-app -am test -Dtest=TikaTableRowSourceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, 9 tests.

If `test_header_row_with_colspan_fails` does not throw, check whether the `Safelist` kept the attribute: `Safelist.relaxed()` allows `colspan`/`rowspan` on `td` and `th` (`StructureMarkdownExtractor.java:76-80`), so the attribute should survive to jsoup. If Tika's HTML mapper drops it instead, assert on a DOCX with a merged cell rather than on HTML.

- [ ] **Step 5: Commit**

```bash
git add datashare-app/src/main/java/org/icij/datashare/tabular/TikaTableRowSource.java datashare-app/src/test/java/org/icij/datashare/tabular/TikaTableRowSourceTest.java
git commit -m "feat(tabular): add a Tika table fallback reader for ods, xlsb, docx and html"
```

---

### Task 5: Resolve a document id to rows

**Files:**
- Create: `datashare-app/src/main/java/org/icij/datashare/tabular/TabularRowReader.java`
- Test: `datashare-app/src/test/java/org/icij/datashare/tabular/TabularRowReaderTest.java`

**Interfaces:**
- Consumes: every reader from Tasks 1 to 4; `JsonRowSource.NDJSON_CONTENT_TYPE`; `TikaTableRowSource.SUPPORTED`; `Indexer.get(String indexName, String id, String root)` (`Indexer.java:57`); `SourceExtractor.getSource(Project, Document)` (`SourceExtractor.java:63`); `Document.getContentType()`, `getContentEncoding()`, `getName()`, `getMetadata()`.
- Produces: `TabularRowReader(Indexer, SourceExtractor)`; package-private `TabularRowReader(Indexer, SourceExtractor, List<RowSource>, RowSource)`; `Stream<Row> rows(Project project, String documentId, String rootId, RowSourceOptions options) throws IOException`; package-private statics `effectiveContentType(String override, String storedType, String filename)` and `delimiterFrom(Map<String,Object> metadata)`.

- [ ] **Step 1: Write the failing test**

```java
package org.icij.datashare.tabular;

import org.icij.datashare.text.Document;
import org.icij.datashare.text.DocumentBuilder;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.elasticsearch.SourceExtractor;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TabularRowReaderTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private final Indexer indexer = mock(Indexer.class);
    private TabularRowReader reader;
    private Project project;

    @Before
    public void setUp() {
        project = new Project("local-datashare");
        reader = new TabularRowReader(indexer, new SourceExtractor(new org.icij.datashare.PropertiesProvider()));
    }

    private Document indexed(String filename, String contentType, String content,
                            Map<String, Object> metadata) throws Exception {
        Path file = folder.getRoot().toPath().resolve(filename);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        Document document = DocumentBuilder.createDoc("docId").with(file)
                .ofContentType(contentType).with(StandardCharsets.UTF_8).with(metadata).build();
        when(indexer.<Document>get("local-datashare", "docId", "docId")).thenReturn(document);
        return document;
    }

    private List<Row> rows(RowSourceOptions options) throws Exception {
        try (Stream<Row> rows = reader.rows(project, "docId", null, options)) {
            return rows.toList();
        }
    }

    @Test
    public void test_reads_a_csv_document_through_the_delimited_reader() throws Exception {
        indexed("companies.csv", "text/csv", "id,name\n1,ACME\n", Map.of());

        assertThat(rows(RowSourceOptions.defaults()).get(0).values().get("name")).isEqualTo("ACME");
    }

    @Test
    public void test_takes_the_delimiter_from_the_document_metadata() throws Exception {
        indexed("companies.csv", "text/csv", "id;name\n1;ACME\n",
                Map.of("tika_metadata_csv_delimiter", "semicolon"));

        assertThat(rows(RowSourceOptions.defaults()).get(0).values().get("name")).isEqualTo("ACME");
    }

    @Test
    public void test_the_mapping_delimiter_beats_the_metadata() throws Exception {
        indexed("companies.csv", "text/csv", "id|name\n1|ACME\n",
                Map.of("tika_metadata_csv_delimiter", "semicolon"));

        assertThat(rows(RowSourceOptions.defaults().withDelimiter('|')).get(0).values().get("name"))
                .isEqualTo("ACME");
    }

    @Test
    public void test_a_jsonl_file_typed_text_plain_reaches_the_json_reader() throws Exception {
        indexed("dump.jsonl", "text/plain", "{\"id\":1,\"name\":\"ACME\"}\n", Map.of());

        assertThat(rows(RowSourceOptions.defaults()).get(0).values().get("name")).isEqualTo("ACME");
    }

    @Test
    public void test_a_csv_typed_text_plain_still_reaches_the_delimited_reader() throws Exception {
        indexed("companies.txt", "text/plain", "id,name\n1,ACME\n", Map.of());

        assertThat(rows(RowSourceOptions.defaults()).get(0).values().get("name")).isEqualTo("ACME");
    }

    @Test
    public void test_the_mapping_content_type_beats_the_stored_one() throws Exception {
        indexed("mislabelled.csv", "text/csv", "{\"id\":1,\"name\":\"ACME\"}\n", Map.of());

        assertThat(rows(RowSourceOptions.defaults().withContentType("application/json"))
                .get(0).values().get("name")).isEqualTo("ACME");
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_an_unsupported_content_type_fails() throws Exception {
        indexed("scan.pdf", "application/pdf", "not really a pdf", Map.of());
        rows(RowSourceOptions.defaults());
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_a_missing_document_fails() throws Exception {
        when(indexer.<Document>get("local-datashare", "missing", "missing")).thenReturn(null);
        try (Stream<Row> ignored = reader.rows(project, "missing", null, RowSourceOptions.defaults())) {
            // the failure is expected before any row is read
        }
    }

    @Test
    public void test_effective_content_type_prefers_the_override() {
        assertThat(TabularRowReader.effectiveContentType("application/json", "text/csv", "a.csv"))
                .isEqualTo("application/json");
    }

    @Test
    public void test_effective_content_type_falls_back_to_the_extension_only_when_generic() {
        assertThat(TabularRowReader.effectiveContentType(null, "text/plain", "a.jsonl"))
                .isEqualTo(JsonRowSource.NDJSON_CONTENT_TYPE);
        assertThat(TabularRowReader.effectiveContentType(null, "text/csv", "a.jsonl"))
                .isEqualTo("text/csv");
        assertThat(TabularRowReader.effectiveContentType(null, "text/plain", "notes.md"))
                .isEqualTo("text/plain");
    }

    @Test
    public void test_delimiter_from_metadata_maps_tika_names() {
        assertThat(TabularRowReader.delimiterFrom(Map.of("tika_metadata_csv_delimiter", "semicolon")))
                .isEqualTo(';');
        assertThat(TabularRowReader.delimiterFrom(Map.of("tika_metadata_csv_delimiter", "tab")))
                .isEqualTo('\t');
        assertThat(TabularRowReader.delimiterFrom(Map.of("tika_metadata_csv_delimiter", "pipe")))
                .isEqualTo('|');
        assertThat(TabularRowReader.delimiterFrom(Map.of("tika_metadata_csv_delimiter", "comma")))
                .isEqualTo(',');
        assertThat(TabularRowReader.delimiterFrom(Map.of())).isNull();
        assertThat(TabularRowReader.delimiterFrom(Map.of("tika_metadata_csv_delimiter", "unknown")))
                .isNull();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -pl datashare-app -am test -Dtest=TabularRowReaderTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL at compilation, `cannot find symbol: class TabularRowReader`.

- [ ] **Step 3: Write the implementation**

```java
package org.icij.datashare.tabular;

import org.icij.datashare.text.Document;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.elasticsearch.SourceExtractor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Turns the document a mapping names into rows. Addressing the source by document id rather than by
 * path is what buys embedded sources (a CSV inside a ZIP, a workbook attached to an email) through
 * SourceExtractor, plus the content type and charset Tika already detected at index time. It also
 * means no user-supplied path reaches the filesystem, so this route has no traversal surface at all.
 *
 * Authorization on the project is not checked here: it belongs to the callers wiring the REST and CLI
 * triggers, not to a reader.
 */
public class TabularRowReader {
    private static final Set<String> GENERIC_TYPES =
            Set.of("text/plain", "application/octet-stream");

    // Only the formats a reader claims. Tika 3.3.0 types a .csv named .txt, and every .jsonl, as
    // text/plain, so without this the delimited reader would claim NDJSON files.
    private static final Map<String, String> TYPE_BY_EXTENSION = Map.of(
            "csv", "text/csv",
            "tsv", "text/tab-separated-values",
            "psv", "text/csv",
            "txt", "text/plain",
            "json", "application/json",
            "jsonl", JsonRowSource.NDJSON_CONTENT_TYPE,
            "ndjson", JsonRowSource.NDJSON_CONTENT_TYPE);

    private static final String DELIMITER_METADATA_KEY = "tika_metadata_csv_delimiter";

    private static final Map<String, Character> DELIMITER_BY_TIKA_NAME = Map.of(
            "comma", ',', "tab", '\t', "pipe", '|', "semicolon", ';');

    private final Indexer indexer;
    private final SourceExtractor sourceExtractor;
    private final List<RowSource> readers;
    private final RowSource fallback;

    public TabularRowReader(Indexer indexer, SourceExtractor sourceExtractor) {
        this(indexer, sourceExtractor,
                List.of(new DelimitedRowSource(), new WorkbookRowSource(), new JsonRowSource()),
                new TikaTableRowSource());
    }

    TabularRowReader(Indexer indexer, SourceExtractor sourceExtractor, List<RowSource> readers,
                     RowSource fallback) {
        this.indexer = indexer;
        this.sourceExtractor = sourceExtractor;
        this.readers = readers;
        this.fallback = fallback;
    }

    /**
     * @param rootId the container the document was extracted from, or null for a root document. ES
     *               routes an embedded document by its root, so this cannot be derived here.
     */
    public Stream<Row> rows(Project project, String documentId, String rootId,
                            RowSourceOptions options) throws IOException {
        Document document = indexer.get(project.getName(), documentId,
                rootId == null ? documentId : rootId);
        if (document == null) {
            throw new IllegalArgumentException("no such document in " + project.getName() + ": " + documentId);
        }
        RowSourceOptions resolved = resolve(document, options);
        RowSource reader = select(resolved.contentType());
        InputStream source = sourceExtractor.getSource(project, document);
        try {
            return reader.rows(source, resolved).onClose(() -> closeQuietly(source));
        } catch (IOException | RuntimeException failure) {
            closeQuietly(source);
            throw failure;
        }
    }

    private RowSourceOptions resolve(Document document, RowSourceOptions options) {
        RowSourceOptions resolved = options.withContentType(effectiveContentType(
                options.contentType(), document.getContentType(), document.getName()));
        if (resolved.charset() == null && document.getContentEncoding() != null) {
            resolved = resolved.withCharset(document.getContentEncoding());
        }
        if (resolved.delimiter() == null) {
            resolved = resolved.withDelimiter(delimiterFrom(document.getMetadata()));
        }
        return resolved;
    }

    private RowSource select(String contentType) {
        for (RowSource reader : readers) {
            if (reader.supports(contentType)) {
                return reader;
            }
        }
        if (fallback.supports(contentType)) {
            return fallback;
        }
        throw new IllegalArgumentException("no reader supports " + contentType);
    }

    /**
     * The mapping's override wins; otherwise a generic stored type is refined by extension, because
     * Tika has no mimetype for jsonl or ndjson and types a .csv without the extension as text/plain.
     */
    static String effectiveContentType(String override, String storedType, String filename) {
        if (override != null) {
            return override;
        }
        if (!GENERIC_TYPES.contains(storedType)) {
            return storedType;
        }
        return Optional.ofNullable(TYPE_BY_EXTENSION.get(extension(filename))).orElse(storedType);
    }

    private static String extension(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * Tika's TextAndCSVParser sniffs the delimiter at index time and records its name under
     * csv:delimiter, which reaches the metadata map as tika_metadata_csv_delimiter. Reading it beats
     * sniffing again here: it costs nothing and it cannot disagree with the delimiter Tika used to
     * produce the document's indexed text.
     */
    static Character delimiterFrom(Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }
        Object name = metadata.get(DELIMITER_METADATA_KEY);
        return name == null ? null : DELIMITER_BY_TIKA_NAME.get(name.toString().toLowerCase(Locale.ROOT));
    }

    private static void closeQuietly(InputStream source) {
        try {
            source.close();
        } catch (IOException ignored) {
            // the read already failed or already finished; the close failure adds nothing actionable
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -pl datashare-app -am test -Dtest=TabularRowReaderTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, 11 tests.

If `DocumentBuilder.createDoc("docId").with(path)` does not set the root id such that `Document.isRootDocument()` is true, add `.withRootId("docId").withExtractionLevel((short) 0)` to the builder chain: `SourceExtractor.getSource` takes the embedded path otherwise and will look for a container.

- [ ] **Step 5: Commit**

```bash
git add datashare-app/src/main/java/org/icij/datashare/tabular/TabularRowReader.java datashare-app/src/test/java/org/icij/datashare/tabular/TabularRowReaderTest.java
git commit -m "feat(tabular): resolve a document id to its rows"
```

---

### Task 6: Cross-reader equivalence

**Files:**
- Test: `datashare-app/src/test/java/org/icij/datashare/tabular/RowSourceEquivalenceTest.java`

**Interfaces:**
- Consumes: every reader from Tasks 1 to 4.
- Produces: nothing. This is the issue's last checklist item.

Every fixture is generated in the test rather than committed, so what "the same table" means is stated in code. The ODS package is built by hand because POI cannot write ODS: a ZIP holding a `mimetype` entry and a minimal `content.xml`. The content type is passed explicitly in the options, so ZIP-level detection is not what is under test.

The one format on the allowlist with no authorable fixture is `.xlsb`: POI cannot write it and there is no practical way to build one in a test. Its coverage is the `supports` assertion in Task 4. Note this rather than pretend otherwise.

- [ ] **Step 1: Write the failing test**

```java
package org.icij.datashare.tabular;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.stream.Stream;

import static org.fest.assertions.Assertions.assertThat;

/**
 * The same logical table in every format a reader claims, asserted to produce identical rows. This is
 * what keeps a new reader honest: adding one means adding its fixture here.
 */
public class RowSourceEquivalenceTest {
    private static final List<Map<String, String>> EXPECTED = List.of(
            Map.of("id", "1", "name", "ACME", "country", "FR"),
            Map.of("id", "2", "name", "Globex", "country", "US"));

    private static List<Row> read(RowSource source, byte[] content, RowSourceOptions options)
            throws Exception {
        try (InputStream stream = new ByteArrayInputStream(content);
             Stream<Row> rows = source.rows(stream, options)) {
            return rows.toList();
        }
    }

    private static void assertEquivalent(List<Row> rows) {
        assertThat(rows).hasSize(2);
        for (int index = 0; index < EXPECTED.size(); index++) {
            assertThat(rows.get(index).number()).isEqualTo(index + 1L);
            assertThat(rows.get(index).values()).isEqualTo(EXPECTED.get(index));
        }
    }

    @Test
    public void test_comma_separated() throws Exception {
        assertEquivalent(read(new DelimitedRowSource(),
                "id,name,country\n1,ACME,FR\n2,Globex,US\n".getBytes(StandardCharsets.UTF_8),
                RowSourceOptions.defaults()));
    }

    @Test
    public void test_semicolon_separated() throws Exception {
        assertEquivalent(read(new DelimitedRowSource(),
                "id;name;country\n1;ACME;FR\n2;Globex;US\n".getBytes(StandardCharsets.UTF_8),
                RowSourceOptions.defaults().withDelimiter(';')));
    }

    @Test
    public void test_tab_separated() throws Exception {
        assertEquivalent(read(new DelimitedRowSource(),
                "id\tname\tcountry\n1\tACME\tFR\n2\tGlobex\tUS\n".getBytes(StandardCharsets.UTF_8),
                RowSourceOptions.defaults().withDelimiter('\t')));
    }

    @Test
    public void test_xlsx() throws Exception {
        assertEquivalent(read(new WorkbookRowSource(), workbook(new XSSFWorkbook()),
                RowSourceOptions.defaults()));
    }

    @Test
    public void test_legacy_xls() throws Exception {
        assertEquivalent(read(new WorkbookRowSource(), workbook(new HSSFWorkbook()),
                RowSourceOptions.defaults()));
    }

    @Test
    public void test_json_array() throws Exception {
        assertEquivalent(read(new JsonRowSource(),
                ("[{\"id\":1,\"name\":\"ACME\",\"country\":\"FR\"},"
                        + "{\"id\":2,\"name\":\"Globex\",\"country\":\"US\"}]")
                        .getBytes(StandardCharsets.UTF_8),
                RowSourceOptions.defaults()));
    }

    @Test
    public void test_line_delimited_json() throws Exception {
        assertEquivalent(read(new JsonRowSource(),
                ("{\"id\":1,\"name\":\"ACME\",\"country\":\"FR\"}\n"
                        + "{\"id\":2,\"name\":\"Globex\",\"country\":\"US\"}\n")
                        .getBytes(StandardCharsets.UTF_8),
                RowSourceOptions.defaults()));
    }

    @Test
    public void test_html_table_through_the_fallback() throws Exception {
        String html = "<html><body><table>"
                + "<tr><th>id</th><th>name</th><th>country</th></tr>"
                + "<tr><td>1</td><td>ACME</td><td>FR</td></tr>"
                + "<tr><td>2</td><td>Globex</td><td>US</td></tr>"
                + "</table></body></html>";

        assertEquivalent(read(new TikaTableRowSource(), html.getBytes(StandardCharsets.UTF_8),
                RowSourceOptions.defaults().withContentType("text/html")));
    }

    @Test
    public void test_open_document_spreadsheet_through_the_fallback() throws Exception {
        assertEquivalent(read(new TikaTableRowSource(), ods(),
                RowSourceOptions.defaults()
                        .withContentType("application/vnd.oasis.opendocument.spreadsheet")));
    }

    private static byte[] workbook(Workbook workbook) throws Exception {
        Sheet sheet = workbook.createSheet("companies");
        String[][] table = {{"id", "name", "country"}, {"1", "ACME", "FR"}, {"2", "Globex", "US"}};
        for (int rowIndex = 0; rowIndex < table.length; rowIndex++) {
            org.apache.poi.ss.usermodel.Row row = sheet.createRow(rowIndex);
            for (int column = 0; column < table[rowIndex].length; column++) {
                row.createCell(column).setCellValue(table[rowIndex][column]);
            }
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.write(out);
            workbook.close();
            return out.toByteArray();
        }
    }

    /**
     * A minimal OpenDocument spreadsheet, built by hand because POI cannot write ODS. Only the
     * mimetype entry and content.xml are needed for Tika's OpenDocument parser to emit a table.
     */
    private static byte[] ods() throws Exception {
        String content = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<office:document-content"
                + " xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\""
                + " xmlns:table=\"urn:oasis:names:tc:opendocument:xmlns:table:1.0\""
                + " xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\">"
                + "<office:body><office:spreadsheet>"
                + "<table:table table:name=\"companies\">"
                + row("id", "name", "country")
                + row("1", "ACME", "FR")
                + row("2", "Globex", "US")
                + "</table:table>"
                + "</office:spreadsheet></office:body></office:document-content>";

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("mimetype"));
            zip.write("application/vnd.oasis.opendocument.spreadsheet".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("content.xml"));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    private static String row(String... cells) {
        StringBuilder row = new StringBuilder("<table:table-row>");
        for (String cell : cells) {
            row.append("<table:table-cell><text:p>").append(cell).append("</text:p></table:table-cell>");
        }
        return row.append("</table:table-row>").toString();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails, then passes**

Run: `mvn -pl datashare-app -am test -Dtest=RowSourceEquivalenceTest -Dsurefire.failIfNoSpecifiedTests=false`

Every reader already exists, so the failures here are real disagreements between readers rather than missing classes, which is the point of the task. Expect to have to fix one of these two:

- **Numeric coercion.** `JsonRowSource` renders `1` from JSON as `"1"` via `asText()`, and `WorkbookRowSource` renders a string cell `"1"` as `"1"`, so they agree. If a workbook fixture stored numbers instead of strings, `DataFormatter` would render `1` as `"1"` but `1.0` as `"1"` only with the right cell format. The fixture writes strings deliberately, for that reason.
- **ODS not parsing.** If Tika returns no table for the hand-built package, print `new StructureMarkdownExtractor().extract(...)` output for it and check whether `content.xml` needs an `office:version` attribute. Do not weaken the assertion: if the fixture cannot be made valid, delete that one test and record in the spec that ODS coverage needs an externally produced file.

Expected once green: PASS, 9 tests.

- [ ] **Step 3: Run the whole tabular suite together**

Run: `mvn -pl datashare-app -am test -Dtest=DelimitedRowSourceTest,WorkbookRowSourceTest,JsonRowSourceTest,TikaTableRowSourceTest,TabularRowReaderTest,RowSourceEquivalenceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, 57 tests.

- [ ] **Step 4: Commit**

```bash
git add datashare-app/src/test/java/org/icij/datashare/tabular/RowSourceEquivalenceTest.java
git commit -m "test(tabular): assert every reader returns the same rows for an equivalent file"
```

---

## Self-review notes

Spec coverage checked section by section. Every spec requirement maps to a task: the contract to Task 1, the three tier-1 readers to Tasks 1 to 3, the tier-2 fallback and its allowlist to Task 4, resolution and the content-type collisions to Task 5, and the equivalence test to Task 6. The error table in the spec is covered by tests in the task that owns each error.

Two gaps recorded rather than papered over:

1. **`.xlsb` has no round-trip test.** POI cannot write the format and no fixture is authorable in a test. Coverage is the `supports` assertion only. Verifying it needs an externally produced file, which is a follow-up, not a step someone can execute from this plan.
2. **`.numbers` has no test at all.** Same reason, plus the format is best-effort: Tika's handler covers the older iWork package format while newer IWA-based files are detected but not reliably parsed.

Type consistency checked across tasks: `Row.headers(List<String>)` is defined in Task 1 and used in Tasks 2 and 4; `JsonRowSource.NDJSON_CONTENT_TYPE` is defined in Task 3 and used in Task 5; `TikaTableRowSource.SUPPORTED` is defined in Task 4 and referenced in Task 5's error path; `RowSourceOptions.withContentType`, `withCharset` and `withDelimiter` are defined in Task 1 and used in Tasks 4, 5 and 6.

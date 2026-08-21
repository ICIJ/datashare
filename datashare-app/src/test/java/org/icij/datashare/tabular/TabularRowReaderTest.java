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

    private static final List<String> CONTENT_FIELDS = List.of("content", "content_translated");

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
        when(indexer.<Document>get("local-datashare", "docId", "docId", CONTENT_FIELDS)).thenReturn(document);
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

    @Test
    public void test_an_unsupported_content_type_fails() throws Exception {
        indexed("scan.pdf", "application/pdf", "not really a pdf", Map.of());

        try {
            rows(RowSourceOptions.defaults());
            throw new AssertionError("expected an IllegalArgumentException");
        } catch (IllegalArgumentException failure) {
            assertThat(failure.getMessage()).contains("application/pdf");
            assertThat(failure.getMessage()).contains("text/csv");
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_a_missing_document_fails() throws Exception {
        when(indexer.<Document>get("local-datashare", "missing", "missing", CONTENT_FIELDS)).thenReturn(null);
        try (Stream<Row> ignored = reader.rows(project, "missing", null, RowSourceOptions.defaults())) {
            // the failure is expected before any row is read
        }
    }

    @Test
    public void test_a_reader_failure_closes_the_source() throws Exception {
        String html = "<html><body><p>no table here</p></body></html>";
        Document document = indexed("page.html", "text/html", html, Map.of());
        SourceExtractor sourceExtractor = mock(SourceExtractor.class);
        TrackingInputStream source = new TrackingInputStream(html);
        when(sourceExtractor.getSource(project, document)).thenReturn(source);

        try (Stream<Row> ignored = new TabularRowReader(indexer, sourceExtractor)
                .rows(project, "docId", null, RowSourceOptions.defaults())) {
            throw new AssertionError("expected an IllegalArgumentException");
        } catch (IllegalArgumentException failure) {
            assertThat(failure.getMessage()).contains("no table");
        }

        assertThat(source.closed).isTrue();
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

    /**
     * The extension table refines both generic types, and application/octet-stream is the one where
     * the txt entry carries its weight: no reader claims octet-stream, so without it a .txt whose
     * bytes Tika declined to type would fail instead of reaching the delimited reader.
     */
    @Test
    public void test_effective_content_type_refines_an_octet_stream_txt() {
        assertThat(TabularRowReader.effectiveContentType(null, "application/octet-stream", "notes.txt"))
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
    /**
     * An embedded document carries its container's path, so the path-derived name would hand a
     * records.jsonl inside an archive the name of the archive and route the ndjson to the csv reader.
     */
    @Test
    public void test_the_name_tika_recorded_beats_the_container_path() throws Exception {
        indexed("archive.zip", "text/plain", "{\"id\":1,\"name\":\"ACME\"}\n",
                Map.of("tika_metadata_resourcename", "records.jsonl"));

        List<Row> rows = rows(RowSourceOptions.defaults());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).values().get("name")).isEqualTo("ACME");
    }

    @Test
    public void test_a_document_with_no_detected_content_type_is_refined_by_extension() throws Exception {
        indexed("contacts.csv", null, "id,name\n1,ACME\n", Map.of());

        assertThat(rows(RowSourceOptions.defaults()).get(0).values().get("name")).isEqualTo("ACME");
    }

    @Test
    public void test_a_tsv_extension_supplies_the_delimiter_its_metadata_does_not() throws Exception {
        indexed("companies.tsv", "text/plain", "id\tname\n1\tACME\n", Map.of());

        assertThat(rows(RowSourceOptions.defaults()).get(0).values().get("name")).isEqualTo("ACME");
    }

}

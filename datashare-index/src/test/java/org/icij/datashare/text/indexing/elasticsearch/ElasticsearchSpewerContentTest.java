package org.icij.datashare.text.indexing.elasticsearch;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.extract.MemoryDocumentCollectionFactory;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.extract.document.DocumentFactory;
import org.icij.extract.document.PathIdentifier;
import org.icij.extract.document.TikaDocument;
import org.icij.spewer.FieldNames;
import org.junit.Test;

import java.io.Reader;
import java.io.StringReader;
import java.util.Map;

import static java.nio.file.Paths.get;
import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.Mockito.mock;

// Unit tests for the bounded reader in ElasticsearchSpewer.readContent. These do not need
// Elasticsearch: they exercise the read/cap/trim path directly with in-memory readers.
public class ElasticsearchSpewerContentTest {
    private ElasticsearchSpewer spewerWithMaxContentLength(String maxContentLength) {
        return new ElasticsearchSpewer(mock(Indexer.class), new MemoryDocumentCollectionFactory<>(),
                text -> Language.ENGLISH, new FieldNames(),
                new PropertiesProvider(Map.of("maxContentLength", maxContentLength)));
    }

    private TikaDocument documentReading(Reader reader) {
        TikaDocument document = new DocumentFactory().withIdentifier(new PathIdentifier()).create(get("content.txt"));
        document.setReader(reader);
        return document;
    }

    @Test
    public void test_reads_whole_content_when_shorter_than_limit() throws Exception {
        ElasticsearchSpewer spewer = spewerWithMaxContentLength("20");
        assertThat(spewer.readContent(documentReading(new StringReader("short content")))).isEqualTo("short content");
    }

    @Test
    public void test_caps_content_to_max_content_length_and_trims() throws Exception {
        ElasticsearchSpewer spewer = spewerWithMaxContentLength("20");
        // The first 20 chars are "this content should " and the trailing space is trimmed off.
        assertThat(spewer.readContent(documentReading(new StringReader("this content should be truncated"))))
                .isEqualTo("this content should");
    }

    @Test
    public void test_trims_leading_and_trailing_whitespace() throws Exception {
        ElasticsearchSpewer spewer = spewerWithMaxContentLength("-1");
        assertThat(spewer.readContent(documentReading(new StringReader("  \n hello world \t ")))).isEqualTo("hello world");
    }

    @Test
    public void test_unbounded_reads_full_content_for_normal_document() throws Exception {
        ElasticsearchSpewer spewer = spewerWithMaxContentLength("-1");
        String text = "a".repeat(5_000_000);
        assertThat(spewer.readContent(documentReading(new StringReader(text))).length()).isEqualTo(5_000_000);
    }

    @Test
    public void test_bounded_read_stops_without_consuming_the_whole_reader() throws Exception {
        ElasticsearchSpewer spewer = spewerWithMaxContentLength("20");
        CountingInfiniteReader reader = new CountingInfiniteReader('a');

        String content = spewer.readContent(documentReading(reader));

        assertThat(content.length()).isEqualTo(20);
        // The reader yields an effectively infinite stream, yet the bounded read touches only a
        // handful of chars before stopping. The old toString(reader) would have read until it hit
        // the 2^31-1 array cap and thrown OutOfMemoryError.
        assertThat(reader.charsProduced).isLessThan(1_000_000L);
    }

    @Test
    public void test_leading_whitespace_does_not_consume_the_content_budget() throws Exception {
        ElasticsearchSpewer spewer = spewerWithMaxContentLength("20");
        // A document whose extracted text begins with more than maxContentLength worth of blank
        // lines (blank pages, OCR page breaks before the body) must still index its real text.
        // The old toString(reader).trim() stripped that leading whitespace before capping; a
        // regression that caps the raw stream first would fill the budget with whitespace and trim
        // to an empty, unsearchable document.
        String text = " ".repeat(50) + "this content should be truncated";
        assertThat(spewer.readContent(documentReading(new StringReader(text)))).isEqualTo("this content should");
    }

    @Test
    public void test_whitespace_only_reader_terminates_with_empty_content() throws Exception {
        ElasticsearchSpewer spewer = spewerWithMaxContentLength("20");
        CountingInfiniteReader reader = new CountingInfiniteReader(' ');

        String content = spewer.readContent(documentReading(reader));

        // An infinite run of pure whitespace never starts a body: it must terminate (the leading
        // whitespace skip is bounded) and yield empty content without buffering the whole stream.
        assertThat(content).isEmpty();
        assertThat(reader.charsProduced).isLessThan(1_000_000L);
    }

    // A reader that hands out an effectively infinite stream of a single char and counts how many
    // chars it produced, so a test can prove the bounded read stops early instead of draining it.
    private static class CountingInfiniteReader extends Reader {
        private final char fill;
        private long charsProduced = 0;

        private CountingInfiniteReader(char fill) {
            this.fill = fill;
        }

        @Override
        public int read(char[] cbuf, int off, int len) {
            for (int i = 0; i < len; i++) {
                cbuf[off + i] = fill;
            }
            charsProduced += len;
            return len;
        }

        @Override
        public void close() {}
    }
}

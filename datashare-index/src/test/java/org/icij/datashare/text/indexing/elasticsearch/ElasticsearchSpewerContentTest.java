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
        CountingReader reader = new CountingReader('a', Long.MAX_VALUE);

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
    public void test_leading_whitespace_spanning_multiple_chunks_is_still_indexed() throws Exception {
        ElasticsearchSpewer spewer = spewerWithMaxContentLength("20");
        // Regression: when the leading whitespace fills one or more whole read chunks (> 8192 chars)
        // the body arrives in a later chunk. The skip must keep reading across chunks instead of
        // giving up once it has skipped `limit` whitespace, otherwise the real text is dropped and an
        // empty document is indexed.
        String text = " ".repeat(20_000) + "this content should be truncated";
        assertThat(spewer.readContent(documentReading(new StringReader(text)))).isEqualTo("this content should");
    }

    @Test
    public void test_trailing_whitespace_beyond_the_cap_is_not_treated_as_content() throws Exception {
        ElasticsearchSpewer spewer = spewerWithMaxContentLength("20");
        // The real text is exactly the cap; only trailing whitespace overflows the read window. That
        // is not a truncation (the old toString(reader).trim() dropped the trailing whitespace and
        // saw the content fit), so the body must be indexed whole.
        String text = "12345678901234567890" + " ".repeat(5_000);
        assertThat(spewer.readContent(documentReading(new StringReader(text)))).isEqualTo("12345678901234567890");
    }

    @Test
    public void test_trailing_whitespace_tail_beyond_the_cap_does_not_drain_the_reader() throws Exception {
        ElasticsearchSpewer spewer = spewerWithMaxContentLength("20");
        // Regression: a document whose real text reaches the cap and is then followed by an
        // effectively infinite run of trailing whitespace must not be scanned to end of input just to
        // decide the truncation-warning flag. The bounded read has to stop after the current chunk
        // instead of draining the reader, otherwise the indexer hangs forever on such a document.
        ContentThenInfiniteWhitespaceReader reader = new ContentThenInfiniteWhitespaceReader('a', 20);

        String content = spewer.readContent(documentReading(reader));

        assertThat(content).isEqualTo("a".repeat(20));
        assertThat(reader.charsProduced).isLessThan(1_000_000L);
    }

    @Test
    public void test_whitespace_only_reader_terminates_with_empty_content() throws Exception {
        ElasticsearchSpewer spewer = spewerWithMaxContentLength("20");
        CountingReader reader = new CountingReader(' ', 5_000_000L);

        String content = spewer.readContent(documentReading(reader));

        // A run of pure whitespace never starts a body: it terminates at end of input and yields
        // empty content without ever buffering the stream.
        assertThat(content).isEmpty();
        assertThat(reader.charsProduced).isEqualTo(5_000_000L);
    }

    // A reader that yields `contentChars` copies of a non-whitespace char and then an effectively
    // infinite run of spaces, counting how many chars it produced so a test can prove the bounded
    // read stops after the cap instead of draining the trailing whitespace to end of input.
    private static class ContentThenInfiniteWhitespaceReader extends Reader {
        private final char content;
        private final long contentChars;
        private long charsProduced = 0;

        private ContentThenInfiniteWhitespaceReader(char content, long contentChars) {
            this.content = content;
            this.contentChars = contentChars;
        }

        @Override
        public int read(char[] cbuf, int off, int len) {
            for (int i = 0; i < len; i++) {
                cbuf[off + i] = charsProduced + i < contentChars ? content : ' ';
            }
            charsProduced += len;
            return len;
        }

        @Override
        public void close() {}
    }

    // A reader that hands out a stream of a single char up to `total` chars then reports end of
    // input, counting how many chars it produced so a test can prove how much the read touched. Pass
    // Long.MAX_VALUE for an effectively infinite stream.
    private static class CountingReader extends Reader {
        private final char fill;
        private final long total;
        private long charsProduced = 0;

        private CountingReader(char fill, long total) {
            this.fill = fill;
            this.total = total;
        }

        @Override
        public int read(char[] cbuf, int off, int len) {
            if (charsProduced >= total) {
                return -1;
            }
            int n = (int) Math.min(len, total - charsProduced);
            for (int i = 0; i < n; i++) {
                cbuf[off + i] = fill;
            }
            charsProduced += n;
            return n;
        }

        @Override
        public void close() {}
    }
}

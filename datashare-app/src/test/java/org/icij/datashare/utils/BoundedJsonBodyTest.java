package org.icij.datashare.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.codestory.http.Request;
import org.icij.datashare.BodyTooLargeException;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class BoundedJsonBodyTest {
    private static final int MAX = 16;
    private static final int TEN_MIB = 10 * 1024 * 1024;
    private static final ObjectMapper objectMapper = new ObjectMapper();
    @Test
    public void test_reads_a_json_body_smaller_than_the_limit() throws Exception {
        JsonNode node = BoundedJsonBody.boundedRead(requestWith("{\"a\":\"b\"}"), objectMapper, MAX);

        assertThat(node.get("a").asText()).isEqualTo("b");
    }

    @Test
    public void test_reads_a_json_body_of_exactly_the_limit() throws Exception {
        String body = jsonOfLength(MAX);
        assertThat(body.getBytes(StandardCharsets.UTF_8).length).isEqualTo(MAX);

        JsonNode node = BoundedJsonBody.boundedRead(requestWith(body), objectMapper, MAX);

        assertThat(node.get("a").asText()).hasSize(MAX - 8);
    }

    @Test
    public void test_rejects_a_json_body_one_byte_above_the_limit() {
        Request request = requestWith(jsonOfLength(MAX + 1));

        assertThrows(BodyTooLargeException.class, () -> BoundedJsonBody.boundedRead(request, objectMapper, MAX));
    }

    @Test
    public void test_the_limit_counts_bytes_and_not_characters() throws Exception {
        // {"a":"é"} is 9 characters but 10 bytes in UTF-8
        String body = "{\"a\":\"é\"}";
        assertThat(body.length()).isEqualTo(9);

        assertThrows(BodyTooLargeException.class, () -> BoundedJsonBody.boundedRead(requestWith(body), objectMapper, 9));
        assertThat(BoundedJsonBody.boundedRead(requestWith(body), objectMapper, 10).get("a").asText()).isEqualTo("é");
    }

    @Test
    public void test_a_body_of_empty_arrays_above_the_limit_is_rejected_before_being_parsed() {
        Request request = requestWith("[" + "[],".repeat(MAX) + "[]]");

        assertThrows(BodyTooLargeException.class, () -> BoundedJsonBody.boundedRead(request, objectMapper, MAX));
    }

    @Test
    public void test_does_not_read_a_huge_body_beyond_the_limit() {
        CountingInputStream tenMib = new CountingInputStream(TEN_MIB);

        assertThrows(BodyTooLargeException.class, () -> BoundedJsonBody.boundedRead(requestWith(tenMib), objectMapper, MAX));

        // slack for an internal buffer, but nowhere near the 10 MiB sent
        assertThat(tenMib.bytesRead()).isLessThan(1024);
    }

    @Test
    public void test_does_not_load_the_whole_body_through_the_request() throws Exception {
        Request request = requestWith("{\"a\":\"b\"}");

        BoundedJsonBody.boundedRead(request, objectMapper, MAX);

        // content() and contentAsBytes() both materialize the entire body in memory
        verify(request, never()).content();
        verify(request, never()).contentAsBytes();
    }

    @Test
    public void test_invalid_json_is_a_parse_error_and_not_a_too_large_error() {
        Request request = requestWith("not json");

        assertThrows(JsonProcessingException.class, () -> BoundedJsonBody.boundedRead(request, objectMapper, MAX));
    }

    @Test
    public void test_closes_the_body_stream_after_a_successful_read() throws Exception {
        CountingInputStream stream = new CountingInputStream("{\"a\":\"b\"}");

        BoundedJsonBody.boundedRead(requestWith(stream), objectMapper, MAX);

        assertThat(stream.isClosed()).isTrue();
    }

    @Test
    public void test_closes_the_body_stream_when_the_body_is_too_large() {
        CountingInputStream stream = new CountingInputStream(TEN_MIB);

        assertThrows(BodyTooLargeException.class, () -> BoundedJsonBody.boundedRead(requestWith(stream), objectMapper, MAX));

        assertThat(stream.isClosed()).isTrue();
    }

    private static String jsonOfLength(int bytes) {
        // {"a":"<padding>"} has 8 bytes of structure around the padding
        return "{\"a\":\"" + "b".repeat(bytes - 8) + "\"}";
    }

    private static Request requestWith(String body) {
        return requestWith(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static Request requestWith(InputStream body) {
        try {
            Request request = mock(Request.class);
            when(request.inputStream()).thenReturn(body);
            return request;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Serves {@code length} bytes without allocating them, and counts what is actually read. */
    private static class CountingInputStream extends InputStream {
        private final byte[] content;
        private final int length;
        private int position;
        private boolean closed;

        CountingInputStream(String content) {
            this.content = content.getBytes(StandardCharsets.UTF_8);
            this.length = this.content.length;
        }

        CountingInputStream(int length) {
            this.content = null;
            this.length = length;
        }

        @Override
        public int read() {
            if (position >= length) {
                return -1;
            }
            int next = content == null ? '[' : content[position];
            position++;
            return next;
        }

        @Override
        public void close() {
            closed = true;
        }

        int bytesRead() {
            return position;
        }

        boolean isClosed() {
            return closed;
        }
    }
}

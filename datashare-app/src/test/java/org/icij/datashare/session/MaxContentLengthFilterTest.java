package org.icij.datashare.session;

import net.codestory.http.Context;
import net.codestory.http.filters.PayloadSupplier;
import net.codestory.http.payload.Payload;
import org.icij.datashare.PropertiesProvider;
import org.junit.Test;

import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.cli.DatashareCliOptions.MAX_HTTP_BODY_SIZE_OPT;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MaxContentLengthFilterTest {
    private final Payload nextPayload = Payload.ok();
    private final PayloadSupplier nextFilter = () -> nextPayload;
    private final Context context = mock(Context.class);

    private MaxContentLengthFilter filterWithLimit(long maxBytes) {
        return new MaxContentLengthFilter(
                new PropertiesProvider(Map.of(MAX_HTTP_BODY_SIZE_OPT, String.valueOf(maxBytes))));
    }

    @Test
    public void test_rejects_when_content_length_exceeds_limit() throws Exception {
        when(context.header("Content-Length")).thenReturn("2000");
        Payload result = filterWithLimit(1000).apply("/auth/login", context, nextFilter);
        assertThat(result.code()).isEqualTo(413);
    }

    @Test
    public void test_passes_when_content_length_under_limit() throws Exception {
        when(context.header("Content-Length")).thenReturn("500");
        Payload result = filterWithLimit(1000).apply("/auth/login", context, nextFilter);
        assertThat(result).isSameAs(nextPayload);
    }

    @Test
    public void test_passes_when_no_content_length() throws Exception {
        when(context.header("Content-Length")).thenReturn(null);
        Payload result = filterWithLimit(1000).apply("/api/status", context, nextFilter);
        assertThat(result).isSameAs(nextPayload);
    }

    @Test
    public void test_passes_when_content_length_unparseable() throws Exception {
        when(context.header("Content-Length")).thenReturn("not-a-number");
        Payload result = filterWithLimit(1000).apply("/auth/login", context, nextFilter);
        assertThat(result).isSameAs(nextPayload);
    }
}

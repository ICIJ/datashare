package org.icij.datashare.utils;

import net.codestory.http.payload.Payload;
import org.junit.Test;

import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PayloadFormatterTest {

    @Test
    public void test_error_should_create_payload_with_error_message_and_status() {
        PayloadFormatter payloadFormatter = new PayloadFormatter();
        String message = "Error message";
        int status = 400;

        Map<String, String> expectedBody = Collections.singletonMap("error", message);
        Payload expectedPayload = new Payload(expectedBody).withCode(status);

        Payload result = payloadFormatter.error(message, status);

        assertTrue(result.isError());
        assertEquals(result.rawContent(), expectedPayload.rawContent());
    }
}
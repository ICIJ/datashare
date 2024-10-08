package org.icij.datashare.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;

public class JsonPayloadTest {
    @Test
    public void test_simple_payload() {
        JsonPayload simplePayload = new JsonPayload(200, new IntContent(12));
        assertThat(simplePayload.code()).isEqualTo(200);
        assertThat(simplePayload.rawContentType()).isEqualTo("application/json");
        assertThat(simplePayload.rawContent()).isEqualTo("{\"value\":12}");
    }

     @Test
    public void test_simple_payload_default_code_is_OK() {
        JsonPayload simplePayload = new JsonPayload(new IntContent(12));
        assertThat(simplePayload.code()).isEqualTo(200);
    }

    @Test
    public void test_empty_body() {
        JsonPayload emptyPayload = new JsonPayload(201);
        assertThat(emptyPayload.code()).isEqualTo(201);
        assertThat(emptyPayload.rawContentType()).isEqualTo("application/json");
        assertThat(emptyPayload.rawContent()).isEqualTo("{}");
    }

    record IntContent(int value) {}
}
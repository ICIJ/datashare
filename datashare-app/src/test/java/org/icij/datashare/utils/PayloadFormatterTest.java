package org.icij.datashare.utils;

import net.codestory.http.payload.Payload;
import org.junit.Test;

import java.util.Collections;
import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PayloadFormatterTest {

    @Test
    public void test_error_should_create_payload_with_error_message_and_status() {
        String message = "Error message";
        int status = 400;

        Map<String, String> expectedBody = Collections.singletonMap("error", message);
        Payload expectedPayload = new Payload(expectedBody).withCode(status);

        Payload result = PayloadFormatter.error(message, status);

        assertTrue(result.isError());
        assertEquals(result.rawContent(), expectedPayload.rawContent());
    }

    @Test
    public void test_allow_single_method() {
        Payload payload = PayloadFormatter.allowMethods("GET");

        assertThat(payload.headers()).includes(entry("Access-Control-Allow-Methods", "GET"));
    }

    @Test
    public void test_allow_multiple_methods() {
        Payload payload = PayloadFormatter.allowMethods("GET", "POST");

        assertThat(payload.headers()).includes(entry("Access-Control-Allow-Methods", "GET, POST"));
    }

    @Test
    public void test_allow_multiple_comma_separated_methods() {
        Payload payload = PayloadFormatter.allowMethods("GET,POST");

        assertThat(payload.headers()).includes(entry("Access-Control-Allow-Methods", "GET, POST"));
    }

    @Test
    public void test_allow_multiple_comma_separated_trimmed_methods() {
        Payload payload = PayloadFormatter.allowMethods("GET,  POST", "PATCH");

        assertThat(payload.headers()).includes(entry("Access-Control-Allow-Methods", "GET, POST, PATCH"));
    }

    @Test
    public void test_create_json_payload_with_string() {
        Payload payload = PayloadFormatter.json("Foo");
        assertThat(payload.rawContentType()).isEqualTo("application/json");
    }

    @Test
    public void test_create_json_payload_with_list_of_string() {
        Payload payload = PayloadFormatter.json(new String[]{"Foo", "Bar"});
        assertThat(payload.rawContentType()).isEqualTo("application/json");
    }
}
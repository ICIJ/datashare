package org.icij.datashare.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.util.List;

import static org.fest.assertions.Assertions.assertThat;

public class McpArgumentsTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonNode arguments(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    @Test
    public void test_require_texts_returns_the_list() throws Exception {
        assertThat(McpArguments.requireTexts(arguments("{\"ids\":[\"a\",\"b\"]}"), "ids", 100, 64))
                .isEqualTo(List.of("a", "b"));
    }

    @Test(expected = McpToolException.InvalidArgument.class)
    public void test_require_texts_rejects_a_missing_list() throws Exception {
        McpArguments.requireTexts(arguments("{}"), "ids", 100, 64);
    }

    @Test(expected = McpToolException.InvalidArgument.class)
    public void test_require_texts_rejects_an_empty_list() throws Exception {
        McpArguments.requireTexts(arguments("{\"ids\":[]}"), "ids", 100, 64);
    }

    @Test(expected = McpToolException.InvalidArgument.class)
    public void test_require_texts_rejects_a_list_above_the_cap() throws Exception {
        McpArguments.requireTexts(arguments("{\"ids\":[\"a\",\"b\",\"c\"]}"), "ids", 2, 64);
    }

    @Test(expected = McpToolException.InvalidArgument.class)
    public void test_require_texts_rejects_a_non_string_item() throws Exception {
        McpArguments.requireTexts(arguments("{\"ids\":[1]}"), "ids", 100, 64);
    }

    @Test(expected = McpToolException.InvalidArgument.class)
    public void test_require_texts_rejects_a_blank_item() throws Exception {
        McpArguments.requireTexts(arguments("{\"ids\":[\" \"]}"), "ids", 100, 64);
    }

    @Test(expected = McpToolException.InvalidArgument.class)
    public void test_require_texts_rejects_a_value_that_is_not_a_list() throws Exception {
        McpArguments.requireTexts(arguments("{\"ids\":\"a\"}"), "ids", 100, 64);
    }

    @Test
    public void test_require_texts_accepts_an_item_at_the_length_limit() throws Exception {
        String atLimit = "x".repeat(64);

        assertThat(McpArguments.requireTexts(arguments("{\"ids\":[\"" + atLimit + "\"]}"), "ids", 100, 64))
                .isEqualTo(List.of(atLimit));
    }

    @Test(expected = McpToolException.InvalidArgument.class)
    public void test_require_texts_rejects_an_item_above_the_length_limit() throws Exception {
        McpArguments.requireTexts(arguments("{\"ids\":[\"" + "x".repeat(65) + "\"]}"), "ids", 100, 64);
    }
}

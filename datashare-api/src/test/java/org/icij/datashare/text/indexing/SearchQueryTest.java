package org.icij.datashare.text.indexing;


import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;

public class SearchQueryTest {

    @Test
    public void test_is_json() {
        assertThat(new SearchQuery("foo").isJsonQuery()).isFalse();
        assertThat(new SearchQuery("{\"query\":\"foo\"}").isJsonQuery()).isTrue();
    }

    @Test
    public void test_as_json() {
        String jsonString = "{\"query\":\"foo\"}";
        assertThat(new SearchQuery(jsonString).asJson().toString()).isEqualTo(jsonString);
    }

    @Test(expected = IllegalStateException.class)
    public void test_as_json_throws_exception() {
        new SearchQuery("foo").asJson();
    }

    @Test(expected = IllegalStateException.class)
    public void test_null_as_json_throws_exception() {
        new SearchQuery(null).asJson();
    }

    @Test
    public void test_null_query() {
        SearchQuery nullQuery = new SearchQuery(null);
        assertThat(nullQuery.isJsonQuery()).isFalse();
        assertThat(nullQuery.isNull()).isTrue();
        assertThat(nullQuery.equals(new SearchQuery(null))).isTrue();
    }
}
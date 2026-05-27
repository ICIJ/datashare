package org.icij.datashare.utils;

import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assert.assertThrows;

public class IndexAccessVerifierTest {

    private IndexAccessVerifier indexAccessVerifier;

    @Test
    public void test_check_single_index() {
        assertThat(IndexAccessVerifier.checkIndices("foo")).isEqualTo("foo");
    }


    @Test
    public void test_check_invalid_single_index() {
        assertThrows(IllegalArgumentException.class, () -> {
            IndexAccessVerifier.checkIndices("foo?");
        });
    }

    @Test
    public void test_check_multiple_indices() {
        assertThat(IndexAccessVerifier.checkIndices("bar,foo")).isEqualTo("bar,foo");
    }

    @Test
    public void test_check_invalid_multiple_indices() {
        assertThrows(IllegalArgumentException.class, () -> {
            IndexAccessVerifier.checkIndices("bar,foo!");
        });
    }

    @Test
    public void test_is_async_search_submit() {
        assertThat(IndexAccessVerifier.isAsyncSearchSubmit("my-index/_async_search")).isTrue();
        assertThat(IndexAccessVerifier.isAsyncSearchSubmit("a,b/_async_search")).isTrue();
        assertThat(IndexAccessVerifier.isAsyncSearchSubmit("my-index/_search")).isFalse();
        assertThat(IndexAccessVerifier.isAsyncSearchSubmit("_async_search/some-id")).isFalse();
        assertThat(IndexAccessVerifier.isAsyncSearchSubmit("_async_search")).isFalse();
    }

    @Test
    public void test_is_async_search_status_path() {
        assertThat(IndexAccessVerifier.isAsyncSearchStatusPath("_async_search/some-id")).isTrue();
        assertThat(IndexAccessVerifier.isAsyncSearchStatusPath("_async_search/a/b==")).isTrue();
        assertThat(IndexAccessVerifier.isAsyncSearchStatusPath("my-index/_async_search")).isFalse();
        assertThat(IndexAccessVerifier.isAsyncSearchStatusPath("my-index/_search")).isFalse();
        assertThat(IndexAccessVerifier.isAsyncSearchStatusPath("_async_search")).isFalse();
    }

    @Test
    public void test_async_search_id_reconstructs_full_id() {
        assertThat(IndexAccessVerifier.asyncSearchId("_async_search/abc==")).isEqualTo("abc==");
        // ES ids may contain slashes; the id is everything after "_async_search/"
        assertThat(IndexAccessVerifier.asyncSearchId("_async_search/ab/cd==")).isEqualTo("ab/cd==");
    }
}

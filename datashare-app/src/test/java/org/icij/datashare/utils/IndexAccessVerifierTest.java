package org.icij.datashare.utils;

import org.junit.Test;
import org.junit.Before;

import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assert.assertThrows;

public class IndexAccessVerifierTest {

    private IndexAccessVerifier indexAccessVerifier;

    @Before
    public void setUp() {
        indexAccessVerifier = new IndexAccessVerifier();
    }

    @Test
    public void test_check_single_index() {
        assertThat(indexAccessVerifier.checkIndices("foo")).isEqualTo("foo");
    }


    @Test
    public void test_check_invalid_single_index() {
        assertThrows(IllegalArgumentException.class, () -> {
            indexAccessVerifier.checkIndices("foo?");
        });
    }

    @Test
    public void test_check_multiple_indices() {
        assertThat(indexAccessVerifier.checkIndices("bar,foo")).isEqualTo("bar,foo");
    }

    @Test
    public void test_check_invalid_multiple_indices() {
        assertThrows(IllegalArgumentException.class, () -> {
            indexAccessVerifier.checkIndices("bar,foo!");
        });
    }
}

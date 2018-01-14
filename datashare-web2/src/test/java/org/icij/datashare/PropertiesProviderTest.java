package org.icij.datashare;

import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;

public class PropertiesProviderTest {
    @Test
    public void testReadProperties() throws Exception {
        assertThat(new PropertiesProvider().getProperties().getProperty("foo")).isEqualTo("bar");
    }

    @Test
    public void testUnknownFileThrowsExplicitException() throws Exception {
        try {
            new PropertiesProvider("unknown.file").getProperties().getProperty("foo");
        } catch (IllegalArgumentException e) {
            assertThat(e).hasMessage("unknown.file not found in classpath");
        }
    }
}
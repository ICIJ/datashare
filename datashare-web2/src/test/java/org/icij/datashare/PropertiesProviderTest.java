package org.icij.datashare;

import org.junit.Test;

import static java.util.Optional.empty;
import static java.util.Optional.of;
import static org.fest.assertions.Assertions.assertThat;

public class PropertiesProviderTest {
    @Test
    public void test_read_properties() {
        assertThat(new PropertiesProvider().getProperties().getProperty("foo")).isEqualTo("bar");
    }

    @Test
    public void test_unknown_file_sets_empty_properties() throws Exception {
        assertThat(new PropertiesProvider("unknown.file").getProperties().getProperty("foo")).isNull();
    }

    @Test
    public void test_if_present() {
        assertThat(new PropertiesProvider().getIfPresent("foo")).isEqualTo(of("bar"));
        assertThat(new PropertiesProvider().getIfPresent("unknown")).isEqualTo(empty());
    }
}
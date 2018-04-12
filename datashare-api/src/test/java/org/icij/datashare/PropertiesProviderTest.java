package org.icij.datashare;

import org.junit.Test;

import java.util.Properties;

import static java.util.Optional.empty;
import static java.util.Optional.of;
import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;

public class PropertiesProviderTest {
    @Test
    public void test_read_properties() {
        assertThat(new PropertiesProvider().getProperties().getProperty("foo")).isEqualTo("bar");
    }

    @Test
    public void test_unknown_file_sets_empty_properties() {
        assertThat(new PropertiesProvider("unknown.file").getProperties().getProperty("foo")).isNull();
    }

    @Test
    public void test_if_present() {
        assertThat(new PropertiesProvider().get("foo")).isEqualTo(of("bar"));
        assertThat(new PropertiesProvider().get("unknown")).isEqualTo(empty());
    }

    @Test
    public void test_merge_properties() {
        Properties properties = new Properties();
        properties.setProperty("foo", "baz");
        properties.setProperty("bar", "qux");

        Properties merged = new PropertiesProvider().mergeWith(properties);
        assertThat(merged).includes(entry("foo", "baz"), entry("messageBusAddress", "redis"), entry("bar", "qux"));
    }
}
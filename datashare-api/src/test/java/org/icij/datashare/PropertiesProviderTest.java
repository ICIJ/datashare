package org.icij.datashare;

import org.junit.Test;

import java.util.HashMap;
import java.util.Properties;

import static java.util.Optional.empty;
import static java.util.Optional.of;
import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;
import static org.icij.datashare.test.TestUtils.putEnv;

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
    public void test_merge_properties_in_provider() {
        Properties properties = new Properties();
        properties.setProperty("foo", "baz");
        properties.setProperty("bar", "qux");

        Properties merged = new PropertiesProvider().mergeWith(properties).getProperties();
        assertThat(merged).includes(entry("foo", "baz"), entry("messageBusAddress", "redis"), entry("bar", "qux"));
    }

    @Test
    public void test_create_merged_properties() {
        Properties properties = new Properties();
        properties.setProperty("foo", "baz");
        properties.setProperty("bar", "qux");

        PropertiesProvider propertiesProvider = new PropertiesProvider();
        Properties merged = propertiesProvider.createMerged(properties);

        assertThat(merged).includes(entry("foo", "baz"), entry("messageBusAddress", "redis"), entry("bar", "qux"));
        assertThat(propertiesProvider.getProperties()).excludes(entry("foo", "baz"), entry("bar", "qux"));
    }

    @Test
    public void test_filtered_properties() {
        PropertiesProvider provider = new PropertiesProvider(new HashMap<String, String>() {{
            put("foo", "fop");
            put("bar", "bap");
            put("baz", "bap");
        }});
        assertThat(provider.getFilteredProperties("ba.*")).
                excludes(entry("bar", "bap"), entry("baz", "bap")).
                includes(entry("foo", "fop"));
        assertThat(provider.getFilteredProperties(".o.")).
                includes(entry("bar", "bap"), entry("baz", "bap")).
                excludes(entry("foo", "fop"));
        assertThat(provider.getFilteredProperties("b.*", ".*o")).isEmpty();
    }

    @Test
    public void test_adds_ext_ds_env_parameters() throws Exception {
        putEnv("DS_DOCKER_VARIABLE", "value");

        PropertiesProvider propertiesProvider = new PropertiesProvider();
        assertThat(propertiesProvider.getProperties().entrySet().size()).isEqualTo(3);
        assertThat(propertiesProvider.get("variable")).isEqualTo(of("value"));
    }

    @Test
    public void test_adds_ext_ds_env_parameters_camel_case_with_underscores() throws Exception {
        putEnv("DS_DOCKER_MY_VARIABLE", "value");

        PropertiesProvider propertiesProvider = new PropertiesProvider();
        assertThat(propertiesProvider.getProperties().entrySet().size()).isEqualTo(3);
        assertThat(propertiesProvider.get("myVariable")).isEqualTo(of("value"));
    }

    @Test
    public void test_adds_no_ds_env_variables() throws Exception {
        putEnv("MY_VARIABLE", "value");
        assertThat(new PropertiesProvider().getProperties().entrySet().size()).isEqualTo(2);
    }
}

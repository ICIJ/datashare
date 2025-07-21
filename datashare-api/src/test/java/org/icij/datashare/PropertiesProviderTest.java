package org.icij.datashare;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static java.nio.file.Files.readAllLines;
import static java.util.Arrays.asList;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;

public class PropertiesProviderTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    @Test
    public void test_read_properties_first_from_stgs_file() throws Exception {
        File settings = folder.newFile("file.conf");
        Files.write(settings.toPath(), asList("foo=doe", "bar=baz"));

        Properties properties = new PropertiesProvider(settings.getAbsolutePath()).getProperties();

        assertThat(properties)
                .includes(entry("foo", "doe"), entry("bar", "baz"))
                .excludes(entry("messageBusAddress", "redis"));
    }

    @Test
    public void test_read_properties_from_classpath() {
        assertThat(new PropertiesProvider((String) null).getProperties().getProperty("foo")).isEqualTo("bar");
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
        assertThat(merged).includes(entry("foo", "bar"), entry("messageBusAddress", "redis"), entry("bar", "qux"));
    }

    @Test
    public void test_override_properties_in_provider() {
        Properties properties = new Properties();
        properties.setProperty("foo", "baz");
        properties.setProperty("bar", "qux");

        Properties merged = new PropertiesProvider().overrideWith(properties).getProperties();
        assertThat(merged).includes(entry("foo", "baz"), entry("messageBusAddress", "redis"), entry("bar", "qux"));
    }

    @Test
    public void test_override_properties_in_properties_provider() {
        PropertiesProvider propertiesProvider = new PropertiesProvider();
        propertiesProvider.setProperty("foo", "baz");
        propertiesProvider.setProperty("bar", "qux");

        Properties merged = new PropertiesProvider().overrideWith(propertiesProvider).getProperties();
        assertThat(merged).includes(entry("foo", "baz"), entry("messageBusAddress", "redis"), entry("bar", "qux"));
    }

    @Test
    public void test_create_overridden_with() {
        Properties properties = new Properties();
        properties.setProperty("foo", "baz");
        properties.setProperty("bar", "qux");

        PropertiesProvider propertiesProvider = new PropertiesProvider(new HashMap<>() {{
            put("bar", "pre");
        }});
        Properties overridden = propertiesProvider.createOverriddenWith(new HashMap(properties));
        assertThat(overridden).includes(entry("foo","baz"),entry("bar","qux"));
    }

    @Test
    public void test_create_merged_properties() {
        Properties properties = new Properties();
        properties.setProperty("foo", "baz");
        properties.setProperty("bar", "qux");

        PropertiesProvider propertiesProvider = new PropertiesProvider();
        Properties merged = propertiesProvider.createMerged(properties);

        assertThat(merged).includes(entry("foo", "bar"), entry("messageBusAddress", "redis"), entry("bar", "qux"));
        assertThat(propertiesProvider.getProperties()).excludes(entry("foo", "baz"), entry("bar", "qux"));
    }

    @Test
    public void test_filtered_properties() {
        PropertiesProvider provider = new PropertiesProvider(new HashMap<>() {{
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
    public void test_adds_ext_ds_env_parameters_without_settings_file() throws Exception {
        putEnv("DS_DOCKER_FOO", "bar");
        PropertiesProvider propertiesProvider = new PropertiesProvider("unknown_file");

        assertThat(propertiesProvider.getProperties()).includes(entry("foo", "bar"));
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

    @Test
    public void test_save_settings_file() throws Exception {
        File settings = folder.newFile("datashare.properties");
        Properties properties = new Properties();
        properties.setProperty("foo", "doe");

        new PropertiesProvider(settings.getAbsolutePath()).mergeWith(properties).save();

        assertThat(readAllLines(settings.toPath())).contains("foo=doe");
    }

    @Test
    public void test_save_settings_file_with_no_property_file() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("foo", "doe");
        Path givenPath = folder.getRoot().toPath().resolve("datashare.properties");

        new PropertiesProvider(givenPath.toString()).mergeWith(properties).save();

        assertThat(readAllLines(givenPath)).contains("foo=doe");
    }

    @Test
    public void test_save_settings_file_filter_user_data() throws Exception {
        Properties properties = new Properties();
        properties.putAll(new HashMap<String, Object>() {{
            put("userProject", asList("i1", "i2"));
            put("userFoo", "bar");
        }});
        Path givenPath = folder.getRoot().toPath().resolve("datashare.properties");

        new PropertiesProvider(givenPath.toString()).mergeWith(properties).save();

        Properties actualProperties = new Properties();
        actualProperties.load(new FileInputStream(givenPath.toFile()));
        assertThat(actualProperties).excludes(entry("userFoo", "bar"), entry("userProject", asList("i1", "i2")));
    }

    @Test
    public void test_can_write_file_do_not_erase_file() throws Exception {
        File settings = folder.newFile("datashare.properties");

        assertThat(new PropertiesProvider(settings.getAbsolutePath()).isFileReadable(settings.toPath())).isTrue();
        assertThat(settings).exists();
    }
    @Test
    public void test_unique_hash_code_by_data_dir() {
        PropertiesProvider foo = new PropertiesProvider(Map.of("dataDir", "/foo"));
        PropertiesProvider bar = new PropertiesProvider(Map.of("dataDir", "/bar"));

        assertThat(foo.queueHash()).isNotEqualTo(bar.queueHash());
    }

    @Test
    public void test_unique_hash_code_starting_with_default_project() {
        PropertiesProvider foo = new PropertiesProvider(Map.of("defaultProject", "foo"));
        PropertiesProvider bar = new PropertiesProvider(Map.of("defaultProject", "bar"));

        assertThat(foo.queueHash()).startsWith("foo:");
        assertThat(bar.queueHash()).startsWith("bar:");
    }

    @Test
    public void test_unique_queue_name_with_hash_code_starting_with_default_project() {
        PropertiesProvider foo = new PropertiesProvider(Map.of("defaultProject", "foo"));
        PropertiesProvider bar = new PropertiesProvider(Map.of("defaultProject", "bar"));

        assertThat(foo.queueNameWithHash()).startsWith("extract:queue:foo:");
        assertThat(bar.queueNameWithHash()).startsWith("extract:queue:bar:");
    }
    @Test(expected = NumberFormatException.class)
    public void test_queue_capacity_is_a_number() {
        PropertiesProvider foo = new PropertiesProvider(Map.of("queueCapacity", "foo"));
        foo.queueCapacity();
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_queue_capacity_throw_an_exception_when_negative_number() {
        PropertiesProvider foo = new PropertiesProvider(Map.of("queueCapacity", "-1"));
        foo.queueCapacity();
    }
    @Test
    public void test_queue_capacity_positive_number() {
        PropertiesProvider foo = new PropertiesProvider(Map.of("queueCapacity", "1"));
        assertThat(foo.queueCapacity()).isEqualTo(1);
    }
    @Test
    public void test_queue_capacity_default_number_is_1e6() {
        PropertiesProvider foo = new PropertiesProvider();
        assertThat(foo.queueCapacity()).isEqualTo(1000000);
    }

    @Test
    public void test_properties_to_map() {
        Map<String, Object> map = Map.of("foo", "bar", "baz", 12);
        assertThat(PropertiesProvider.propertiesToMap(new PropertiesProvider(map).getProperties())).isEqualTo(map);
    }

    @Test
    public void test_override_queue_name_with_hash_code_starting_with_default_project() {
        PropertiesProvider props = new PropertiesProvider(Map.of("defaultProject", "foo"));
        assertThat(props.queueName()).isEqualTo("extract:queue");
        assertThat(props.overrideQueueNameWithHash().queueName()).isEqualTo(props.queueNameWithHash());
    }

    @Test
    public void test_override_queue_name_with_hash_code_starting_with_new_project() {
        PropertiesProvider props = new PropertiesProvider();
        assertThat(props.queueName()).isEqualTo("extract:queue");
        assertThat(props.overrideQueueNameWithHash("bar").queueName()).startsWith("extract:queue:bar:");
    }


    /**
     * see https://stackoverflow.com/questions/318239/how-do-i-set-environment-variables-from-java
     */
    private static void putEnv(String name, String value) throws Exception {
      try {
        Class<?> processEnvironmentClass = Class.forName("java.lang.ProcessEnvironment");
        Field theEnvironmentField = processEnvironmentClass.getDeclaredField("theEnvironment");
        theEnvironmentField.setAccessible(true);
        Map<String, String> env = (Map<String, String>) theEnvironmentField.get(null);
        env.put(name, value);
        Field theCaseInsensitiveEnvironmentField = processEnvironmentClass.getDeclaredField("theCaseInsensitiveEnvironment");
        theCaseInsensitiveEnvironmentField.setAccessible(true);
        Map<String, String> cienv = (Map<String, String>)theCaseInsensitiveEnvironmentField.get(null);
        cienv.put(name, value);
      } catch (NoSuchFieldException e) {
        Class[] classes = Collections.class.getDeclaredClasses();
        Map<String, String> env = System.getenv();
        for(Class cl : classes) {
          if("java.util.Collections$UnmodifiableMap".equals(cl.getName())) {
            Field field = cl.getDeclaredField("m");
            field.setAccessible(true);
            Object obj = field.get(env);
            Map<String, String> map = (Map<String, String>) obj;
            map.clear();
            map.put(name, value);
          }
        }
      }
    }
}

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
    public void test_read_properties_first_from_config_file() throws Exception {
        File configFile = folder.newFile("file.conf");
        Files.write(configFile.toPath(), asList("foo=doe", "bar=baz"));

        Properties properties = new PropertiesProvider(configFile.getAbsolutePath()).getProperties();

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
    public void test_adds_ext_ds_env_parameters_without_configuration_file() throws Exception {
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
    public void test_save_configuration_file() throws Exception {
        File configFile = folder.newFile("datashare.properties");
        Properties properties = new Properties();
        properties.setProperty("foo", "doe");

        new PropertiesProvider(configFile.getAbsolutePath()).mergeWith(properties).save();

        assertThat(readAllLines(configFile.toPath())).contains("foo=doe");
    }

    @Test
    public void test_save_configuration_file_with_no_property_file() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("foo", "doe");
        Path givenPath = folder.getRoot().toPath().resolve("datashare.properties");

        new PropertiesProvider(givenPath.toString()).mergeWith(properties).save();

        assertThat(readAllLines(givenPath)).contains("foo=doe");
    }

    @Test
    public void test_save_configuration_file_filter_user_data() throws Exception {
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
        File configFile = folder.newFile("datashare.properties");

        assertThat(new PropertiesProvider(configFile.getAbsolutePath()).isFileReadable(configFile.toPath())).isTrue();
        assertThat(configFile).exists();
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

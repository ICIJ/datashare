package org.icij.datashare;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.fest.assertions.Assertions.assertThat;

/** Consistency of the registries bundled with Datashare (the test classpath shadows them with test ones). */
public class DeliverableRegistryTest {
    @Test
    public void test_bundled_entries_have_a_url_matching_their_version() throws Exception {
        for (String registry : new String[] {"extensions.json", "plugins.json"}) {
            for (JsonNode entry : bundledEntries(registry)) {
                assertThat(entry.get("url").asText()).as(registry + " " + entry.get("id"))
                        .contains(entry.get("version").asText());
            }
        }
    }

    @Test
    public void test_bundled_sha256_when_present_is_64_hexadecimal_characters() throws Exception {
        for (String registry : new String[] {"extensions.json", "plugins.json"}) {
            for (JsonNode entry : bundledEntries(registry)) {
                if (entry.has("sha256")) {
                    assertThat(entry.get("sha256").asText()).as(registry + " " + entry.get("id"))
                            .matches("[0-9a-f]{64}");
                }
            }
        }
    }

    @Test
    public void test_bundled_extensions_with_a_sha256_are_jars_so_the_hash_is_checked() throws Exception {
        for (JsonNode entry : bundledEntries("extensions.json")) {
            if (entry.has("sha256")) {
                assertThat(entry.get("url").asText()).as("extensions.json " + entry.get("id")).endsWith(".jar");
            }
        }
    }

    @Test
    public void test_bundled_plugins_declare_extensions_of_the_extension_registry() throws Exception {
        Set<String> extensionIds = new HashSet<>();
        bundledEntries("extensions.json").forEach(entry -> extensionIds.add(entry.get("id").asText()));

        for (JsonNode plugin : bundledEntries("plugins.json")) {
            for (JsonNode extensionId : plugin.path("extensions")) {
                assertThat(extensionIds).as("plugins.json " + plugin.get("id")).contains(extensionId.asText());
            }
        }
    }

    private JsonNode bundledEntries(String registry) throws Exception {
        // .../datashare-app/target/test-classes -> .../datashare-app/src/main/resources
        Path module = Path.of(getClass().getResource("/").toURI()).getParent().getParent();
        return new ObjectMapper().readTree(module.resolve("src/main/resources").resolve(registry).toFile())
                .get("deliverableList");
    }
}

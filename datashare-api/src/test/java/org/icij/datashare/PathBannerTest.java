package org.icij.datashare;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.text.Project;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Paths;

import static org.fest.assertions.Assertions.assertThat;


public class PathBannerTest {
    @Test
    public void serialize_pathBanner() throws JsonProcessingException {
        PathBanner pathBanner = new PathBanner(Project.project("foo"), Paths.get("toto/tata"), "This is a test");
        assertThat(JsonObjectMapper.writeValueAsString(pathBanner))
                .contains("\"name\":\"foo\"")
                .contains("\"label\":\"foo\"")
                .contains("\"sourcePath\":\"file:///vault/foo\"")
                .contains("\"note\":\"This is a test\"")
                .contains("\"blurSensitiveMedia\":false")
                .contains("\"variant\":\"info\"");
    }

    @Test
    public void deserialize_pathBanner() throws IOException {
        PathBanner pathBanner = JsonObjectMapper.readValue("{\"project\":{\"name\":\"project\",\"sourcePath\":\"file:///vault/foo\",\"id\":\"foo\"},\"note\":\"This is a test\",\"path\":\"toto/tata\",\"variant\":\"info\", \"blurSensitiveMedia\":false}", PathBanner.class);
        assertThat(pathBanner.path.toString()).isEqualTo("toto/tata");
        assertThat(pathBanner.project.getId()).isEqualTo("project");
        assertThat(pathBanner.note).isEqualTo("This is a test");
        assertThat(pathBanner.blurSensitiveMedia).isEqualTo(false);
        assertThat(pathBanner.variant).isEqualTo(PathBanner.Variant.info);
    }
}

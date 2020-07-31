package org.icij.datashare;

import org.icij.datashare.test.JarUtil;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Set;
import java.util.stream.Collectors;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.Extension.Type.WEB;

public class ExtensionServiceTest {
    @Rule public TemporaryFolder extensionFolder = new TemporaryFolder();
    @Rule public TemporaryFolder otherFolder = new TemporaryFolder();
    @Test
    public void test_get_list() {
        Set<Extension> extensions = new ExtensionService(extensionFolder.getRoot().toPath()).list();
        assertThat(extensions).hasSize(3);
        assertThat(extensions.stream().map(Extension::getId).collect(Collectors.toSet()))
                .containsOnly("my-extension-foo", "my-extension-baz", "my-extension-bar");
    }

    @Test
    public void test_get_extension() {
        Set<Extension> extensions = new ExtensionService(extensionFolder.getRoot().toPath()).list("my-extension-baz");
        assertThat(extensions).hasSize(1);
        assertThat(extensions.iterator().next().type).isEqualTo(WEB);
    }

    @Test
    public void test_install_an_extension_from_id() throws IOException {
        JarUtil.createJar(otherFolder.getRoot().toPath(), "my-extension", SOURCE);
        ExtensionService extensionService = new ExtensionService(extensionFolder.getRoot().toPath(), new ByteArrayInputStream(("{\"deliverableList\": [" +
                       "{\"id\":\"my-extension\", \"url\": \"" + otherFolder.getRoot().toPath().resolve("my-extension.jar").toUri() + "\"}" +
                       "]}").getBytes()));

        extensionService.downloadAndInstall("my-extension");

        assertThat(extensionFolder.getRoot().toPath().resolve("my-extension.jar").toFile()).exists();
    }

    @Test
    public void test_install_an_extension_from_url() throws IOException {
        JarUtil.createJar(otherFolder.getRoot().toPath(), "my-extension", SOURCE);
        ExtensionService extensionService = new ExtensionService(extensionFolder.getRoot().toPath());

        extensionService.downloadAndInstall(otherFolder.getRoot().toPath().resolve("my-extension.jar").toUri().toURL());

        assertThat(extensionFolder.getRoot().toPath().resolve("my-extension.jar").toFile()).exists();
    }

    @Test
    public void test_delete_extension_by_id() throws IOException {
        JarUtil.createJar(extensionFolder.getRoot().toPath(), "my-extension", SOURCE);
        ExtensionService extensionService = new ExtensionService(extensionFolder.getRoot().toPath(), new ByteArrayInputStream(("{\"deliverableList\": [" +
                "{\"id\":\"my-extension\", \"url\": \"" + otherFolder.getRoot().toPath().resolve("my-extension.jar").toUri() + "\"}" +
                "]}").getBytes()));
        assertThat(extensionFolder.getRoot().toPath().resolve("my-extension.jar").toFile()).exists();
        
        extensionService.delete("my-extension");
        assertThat(extensionFolder.getRoot().toPath().resolve("my-extension.jar").toFile()).doesNotExist();

    }

    public static final String SOURCE = "package org.icij.datashare.mode;\n" +
            "\n" +
            "import net.codestory.http.annotations.Get;\n" +
            "import net.codestory.http.annotations.Prefix;\n" +
            "\n" +
            "public class FooResource {\n" +
            "    @Get(\"url\")\n" +
            "    public String url() {\n" +
            "        return \"hello from foo extension with %s\";\n" +
            "    }\n" +
            "}";
}

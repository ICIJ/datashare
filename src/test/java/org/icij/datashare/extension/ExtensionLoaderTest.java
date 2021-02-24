package org.icij.datashare.extension;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.test.JarUtil.createJar;

public class ExtensionLoaderTest {
    @Test
    public void test_load_jars() throws Exception {
        Path extensionsDir = Paths.get(getClass().getResource("/extensions").toURI());
        extensionsDir.resolve("executable.jar").toFile().setExecutable(true);

        assertThat(new ExtensionLoader(extensionsDir).getJars()).hasSize(1);
        assertThat(new ExtensionLoader(extensionsDir).getJars()).containsOnly(extensionsDir.resolve("executable.jar").toFile());
    }

    @Test(timeout = 5000)
    public void test_no_static_initializers() throws Exception {
        Path extensionsDir = Paths.get(getClass().getResource("/extensions").toURI());
        createJar(extensionsDir,"extension",INFINITE_INITIALIZER_LOOP_SOURCE);
        extensionsDir.resolve("extension.jar").toFile().setExecutable(true);

        new ExtensionLoader(extensionsDir).load(c -> {},c -> true); // should not timeout
    }

    String INFINITE_INITIALIZER_LOOP_SOURCE = "package org.icij.datashare.extension;\n" +
            "\n" +
            "public class InfiniteInitializerLoop {\n" +
            "    static {\n" +
            "        new InfiniteInitializerLoop();\n" +
            "    }\n" +
            "\n" +
            "    private InfiniteInitializerLoop() {\n" +
            "        while (true){\n" +
            "            try {\n" +
            "                Thread.sleep(100);\n" +
            "            } catch (InterruptedException e) {\n" +
            "            }\n" +
            "        }\n" +
            "    }\n" +
            "}";
}

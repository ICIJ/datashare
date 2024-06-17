package org.icij.datashare.extension;

import org.icij.datashare.test.LogbackAppenderWrapper;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.slf4j.event.Level;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.test.JarUtil.createJar;

public class ExtensionLoaderTest {
    public @Rule TemporaryFolder folder = new TemporaryFolder();
    private final LogbackAppenderWrapper logWrapper = new LogbackAppenderWrapper();

    @Test
    public void test_load_jars() throws Exception {
        Path extensionsDir = Paths.get(getClass().getResource("/extensions").toURI());
        extensionsDir.resolve("executable.jar").toFile().setExecutable(true);

        assertThat(new ExtensionLoader(extensionsDir).getJars()).hasSize(1);
        assertThat(new ExtensionLoader(extensionsDir).getJars()).containsOnly(extensionsDir.resolve("executable.jar").toFile());
    }

    @Test(timeout = 50000)
    public void test_no_static_initializers() throws Exception {
        Path extensionsDir = folder.getRoot().toPath();
        createJar(extensionsDir,"extension",INFINITE_INITIALIZER_LOOP_SOURCE);
        extensionsDir.resolve("extension.jar").toFile().setExecutable(true);

        new ExtensionLoader(extensionsDir).load(c -> {},c -> true); // should not timeout
    }

    @Test
    public void test_module_info_should_not_log_errors() throws Exception {
        Path extensionsDir = Paths.get(getClass().getResource("/extensions").toURI());
        new ExtensionLoader(extensionsDir).findClassesInJar(aClass -> true, extensionsDir.resolve("executable.jar").toFile());
        assertThat(logWrapper.logs(Level.WARN)).isEmpty();
        assertThat(logWrapper.logs(Level.ERROR)).isEmpty();
    }

    @After
    public void tearDown() throws Exception {
        logWrapper.reset();
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

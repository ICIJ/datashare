package org.icij.datashare.mode;

import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.HashMap;

import static org.icij.datashare.test.JarUtil.createJar;

public class CommonModeTest extends AbstractProdWebServerTest {
    @Rule public TemporaryFolder pluginFolder = new TemporaryFolder();
    CommonMode mode;

    @Test
    public void test_one_extension_with_prefix_and_get() throws Exception {
        String source = "package org.icij.datashare.mode;\n" +
                "\n" +
                "import net.codestory.http.annotations.Get;\n" +
                "import net.codestory.http.annotations.Prefix;\n" +
                "\n" +
                "@Prefix(\"foo\")\n" +
                "public class FooResource {\n" +
                "    @Get(\"url\")\n" +
                "    public String url() {\n" +
                "        return \"hello from foo extension\";\n" +
                "    }\n" +
                "}";
        createJar(pluginFolder.getRoot().toPath(), "extension", source);
        configure(routes -> mode.addExtensionConfiguration(routes));
        get("/foo/url").should().respond(200).contain("hello from foo extension");
    }

    @Before
    public void setUp() {
        mode = new CommonMode(new HashMap<String, String>() {{
            put("pluginsDir", pluginFolder.getRoot().toString());
        }});
    }
}

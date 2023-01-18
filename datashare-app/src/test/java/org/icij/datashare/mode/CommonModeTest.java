package org.icij.datashare.mode;

import net.codestory.rest.FluentRestTest;
import net.codestory.rest.RestAssert;
import org.icij.datashare.cli.QueueType;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashMap;

import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.Optional.ofNullable;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.test.JarUtil.createJar;

@RunWith(Parameterized.class)
public class CommonModeTest extends AbstractProdWebServerTest {
    private final String method;
    @Rule public TemporaryFolder pluginFolder = new TemporaryFolder();
    CommonMode mode;
    @Parameterized.Parameters
    public static Collection<Object[]> methods() {
        return asList(new Object[][]{{"Get"}, {"Post"}, {"Patch"}, {"Put"}, {"Delete"}, {"Options"}});
    }

    @Test
    public void test_one_extension() throws Throwable {
        test_one_extension(method, "/foo").should().respond(200).contain("hello from foo extension");
    }

    RestAssert test_one_extension(String method, String prefix) throws Throwable {
        String prefixString = prefix == null ? "\n":"@Prefix(\""+ prefix + "\")\n";
        String source = format("package org.icij.datashare.mode;\n" +
                "\n" +
                "import net.codestory.http.annotations.%s;\n" +
                "import net.codestory.http.annotations.Prefix;\n" +
                "\n" + prefixString +
                "public class FooResource {\n" +
                "    @%s(\"url\")\n" +
                "    public String url() {\n" +
                "        return \"hello from foo extension with %s\";\n" +
                "    }\n" +
                "}", method, method, method);
        createJar(pluginFolder.getRoot().toPath(), "extension", source);
        configure(routes -> mode.addExtensionConfiguration(routes));

        Method restAssertMethod = FluentRestTest.class.getMethod(method.toLowerCase(), String.class);
        return (RestAssert) restAssertMethod.invoke(this, ofNullable(prefix).orElse("") + "/url");
    }

    @Test
    public void test_has_redis_property() {
        CommonMode modeWithRedis = new CommonMode(new HashMap<String, String>() {{
            put("extensionsDir", pluginFolder.getRoot().toString());
            put("busType", QueueType.REDIS.name());
        }});

        CommonMode modeWithoutRedis = new CommonMode(new HashMap<String, String>() {{
            put("extensionsDir", pluginFolder.getRoot().toString());
            put("queueType", QueueType.MEMORY.name());
        }});

        assertThat(modeWithRedis.hasRedisProperty()).isTrue();
        assertThat(modeWithoutRedis.hasRedisProperty()).isFalse();
    }

    public CommonModeTest(String method) { this.method = method;}

    @Before
    public void setUp() {
        mode = new CommonMode(new HashMap<String, String>() {{
            put("extensionsDir", pluginFolder.getRoot().toString());
        }});
    }
}

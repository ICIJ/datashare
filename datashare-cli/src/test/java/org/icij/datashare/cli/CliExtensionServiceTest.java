package org.icij.datashare.cli;

import static org.fest.assertions.Assertions.assertThat;

import com.google.inject.Guice;
import java.util.List;
import java.util.Properties;
import org.icij.datashare.cli.spi.CliExtension;
import org.junit.Rule;
import org.junit.Test;
import org.junit.contrib.java.lang.system.SystemOutRule;

public class CliExtensionServiceTest {
    @Rule
    public final SystemOutRule systemOutRule = new SystemOutRule().enableLog();

    @Test
    public void test_load_and_init_extension() throws Exception {
        List<CliExtension> extensions = CliExtensionService.getInstance().getExtensions();

        assertThat(extensions).hasSize(1);
        CliExtension extension = extensions.get(0);
        assertThat(extension).isInstanceOf(CliExtensionFoo.class);

        extension.init(Guice::createInjector);
        Properties props = new Properties();
        props.setProperty("foo", "bar");
        extension.run(props);
        assertThat(systemOutRule.getLog()).contains("foo=bar");
    }
}

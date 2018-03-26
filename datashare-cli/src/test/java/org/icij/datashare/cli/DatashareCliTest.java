package org.icij.datashare.cli;

import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;

public class DatashareCliTest {
    @Test
    public void test_web_opt() throws Exception {
        DatashareCli.parseArguments(new String[] {"-o"});
        assertThat(DatashareCli.webServer).isFalse();

        DatashareCli.parseArguments(new String[] {"--web"});
        assertThat(DatashareCli.webServer).isTrue();

        DatashareCli.parseArguments(new String[] {"-w"});
        assertThat(DatashareCli.webServer).isTrue();
    }
}
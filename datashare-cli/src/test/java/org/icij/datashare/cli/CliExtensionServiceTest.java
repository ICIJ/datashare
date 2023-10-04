package org.icij.datashare.cli;

import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;

public class CliExtensionServiceTest {
    @Test
    public void test_load_extension() {
        assertThat(CliExtensionService.getInstance().getExtensions()).hasSize(1);
        assertThat(CliExtensionService.getInstance().getExtensions().get(0)).isInstanceOf(CliExtensionFoo.class);
    }
}

package org.icij.datashare.cli.command;

import org.junit.Test;

import java.util.Properties;

import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;
import static org.icij.datashare.cli.DatashareCliOptions.ENTITIES_EXPORT_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.ENTITIES_EXPORT_OUTPUT_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.MODE_OPT;

public class EntitiesCommandTest extends AbstractDatashareCommandTest {
    @Test
    public void test_entities_with_no_subcommand_exits_2() {
        assertThat(parseExitCode("entities")).isEqualTo(2);
    }

    @Test
    public void test_export_defaults_to_ftm_on_stdout() {
        Properties props = parse("entities", "export");

        assertThat(props).includes(entry(ENTITIES_EXPORT_OPT, "ftm"), entry(MODE_OPT, "CLI"));
        assertThat(props.containsKey(ENTITIES_EXPORT_OUTPUT_OPT)).isFalse();
    }

    @Test
    public void test_export_flags_propagate() {
        Properties props = parse("entities", "export", "--model", "wikidata", "--output", "out.json");

        assertThat(props).includes(entry(ENTITIES_EXPORT_OPT, "wikidata"), entry(ENTITIES_EXPORT_OUTPUT_OPT, "out.json"));
    }
}

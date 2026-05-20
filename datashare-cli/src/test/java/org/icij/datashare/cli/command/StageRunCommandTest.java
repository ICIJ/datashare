package org.icij.datashare.cli.command;

import org.junit.Test;

import java.util.Properties;

import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;

public class StageRunCommandTest extends AbstractDatashareCommandTest {

    @Test
    public void test_stage_run_scan_index() {
        Properties props = parse("stage", "run", "--stages", "SCAN,INDEX");
        assertThat(props).includes(entry("mode", "CLI"));
        assertThat(props).includes(entry("stages", "SCAN,INDEX"));
    }

    @Test
    public void test_stage_run_single_stage() {
        Properties props = parse("stage", "run", "--stages", "SCAN");
        assertThat(props).includes(entry("mode", "CLI"));
        assertThat(props).includes(entry("stages", "SCAN"));
    }

    @Test
    public void test_stage_run_all_main_stages() {
        Properties props = parse("stage", "run", "--stages", "SCAN,INDEX,NLP");
        assertThat(props).includes(entry("stages", "SCAN,INDEX,NLP"));
    }

    @Test
    public void test_stage_run_missing_stages_fails() {
        int exitCode = parseExitCode("stage", "run");
        assertThat(exitCode).isNotEqualTo(0);
    }

    @Test
    public void test_stage_run_with_data_dir() {
        Properties props = parse("--dataDir", "/data/docs", "stage", "run", "--stages", "SCAN,INDEX");
        assertThat(props).includes(entry("mode", "CLI"));
        assertThat(props).includes(entry("stages", "SCAN,INDEX"));
        assertThat(props).includes(entry("dataDir", "/data/docs"));
    }

    @Test
    public void test_nlp_parallelism() {
        Properties props = parse("stage", "run", "--stages", "NLP", "--nlpParallelism", "4");
        assertThat(props).includes(entry("nlpParallelism", "4"));
    }
}

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

    @Test
    public void test_ocr_strategy() {
        Properties props = parse("stage", "run", "--stages", "SCAN,INDEX", "--ocrStrategy", "OCR_AND_TEXT_EXTRACTION");
        assertThat(props).includes(entry("ocrStrategy", "OCR_AND_TEXT_EXTRACTION"));
    }

    @Test
    public void test_ocr_strategy_absent_by_default() {
        Properties props = parse("stage", "run", "--stages", "SCAN,INDEX");
        assertThat(props.containsKey("ocrStrategy")).isFalse();
    }

    @Test
    public void test_max_embed_depth() {
        Properties props = parse("stage", "run", "--stages", "SCAN,INDEX", "--maxEmbedDepth", "5");
        assertThat(props).includes(entry("maxEmbedDepth", "5"));
    }

    @Test
    public void test_max_embed_depth_defaults_to_20() {
        Properties props = parse("stage", "run", "--stages", "SCAN,INDEX");
        assertThat(props).includes(entry("maxEmbedDepth", "20"));
    }

    @Test
    public void test_scroll_options() {
        Properties props = parse("stage", "run", "--stages", "ENQUEUEIDX",
                "--scroll", "30000ms", "--scrollSize", "500", "--scrollSlices", "2");
        assertThat(props).includes(entry("scroll", "30000ms"));
        assertThat(props).includes(entry("scrollSize", "500"));
        assertThat(props).includes(entry("scrollSlices", "2"));
    }

    @Test
    public void test_scroll_options_defaults() {
        Properties props = parse("stage", "run", "--stages", "SCANIDX");
        assertThat(props).includes(entry("scroll", "60000ms"));
        assertThat(props).includes(entry("scrollSize", "1000"));
        assertThat(props).includes(entry("scrollSlices", "1"));
    }

    @Test
    public void test_report_name() {
        Properties props = parse("stage", "run", "--stages", "SCAN,INDEX", "--reportName", "my-report");
        assertThat(props).includes(entry("reportName", "my-report"));
    }

    @Test
    public void test_report_name_absent_by_default() {
        Properties props = parse("stage", "run", "--stages", "SCAN,INDEX");
        assertThat(props.containsKey("reportName")).isFalse();
    }

    @Test
    public void test_max_content_length() {
        Properties props = parse("stage", "run", "--stages", "SCAN,INDEX", "--maxContentLength", "10000000");
        assertThat(props).includes(entry("maxContentLength", "10000000"));
    }

    @Test
    public void test_max_content_length_defaults_to_20000000() {
        Properties props = parse("stage", "run", "--stages", "SCAN,INDEX");
        assertThat(props).includes(entry("maxContentLength", "20000000"));
    }

    @Test
    public void test_stage_run_parse_timeout() {
        Properties props = parse("stage", "run", "--stages", "SCAN,INDEX", "--parseTimeout", "48h");
        assertThat(props).includes(entry("parseTimeout", "48h"));
    }

    @Test
    public void test_stage_run_parse_timeout_defaults_to_24h() {
        Properties props = parse("stage", "run", "--stages", "SCAN,INDEX");
        assertThat(props).includes(entry("parseTimeout", "24h"));
    }
}

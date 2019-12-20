package org.icij.datashare;

import org.icij.datashare.cli.DatashareCli;
import org.junit.Test;

import java.util.HashMap;

import static org.fest.assertions.Assertions.assertThat;

public class PipelineHelperTest {
    @Test(expected = IllegalArgumentException.class)
    public void test_get_queue_name_unknown_stage() {
        new PipelineHelper(new PropertiesProvider(new HashMap<String, String>() {{
                    put("stages", "SCAN,INDEX");}})).getQueueNameFor(DatashareCli.Stage.NLP);
    }

    @Test
    public void test_get_queue_name_scan_index() {
        PipelineHelper pipelineHelper = new PipelineHelper(new PropertiesProvider(new HashMap<String, String>() {{
            put("stages", "SCAN,INDEX");
            put("queueName", "extract:queue");
        }}));
        assertThat(pipelineHelper.getQueueNameFor(DatashareCli.Stage.SCAN)).isEqualTo("extract:queue");
        assertThat(pipelineHelper.getQueueNameFor(DatashareCli.Stage.INDEX)).isEqualTo("extract:queue");
    }

    @Test
    public void test_get_queue_name_scan_index_deduplicate() {
        PipelineHelper pipelineHelper = new PipelineHelper(new PropertiesProvider(new HashMap<String, String>() {{
            put("stages", "SCAN,DEDUPLICATE,INDEX");
            put("queueName", "extract:queue");
        }}));
        assertThat(pipelineHelper.getQueueNameFor(DatashareCli.Stage.SCAN)).isEqualTo("extract:queue");
        assertThat(pipelineHelper.getQueueNameFor(DatashareCli.Stage.DEDUPLICATE)).isEqualTo("extract:queue");
        assertThat(pipelineHelper.getQueueNameFor(DatashareCli.Stage.INDEX)).isEqualTo("extract:queue:deduplicate");
    }

    @Test
    public void test_get_queue_name_scan_deduplicate_filter_index() {
        PipelineHelper pipelineHelper = new PipelineHelper(new PropertiesProvider(new HashMap<String, String>() {{
            put("stages", "SCAN,DEDUPLICATE,FILTER,INDEX");
            put("queueName", "extract:queue");
        }}));
        assertThat(pipelineHelper.getQueueNameFor(DatashareCli.Stage.SCAN)).isEqualTo("extract:queue");
        assertThat(pipelineHelper.getQueueNameFor(DatashareCli.Stage.DEDUPLICATE)).isEqualTo("extract:queue");
        assertThat(pipelineHelper.getQueueNameFor(DatashareCli.Stage.FILTER)).isEqualTo("extract:queue:deduplicate");
        assertThat(pipelineHelper.getQueueNameFor(DatashareCli.Stage.INDEX)).isEqualTo("extract:queue:filter");
    }
}

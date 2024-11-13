package org.icij.datashare;

import org.junit.Test;

import java.util.HashMap;

import static java.util.Arrays.asList;
import static org.fest.assertions.Assertions.assertThat;

public class PipelineHelperTest {
    @Test
    public void test_default_args_for_webapp() {
        assertThat(new PipelineHelper(new PropertiesProvider()).stages).isEqualTo(asList(Stage.SCAN, Stage.INDEX, Stage.NLP));
    }

    @Test
    public void test_get_queue_name_unknown_stage() {
        assertThat(new PipelineHelper(new PropertiesProvider(new HashMap<>() {{
            put("stages", "SCAN,INDEX");
        }})).getQueueNameFor(Stage.NLP)).isEqualTo("extract:queue:nlp");
    }

    @Test
    public void test_get_queue_name_for_scanidx_output() {
        assertThat(new PipelineHelper(new PropertiesProvider(new HashMap<>() {{
            put("stages", "SCANIDX,INDEX");
        }})).getOutputQueueNameFor(Stage.SCANIDX)).isEqualTo("extract:queue:index");
    }

    @Test
    public void test_get_queue_name_for_enqueue_from_index() {
        assertThat(new PipelineHelper(new PropertiesProvider(new HashMap<>() {{
        }})).getOutputQueueNameFor(Stage.ENQUEUEIDX)).isEqualTo("extract:queue:nlp");
    }

    @Test
    public void test_get_output_queue_name_for_last_pipeline_step() {
        String name = new PipelineHelper(new PropertiesProvider(new HashMap<>() {{
            put("stages", "SCAN,INDEX");
        }})).getOutputQueueNameFor(Stage.INDEX);
        assertThat(name).isEqualTo("extract:queue:nlp");
    }

    @Test
    public void test_get_queue_names_for_batch_nlp_pipeline() {
        PipelineHelper pipelineHelper = new PipelineHelper(new PropertiesProvider(new HashMap<>() {{
            put("stages", "SCAN,INDEX,BATCHNLP");
        }}));
        assertThat(pipelineHelper.getQueueNameFor(Stage.BATCHNLP)).isEqualTo("extract:queue:batchnlp");
    }

    @Test
    public void test_get_queue_names_for_batch_nlp_pipeline_from_index() {
        PipelineHelper pipelineHelper = new PipelineHelper(new PropertiesProvider(new HashMap<>() {{
            put("stages", "CREATENLPBATCHESFROMIDX,BATCHNLP");
        }}));
        assertThat(pipelineHelper.getQueueNameFor(Stage.BATCHNLP)).isEqualTo("extract:queue:batchnlp");
    }

    @Test
    public void test_get_queue_names_for_batch_nlp() {
        PipelineHelper pipelineHelper = new PipelineHelper(new PropertiesProvider(new HashMap<>() {{
            put("stages", "SCAN,INDEX,NLP");
        }}));
        assertThat(pipelineHelper.getQueueNameFor(Stage.NLP)).isEqualTo("extract:queue:nlp");
    }

    @Test
    public void test_get_queue_names_for_nlp_pipeline_from_index() {
        PipelineHelper pipelineHelper = new PipelineHelper(new PropertiesProvider(new HashMap<>() {{
            put("stages", "ENQUEUEIDX,NLP");
        }}));
        assertThat(pipelineHelper.getQueueNameFor(Stage.NLP)).isEqualTo("extract:queue:nlp");
    }

    @Test
    public void test_get_queue_name_scan_index() {
        PipelineHelper pipelineHelper = new PipelineHelper(new PropertiesProvider(new HashMap<>() {{
            put("stages", "SCAN,INDEX");
            put("queueName", "test:queue");
        }}));
        assertThat(pipelineHelper.getQueueNameFor(Stage.SCAN)).isNull();
        assertThat(pipelineHelper.getOutputQueueNameFor(Stage.SCAN)).isEqualTo("test:queue:index");
        assertThat(pipelineHelper.getQueueNameFor(Stage.INDEX)).isEqualTo("test:queue:index");
    }

    @Test
    public void test_get_queue_name_scan_index_deduplicate() {
        PipelineHelper pipelineHelper = new PipelineHelper(new PropertiesProvider(new HashMap<>() {{
            put("stages", "SCAN,DEDUPLICATE,INDEX");
            put("queueName", "extract:queue");
        }}));
        assertThat(pipelineHelper.getOutputQueueNameFor(Stage.SCAN)).isEqualTo("extract:queue:deduplicate");
        assertThat(pipelineHelper.getQueueNameFor(Stage.DEDUPLICATE)).isEqualTo("extract:queue:deduplicate");
        assertThat(pipelineHelper.getQueueNameFor(Stage.INDEX)).isEqualTo("extract:queue:index");
    }

    @Test
    public void test_get_queue_name_when_no_stage_is_provided_like_in_web_mode() {
        assertThat(new PipelineHelper(new PropertiesProvider(new HashMap<>() )).getQueueNameFor(Stage.NLP)).isEqualTo("extract:queue:nlp");
    }
}

package org.icij.datashare.tasks;

import org.apache.tika.parser.pdf.PDFParserConfig;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.test.LogbackAppenderWrapper;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchSpewer;
import org.icij.extract.document.DocumentFactory;
import org.icij.extract.extractor.Extractor;
import org.icij.task.Option;
import org.icij.task.Options;
import org.icij.task.StringOptionParser;
import org.junit.After;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.slf4j.event.Level;

import java.util.HashMap;
import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.cli.DatashareCliOptions.PARSE_TIMEOUT_OPT;
import static org.icij.datashare.user.User.nullUser;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class IndexTaskTest {
    private final LogbackAppenderWrapper logWrapper = new LogbackAppenderWrapper();

    @Test
    public void test_options_include_ocr() throws Exception {
        ElasticsearchSpewer spewer = mock(ElasticsearchSpewer.class);
        Mockito.when(spewer.configure(Mockito.any())).thenReturn(spewer);
        IndexTask indexTask = new IndexTask(spewer, mock(DocumentCollectionFactory.class), new Task<>(IndexTask.class.getName(), nullUser(), new HashMap<>(){{
            put("queueName", "test:queue");
        }}), null);
        Options<String> options = indexTask.options();
        assertThat(options.toString()).contains("ocr=");
    }

    @Test
    public void test_options_include_ocr_language() throws Exception {
        ElasticsearchSpewer spewer = mock(ElasticsearchSpewer.class);
        Mockito.when(spewer.configure(Mockito.any())).thenReturn(spewer);
        IndexTask indexTask = new IndexTask(spewer, mock(DocumentCollectionFactory.class), new Task<>(IndexTask.class.getName(), nullUser(), new HashMap<>(){{
            put("queueName", "test:queue");
        }}), null);
        Options<String> options = indexTask.options();
        assertThat(options.toString()).contains("ocrLanguage=");
    }

    @Test
    public void test_options_include_progress_heartbeat_interval() throws Exception {
        ElasticsearchSpewer spewer = mock(ElasticsearchSpewer.class);
        Mockito.when(spewer.configure(Mockito.any())).thenReturn(spewer);
        IndexTask indexTask = new IndexTask(spewer, mock(DocumentCollectionFactory.class), new Task<>(IndexTask.class.getName(), nullUser(), new HashMap<>(){{
            put("queueName", "test:queue");
        }}), null);
        Options<String> options = indexTask.options();
        assertThat(options.toString()).contains("progressHeartbeatInterval=");
    }

    @Test
    public void test_options_include_language() throws Exception {
        ElasticsearchSpewer spewer = mock(ElasticsearchSpewer.class);
        Mockito.when(spewer.configure(Mockito.any())).thenReturn(spewer);
        IndexTask indexTask = new IndexTask(spewer, mock(DocumentCollectionFactory.class), new Task<>(IndexTask.class.getName(), nullUser(), new HashMap<>(){{
            put("language", "FRENCH");
            put("queueName", "test:queue");
        }}), null);

        Options<String> options = indexTask.options();
        assertThat(options.toString()).contains("language=");
    }
    
    @Test
    public void test_configure_called_on_spewer() throws Exception {
        ElasticsearchSpewer spewer = mock(ElasticsearchSpewer.class);
        Mockito.when(spewer.configure(Mockito.any())).thenReturn(spewer);

        new IndexTask(spewer, mock(DocumentCollectionFactory.class), new Task<>(IndexTask.class.getName(), nullUser(), Map.of("charset", "UTF-16")), null);

        ArgumentCaptor<Options> captor = ArgumentCaptor.forClass(Options.class);
        verify(spewer).configure(captor.capture());
        Option<String> option  = new Option<>("charset", StringOptionParser::new).update("UTF-16");
        assertThat(captor.getValue()).contains(option);
    }

    @Test
    public void test_configure_project_on_spewer() throws Exception {
        ElasticsearchSpewer spewer = mock(ElasticsearchSpewer.class);
        Mockito.when(spewer.configure(Mockito.any())).thenReturn(spewer);

        new IndexTask(spewer, mock(DocumentCollectionFactory.class), new Task<>(IndexTask.class.getName(), nullUser(), Map.of("defaultProject", "foo", "projectName", "bar")), null);

        ArgumentCaptor<Options> captor = ArgumentCaptor.forClass(Options.class);
        verify(spewer).configure(captor.capture());
        Option<String> defaultOpt  = new Option<>("defaultProject", StringOptionParser::new).update("foo");
        Option<String> nameOpt  = new Option<>("projectName", StringOptionParser::new).update("bar");
        assertThat(captor.getValue()).contains(defaultOpt, nameOpt);
    }

    @Test
    public void test_ocr_strategy_reaches_extractor() throws Exception {
        ElasticsearchSpewer spewer = mock(ElasticsearchSpewer.class);
        Mockito.when(spewer.configure(Mockito.any())).thenReturn(spewer);
        IndexTask indexTask = new IndexTask(spewer, mock(DocumentCollectionFactory.class),
                new Task<>(IndexTask.class.getName(), nullUser(), new HashMap<>(){{
                    put("queueName", "test:queue");
                }}), null);

        Options<String> bound = indexTask.options().createFrom(Options.from(Map.of(
                "ocrStrategy", "AUTO", "queueName", "test:queue")));
        DocumentFactory documentFactory = new DocumentFactory().configure(bound);
        Extractor extractor = new Extractor(documentFactory, bound);

        assertThat(extractor.getOcrStrategy()).isEqualTo(PDFParserConfig.OCR_STRATEGY.AUTO);
        assertThat(extractor.isExtractInlineImages()).isFalse();
    }

    @Test
    public void test_max_embed_depth_reaches_extractor() throws Exception {
        ElasticsearchSpewer spewer = mock(ElasticsearchSpewer.class);
        Mockito.when(spewer.configure(Mockito.any())).thenReturn(spewer);
        IndexTask indexTask = new IndexTask(spewer, mock(DocumentCollectionFactory.class),
                new Task<>(IndexTask.class.getName(), nullUser(), new HashMap<>(){{
                    put("queueName", "test:queue");
                }}), null);

        Options<String> bound = indexTask.options().createFrom(Options.from(Map.of(
                "maxEmbedDepth", "3", "queueName", "test:queue")));
        DocumentFactory documentFactory = new DocumentFactory().configure(bound);
        Extractor extractor = new Extractor(documentFactory, bound);

        assertThat(extractor.getMaxEmbedDepth()).isEqualTo(3);
    }

    @Test
    public void test_parse_timeout_value_reaches_extractor_options() throws Exception {
        ElasticsearchSpewer spewer = mock(ElasticsearchSpewer.class);
        Mockito.when(spewer.configure(Mockito.any())).thenReturn(spewer);

        new IndexTask(spewer, mock(DocumentCollectionFactory.class),
                new Task<>(IndexTask.class.getName(), nullUser(),
                        Map.of(PARSE_TIMEOUT_OPT, "48h", "queueName", "test:queue")), null);

        ArgumentCaptor<Options> captor = ArgumentCaptor.forClass(Options.class);
        verify(spewer).configure(captor.capture());
        Option<String> option = new Option<>(PARSE_TIMEOUT_OPT, StringOptionParser::new).update("48h");
        assertThat(captor.getValue()).contains(option);
    }

    @Test
    public void test_warns_when_parse_timeout_disabled() throws Exception {
        ElasticsearchSpewer spewer = mock(ElasticsearchSpewer.class);
        Mockito.when(spewer.configure(Mockito.any())).thenReturn(spewer);

        new IndexTask(spewer, mock(DocumentCollectionFactory.class),
                new Task<>(IndexTask.class.getName(), nullUser(),
                        Map.of(PARSE_TIMEOUT_OPT, "0", "queueName", "test:queue")), null);

        assertThat(logWrapper.logs(Level.WARN).stream()
                .anyMatch(l -> l.contains("parseTimeout") && l.contains("DISABLED"))).isTrue();
    }

    @Test
    public void test_does_not_warn_when_parse_timeout_enabled() throws Exception {
        ElasticsearchSpewer spewer = mock(ElasticsearchSpewer.class);
        Mockito.when(spewer.configure(Mockito.any())).thenReturn(spewer);

        new IndexTask(spewer, mock(DocumentCollectionFactory.class),
                new Task<>(IndexTask.class.getName(), nullUser(),
                        Map.of(PARSE_TIMEOUT_OPT, "48h", "queueName", "test:queue")), null);

        assertThat(logWrapper.logs(Level.WARN).stream()
                .anyMatch(l -> l.contains("parseTimeout"))).isFalse();
    }

    @After
    public void tearDown() throws Exception {
        logWrapper.reset();
    }
}

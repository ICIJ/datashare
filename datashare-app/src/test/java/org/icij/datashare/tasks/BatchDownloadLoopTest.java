package org.icij.datashare.tasks;

import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.rest.RestStatus;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.batch.BatchDownload;
import org.icij.datashare.user.User;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;

import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.cli.DatashareCliOptions.BATCH_DOWNLOAD_ZIP_TTL;
import static org.icij.datashare.text.Project.project;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.initMocks;

public class BatchDownloadLoopTest {
    private final BlockingQueue<BatchDownload> batchDownloadQueue = new LinkedBlockingQueue<>();
    @Mock BatchDownloadRunner batchRunner;
    @Mock TaskFactory factory;
    @Mock TaskManager manager;
    @Captor
    private ArgumentCaptor<TaskView<File>> argCaptor;

    @Test
    public void test_loop() throws Exception {
        BatchDownloadCleaner batchDownloadCleaner = mock(BatchDownloadCleaner.class);
        BatchDownloadLoop app = new BatchDownloadLoop(new PropertiesProvider(), batchDownloadQueue, factory, manager) {
            @Override
            public BatchDownloadCleaner createDownloadCleaner(Path downloadDir, int ttlHour) {
                return batchDownloadCleaner;
            }
        };
        batchDownloadQueue.add(new BatchDownload(project("prj"), User.local(), "query"));
        app.enqueuePoison();

        app.run();

        verify(batchRunner).call();
        verify(manager).save(argCaptor.capture());
        verify(batchDownloadCleaner, times(2)).run();
        assertThat(argCaptor.getValue().getState()).isEqualTo(TaskView.State.DONE);
    }

    @Test
    public void test_ttl_property() {
        BatchDownloadCleaner batchDownloadCleaner = mock(BatchDownloadCleaner.class);
        PropertiesProvider propertiesProvider = new PropertiesProvider(new HashMap<String, String>() {{
            put(BATCH_DOWNLOAD_ZIP_TTL, "15");
        }});
        BatchDownloadLoop app = new BatchDownloadLoop(propertiesProvider, batchDownloadQueue, factory, manager) {
            @Override
            public BatchDownloadCleaner createDownloadCleaner(Path downloadDir, int ttlHour) {
                assertThat(ttlHour).isEqualTo(15);
                return batchDownloadCleaner;
            }
        };
        app.enqueuePoison();

        app.run();
    }

    @Test
    public void test_elasticsearch_exception__should_not_be_serialized() throws Exception {
        when(batchRunner.call()).thenThrow(new ElasticsearchStatusException("error", RestStatus.BAD_REQUEST, new RuntimeException()));
        BatchDownloadCleaner batchDownloadCleaner = mock(BatchDownloadCleaner.class);
        BatchDownloadLoop app = new BatchDownloadLoop(new PropertiesProvider(), batchDownloadQueue, factory, manager) {
            @Override
            public BatchDownloadCleaner createDownloadCleaner(Path downloadDir, int ttlHour) {
                return batchDownloadCleaner;
            }
        };
        batchDownloadQueue.add(new BatchDownload(project("prj"), User.local(), "query"));
        app.enqueuePoison();

        app.run();

        verify(manager).save(argCaptor.capture());
        assertThat(argCaptor.getValue().getState()).isEqualTo(TaskView.State.ERROR);
        assertThat(argCaptor.getValue().error.getClass()).isNotEqualTo(ElasticsearchStatusException.class);
    }

    @Before
    public void setUp() {
        initMocks(this);
        when(factory.createDownloadRunner(any(), any())).thenReturn(batchRunner);
    }
}
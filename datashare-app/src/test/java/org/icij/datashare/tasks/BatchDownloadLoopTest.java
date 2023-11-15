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

import static java.util.Collections.singletonList;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.cli.DatashareCliOptions.*;
import static org.icij.datashare.text.Project.project;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.initMocks;

public class BatchDownloadLoopTest {
    private final BlockingQueue<BatchDownload> batchDownloadQueue = new LinkedBlockingQueue<>();
    @Mock BatchDownloadRunner batchRunner;
    @Mock TaskFactory factory;
    @Mock TaskSupplier supplier;

    @Test
    public void test_loop() throws Exception {
        BatchDownloadCleaner batchDownloadCleaner = mock(BatchDownloadCleaner.class);
        BatchDownloadLoop app = new BatchDownloadLoop(createProvider(), batchDownloadQueue, factory, supplier) {
            @Override
            public BatchDownloadCleaner createDownloadCleaner(Path downloadDir, int ttlHour) {
                return batchDownloadCleaner;
            }
        };
        batchDownloadQueue.add(new BatchDownload(singletonList(project("prj")), User.local(), "query"));
        app.enqueuePoison();

        app.run();

        verify(batchRunner).call();
        verify(supplier).result(anyString(), anyObject());
        verify(batchDownloadCleaner, times(2)).run();
    }

    @Test
    public void test_ttl_property() {
        BatchDownloadCleaner batchDownloadCleaner = mock(BatchDownloadCleaner.class);
        BatchDownloadLoop app = new BatchDownloadLoop(createProvider(), batchDownloadQueue, factory, supplier) {
            @Override
            public BatchDownloadCleaner createDownloadCleaner(Path downloadDir, int ttlHour) {
                assertThat(ttlHour).isEqualTo(DEFAULT_BATCH_DOWNLOAD_ZIP_TTL);
                return batchDownloadCleaner;
            }
        };
        app.enqueuePoison();

        app.run();
    }

    @Before
    public void setUp() {
        initMocks(this);
        when(factory.createDownloadRunner(any(), any())).thenReturn(batchRunner);
    }

    private PropertiesProvider createProvider() {
        return new PropertiesProvider(new HashMap<>() {{
            put(BATCH_DOWNLOAD_ZIP_TTL, String.valueOf(DEFAULT_BATCH_DOWNLOAD_ZIP_TTL));
            put(BATCH_DOWNLOAD_DIR, DEFAULT_BATCH_DOWNLOAD_DIR);
        }});
    }
}
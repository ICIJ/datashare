package org.icij.datashare.tasks;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.batch.BatchDownload;
import org.icij.datashare.user.User;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.io.Serializable;
import java.nio.file.Path;
import java.util.HashMap;

import static java.util.Collections.singletonList;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.cli.DatashareCliOptions.*;
import static org.icij.datashare.text.Project.project;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.initMocks;

public class BatchDownloadLoopTest {
    @Mock BatchDownloadRunner batchRunner;
    @Mock TaskFactory factory;
    @Mock TaskSupplier supplier;

    @Test(timeout = 2000)
    public void test_loop() throws Exception {
        BatchDownloadCleaner batchDownloadCleaner = mock(BatchDownloadCleaner.class);
        BatchDownloadLoop app = new BatchDownloadLoop(createProvider(), factory, supplier) {
            @Override
            public BatchDownloadCleaner createDownloadCleaner(Path downloadDir, int ttlHour) {
                return batchDownloadCleaner;
            }
        };
        BatchDownload batchDownload = new BatchDownload(singletonList(project("prj")), User.local(), "query");
        TaskView<Serializable> task = new TaskView<>(BatchDownloadRunner.class.getName(), batchDownload.user, new HashMap<>() {{
            put("batchDownload", batchDownload);
        }});
        when(supplier.get(anyInt(), any())).thenReturn(task, TaskView.nullObject());

        Integer nb = app.call();

        assertThat(nb).isEqualTo(1);
        verify(batchRunner).call();
        verify(supplier).result(eq(task.id), any());
        verify(batchDownloadCleaner, times(2)).run();
    }

    @Test(timeout = 2000)
    public void test_ttl_property() throws Exception{
        BatchDownloadCleaner batchDownloadCleaner = mock(BatchDownloadCleaner.class);
        BatchDownloadLoop app = new BatchDownloadLoop(createProvider(), factory, supplier) {
            @Override
            public BatchDownloadCleaner createDownloadCleaner(Path downloadDir, int ttlHour) {
                assertThat(ttlHour).isEqualTo(DEFAULT_BATCH_DOWNLOAD_ZIP_TTL);
                return batchDownloadCleaner;
            }
        };
        when(supplier.get(anyInt(), any())).thenReturn( TaskView.nullObject());

        assertThat(app.call()).isEqualTo(0);
    }

    @Before
    public void setUp() {
        initMocks(this);
        when(factory.createDownloadRunner(any(), any())).thenReturn(batchRunner);
    }

    private PropertiesProvider createProvider() {
        return new PropertiesProvider(new HashMap<>() {{
            put(BATCH_DOWNLOAD_ZIP_TTL_OPT, String.valueOf(DEFAULT_BATCH_DOWNLOAD_ZIP_TTL));
            put(BATCH_DOWNLOAD_DIR_OPT, DEFAULT_BATCH_DOWNLOAD_DIR);
        }});
    }
}
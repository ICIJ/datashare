package org.icij.datashare.tasks.temporal;

import io.temporal.failure.ApplicationFailure;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.extract.ScanOptions;
import org.junit.Test;
import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assert.fail;

public class ScanActivityImplTest {
    // the check runs before any Temporal call, so no interlocutor is needed to exercise a refusal
    private final ScanActivityImpl activity =
            new ScanActivityImpl(null, new PropertiesProvider(Map.of("dataDir", "/path/to/data")));

    @Test
    public void test_refuses_path_outside_data_dir() throws Exception {
        assertRefused(Paths.get("/"));
    }

    @Test
    public void test_refuses_parent_traversal() throws Exception {
        assertRefused(Paths.get("/path/to/data/../../etc"));
    }

    private void assertRefused(Path path) throws Exception {
        try {
            activity.run(ScanOptions.defaultValues(), path);
            fail("expected a non retryable ApplicationFailure");
        } catch (ApplicationFailure expected) {
            assertThat(expected.isNonRetryable()).isTrue();
            assertThat(expected.getMessage()).contains("must be inside the data directory /path/to/data");
        }
    }
}

package org.icij.datashare.tasks.temporal;

import io.temporal.failure.ApplicationFailure;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.extract.ScanOptions;
import org.junit.Test;
import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assert.assertThrows;

public class ScanActivityImplTest {
    // the check runs before any Temporal call, so no interlocutor is needed to exercise a refusal
    private final ScanActivityImpl activity =
            new ScanActivityImpl(null, new PropertiesProvider(Map.of("dataDir", "/path/to/data")));

    @Test
    public void test_refuses_path_outside_data_dir() {
        assertRefused(Paths.get("/"));
    }

    @Test
    public void test_refuses_parent_traversal() {
        assertRefused(Paths.get("/path/to/data/../../etc"));
    }

    private void assertRefused(Path path) {
        ApplicationFailure refusal = assertThrows(ApplicationFailure.class,
                () -> activity.run(ScanOptions.defaultValues(), path));
        assertThat(refusal.isNonRetryable()).isTrue();
        assertThat(refusal.getMessage()).contains("must be inside the data directory /path/to/data");
    }
}

package org.icij.datashare.tasks.temporal;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import org.icij.datashare.extract.ScanOptions;
import java.io.IOException;
import java.nio.file.Path;
import org.icij.datashare.tasks.ArtifactOptions;
import org.icij.datashare.text.indexing.elasticsearch.IndexOptions;

@WorkflowInterface
public interface IndexWorkflow {
    @WorkflowMethod(name = "IndexWorkflow")
    Long run(final ScanOptions scanOptions, final Path path, final String index, final IndexOptions indexOptions,
             final ArtifactOptions artifactOptions) throws IOException;

}

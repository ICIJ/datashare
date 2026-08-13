package org.icij.datashare.tasks.temporal;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import org.icij.datashare.extract.ScanOptions;
import org.icij.datashare.text.indexing.elasticsearch.IndexOptions;
import java.io.IOException;
import java.nio.file.Path;

@WorkflowInterface
public interface IndexationWorkflow {
    @WorkflowMethod(name = "IndexWorkflow")
    Long run(final ScanOptions scanOptions, final Path path, final String index, final IndexOptions indexOptions) throws
            IOException;

}

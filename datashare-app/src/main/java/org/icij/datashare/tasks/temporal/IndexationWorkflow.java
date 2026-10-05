package org.icij.datashare.tasks.temporal;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.extract.ScanOptions;
import java.io.IOException;
import java.nio.file.Path;

@WorkflowInterface
public interface IndexationWorkflow {
    @WorkflowMethod(name = "IndexWorkflow")
    Long run(final ScanOptions scanOptions, final Path path, final String index,
             final PropertiesProvider propertiesProvider) throws IOException;

}

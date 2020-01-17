package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.cli.DatashareCli;
import org.icij.datashare.user.User;
import org.icij.extract.report.ReportMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * filters the document queue with a set
 */
public class FilterTask extends PipelineTask {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final ReportMap reportMap;

    @Inject
    public FilterTask(final DocumentCollectionFactory factory, final PropertiesProvider propertiesProvider, @Assisted User user, @Assisted String queueName) {
        super(DatashareCli.Stage.FILTER, user, queueName, factory, propertiesProvider);
        this.reportMap = factory.createMap(propertiesProvider, propertiesProvider.get("reportName").orElseThrow(() -> new IllegalArgumentException("no map property defined")));
    }

    @Override
    public Long call() throws Exception {
        long extracted  = transferToOutputQueue(p -> !reportMap.containsKey(p));
        logger.info("filtered {} paths from queue {} to {}", extracted, queue.getName(), getOutputQueueName());
        queue.close();
        reportMap.close();
        return extracted;
    }
}

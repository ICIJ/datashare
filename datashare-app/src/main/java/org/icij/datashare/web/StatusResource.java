package org.icij.datashare.web;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import net.codestory.http.Context;
import net.codestory.http.annotations.Get;
import net.codestory.http.annotations.Prefix;
import net.codestory.http.payload.Payload;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Repository;
import org.icij.datashare.com.DataBus;
import org.icij.datashare.openmetrics.StatusMapper;
import org.icij.datashare.tasks.DocumentCollectionFactory;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.extract.queue.DocumentQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
@Prefix("/api")
public class StatusResource {
    Logger logger = LoggerFactory.getLogger(getClass());
    private PropertiesProvider propertiesProvider;
    private final Repository repository;
    private final Indexer indexer;
    private final DataBus dataBus;
    private DocumentQueue queue;

    @Inject
    public StatusResource(PropertiesProvider propertiesProvider, Repository repository, Indexer indexer, DataBus dataBus, DocumentCollectionFactory documentCollectionFactory) {
        this.propertiesProvider = propertiesProvider;
        this.repository = repository;
        this.indexer = indexer;
        this.dataBus = dataBus;
        this.queue = documentCollectionFactory.createQueue(propertiesProvider, propertiesProvider.get(PropertiesProvider.QUEUE_NAME_OPTION).orElse("extract:queue"));
    }

    @Operation(description = "Retrieve the status of databus connection, database connection, shared queues and index.",
            parameters = { @Parameter(name = "format=openmetrics", description = "if provided in the URL it will return the status in openmetrics format", in = ParameterIn.QUERY) })
    @ApiResponse(responseCode = "200", description = "returns the status of datashare elements", useReturnTypeSchema = true)
    @Get("/status")
    public Payload getStatus(Context context) {
        boolean queueStatus = false;
        int queueSize = 0;
        try{
            queueSize = queue.size();
            queueStatus = true;
        } catch (RuntimeException ex){
            logger.error("Queue Health Error : ",ex);
        }
        Status status = new Status(repository.getHealth(), indexer.getHealth(), dataBus.getHealth(), queueStatus, queueSize);
        if ("openmetrics".equals(context.request().query().get("format"))) {
            return new Payload("text/plain;version=0.0.4",
                    new StatusMapper("datashare", status, propertiesProvider.get("platform").orElse(null)).toString());
        } else {
            return new Payload(status);
        }
    }

    public static class Status {
        public final boolean database;
        public final boolean index;
        public final boolean databus;
        public final boolean document_queue_status;
        public final int document_queue_size;

        Status(boolean database, boolean index, boolean databus, boolean queue, int queueSize) {
            this.database = database;
            this.index = index;
            this.databus = databus;
            this.document_queue_status = queue;
            this.document_queue_size = queueSize;
        }
    }
}



package org.icij.datashare.web;

import com.google.inject.Inject;
import com.google.inject.ProvisionException;
import net.codestory.http.Context;
import net.codestory.http.annotations.Get;
import net.codestory.http.annotations.Prefix;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Repository;
import org.icij.datashare.com.DataBus;
import org.icij.datashare.tasks.DocumentCollectionFactory;
import org.icij.datashare.text.indexing.Indexer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.BlockingQueue;

@Prefix("/api/status")
public class StatusResource {
    Logger logger = LoggerFactory.getLogger(getClass());
    private PropertiesProvider propertiesProvider;
    private final Repository repository;
    private final Indexer indexer;
    private final DataBus dataBus;
    private DocumentCollectionFactory documentCollectionFactory;


    @Inject
    public StatusResource(PropertiesProvider propertiesProvider, Repository repository, Indexer indexer, DataBus dataBus, DocumentCollectionFactory documentCollectionFactory) {
        this.propertiesProvider = propertiesProvider;
        this.repository = repository;
        this.indexer = indexer;
        this.dataBus = dataBus;
        this.documentCollectionFactory = documentCollectionFactory;
    }

    /**
     *
     *
     * @return
     */
    @Get()
    public Status getStatus(Context context) {
        boolean queueStatus = false;
        try{
            queueStatus = documentCollectionFactory.createQueue(propertiesProvider, "health:queue").size() >= 0;
        } catch (RuntimeException ex){
            logger.error("Queue Health Error : ",ex);
        }
        return new Status(
                repository.getHealth(),indexer.getHealth(), dataBus.getHealth(), queueStatus
        );
    }

    static class Status {
        final boolean database;
        final boolean index;
        final boolean databus;
        final boolean queue;

        Status(boolean database, boolean index, boolean databus, boolean queue) {
            this.database = database;
            this.index = index;
            this.databus = databus;
            this.queue = queue;
        }
    }
}



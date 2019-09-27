package org.icij.datashare.web;

import net.codestory.http.Context;
import net.codestory.http.filters.Filter;
import net.codestory.http.filters.PayloadSupplier;
import net.codestory.http.payload.Payload;
import org.elasticsearch.client.RestHighLevelClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Warning the waiter could block the JVM if it has not pinged ES
 */
public class IndexWaiterFilter implements Filter {
    private Logger LOGGER = LoggerFactory.getLogger(getClass());
    private static int TIMEOUT_SECONDS = 45;
    private static final String WAIT_CONTENT = "<!DOCTYPE html>" +
            "<head><meta HTTP-EQUIV=\"refresh\" CONTENT=\"2\"><title>Datashare</title></head>" +
            "<body>waiting for Datashare to be up...</body>";
    private final RestHighLevelClient client;
    private final AtomicBoolean indexOk = new AtomicBoolean(false);
    final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Inject
    public IndexWaiterFilter(final RestHighLevelClient client) {
        this.client = client;
        waitForIndexAsync();
    }

    @Override
    public Payload apply(String s, Context context, PayloadSupplier payloadSupplier) throws Exception {
        if (!indexOk.get()) {
            return new Payload("text/html", WAIT_CONTENT);
        }
        return payloadSupplier.get();
    }

    synchronized IndexWaiterFilter waitForIndexAsync() {
        executor.submit(() -> {
            for (int i = 0; i < TIMEOUT_SECONDS; i++) {
                try {
                    if (client.ping()) {
                        this.indexOk.set(true);
                        LOGGER.info("Ping elasticsearch succeeded");
                        break;
                    }
                } catch (IOException|RuntimeException e) {
                    LOGGER.info("Ping failed. Waiting for Elasticsearch to be up " + e);
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        LOGGER.warn("interrupted while waiting for elasticsearch", ie);
                    }
                }
            }
        });
        return this;
    }

    @Override
    public boolean matches(String uri, Context context) { return true;}
}

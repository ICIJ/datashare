package org.icij.datashare.com;

import org.apache.qpid.server.SystemLauncher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class QpidDatabusEmbedded {
    private static final String INITIAL_CONFIGURATION = "qpid-config.json";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Logger logger = LoggerFactory.getLogger(getClass());

    public void start() throws Exception {
        final SystemLauncher systemLauncher = new SystemLauncher();
        try {
            executor.execute(() -> {
                try {
                    systemLauncher.startup(createSystemConfig());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            // performMessagingOperations();
        } finally {
            systemLauncher.shutdown();
        }
    }

    public void stop() throws InterruptedException {
        executor.shutdown();
        executor.awaitTermination(3, TimeUnit.SECONDS);
    }

    private Map<String, Object> createSystemConfig() {
        Map<String, Object> attributes = new HashMap<>();
        URL initialConfig = QpidDatabusEmbedded.class.getClassLoader().getResource(INITIAL_CONFIGURATION);
        logger.info("initial config : {}", initialConfig.toExternalForm());
        attributes.put("type", "Memory");
        attributes.put("initialConfigurationLocation", initialConfig.toExternalForm());
        attributes.put("startupLoggedToSystemOut", true);
        return attributes;
    }

    public static void main(String[] args) throws Exception {
        QpidDatabusEmbedded server = new QpidDatabusEmbedded();
        server.start();
    }
}
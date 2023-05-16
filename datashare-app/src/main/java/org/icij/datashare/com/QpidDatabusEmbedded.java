package org.icij.datashare.com;

import org.apache.qpid.server.SystemLauncher;
import org.apache.qpid.server.store.MemorySystemConfigImplFactory;

import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class QpidDatabusEmbedded {
    private static final String INITIAL_CONFIGURATION = "qpid-config.json";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public void start() throws Exception {
        final SystemLauncher systemLauncher = new SystemLauncher();
        try {
            executor.execute(() -> {
                try {
                    ServiceLoader.load(MemorySystemConfigImplFactory.class);
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

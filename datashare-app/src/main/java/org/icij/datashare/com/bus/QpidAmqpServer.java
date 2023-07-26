package org.icij.datashare.com.bus;

import org.apache.qpid.server.SystemLauncher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class QpidAmqpServer {
    private static final String INITIAL_CONFIGURATION = "qpid-config.json";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Logger logger = LoggerFactory.getLogger(getClass());

    public QpidAmqpServer(int port) {
        System.setProperty("qpid.amqp_port", String.valueOf(port));
    }

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
        } finally {
            systemLauncher.shutdown();
        }
    }

    public boolean stop() {
        executor.shutdown();
        try {
            return executor.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private Map<String, Object> createSystemConfig() {
        Map<String, Object> attributes = new HashMap<>();
        URL initialConfig = QpidAmqpServer.class.getClassLoader().getResource(INITIAL_CONFIGURATION);
        logger.info("initial config : {}", initialConfig.toExternalForm());
        attributes.put("type", "Memory");
        attributes.put("initialConfigurationLocation", initialConfig.toExternalForm());
        attributes.put("startupLoggedToSystemOut", true);
        return attributes;
    }

    public static void main(String[] args) throws Exception {
        QpidAmqpServer server = new QpidAmqpServer(5672);
        server.start();
    }
}
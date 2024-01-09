package org.icij.datashare.com.bus.amqp;

import org.apache.qpid.server.SystemLauncher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * AMQP memory server for embedded mode and tests.
 */
public class QpidAmqpServer {
    private static final String INITIAL_CONFIGURATION = "qpid-config.json";
    final SystemLauncher systemLauncher = new SystemLauncher();
    private final Logger logger = LoggerFactory.getLogger(getClass());

    public QpidAmqpServer(int port) {
        System.setProperty("qpid.amqp_port", String.valueOf(port));
    }

    public void start() {
        try {
            systemLauncher.startup(createSystemConfig());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public boolean shutdown() {
        systemLauncher.shutdown();
        return true;
    }

    private Map<String, Object> createSystemConfig() {
        Map<String, Object> attributes = new HashMap<>();
        URL initialConfig = QpidAmqpServer.class.getClassLoader().getResource(INITIAL_CONFIGURATION);
        logger.info("initial config : {}", Objects.requireNonNull(initialConfig," initialConfig cannot be null").toExternalForm());
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
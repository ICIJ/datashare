package org.icij.datashare.asynctasks.bus.amqp;

import org.apache.qpid.server.SystemLauncher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * AMQP memory server for embedded mode and tests.
 */
public class QpidAmqpServer implements Closeable {
    private static final String INITIAL_CONFIGURATION = "qpid-config.json";
    final SystemLauncher systemLauncher = new SystemLauncher();
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Map<String, Object> config;

    public QpidAmqpServer(int port) {
        this(port, INITIAL_CONFIGURATION);
    }

    public QpidAmqpServer(int port, String qpidConfigurationFileName) {
        System.setProperty("qpid.amqp_port", String.valueOf(port));
        config = createSystemConfig(qpidConfigurationFileName);
    }

    public QpidAmqpServer start() {
        try {
            systemLauncher.startup(config);
            return this;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public boolean shutdown() {
        systemLauncher.shutdown();
        return true;
    }

    private Map<String, Object> createSystemConfig(final String configFileName) {
        Map<String, Object> attributes = new HashMap<>();
        URL initialConfig = QpidAmqpServer.class.getClassLoader().getResource(configFileName);
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

    @Override
    public void close() throws IOException {
        shutdown();
    }
}
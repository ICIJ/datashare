package org.icij.datashare.text.indexing.elasticsearch;

import org.opensearch.Version;
import org.opensearch.analysis.common.CommonAnalysisModulePlugin;
import org.opensearch.common.settings.Settings;
import org.opensearch.index.reindex.ReindexModulePlugin;
import org.opensearch.join.ParentJoinModulePlugin;
import org.opensearch.node.InternalSettingsPreparer;
import org.opensearch.node.Node;
import org.opensearch.painless.PainlessModulePlugin;
import org.opensearch.plugins.Plugin;
import org.opensearch.plugins.PluginInfo;
import org.opensearch.transport.Netty4ModulePlugin;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;

import static java.util.Arrays.asList;
import static java.util.Optional.ofNullable;

/**
 * inspired by :
 * https://github.com/elastic/elasticsearch-hadoop/blob/fefcf8b191d287aca93a04144c67b803c6c81db5/mr/src/itest/java/org/elasticsearch/hadoop/EsEmbeddedServer.java
 */
public class EsEmbeddedServer implements Closeable {
    private final Node node;

    public EsEmbeddedServer(String clusterName, String homePath, String dataPath, String httpPort) {
        this(clusterName, homePath, dataPath, httpPort, "9300");
    }

    public EsEmbeddedServer(String clusterName, String homePath, String dataPath, String httpPort, String transportPort) {
        Settings settings = Settings.builder()
                .put("transport.type", "netty4")
                .put("http.type", "netty4")
                .put("indices.query.bool.max_clause_count", "16384")
                .put("cluster.routing.allocation.disk.watermark.low", "90%")
                .put("cluster.routing.allocation.disk.watermark.high", "99%")
                .put("cluster.routing.allocation.disk.watermark.flood_stage", "100%")
                .put("path.home", homePath)
                .put("path.data", dataPath)
                .put("http.port", httpPort)
                .put("transport.port", transportPort)
                .put("cluster.name", clusterName).build();
        try {
            node = createNode(settings);
        } catch (IllegalArgumentException iae) {
            if (iae.getMessage() != null && iae.getMessage().contains("Could not load codec")) {
                LoggerFactory.getLogger(getClass()).error("Your index version on disk ({}) doesn't seem to have the same " +
                        "version as the embedded Elasticsearch engine ({}). Please migrate it with snapshots, " +
                        "or remove it then restart datashare.", dataPath, Version.CURRENT);
            }
            throw iae;
        }
    }

    public EsEmbeddedServer start() {
        try {
            node.start();
            return this;
        } catch (Exception e) {
            throw new RuntimeException("Encountered exception during embedded node startup", e);
        }
    }

    PluginConfigurableNode createNode(Settings settings) {
        return new PluginConfigurableNode(settings, asList(
                Netty4ModulePlugin.class,
                ParentJoinModulePlugin.class,
                CommonAnalysisModulePlugin.class,
                PainlessModulePlugin.class,
                ReindexModulePlugin.class
        ));
    }

    @Override
    public void close() throws IOException {
        node.close();
    }

    public boolean isClosed() {
        return node.isClosed();
    }

    static class PluginConfigurableNode extends Node {
        public PluginConfigurableNode(Settings settings, Collection<Class<? extends Plugin>> classpathPlugins) {
            super(InternalSettingsPreparer.prepareEnvironment(settings, new HashMap<>(), null, () -> "datashare"),
                    classpathPlugins.stream()
                            .map(
                                    p -> new PluginInfo(
                                            p.getName(),
                                            "classpath plugin",
                                            "NA",
                                            Version.CURRENT,
                                            "1.8",
                                            p.getName(),
                                            null,
                                            Collections.emptyList(),
                                            false
                                    )
                            )
                            .toList(), true);
        }
    }

    public static void main(String[] args) throws InterruptedException, IOException {
        int nbIterations = -1;
        if (args.length > 1) {
            nbIterations = Integer.parseInt(args[1]);
        }
        EsEmbeddedServer server = new EsEmbeddedServer("datashare",
                System.getenv("DS_ELASTICSEARCH_HOME_PATH"),
                System.getenv("DS_ELASTICSEARCH_DATA_PATH"),
                ofNullable(System.getenv("DS_ELASTICSEARCH_HTTP_PORT")).orElse("9200"),
                ofNullable(System.getenv("DS_ELASTICSEARCH_TRANSPORT_PORT")).orElse("9300"));
        server.start();
        while (!server.isClosed() && nbIterations-- != 0) {
            Thread.sleep(1000);
        }
        server.close();
    }
}

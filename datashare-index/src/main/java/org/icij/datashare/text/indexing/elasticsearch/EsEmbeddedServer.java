package org.icij.datashare.text.indexing.elasticsearch;

import org.elasticsearch.analysis.common.CommonAnalysisPlugin;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.join.ParentJoinPlugin;
import org.elasticsearch.node.InternalSettingsPreparer;
import org.elasticsearch.node.Node;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.transport.Netty4Plugin;

import java.io.IOException;
import java.util.Collection;

import static java.util.Arrays.asList;
/**
 * inspired by :
 * https://github.com/elastic/elasticsearch-hadoop/blob/fefcf8b191d287aca93a04144c67b803c6c81db5/mr/src/itest/java/org/elasticsearch/hadoop/EsEmbeddedServer.java
 */
public class EsEmbeddedServer {
    private final Node node;

    public EsEmbeddedServer(String clusterName, String homePath, String dataPath, String httpPort) {
        Settings settings = Settings.builder()
                .put("transport.type", "netty4")
                .put("http.type", "netty4")
                .put("path.home", homePath)
                .put("path.data", dataPath)
                .put("http.port", httpPort)
                //.put("script.inline", "true")
                .put("cluster.name", clusterName).build();

        node = new PluginConfigurableNode(settings, asList(Netty4Plugin.class, ParentJoinPlugin.class, CommonAnalysisPlugin.class));
    }

    public void start() {
        try {
            node.start();
        } catch (Exception e) {
            throw new RuntimeException("Encountered exception during embedded node startup", e);
        }
    }

    public void stop() throws IOException {
        node.close();
    }

    private static class PluginConfigurableNode extends Node {
        public PluginConfigurableNode(Settings settings, Collection<Class<? extends Plugin>> classpathPlugins) {
            super(InternalSettingsPreparer.prepareEnvironment(settings, null), classpathPlugins, true);
        }
        @Override
        protected void registerDerivedNodeNameWithLogger(String s) {}
    }
}

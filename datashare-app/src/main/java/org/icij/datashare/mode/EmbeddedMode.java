package org.icij.datashare.mode;

import org.icij.datashare.text.indexing.elasticsearch.EsEmbeddedServer;

import java.util.Map;
import java.util.Properties;

public class EmbeddedMode extends LocalMode {
    EmbeddedMode(Properties properties) { super(properties);}
    public EmbeddedMode(Map<String, String> properties) { super(properties);}

    @Override
    protected void configure() {
        super.configure();
        new EsEmbeddedServer("datashare", "/home/dev/es", "/home/dev/es", "9200").start();
    }
}

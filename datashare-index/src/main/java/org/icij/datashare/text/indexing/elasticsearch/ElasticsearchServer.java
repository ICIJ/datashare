package org.icij.datashare.text.indexing.elasticsearch;

import pl.allegro.tech.embeddedelasticsearch.EmbeddedElastic;
import pl.allegro.tech.embeddedelasticsearch.IndexSettings;
import pl.allegro.tech.embeddedelasticsearch.PopularProperties;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static java.lang.ClassLoader.getSystemResourceAsStream;

public class ElasticsearchServer {
    private final ExecutorService threadPool = Executors.newSingleThreadExecutor();

    public void start() throws IOException {
        EmbeddedElastic embeddedElastic = EmbeddedElastic.builder()
                .withElasticVersion("6.3.0")
                .withSetting(PopularProperties.HTTP_PORT, 9201)
                .withSetting(PopularProperties.CLUSTER_NAME, "datashare")
                .withIndex("local-datashare", IndexSettings.builder()
                        .withType("doc", getSystemResourceAsStream("datashare_index_mappings.json"))
                        .withSettings(getSystemResourceAsStream("datashare_index_settings.json"))
                        .build())
                .build();
        threadPool.submit(embeddedElastic::start);
    }
}

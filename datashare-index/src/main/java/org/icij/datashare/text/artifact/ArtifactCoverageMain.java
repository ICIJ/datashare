package org.icij.datashare.text.artifact;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.cli.DatashareCliOptions;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchConfiguration;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.icij.datashare.text.indexing.elasticsearch.SourceExtractor;

import java.nio.file.Path;
import java.util.Map;

/** Standalone audit runner: checks artifact coverage for a real project's ES index +
 *  artifact directory, outside any test. Deliberately not wired into the JOpt/picocli
 *  CLI surfaces; run it directly with `exec:java`, see the class brief for the command. */
public class ArtifactCoverageMain {
    private static final int SCROLL_SIZE = 100;

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("usage: ArtifactCoverageMain <elasticsearchUrl> <indexName> <artifactDir>");
            System.exit(2);
            return;
        }
        String elasticsearchUrl = args[0];
        String indexName = args[1];
        String artifactDir = args[2];

        PropertiesProvider props = new PropertiesProvider(Map.of(
                ElasticsearchConfiguration.INDEX_ADDRESS_PROP, elasticsearchUrl,
                DatashareCliOptions.ARTIFACT_DIR_OPT, artifactDir,
                PropertiesProvider.DEFAULT_PROJECT_OPT, indexName));

        ElasticsearchClient client = ElasticsearchConfiguration.createESClient(props);
        ElasticsearchIndexer indexer = new ElasticsearchIndexer(client, props);
        SourceExtractor extractor = new SourceExtractor(props);

        ArtifactCoverageChecker.Report report = new ArtifactCoverageChecker(indexer, extractor)
                .check(Project.project(indexName), Path.of(artifactDir), SCROLL_SIZE);

        System.out.println(report.summary());
        System.exit(report.complete() ? 0 : 1);
    }
}

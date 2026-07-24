package org.icij.datashare.text.indexing.elasticsearch;

import org.icij.datashare.Entity;
import org.icij.datashare.HumanReadableSize;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.text.Hasher;
import static java.lang.String.valueOf;
import static org.icij.datashare.PropertiesProvider.DEFAULT_PROJECT_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_DEFAULT_PROJECT;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_INDEX_TIMEOUT;
import static org.icij.datashare.cli.DatashareCliOptions.INDEX_TIMEOUT_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PARALLELISM_OPT;

public record IndexOptions(int parallelThreads, int indexTimeout, int maxContentLength, String defaultIndexName, Hasher digestAlgorithm) {
    public static IndexOptions fromPropertiesProvider(PropertiesProvider propertiesProvider) {
        int parallelism = propertiesProvider.get(PARALLELISM_OPT).map(Integer::parseInt)
                                            .orElse(Runtime.getRuntime().availableProcessors());
        int indexTimeout =
                Integer.parseInt(propertiesProvider.get(INDEX_TIMEOUT_OPT).orElse(valueOf(DEFAULT_INDEX_TIMEOUT)));
        String defaultIndexName = propertiesProvider.get(DEFAULT_PROJECT_OPT).orElse(DEFAULT_DEFAULT_PROJECT);
        int maxContentLength = getMaxContentLength(propertiesProvider);
        Hasher digestAlgorithm = getDigestAlgorithm(propertiesProvider);

        return new IndexOptions(parallelism, indexTimeout, maxContentLength, defaultIndexName, digestAlgorithm);
    }

    private static int getMaxContentLength(PropertiesProvider propertiesProvider) {
        return (int) Math.min(HumanReadableSize.parse(propertiesProvider.get("maxContentLength").orElse("-1")),
                              Integer.MAX_VALUE);
    }

    private static Hasher getDigestAlgorithm(PropertiesProvider propertiesProvider) {
        return Hasher.parse(propertiesProvider.get("digestAlgorithm").orElse(Entity.DEFAULT_DIGESTER.name()))
                     .orElse(Entity.DEFAULT_DIGESTER);
    }
}

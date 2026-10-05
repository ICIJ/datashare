package org.icij.datashare.text.indexing.elasticsearch;

import org.icij.datashare.Entity;
import org.icij.datashare.HumanReadableSize;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.text.Hasher;
import org.icij.task.Options;
import java.util.HashMap;
import java.util.Map;
import static java.lang.String.valueOf;
import static org.icij.datashare.PropertiesProvider.DEFAULT_PROJECT_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_DEFAULT_PROJECT;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_INDEX_TIMEOUT;
import static org.icij.datashare.cli.DatashareCliOptions.INDEX_TIMEOUT_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.OCR_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.OCR_STRATEGY_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PARALLELISM_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PARSE_TIMEOUT_OPT;
import org.icij.task.annotation.Option;

/**
 * Options for indexation
 * @param parallelThreads
 * @param indexTimeout
 * @param maxContentLength
 * @param defaultIndexName
 * @param digestAlgorithm
 * @param idMethod
 * @param charset
 * @param language
 * @param digestProjectName
 * @param outputFormat
 * @param embedHandling
 * @param embedOutput
 * @param ocrCache
 * @param ocrLanguage
 * @param ocrStrategy
 * @param ocrTimeout
 * @param parseTimeout
 * @param ocr
 * @param ocrType
 * @param embedMemoryBudgetMb
 * @param embedMemoryPressureThreshold
 * @param ocrParallelism
 * @param ocrFanout
 * @param ocrMinImageBytes
 * @param progressHeartbeatInterval
 * @param streamingSpew
 * @param spewQueueCapacity
 * @param pstFolderFanout
 * @param pstParseParallelism
 * @param legacyUntitledNaming
 * @param maxEmbedDepth
 * @param maxEmbedSizeBytes
 */
public record IndexOptions(int parallelThreads, int indexTimeout, int maxContentLength, String defaultIndexName, Hasher digestAlgorithm, String idMethod, String charset, String language, String digestProjectName, String outputFormat, String embedHandling, String embedOutput, String ocrCache, String ocrLanguage, String ocrStrategy, String ocrTimeout, String parseTimeout, Boolean ocr, String ocrType, Integer embedMemoryBudgetMb, Double embedMemoryPressureThreshold, Integer ocrParallelism, Boolean ocrFanout, Long ocrMinImageBytes, String progressHeartbeatInterval, Boolean streamingSpew, Integer spewQueueCapacity, Boolean pstFolderFanout, Integer pstParseParallelism, Boolean legacyUntitledNaming, Integer maxEmbedDepth, Long maxEmbedSizeBytes) {
    public static IndexOptions fromPropertiesProvider(PropertiesProvider p) {
        int parallelism =
                p.get(PARALLELISM_OPT).map(Integer::parseInt).orElse(Runtime.getRuntime().availableProcessors());
        int indexTimeout = Integer.parseInt(p.get(INDEX_TIMEOUT_OPT).orElse(valueOf(DEFAULT_INDEX_TIMEOUT)));
        String defaultIndexName = p.get(DEFAULT_PROJECT_OPT).orElse(DEFAULT_DEFAULT_PROJECT);
        int maxContentLength = getMaxContentLength(p);
        Hasher digestAlgorithm = getDigestAlgorithm(p);

        return new IndexOptions(parallelism, indexTimeout, maxContentLength, defaultIndexName, digestAlgorithm,
                                p.get("idMethod").orElse(null), p.get("charset").orElse(null),
                                p.get("language").orElse(null), p.get("digestProjectName").orElse(null),
                                p.get("outputFormat").orElse(null), p.get("embedHandling").orElse(null),
                                p.get("embedOutput").orElse(null), p.get("ocrCache").orElse(null),
                                p.get("ocrLanguage").orElse(null), p.get(OCR_STRATEGY_OPT).orElse(null),
                                p.get("ocrTimeout").orElse(null), p.get(PARSE_TIMEOUT_OPT).orElse(null),
                                p.get(OCR_OPT).map(Boolean::parseBoolean).orElse(null), p.get("ocrType").orElse(null),
                                p.get("embedMemoryBudgetMb").map(Integer::parseInt).orElse(null),
                                p.get("embedMemoryPressureThreshold").map(Double::parseDouble).orElse(null),
                                p.get("ocrParallelism").map(Integer::parseInt).orElse(null),
                                p.get("ocrFanout").map(Boolean::parseBoolean).orElse(null),
                                p.get("ocrMinImageBytes").map(Long::parseLong).orElse(null),
                                p.get("progressHeartbeatInterval").orElse(null),
                                p.get("streamingSpew").map(Boolean::parseBoolean).orElse(null),
                                p.get("spewQueueCapacity").map(Integer::parseInt).orElse(null),
                                p.get("pstFolderFanout").map(Boolean::parseBoolean).orElse(null),
                                p.get("pstParseParallelism").map(Integer::parseInt).orElse(null),
                                p.get("legacyUntitledNaming").map(Boolean::parseBoolean).orElse(null),
                                p.get("maxEmbedDepth").map(Integer::parseInt).orElse(null),
                                p.get("maxEmbedSizeBytes").map(Long::parseLong).orElse(null));
    }

    /** The {@link Options} {@code org.icij.extract.document.DocumentFactory#configure(Options)} needs. */
    public Options<String> documentFactoryOptions() {
        Map<String, Object> options = new HashMap<>();
        putIfPresent(options, "idMethod", idMethod);
        putIfPresent(options, "digestAlgorithm", digestAlgorithm);
        putIfPresent(options, "charset", charset);
        putIfPresent(options, "language", language);
        return Options.from(options);
    }

    /** The {@link Options} the {@code org.icij.extract.extractor.Extractor} constructor needs. */
    public Options<String> extractorOptions() {
        Map<String, Object> options = new HashMap<>();
        putIfPresent(options, "digestAlgorithm", digestAlgorithm);
        putIfPresent(options, "digestProjectName", digestProjectName);
        putIfPresent(options, "outputFormat", outputFormat);
        putIfPresent(options, "embedHandling", embedHandling);
        putIfPresent(options, "embedOutput", embedOutput);
        putIfPresent(options, "ocrCache", ocrCache);
        putIfPresent(options, "ocrLanguage", ocrLanguage);
        putIfPresent(options, "ocrStrategy", ocrStrategy);
        putIfPresent(options, "ocrTimeout", ocrTimeout);
        putIfPresent(options, "parseTimeout", parseTimeout);
        putIfPresent(options, "ocr", ocr);
        putIfPresent(options, "ocrType", ocrType);
        putIfPresent(options, "embedMemoryBudgetMb", embedMemoryBudgetMb);
        putIfPresent(options, "embedMemoryPressureThreshold", embedMemoryPressureThreshold);
        putIfPresent(options, "ocrParallelism", ocrParallelism);
        putIfPresent(options, "ocrFanout", ocrFanout);
        putIfPresent(options, "ocrMinImageBytes", ocrMinImageBytes);
        putIfPresent(options, "progressHeartbeatInterval", progressHeartbeatInterval);
        putIfPresent(options, "streamingSpew", streamingSpew);
        putIfPresent(options, "spewQueueCapacity", spewQueueCapacity);
        putIfPresent(options, "pstFolderFanout", pstFolderFanout);
        putIfPresent(options, "pstParseParallelism", pstParseParallelism);
        putIfPresent(options, "legacyUntitledNaming", legacyUntitledNaming);
        putIfPresent(options, "maxEmbedDepth", maxEmbedDepth);
        putIfPresent(options, "maxEmbedSizeBytes", maxEmbedSizeBytes);
        return Options.from(options);
    }

    private static void putIfPresent(Map<String, Object> options, String key, Object value) {
        if (value != null) {
            options.put(key, value.toString());
        }
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

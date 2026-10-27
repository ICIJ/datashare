package org.icij.datashare.text.indexing.elasticsearch;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.icij.datashare.HumanReadableSize;
import org.icij.datashare.PropertiesProvider;
import org.icij.extract.document.DocumentFactoryOptions;
import org.icij.extract.extractor.ExtractorOptions;
import static java.util.Optional.ofNullable;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_DEFAULT_PROJECT;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_INDEX_TIMEOUT;

/**
 * Options for indexation. The scalar components are named after their property keys, so
 * {@link PropertiesProvider#toRecord} fills them; {@link #of} adds the two nested records it cannot build.
 * @param parallelism number of files extracted in parallel, the number of processors when absent
 * @param indexTimeout minutes to wait for the consumer to terminate
 * @param maxContentLength max indexed characters of a document content, -1 for the default limit
 * @param defaultProject name of the index documents go to
 * @param languageDetection options of the language detection
 * @param documentFactory options of the DocumentFactory
 * @param extractor options of the Extractor
 */
public record IndexOptions(int parallelism, int indexTimeout, int maxContentLength, String defaultProject,
                           LanguageOptions languageDetection, DocumentFactoryOptions documentFactory,
                           ExtractorOptions extractor) {
    public IndexOptions {
        parallelism = parallelism == 0 ? Runtime.getRuntime().availableProcessors() : parallelism;
        indexTimeout = indexTimeout == 0 ? DEFAULT_INDEX_TIMEOUT : indexTimeout;
        defaultProject = ofNullable(defaultProject).orElse(DEFAULT_DEFAULT_PROJECT);
        languageDetection = ofNullable(languageDetection).orElseGet(() -> new LanguageOptions(null, null));
    }

    /** Accepts both forms the record meets: the "10M"-style string from the properties, and the number it serializes to. */
    @JsonCreator
    public IndexOptions(@JsonProperty("parallelism") int parallelism, @JsonProperty("indexTimeout") int indexTimeout,
                        @JsonProperty("maxContentLength") Object maxContentLength,
                        @JsonProperty("defaultProject") String defaultProject,
                        @JsonProperty("languageDetection") LanguageOptions languageDetection,
                        @JsonProperty("documentFactory") DocumentFactoryOptions documentFactory,
                        @JsonProperty("extractor") ExtractorOptions extractor) {
        this(parallelism, indexTimeout, parseMaxContentLength(maxContentLength), defaultProject, languageDetection,
             documentFactory, extractor);
    }

    public static IndexOptions of(PropertiesProvider p) {
        IndexOptions scalars = p.toRecord(IndexOptions.class);
        return new IndexOptions(scalars.parallelism, scalars.indexTimeout, scalars.maxContentLength,
                                scalars.defaultProject, p.toRecord(LanguageOptions.class),
                                p.toRecord(DocumentFactoryOptions.class), p.toRecord(ExtractorOptions.class));
    }

    private static int parseMaxContentLength(Object maxContentLength) {
        if (maxContentLength == null || maxContentLength.toString().isBlank()) {
            return -1;
        }
        return (int) Math.min(HumanReadableSize.parse(maxContentLength.toString()), Integer.MAX_VALUE);
    }
}

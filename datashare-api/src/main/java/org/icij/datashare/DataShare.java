package org.icij.datashare;

import org.icij.datashare.function.ThrowingFunction;
import org.icij.datashare.text.extraction.FileParser;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.nlp.Pipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

import static java.util.Arrays.asList;


public final class DataShare {
    private static final Logger LOGGER = LoggerFactory.getLogger(DataShare.class);
    public static final List<DataShare.Stage> DEFAULT_STAGES = asList(DataShare.Stage.values());
    public static final int DEFAULT_PARSER_PARALLELISM = FileParser.DEFAULT_PARALLELISM;
    public static final List<Pipeline.Type> DEFAULT_NLP_PIPELINES = asList(Pipeline.Type.values());
    public static final int DEFAULT_NLP_PARALLELISM = Pipeline.DEFAULT_PARALLELISM;
    public static final String DEFAULT_INDEX = "datashare-local";

    public static void processDirectory(Path inputDir, int fileParserParallelism, boolean enableOcr, List<Pipeline.Type> nlpPipelineTypes, int nlpPipelineParallelism, Indexer indexer, String index) {
        LOGGER.info("Running Stand Alone");
        LOGGER.info("Source Directory:             " + inputDir);
        LOGGER.info("File Parser with OCR:         " + enableOcr);
        LOGGER.info("Nlp Pipelines:                " + nlpPipelineTypes);
        LOGGER.info("Nlp Pipeline Parallelism:     " + nlpPipelineParallelism);
    }

    public enum Stage {
        SCANNING,
        PARSING,
        NLP;

        public static final Comparator<Stage> comparator = Comparator.comparing(Stage::ordinal);

        public static Optional<Stage> parse(final String stage) {
            if (stage== null || stage.isEmpty())
                return Optional.empty();
            try {
                return Optional.of(valueOf(stage.toUpperCase(Locale.ROOT)));
            }  catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }

        public static ThrowingFunction<List<String>, List<Stage>> parseAll =
                list ->
                        list.stream()
                                .map(Stage::parse)
                                .filter(Optional::isPresent)
                                .map(Optional::get)
                                .collect(Collectors.toList());
    }
}

package org.icij.datashare.cli.command;

import org.icij.datashare.PipelineHelper;
import org.icij.datashare.cli.OcrType;
import org.icij.datashare.text.nlp.Pipeline;
import picocli.CommandLine.Option;

import java.util.Properties;

import static org.icij.datashare.PropertiesProvider.RESUME_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.*;

/**
 * Options specific to the stage run subcommand.
 */
public class PipelineOptions {

    @Option(names = {"--artifactDir"}, description = "Artifact directory for embedded caching")
    String artifactDir;

    @Option(names = {"--nlpPipeline"}, description = "NLP pipeline to be run", defaultValue = "CORENLP")
    Pipeline.Type nlpPipeline;

    @Option(names = {"--nlpParallelism"}, description = "NLP extraction threads per pipeline", defaultValue = "1")
    int nlpParallelism;

    @Option(names = {"--batchSize"}, description = "Batch size of NLP extraction task", defaultValue = "1024")
    int batchSize;

    @Option(names = {"--maxTextLength"}, description = "Max text length for NLP", defaultValue = "1024")
    int maxTextLength;

    @Option(names = {"-o", "--ocr"}, description = "Enable OCR at file parsing time", defaultValue = "true")
    boolean ocr;

    @Option(names = {"--ocrType"}, description = "OCR implementation: TESSERACT or TESS4J", defaultValue = "TESSERACT")
    OcrType ocrType;

    @Option(names = {"--ocrLanguage"}, description = "OCR languages for tesseract")
    String ocrLanguage;

    @Option(names = {"--parallelism"}, description = "Number of task management threads")
    Integer parallelism;

    @Option(names = {"--parserParallelism"}, description = "Number of file parser threads", defaultValue = "1")
    int parserParallelism;

    @Option(names = {"-r", "--resume"}, description = "Resume pending operations")
    boolean resume;

    @Option(names = {"--followSymlinks"}, description = "Follow symlinks while scanning", defaultValue = "true")
    boolean followSymlinks;

    @Option(names = {"--createIndex"}, description = "Create an index with the given name")
    String createIndex;

    @Option(names = {"--indexTimeout"}, description = "Index timeout in minutes", defaultValue = "30")
    int indexTimeout;

    @Option(names = {"--searchQuery"}, description = "JSON query for EnqueueFromIndex task")
    String searchQuery;

    /** Converts the parsed pipeline option fields into a Properties map for the rest of the application. */
    public Properties toProperties() {
        Properties props = new Properties();
        DatashareOptions.putIfNotNull(props, ARTIFACT_DIR_OPT, artifactDir);
        DatashareOptions.putIfNotNull(props, NLP_PIPELINE_OPT, nlpPipeline);
        DatashareOptions.put(props, NLP_PARALLELISM_OPT, nlpParallelism);
        DatashareOptions.put(props, NLP_BATCH_SIZE_OPT, batchSize);
        DatashareOptions.put(props, NLP_MAX_TEXT_LENGTH_OPT, maxTextLength);
        DatashareOptions.put(props, OCR_OPT, ocr);
        DatashareOptions.putIfNotNull(props, OCR_TYPE_OPT, ocrType);
        DatashareOptions.putIfNotNull(props, OCR_LANGUAGE_OPT, ocrLanguage);
        DatashareOptions.putIfNotNull(props, PARALLELISM_OPT, parallelism);
        DatashareOptions.put(props, PARSER_PARALLELISM_OPT, parserParallelism);
        DatashareOptions.putIfTrue(props, RESUME_OPT, resume);
        DatashareOptions.put(props, FOLLOW_SYMLINKS_OPT, followSymlinks);
        DatashareOptions.putIfNotNull(props, CREATE_INDEX_OPT, createIndex);
        DatashareOptions.put(props, INDEX_TIMEOUT_OPT, indexTimeout);
        DatashareOptions.putIfNotNull(props, SEARCH_QUERY_OPT, searchQuery);
        return props;
    }

}

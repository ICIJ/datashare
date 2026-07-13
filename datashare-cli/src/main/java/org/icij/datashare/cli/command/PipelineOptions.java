package org.icij.datashare.cli.command;

import org.icij.datashare.cli.OcrStrategy;
import org.icij.datashare.cli.OcrType;
import org.icij.datashare.text.nlp.Pipeline;
import picocli.CommandLine.Option;

import java.util.Properties;

import static org.icij.datashare.PropertiesProvider.REPORT_NAME_OPT;
import static org.icij.datashare.PropertiesProvider.RESUME_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.*;

/**
 * Options specific to the stage run subcommand.
 */
public class PipelineOptions {

    @Option(names = {"--artifactDir"}, description = "Artifact directory for embedded caching")
    String artifactDir;

    // arity = "0..1" + fallbackValue = "true" mirrors the JOpt withOptionalArg semantics: a bare
    // --artifacts means "all types", --artifacts=raw,structure selects a subset. Left null when
    // absent so toProperties omits the key and INDEX produces no artifacts.
    @Option(names = {"--artifacts"}, arity = "0..1", fallbackValue = "true",
            description = "Artifact types to produce, comma-separated (bare flag = all types); unknown types are rejected.")
    String artifacts;

    @Option(names = {"--artifactsForce"}, arity = "0..1", fallbackValue = "true", defaultValue = "false",
            description = "Reprocess artifacts even when an up-to-date manifest entry exists (bypasses caching).")
    boolean artifactsForce;

    @Option(names = {"--nlpPipeline"}, description = "NLP pipeline to be run", defaultValue = "CORENLP")
    Pipeline.Type nlpPipeline;

    @Option(names = {"--nlpParallelism"}, description = "NLP extraction threads per pipeline", defaultValue = "1")
    int nlpParallelism;

    @Option(names = {"--batchSize"}, description = "Batch size of NLP extraction task", defaultValue = "1024")
    int batchSize;

    @Option(names = {"--maxTextLength"}, description = "Max text length for NLP", defaultValue = "1024")
    int maxTextLength;

    @Option(names = {"-o", "--ocr"}, description = "Enable OCR at file parsing time", defaultValue = "true", arity = "1")
    boolean ocr;

    @Option(names = {"--ocrType"}, description = "OCR implementation: TESSERACT or TESS4J", defaultValue = "TESSERACT")
    OcrType ocrType;

    @Option(names = {"--ocrLanguage"}, description = "OCR languages for tesseract")
    String ocrLanguage;

    @Option(names = {"--ocrTimeout"}, description = "OCR timeout", defaultValue = DEFAULT_OCR_TIMEOUT)
    String ocrTimeout;

    @Option(names = {"--parseTimeout"}, description = "Wall-clock timeout for a single document's parse and output, e.g. \"30m\" or \"24h\". Set to 0 to disable. Defaults to 24h.", defaultValue = DEFAULT_PARSE_TIMEOUT)
    String parseTimeout;

    // No defaultValue on purpose: when unset the field stays null and putIfNotNull omits the key,
    // so extract applies its own NO_OCR default. A default here would emit the key for every run
    // and silently change OCR behavior for all users.
    @Option(names = {"--ocrStrategy"}, description = "PDF OCR strategy: NO_OCR (default), AUTO, OCR_AND_TEXT_EXTRACTION, OCR_ONLY. Rendering strategies OCR whole pages for scanned/MRC PDFs; only applies when OCR is enabled.")
    OcrStrategy ocrStrategy;

    @Option(names = {"--parallelism"}, description = "Number of task management threads")
    Integer parallelism;

    @Option(names = {"--parserParallelism"}, description = "Number of file parser threads", defaultValue = "1")
    int parserParallelism;

    @Option(names = {"--maxEmbedDepth"}, description = MAX_EMBED_DEPTH_DESC, defaultValue = "20")
    int maxEmbedDepth;

    @Option(names = {"-r", "--resume"}, description = "Resume pending operations")
    boolean resume;

    @Option(names = {"--followSymlinks"}, description = "Follow symlinks while scanning (default: ${DEFAULT-VALUE})", arity = "1", defaultValue = "true")
    boolean followSymlinks;

    @Option(names = {"--createIndex"}, description = "Create an index with the given name")
    String createIndex;

    @Option(names = {"--indexTimeout"}, description = "Index timeout in minutes", defaultValue = "30")
    int indexTimeout;

    @Option(names = {"--searchQuery"}, description = "JSON query for EnqueueFromIndex task")
    String searchQuery;

    @Option(names = {"--scroll"}, description = "Scroll duration used for elasticsearch scrolls", defaultValue = "60000ms")
    String scroll;

    @Option(names = {"--scrollSize"}, description = "Scroll size used for elasticsearch scrolls", defaultValue = "1000")
    int scrollSize;

    @Option(names = {"--scrollSlices"}, description = "Scroll slice max number used for elasticsearch scrolls", defaultValue = "1")
    int scrollSlices;

    @Option(names = {"--reportName"}, description = "Name of the map for the report map (where index results are stored). No report records are saved if not provided")
    String reportName;

    @Option(names = {"--maxContentLength"}, description = "Maximum length (in bytes) of extracted text that could be indexed", defaultValue = "20000000")
    String maxContentLength;

    /** Converts the parsed pipeline option fields into a Properties map for the rest of the application. */
    public Properties toProperties() {
        Properties props = new Properties();
        DatashareOptions.putIfNotNull(props, ARTIFACT_DIR_OPT, artifactDir);
        DatashareOptions.putIfNotNull(props, ARTIFACTS_OPT, artifacts);
        DatashareOptions.put(props, ARTIFACTS_FORCE_OPT, artifactsForce);
        DatashareOptions.putIfNotNull(props, NLP_PIPELINE_OPT, nlpPipeline);
        DatashareOptions.put(props, NLP_PARALLELISM_OPT, nlpParallelism);
        DatashareOptions.put(props, NLP_BATCH_SIZE_OPT, batchSize);
        DatashareOptions.put(props, NLP_MAX_TEXT_LENGTH_OPT, maxTextLength);
        DatashareOptions.put(props, OCR_OPT, ocr);
        DatashareOptions.putIfNotNull(props, OCR_TYPE_OPT, ocrType);
        DatashareOptions.putIfNotNull(props, OCR_LANGUAGE_OPT, ocrLanguage);
        DatashareOptions.putIfNotNull(props, OCR_STRATEGY_OPT, ocrStrategy);
        DatashareOptions.putIfNotNull(props, PARALLELISM_OPT, parallelism);
        DatashareOptions.put(props, PARSER_PARALLELISM_OPT, parserParallelism);
        DatashareOptions.put(props, MAX_EMBED_DEPTH_OPT, maxEmbedDepth);
        DatashareOptions.putIfTrue(props, RESUME_OPT, resume);
        DatashareOptions.put(props, FOLLOW_SYMLINKS_OPT, followSymlinks);
        DatashareOptions.putIfNotNull(props, CREATE_INDEX_OPT, createIndex);
        DatashareOptions.put(props, INDEX_TIMEOUT_OPT, indexTimeout);
        DatashareOptions.put(props, OCR_TIMEOUT, ocrTimeout);
        DatashareOptions.put(props, PARSE_TIMEOUT_OPT, parseTimeout);
        DatashareOptions.putIfNotNull(props, SEARCH_QUERY_OPT, searchQuery);
        DatashareOptions.putIfNotNull(props, SCROLL_DURATION_OPT, scroll);
        DatashareOptions.put(props, SCROLL_SIZE_OPT, scrollSize);
        DatashareOptions.put(props, SCROLL_SLICES_OPT, scrollSlices);
        DatashareOptions.putIfNotNull(props, REPORT_NAME_OPT, reportName);
        DatashareOptions.putIfNotNull(props, MAX_CONTENT_LENGTH_OPT, maxContentLength);
        return props;
    }
}

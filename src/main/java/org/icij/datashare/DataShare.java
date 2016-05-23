package org.icij.datashare;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.Arrays.asList;

import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import joptsimple.*;

import org.icij.datashare.text.processing.NamedEntity;
import org.icij.datashare.util.io.FileSystemUtils;
import static org.icij.datashare.util.function.ThrowingFunctions.joinComma;

import org.icij.datashare.text.Document;

import org.icij.datashare.text.Language;

import org.icij.datashare.text.processing.NLPPipeline;
import static org.icij.datashare.text.processing.NLPPipeline.NLPPipelineType.CORENLP;
import static org.icij.datashare.text.processing.NLPPipeline.NLPPipelineType.GATENLP;
import static org.icij.datashare.text.processing.NLPPipeline.NLPPipelineType.OPENNLP;

import org.icij.datashare.text.processing.NamedEntityCategory;
import static org.icij.datashare.text.processing.NamedEntityCategory.PERSON;
import static org.icij.datashare.text.processing.NamedEntityCategory.ORGANIZATION;
import static org.icij.datashare.text.processing.NamedEntityCategory.LOCATION;

import org.icij.datashare.text.processing.NLPStage;
import static org.icij.datashare.text.processing.NLPStage.POS;
import static org.icij.datashare.text.processing.NLPStage.NER;

import org.icij.datashare.text.processing.NLPPipeline.NLPPipelineType;


/**
 * Datashare
 *
 * Created by julien on 3/9/16.
 */
public class DataShare {

    private static final Logger LOGGER = Logger.getLogger(DataShare.class.getName());

    private static final List<NLPStage> STAGES = asList(POS, NER);

    private static final List<NamedEntityCategory> DEFAULT_ENTITIES = asList(PERSON, ORGANIZATION, LOCATION);

    private static final List<NLPPipelineType> DEFAULT_NLPPIPELINES = asList(GATENLP, CORENLP, OPENNLP);


    private static List<NamedEntityCategory> entityCategories = DEFAULT_ENTITIES;

    private static List<NLPPipelineType> nlpPipelineTypes = DEFAULT_NLPPIPELINES;

    private static File inputDir;

    private static File outputDir;

    private static boolean enableOcr;


    /**
     *
     * @param args is the array of command line arguments
     */
    private static boolean parseCommandLineArguments(String[] args) {

        OptionParser parser = new OptionParser();

        // Input directory argument
        OptionSpec<File> inputDirOpt = parser.acceptsAll( asList( "input-dir", "in" ), "Source documents directory." )
                .withRequiredArg()
                .ofType( File.class )
                .required();

        // Output directory argument
        File tempDir = new File( System.getProperty( "java.io.tmpdir" ) );
        OptionSpec<File> outputDirOpt = parser.acceptsAll( asList("output-dir", "out"), "Result files directory. Defaults to </tmp>."  )
                .withRequiredArg()
                .ofType( File.class )
                .defaultsTo( tempDir );

        // NLP pipelines to run argument
        OptionSpec<NLPPipelineType> nlpPipelinesOpt = parser
                .acceptsAll( asList( "nlp-pipeline", "p"), "NLP pipelines to run in {GATENLP, CORENLP, OPENNLP}" )
                .withRequiredArg()
                .ofType( NLPPipelineType.class )
                .withValuesSeparatedBy( ',' );

        OptionSpec<NamedEntityCategory> entityCategoriesOpt = parser
                .acceptsAll( asList("entity-cat", "e"), "Named Entity category to recognize {PERSON, ORGANIZATION, LOCATION}")
                .withRequiredArg()
                .ofType(NamedEntityCategory.class)
                .withValuesSeparatedBy( ',');

        // OCR argument
        parser.acceptsAll( asList("enable-ocr", "ocr"), "Run OCR while parsing documents. Ensure Tesseract is properly installed before." );

        // Help
        //parser.acceptsAll( asList("help", "h", "?"), "Displays this help page." );

        try {
            // Parse arguments wrt specifications
            OptionSet options = parser.parse( args );

            // Create and assign values from parsed options
            inputDir  = options.valueOf(inputDirOpt);
            outputDir = options.valueOf(outputDirOpt);
            if (options.has("nlp-pipeline"))
                nlpPipelineTypes = options.valuesOf(nlpPipelinesOpt);
            if (options.has("entity-cat"))
                entityCategories = options.valuesOf(entityCategoriesOpt);
            enableOcr = options.has("enable-ocr");

        } catch (Exception e) {
            LOGGER.log(SEVERE, "Failed to parse and get command line arguments", e);
            try {
                parser.printHelpOn( System.out );
            } catch (IOException e1) {
                LOGGER.log(SEVERE, "Failed to display command line arguments help", e1);
            }
            return false;
        }

        return true;
    }


    public static void main(String[] args) {

        // Get DataShare parameter from command line
        if( ! parseCommandLineArguments(args))
            return;

        // Create the pipelines once
        Properties props = new Properties();
        props.setProperty("stages",   joinComma.apply(STAGES));
        props.setProperty("entityCategories", joinComma.apply(entityCategories));
        Map<NLPPipelineType, NLPPipeline> nlpPipelines = new HashMap<>();
        for (NLPPipelineType type : nlpPipelineTypes) {
            Optional<NLPPipeline> pipeline = NLPPipeline.create(type, props);
            if (pipeline.isPresent())
                nlpPipelines.put(type, pipeline.get());
        }

        LOGGER.log(INFO, entityCategories.toString().toUpperCase());

        try {
            // For each file in specified input directory
            List<Path> inputFilePaths = FileSystemUtils.listFilesInDirectory(inputDir.toPath());
            for (Path inputfilePath : inputFilePaths) {

                // Skip processing if corresponding result file already exists
                Path outputFilePath = Paths.get( outputDir.toPath().resolve( inputfilePath.getFileName()).toString() + ".csv" );
                if ( Files.exists(outputFilePath)) {
                    LOGGER.log(INFO, "Skipping " + inputfilePath);
                    continue;
                }

                // Create document from path
                Optional<Document> doc = Document.create(inputfilePath);
                if (doc.isPresent()) {
                    Document document = doc.get();

                    LOGGER.log(INFO, inputfilePath.toString());

                    // Read document
                    document.read(enableOcr);

                    // Get detected document language
                    Language language = document.getLanguage().orElse(Language.ENGLISH);

                    LOGGER.log(INFO, language.toString().toUpperCase(Locale.ROOT));

                    // For each created nlp pipeline
                    List<NamedEntity> entities = new ArrayList<>();
                    for (NLPPipelineType pipelineType : nlpPipelines.keySet()) {

                        LOGGER.log(INFO, pipelineType.toString());

                        NLPPipeline pipeline = nlpPipelines.get(pipelineType);
                        // Set document language
                        pipeline.setLanguage(language);
                        // Run!
                        pipeline.run(document);
                        // Get extracted Named Entities
                        entities.addAll(pipeline.getEntities());
                    }

                    // Write extracted entityCategories to "outputDir/inputFilePath.csv"
                    String entityLines = String.join("\n", entities
                            .stream()
                            .map(NamedEntity::toString)
                            .collect(Collectors.toList()));
                    FileSystemUtils.writeToFile(outputFilePath, StandardCharsets.UTF_8, entityLines);

                } else {
                    LOGGER.log(SEVERE, "Failed to get Document " + inputfilePath);
                }

            }

        } catch (IOException e) {
            e.printStackTrace();
        }

    }

}

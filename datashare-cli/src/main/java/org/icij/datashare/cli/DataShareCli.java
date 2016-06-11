package org.icij.datashare.cli;

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

import org.icij.datashare.util.function.ThrowingFunctions;
import static org.icij.datashare.util.io.FileSystemUtils.listFilesInDirectory;
import static org.icij.datashare.util.io.FileSystemUtils.writeToFile;

import org.icij.datashare.text.Document;
import org.icij.datashare.text.Language;

import org.icij.datashare.text.reading.DocumentParser;

import org.icij.datashare.text.processing.NamedEntity;
import org.icij.datashare.text.processing.NamedEntityCategory;
import static org.icij.datashare.text.processing.NamedEntityCategory.PERSON;
import static org.icij.datashare.text.processing.NamedEntityCategory.ORGANIZATION;
import static org.icij.datashare.text.processing.NamedEntityCategory.LOCATION;

import org.icij.datashare.text.processing.NLPPipeline;
import org.icij.datashare.text.processing.NLPPipeline.NLPPipelineType;
import static org.icij.datashare.text.processing.NLPPipeline.NLPPipelineType.CORENLP;
import static org.icij.datashare.text.processing.NLPPipeline.NLPPipelineType.GATENLP;
import static org.icij.datashare.text.processing.NLPPipeline.NLPPipelineType.OPENNLP;

import org.icij.datashare.text.processing.NLPStage;
import static org.icij.datashare.text.processing.NLPStage.POS;
import static org.icij.datashare.text.processing.NLPStage.NER;


/**
 * Datashare
 *
 * Created by julien on 3/9/16.
 */
public class DataShareCli {

    private static final Logger LOGGER = Logger.getLogger(DataShareCli.class.getName());

    private static final List<NLPStage> DEFAULT_STAGES = asList(POS, NER);

    private static final List<NamedEntityCategory> DEFAULT_ENTITIES = asList(PERSON, ORGANIZATION, LOCATION);

    private static final List<NLPPipelineType> DEFAULT_NLPPIPELINES = asList(GATENLP, CORENLP, OPENNLP);


    private static File inputDir;

    private static File outputDir;

    private static boolean enableOcr;

    private static List<NLPPipelineType> nlpPipelineTypes = DEFAULT_NLPPIPELINES;

    private static List<NamedEntityCategory> entityCategories = DEFAULT_ENTITIES;

    private static List<NLPStage> nlpStages = DEFAULT_STAGES;


    /**
     *
     * @param args is the array of command line arguments
     */
    private static boolean parseCommandLineArguments(String[] args) {
        final char ARG_VALS_SEP = ',';

        OptionParser parser = new OptionParser();

        // Input directory argument
        OptionSpec<File> inputDirOpt = parser
                .acceptsAll( asList( "input-dir", "in", "i"), "Source documents directory." )
                .withRequiredArg()
                .ofType( File.class )
                .required();

        // Output directory argument
        File tempDir = new File( System.getProperty( "java.io.tmpdir" ) );
        OptionSpec<File> outputDirOpt = parser
                .acceptsAll( asList("output-dir", "out", "o"), "Result files directory. Defaults to </tmp>."  )
                .withRequiredArg()
                .ofType( File.class )
                .defaultsTo( tempDir );

        // NLP pipelines to run argument
        OptionSpec<NLPPipelineType> nlpPipelinesOpt = parser
                .acceptsAll( asList("pipeline", "p"), "NLP pipelines to run. Defaults to GATENLP,CORENLP,OPENNLP" )
                .withRequiredArg()
                .ofType( NLPPipelineType.class )
                .withValuesSeparatedBy( ARG_VALS_SEP );

        // Named entity categories to extract
        OptionSpec<NamedEntityCategory> entityCategoriesOpt = parser
                .acceptsAll( asList("entities", "e"), "Named Entity categories to recognize. Defaults to PERSON,ORGANIZATION,LOCATION")
                .withRequiredArg()
                .ofType(NamedEntityCategory.class)
                .withValuesSeparatedBy( ARG_VALS_SEP );

        // Named entity categories to extract
        OptionSpec<NLPStage> stagesOpt = parser
                .acceptsAll( asList("stages", "s"), "Targeted stages. Defaults to POS,NER")
                .withRequiredArg()
                .ofType(NLPStage.class)
                .withValuesSeparatedBy( ARG_VALS_SEP );

        // OCR argument
        parser.acceptsAll( asList("enable-ocr", "ocr", "c"), "Run OCR while parsing documents. Ensure Tesseract is properly installed before." );

        // Help
        //parser.acceptsAll( asList("help", "h", "?"), "Displays this help page." );

        try {
            // Parse arguments wrt specifications
            OptionSet options = parser.parse( args );
            // Create and assign values from parsed options
            inputDir  = options.valueOf(inputDirOpt);
            outputDir = options.valueOf(outputDirOpt);
            if (options.has("pipeline"))
                nlpPipelineTypes = options.valuesOf(nlpPipelinesOpt);
            if (options.has("entities"))
                entityCategories = options.valuesOf(entityCategoriesOpt);
            if (options.has("stages"))
                nlpStages = options.valuesOf(stagesOpt);
            enableOcr = options.has("enable-ocr");

        } catch (Exception e) {
            LOGGER.log(SEVERE, "Failed to parse and get command line arguments", e);
            try {
                parser.printHelpOn( System.out );
            } catch (IOException e1) {
                LOGGER.log(SEVERE, "Failed to display help", e1);
            }
            return false;
        }

        return true;
    }


    public static void main(String[] args) {
        // Get parameters from command line
        if( ! parseCommandLineArguments(args))
            System.exit(1);

        // Set pipelines properties
        Properties props = new Properties();
        props.setProperty("stages", ThrowingFunctions.joinComma.apply(nlpStages));
        props.setProperty("entities", ThrowingFunctions.joinComma.apply(entityCategories));

        LOGGER.log(INFO, nlpPipelineTypes.toString().toUpperCase());
        LOGGER.log(INFO, nlpStages.toString().toUpperCase());
        LOGGER.log(INFO, entityCategories.toString().toUpperCase());

        try {
            // For each file in specified input directory
            List<Path> inputFilePaths = listFilesInDirectory(inputDir.toPath(), DocumentParser.SUPPORTED_FILE_EXTS);

            for (Path inputfilePath : inputFilePaths) {
                // Skip processing if the corresponding result file already exists
                Path inputFileNamePath = inputfilePath.getFileName();
                Path outputFilePath = Paths.get( outputDir.toPath().resolve(inputFileNamePath).toString() + ".csv" );
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
                    boolean docRead = document.read(enableOcr);
                    if (docRead) {
                        // Get detected document language
                        Language language = document.getLanguage().orElse(Language.NONE);
                        int length = document.getLength().orElse(0);

                        LOGGER.log(INFO, String.valueOf(length / 1000) + "K chars");
                        LOGGER.log(INFO, language.toString().toUpperCase(Locale.ROOT));

                        // For each created nlp pipeline
                        List<String> entities = new ArrayList<>();
                        for (NLPPipelineType type : nlpPipelineTypes) {

                            LOGGER.log(INFO, type.toString());
                            // Create a <type> pipeline
                            Optional<NLPPipeline> nlpPipelineOpt = NLPPipeline.create(type, props);
                            if (nlpPipelineOpt.isPresent()) {
                                NLPPipeline nlpPipeline = nlpPipelineOpt.get();
                                // Set language
                                nlpPipeline.setLanguage(language);
                                // Run!
                                nlpPipeline.run(document);
                                // Get extracted named entities
                                List<NamedEntity> nlpPipelineEntities = nlpPipeline.getEntities();
                                // Add serialized entities
                                entities.add(serializeEntities(nlpPipelineEntities));
                            }

                        }

                        // Write extracted entityCategories to "outputDir/inputFilePath.csv"
                        writeEntities(entities, outputFilePath);
                    }

                } else {
                    LOGGER.log(SEVERE, "Failed to get Document " + inputfilePath);
                }

            }

        } catch (Exception e) { //IOException
            e.printStackTrace();
        }

    }

    private static String serializeEntities(List<NamedEntity> entities) {
        return String.join("\n", entities
                .stream()
                .map(NamedEntity::toString)
                .collect(Collectors.toList()));
    }

    private static void writeEntities(List<String> entities, Path outputFilePath) throws IOException {
        writeToFile(
                outputFilePath,
                StandardCharsets.UTF_8,
                String.join("\n", entities)
        );
    }

}

package org.icij.datashare.text.extraction;

import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;

import org.icij.datashare.text.Document;
import org.icij.datashare.concurrent.task.DatashareTask;
import org.icij.datashare.concurrent.Latch;


/**
 * {@link DatashareTask} parsing files on input queue, putting {@link Document}s on output queue
 *
 * Created by julien on 10/11/16.
 */
public class FileParsingTask extends DatashareTask<Path, Document, FileParser.Type> {

    private FileParser fileParser;


    public FileParsingTask(FileParser.Type fileParserType,
                           Properties fileParserProperties,
                           BlockingQueue<Path> input,
                           BlockingQueue<Document> output,
                           Latch noMoreInput) {
        super(fileParserType, fileParserProperties, input, output, noMoreInput);
    }

    public FileParsingTask(Properties fileParserProperties,
                           BlockingQueue<Path> input,
                           BlockingQueue<Document> output,
                           Latch noMoreInput) {
        this(FileParser.DEFAULT_TYPE, fileParserProperties, input, output, noMoreInput);
    }

    public FileParsingTask(BlockingQueue<Path> input,
                           BlockingQueue<Document> output,
                           Latch noMoreInput) {
        this(FileParser.DEFAULT_TYPE, new Properties(), input, output, noMoreInput);
    }


    @Override
    protected boolean initialize() {
        Optional<FileParser> parser = FileParser.create(type, properties);
        if ( ! parser.isPresent()) {
            LOGGER.error("Failed to createList file parser " + type);
            return false;
        }
        fileParser = parser.get();
        return true;
    }

    @Override
    protected Result execute(Path filePath) {
        LOGGER.info("Parsing file from " + filePath);
        try{
            Optional<Document> document = fileParser.parse(filePath);
            if ( ! document.isPresent()) {
                LOGGER.error(type + " failed to create document from " + filePath);
                return Result.FAILURE;
            }
            put( document.get() );
            return Result.SUCCESS;

        } catch (Exception e ) {
            LOGGER.error(type + " failed to run on " + filePath, e);
            return Result.FAILURE;
        }
    }

}

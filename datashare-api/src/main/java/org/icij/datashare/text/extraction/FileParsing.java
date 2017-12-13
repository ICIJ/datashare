package org.icij.datashare.text.extraction;

import org.icij.datashare.concurrent.Latch;
import org.icij.datashare.concurrent.queue.OutputQueue;
import org.icij.datashare.concurrent.queue.QueueForwarding;
import org.icij.datashare.concurrent.task.DatashareTask;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.SourcePath;

import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;
import java.util.stream.IntStream;


/**
 * {@link DatashareTask} parsing files on input queue, putting {@link Document}s on output queue
 *
 * Created by julien on 10/11/16.
 */
public class FileParsing extends DatashareTask<SourcePath, Document, FileParser.Type> {

    public static FileParsing create(FileParser.Type type,
                                     Properties properties,
                                     BlockingQueue<SourcePath> input,
                                     Latch noMoreInput,
                                     BlockingQueue<Document> output) {
        return new FileParsing(type, properties, input, noMoreInput, output);
    }

    public static FileParsing create(FileParser.Type type, Properties properties, QueueForwarding<SourcePath> source) {
        BlockingQueue<SourcePath> input  = new LinkedBlockingQueue<>();
        BlockingQueue<Document>   output = new LinkedBlockingQueue<>();
        FileParsing fileParsing = create(type, properties, input, source.noMoreOutput(), output);
        source.addOutput(fileParsing.inputs());
        return fileParsing;
    }

    public static FileParsing create(FileParser.Type type, boolean doOcr, OutputQueue<SourcePath> source) {
        BlockingQueue<Document> output     = new LinkedBlockingQueue<>();
        Properties              properties = FileParser.Property.build.apply(doOcr).apply(Language.ENGLISH);
        return create(type, properties, source.output(), source.noMoreOutput(), output);
    }

    public static List<FileParsing> create(FileParser.Type type, int parallelism, boolean doOcr, OutputQueue<SourcePath> source) {
        BlockingQueue<Document> output     = new LinkedBlockingQueue<>();
        Properties              properties = FileParser.Property.build.apply(doOcr).apply(Language.ENGLISH);
        return IntStream.rangeClosed(1, parallelism).mapToObj(task ->
                new FileParsing(type, properties, source.output(), source.noMoreOutput(), output)
        ).collect(Collectors.toList());
    }

    public static FileParsing create(FileParser.Type type, boolean doOcr, QueueForwarding<SourcePath> source) {
        Properties properties = FileParser.Property.build.apply(doOcr).apply(Language.ENGLISH);
        return create(type, properties, source);
    }

    public static List<FileParsing> create(FileParser.Type type, int parallelism, boolean doOcr, QueueForwarding<SourcePath> source) {
        BlockingQueue<SourcePath> input      = new LinkedBlockingQueue<>();
        BlockingQueue<Document>   output     = new LinkedBlockingQueue<>();
        Properties                properties = FileParser.Property.build.apply(doOcr).apply(Language.ENGLISH);
        List<FileParsing> fileParsingList = IntStream.rangeClosed(1, parallelism).mapToObj(task ->
                new FileParsing(type, properties, input, source.noMoreOutput(), output)
        ).collect(Collectors.toList());
        source.addOutput(input);
        return fileParsingList;
    }


    private FileParser fileParser;


    private FileParsing(FileParser.Type type,
                        Properties properties,
                        BlockingQueue<SourcePath> input,
                        Latch noMoreInput,
                        BlockingQueue<Document> output) {
        super(type, properties, input, noMoreInput, output);
    }


    @Override
    protected boolean initialize() {
        Optional<FileParser> parser = FileParser.create(type, properties);
        if ( ! parser.isPresent()) {
            LOGGER.error("Failed to create file parser " + type);
            return false;
        }
        fileParser = parser.get();
        return true;
    }

    @Override
    protected Result process(SourcePath sourcePath) {
        LOGGER.info("parsing from " + sourcePath.getPath());
        try{
            Optional<Document> document = fileParser.parse(sourcePath.getPath());
            if ( ! document.isPresent()) {
                LOGGER.error(type + " failed parsing from " + sourcePath);
                return Result.FAILURE;
            }
            put( document.get() );
            return Result.SUCCESS;
        } catch (Exception e ) {
            LOGGER.error(type + " failed running on " + sourcePath, e);
            return Result.FAILURE;
        }
    }

}

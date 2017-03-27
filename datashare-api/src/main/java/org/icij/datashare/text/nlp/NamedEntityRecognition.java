package org.icij.datashare.text.nlp;

import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.icij.datashare.text.Document;
import org.icij.datashare.text.NamedEntity;
import static org.icij.datashare.text.nlp.NlpStage.NER;
import org.icij.datashare.concurrent.Latch;
import org.icij.datashare.concurrent.queue.QueueForwarding;


/**
 * {@link NaturalLanguageProcessing} with {@link NlpStage#NER} as target stage
 *
 * Created by julien on 9/7/16.
 */
public class NamedEntityRecognition extends NaturalLanguageProcessing<Document, NamedEntity> {

    /**
     * Instantiate {@code parallelism} tasks per pipeline type.
     * Tasks of same type share input queue
     */
    public static List<NamedEntityRecognition> create(List<NlpPipeline.Type> types,
                                                      int parallelism,
                                                      Properties properties,
                                                      Map<NlpPipeline.Type, BlockingQueue<Document>> inputMap,
                                                      Latch noMoreInput,
                                                      BlockingQueue<NamedEntity> output) {
        return types.stream().flatMap( type ->
                IntStream.rangeClosed(1, parallelism).mapToObj( task ->
                        new NamedEntityRecognition(type, properties, inputMap.get(type), noMoreInput, output)
                )
        ).collect(Collectors.toList());
    }

    /**
     * Instantiate one input queue per pipeline type; create tasks
     */
    public static List<NamedEntityRecognition> create(List<NlpPipeline.Type> types,
                                                      int parallelism,
                                                      Properties properties,
                                                      Latch noMoreInput,
                                                      BlockingQueue<NamedEntity> output) {
        Map<NlpPipeline.Type, BlockingQueue<Document>> inputMap =
                new HashMap<NlpPipeline.Type, BlockingQueue<Document>>() {{
                    types.forEach( type -> put(type, new LinkedBlockingQueue<>()) );
                }};
        return create(types, parallelism, properties, inputMap, noMoreInput, output);
    }

    /**
     * Build a {@link Properties} object from pipeline parameters; create tasks
     */
    public static List<NamedEntityRecognition> create(List<NlpPipeline.Type> types,
                                                      int parallelism,
                                                      List<NlpStage> stages,
                                                      List<NamedEntity.Category> targetEntities,
                                                      boolean caching,
                                                      Latch noMoreInput,
                                                      BlockingQueue<NamedEntity> output) {
        Properties properties = NlpPipeline.Property.build
                .apply(stages)
                .apply(targetEntities)
                .apply(caching);
        return create(types, parallelism, properties, noMoreInput, output);
    }

    /**
     * Create tasks; add inputs to document forwarding task destinations
     */
    public static List<NamedEntityRecognition> create(List<NlpPipeline.Type> types,
                                                      int parallelism,
                                                      List<NlpStage> stages,
                                                      List<NamedEntity.Category> entities,
                                                      boolean caching,
                                                      QueueForwarding<Document> source) {
        BlockingQueue<NamedEntity> output = new LinkedBlockingQueue<>();
        List<NamedEntityRecognition> ners = create(types, parallelism, stages, entities, caching, source.noMoreOutput(), output);
        types.stream()
                .map( type -> ners.stream().filter(ner -> ner.getType().equals(type)) )
                .map( Stream::findFirst )
                .map( Optional::get )
                .map( NamedEntityRecognition::inputs )
                .forEach( source::addOutput );
        return ners;
    }


    private NamedEntityRecognition(NlpPipeline.Type type,
                                   Properties properties,
                                   BlockingQueue<Document> input,
                                   Latch noMoreInput,
                                   BlockingQueue<NamedEntity> output) {
        super(type, properties, input, noMoreInput, output);
        this.properties.setProperty(NlpPipeline.Property.STAGES.getName(), NER.toString());
    }


    @Override
    protected Result process(Document document) {
        try {
            LOGGER.info(getClass().getName() + " " + type + " running on " + document.getPath() +
                    " - " + document.getLength() +
                    " - " + document.getLanguage());
            Optional<Annotation> annotation = nlpPipeline.run(document);
            if ( annotation.isPresent()) {
                document.namedEntities(annotation.get()).forEach( this::put );
                return Result.SUCCESS;
            }
            return Result.FAILURE;
        } catch (Exception e) {
            LOGGER.error(getClass().getName() + " " + type + " failed running on " + document.getPath() +
                    " - " + document.getLength() +
                    " - " + document.getLanguage(), e);
            return Result.FAILURE;
        }
    }

}

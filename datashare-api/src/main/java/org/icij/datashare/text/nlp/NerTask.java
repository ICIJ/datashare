package org.icij.datashare.text.nlp;

import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.icij.datashare.concurrent.Latch;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.NamedEntity;
import static org.icij.datashare.text.nlp.NlpStage.NER;


/**
 * {@link NlpTask} with {@link NlpStage#NER} as target stage
 *
 * Created by julien on 9/7/16.
 */
public class NerTask extends NlpTask<Document, NamedEntity> {


    public static List<NerTask> createAll(List<NlpPipeline.Type> nlpPipelineTypes,
                                          int nlpPipelineParallelism,
                                          Properties nlpPipelineProperties,
                                          Map<NlpPipeline.Type, BlockingQueue<Document>> nlpPipelineTypeInputQueue,
                                          BlockingQueue<NamedEntity> namedEntitiesQueue,
                                          Latch noMoreInput) {
        return nlpPipelineTypes.stream()
                .flatMap(
                        nlpPipelineType ->
                                IntStream.rangeClosed(1, nlpPipelineParallelism)
                                        .mapToObj( p ->
                                                new NerTask(
                                                        nlpPipelineType,
                                                        nlpPipelineProperties,
                                                        nlpPipelineTypeInputQueue.get(nlpPipelineType),
                                                        namedEntitiesQueue,
                                                        noMoreInput
                                                )
                                        )
                ).collect(Collectors.toList());
    }


    public NerTask(NlpPipeline.Type           pipelineType,
                   Properties                 pipelineProperties,
                   BlockingQueue<Document>    documentsQueue,
                   BlockingQueue<NamedEntity> entitiesQueue,
                   Latch noMoreInput) {
        super(pipelineType, pipelineProperties, documentsQueue, entitiesQueue, noMoreInput);
        properties.setProperty(NlpPipeline.Property.STAGES.getName(), NER.toString());
    }


    @Override
    protected Result execute(Document document) {
        try {
            LOGGER.info(type + " running on " + document.getPath() +
                    " - " + document.getLength() +
                    " - " + document.getLanguage());
            nlpPipeline.run(document)
                    .ifPresent( annotation ->
                            NamedEntity.allFrom(document, annotation)
                                    .forEach(
                                            this::put
                                    )
                    );
            return Result.SUCCESS;
        } catch (Exception e) {
            LOGGER.error(type + " failed running on " + document.getPath() +
                    " - " + document.getLength() +
                    " - " + document.getLanguage(), e);
            return Result.FAILURE;
        }
    }

}

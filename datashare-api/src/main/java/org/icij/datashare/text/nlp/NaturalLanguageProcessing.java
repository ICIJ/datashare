package org.icij.datashare.text.nlp;

import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;

import org.icij.datashare.concurrent.Latch;
import org.icij.datashare.concurrent.task.DatashareTask;
import org.icij.datashare.concurrent.task.Task;


/**
 * {@link Task} running an {@link NlpPipeline}
 *
 * Created by julien on 10/6/16.
 */
public abstract class NaturalLanguageProcessing<I,O> extends DatashareTask<I, O, NlpPipeline.Type> {

    protected NlpPipeline nlpPipeline;


    public NaturalLanguageProcessing(NlpPipeline.Type pipelineType,
                                     Properties pipelineProperties,
                                     BlockingQueue<I> inputQueue,
                                     Latch noMoreInput,
                                     BlockingQueue<O> outputQueue) {
        super(pipelineType, pipelineProperties, inputQueue, noMoreInput, outputQueue);
    }


    @Override
    protected boolean initialize() {
        Optional<NlpPipeline> nlpPipelineOpt = NlpPipeline.create(type, properties);
        if ( ! nlpPipelineOpt.isPresent())
            return false;
        nlpPipeline = nlpPipelineOpt.get();
        return true;
    }

}

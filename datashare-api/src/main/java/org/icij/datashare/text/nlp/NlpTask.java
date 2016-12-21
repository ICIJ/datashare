package org.icij.datashare.text.nlp;

import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.function.Function;

import org.icij.datashare.concurrent.Latch;
import org.icij.datashare.concurrent.task.DatashareTask;
import org.icij.datashare.concurrent.task.Task;


/**
 * {@link Task} running an {@link NlpPipeline}
 *
 * Created by julien on 10/6/16.
 */
public abstract class NlpTask<I,O> extends DatashareTask<I, O, NlpPipeline.Type> {

    protected NlpPipeline nlpPipeline;


    public NlpTask(NlpPipeline.Type pipelineType,
                   Properties pipelineProperties,
                   BlockingQueue<I> inputQueue,
                   BlockingQueue<O> outputQueue,
                   Latch noMoreInput) {
        super(pipelineType, pipelineProperties, inputQueue, outputQueue, noMoreInput);
    }

    @Override
    protected boolean initialize() {
        Optional<NlpPipeline> nlpPipelineOpt = NlpPipeline.create(type, properties);
        if ( ! nlpPipelineOpt.isPresent()) {
            return false;
        }
        nlpPipeline = nlpPipelineOpt.get();
        return true;
    }

}

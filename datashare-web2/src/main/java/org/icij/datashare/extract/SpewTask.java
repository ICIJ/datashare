package org.icij.datashare.extract;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.icij.extract.extractor.DocumentConsumer;
import org.icij.extract.extractor.Extractor;
import org.icij.extract.queue.DocumentQueue;
import org.icij.extract.queue.DocumentQueueDrainer;
import org.icij.spewer.Spewer;
import org.icij.task.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;

public class SpewTask implements Callable<Long> {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final DocumentQueueDrainer drainer;

    private Integer parallelism = 1;

    @Inject
    public SpewTask(final Spewer spewer, final DocumentQueue queue, @Assisted final Options<String> options) {
        options.ifPresent("parallelism", o -> o.parse().asInteger()).ifPresent(this::setParallelism);

        drainer = new DocumentQueueDrainer(queue,
                new DocumentConsumer(spewer, new Extractor().configure(options), this.parallelism)
        ).configure(options);
    }

    @Override
    public Long call() throws Exception {
        logger.info(String.format("Processing up to %d file(s) in parallel.", parallelism));
        return drainer.drain().get();
    }

    private void setParallelism(Integer integer) { this.parallelism = integer;}
}

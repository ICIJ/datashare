package org.icij.datashare.extract;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.icij.extract.extractor.DocumentConsumer;
import org.icij.extract.extractor.Extractor;
import org.icij.extract.queue.DocumentQueue;
import org.icij.extract.queue.DocumentQueueDrainer;
import org.icij.spewer.Spewer;
import org.icij.task.DefaultTask;
import org.icij.task.Options;
import org.icij.task.annotation.OptionsClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@OptionsClass(Extractor.class)
@OptionsClass(DocumentQueueDrainer.class)
public class SpewTask extends DefaultTask<Long> {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final DocumentQueueDrainer drainer;

    private Integer parallelism = 1;

    @Inject
    public SpewTask(final Spewer spewer, final DocumentQueue queue, @Assisted final Options<String> userOptions) {
        userOptions.ifPresent("parallelism", o -> o.parse().asInteger()).ifPresent(this::setParallelism);
        Options<String> allTaskOptions = options().createFrom(userOptions);
        drainer = new DocumentQueueDrainer(queue,
                new DocumentConsumer(spewer, new Extractor().configure(allTaskOptions), this.parallelism)
        ).configure(allTaskOptions);
    }

    @Override
    public Long call() throws Exception {
        logger.info(String.format("Processing up to %d file(s) in parallel.", parallelism));
        return drainer.drain().get();
    }

    private void setParallelism(Integer integer) { this.parallelism = integer;}
}

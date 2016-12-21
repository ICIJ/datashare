package org.icij.datashare.concurrent.task;

import java.util.List;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import static java.util.Collections.singletonList;

import org.icij.datashare.concurrent.Latch;
import org.icij.datashare.util.reflect.EnumTypeToken;


/**
 * {@link QueueInQueueOutTask} running DataShare processor
 *
 * @see EnumTypeToken
 *
 * Created by julien on 10/6/16.
 */
public abstract class DatashareTask<I, O, T extends Enum<T> & EnumTypeToken> extends QueueInQueueOutTask<I,O> {

    protected final T          type;

    protected final Properties properties;


    public DatashareTask(T type,
                         Properties properties,
                         BlockingQueue<I> input,
                         BlockingQueue<O> output,
                         List<Latch> noMoreInput) {
        super(input, output, noMoreInput);
        this.type       = type;
        this.properties = new Properties(properties);
    }

    public DatashareTask(T type,
                         Properties properties,
                         BlockingQueue<I> input,
                         BlockingQueue<O> output,
                         Latch noMoreInput) {
        this(type, properties, input, output, singletonList(noMoreInput));
    }


    public T getType() {
        return type;
    }

}

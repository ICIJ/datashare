package org.icij.datashare.text.indexing.command;

import java.util.function.Supplier;


/**
 * Common structure and behavior of IndexerCommand implementations
 *
 * Created by julien on 6/27/16.
 */
public abstract class AbstractIndexerCommand<T> implements IndexerCommand<T> {

    private final Supplier<T> command;

    public AbstractIndexerCommand(Supplier<T> cmd) {
        command = cmd;
    }

    @Override
    public T execute() {
        return command.get();
    }

}

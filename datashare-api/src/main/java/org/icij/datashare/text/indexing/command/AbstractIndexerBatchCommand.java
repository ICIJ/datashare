package org.icij.datashare.text.indexing.command;

import java.util.function.Supplier;

/**
 * Created by julien on 12/12/16.
 */
public abstract class AbstractIndexerBatchCommand implements IndexerCommand<Void> {

    private Runnable batchCommand;

    AbstractIndexerBatchCommand(Runnable cmd) {
        batchCommand = cmd;
    }

    @Override
    public Void execute() {
        batchCommand.run();
        return null;
    }

}

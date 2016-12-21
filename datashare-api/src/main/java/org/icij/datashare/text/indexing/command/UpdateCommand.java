package org.icij.datashare.text.indexing.command;

import org.icij.datashare.text.indexing.Indexer;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Created by julien on 6/16/16.
 */
public class UpdateCommand extends AbstractIndexerCommand<Boolean> {

    public UpdateCommand(Indexer indexer, String index, String type, String id, String json) {
        super( () -> indexer.update(index, type, id, json) );
    }

    public UpdateCommand(Indexer indexer, String index, String type, String id, Map<String, Object> json) {
        super( () -> indexer.update(index, type, id, json) );
    }

}

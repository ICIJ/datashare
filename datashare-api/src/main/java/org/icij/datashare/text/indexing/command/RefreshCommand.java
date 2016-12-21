package org.icij.datashare.text.indexing.command;

import org.icij.datashare.text.indexing.Indexer;

/**
 * Created by julien on 6/28/16.
 */
public class RefreshCommand extends AbstractIndexerCommand<Boolean> {

    public RefreshCommand(Indexer indexer, String... indices) {
        super( () -> indexer.refreshIndices(indices) );
    }

}

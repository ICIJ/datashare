package org.icij.datashare.text.indexing.command;

import org.icij.datashare.text.indexing.Indexer;

import java.util.List;

/**
 * Created by julien on 6/28/16.
 */
public class GetIndicesCommand extends AbstractIndexerCommand<List<String>> {

    public GetIndicesCommand(Indexer indexer) {
        super( indexer::getIndices );
    }

}

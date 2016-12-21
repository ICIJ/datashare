package org.icij.datashare.text.indexing.command;

import org.icij.datashare.text.indexing.Indexer;

import java.util.Map;
import java.util.stream.Stream;

/**
 * Created by julien on 6/27/16.
 */
public class SearchCommand extends AbstractIndexerCommand<Stream<Map<String, Object>>> {

    public SearchCommand(Indexer indexer, String index, String type, String query) {
        super( () -> indexer.search(index, type, query) );
    }

}

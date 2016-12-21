package org.icij.datashare.text.indexing.command;

import org.icij.datashare.text.indexing.IndexObjectMapper;
import org.icij.datashare.text.indexing.Indexer;

import java.util.Map;

/**
 * Created by julien on 6/20/16.
 */
public class ReadCommand extends AbstractIndexerCommand<Map<String, Object>> {

    public ReadCommand(Indexer indexer, String index, String type, String id) {
        super( () -> indexer.read(index, type, id) );
    }

}

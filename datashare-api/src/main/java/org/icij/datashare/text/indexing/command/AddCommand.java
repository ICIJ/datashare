package org.icij.datashare.text.indexing.command;

import java.util.Map;

import org.icij.datashare.Entity;
import org.icij.datashare.text.indexing.Indexer;


/**
 * Add document to index
 *
 * Created by julien on 6/15/16.
 */
public class AddCommand extends AbstractIndexerBatchCommand {

    /**
     * from JSON as a String
     *
     * @param indexer indexer which actually executes the command
     * @param index   targeted index store
     * @param type    targeted type
     * @param id      id of the indexed document
     * @param json    JSON String representing indexed document fields
     */
    public AddCommand(Indexer indexer, String index, String type, String id, String json) {
        super( () -> indexer.addBatch(index, type, id, json) );
    }

    /**
     * from JSON as a Map
     *
     * @param indexer indexer which actually executes the command
     * @param index   targeted index store
     * @param type    targeted type
     * @param id      id of the indexed document
     * @param json    JSON Map representing indexed document fields
     */
    public AddCommand(Indexer indexer, String index, String type, String id, Map<String, Object> json) {
        super(  () -> indexer.addBatch(index, type, id, json) );
    }

    /**
     * from an Object
     *
     * @param indexer indexer which actually executes the command
     * @param index   targeted index store
     * @param obj     object to index
     */
    public <T extends Entity> AddCommand(Indexer indexer, String index, T obj) {
        super(  () -> indexer.addBatch(index, obj) );
    }

}


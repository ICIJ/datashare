package org.icij.datashare.text.indexing.command;

import org.icij.datashare.Entity;
import org.icij.datashare.text.indexing.Indexer;


/**
 * Delete document from index
 *
 * Created by julien on 6/15/16.
 */
public class DeleteCommand extends AbstractIndexerBatchCommand {

    /**
     * Delete document with {@code id} from String
     *
     * @param indexer indexer which actually executes the command
     * @param index   targeted index store
     * @param type    targeted type
     * @param id      id of the document to be deleted
     */
    public DeleteCommand(Indexer indexer, String index, String type, String id) {
        super(  () -> indexer.batchDelete(index, type, id) );
    }

    /**
     * Delete document with {@code id} from Object
     *
     * @param indexer indexer which actually executes the command
     * @param index   targeted index store
     * @param obj     Object holding the {@code id} of the document to be deleted
     */
    public <T extends Entity> DeleteCommand(Indexer indexer, String index, T obj) {
        super(  () -> indexer.batchDelete(index, obj) );
    }

}

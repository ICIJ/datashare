package org.icij.datashare.text.indexing.command;


/**
 * Command returning an object of type T
 *
 * Created by julien on 6/15/16.
 */
public interface IndexerCommand<T> {

    T execute();

}

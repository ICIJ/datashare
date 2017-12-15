package org.icij.datashare;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.icij.datashare.text.hashing.Hasher;

import java.io.Serializable;

import static org.icij.datashare.text.hashing.Hasher.SHA_384;


/**
 * DataShare domain entities interface
 *
 * Created by julien on 8/6/16.
 */
public interface Entity extends Serializable {
    Log LOGGER = LogFactory.getLog(Entity.class);

    Hasher HASHER = SHA_384;

    String getHash();

}

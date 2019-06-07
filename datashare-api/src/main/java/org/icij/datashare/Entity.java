package org.icij.datashare;

import org.icij.datashare.text.Hasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;

import static org.icij.datashare.text.Hasher.SHA_256;


/**
 * DataShare domain entities interface
 *
 * Created by julien on 8/6/16.
 */
public interface Entity extends Serializable {
    Logger LOGGER = LoggerFactory.getLogger(Entity.class);
    Hasher HASHER = SHA_256;

    String getId();
}

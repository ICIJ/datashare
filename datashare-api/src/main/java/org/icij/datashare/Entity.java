package org.icij.datashare;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.icij.datashare.text.hashing.Hasher;

import java.io.Serializable;
import java.util.Optional;

import static org.icij.datashare.text.hashing.Hasher.SHA_384;


/**
 * DataShare domain entities interface
 *
 * Created by julien on 8/6/16.
 */
public interface Entity extends Serializable {

    Hasher HASHER = SHA_384;

    String getHash();

}

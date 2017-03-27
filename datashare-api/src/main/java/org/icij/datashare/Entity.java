package org.icij.datashare;

import java.io.Serializable;

import org.icij.datashare.text.hashing.Hasher;
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
